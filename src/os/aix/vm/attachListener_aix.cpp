/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2012, 2013 SAP AG. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "runtime/interfaceSupport.hpp"
#include "runtime/os.hpp"
#include "services/attachListener.hpp"
#include "services/dtraceAttacher.hpp"

#include <unistd.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>

#ifndef UNIX_PATH_MAX
#define UNIX_PATH_MAX   sizeof(((struct sockaddr_un *)0)->sun_path)
#endif

// The attach mechanism on Linux uses a UNIX domain socket. An attach listener
// thread is created at startup or is created on-demand via a signal from
// the client tool. The attach listener creates a socket and binds it to a file
// in the filesystem. The attach listener then acts as a simple (single-
// threaded) server - it waits for a client to connect, reads the request,
// executes it, and returns the response to the client via the socket
// connection.
//
// As the socket is a UNIX domain socket it means that only clients on the
// local machine can connect. In addition there are two other aspects to
// the security:
// 1. The well known file that the socket is bound to has permission 400
// 2. When a client connect, the SO_PEERID socket option is used to
//    obtain the credentials of client. We check that the effective uid
//    of the client matches this process.

// forward reference
class AixAttachOperation;

class AixAttachListener: AllStatic {
 private:
  // the path to which we bind the UNIX domain socket
  static char _path[UNIX_PATH_MAX];
  static bool _has_path;
  // Shutdown marker to prevent accept blocking during clean-up.
  static bool _shutdown;

  // the file descriptor for the listening socket
  static int _listener;

  static void set_path(char* path) {
    if (path == NULL) {
      _has_path = false;
    } else {
      strncpy(_path, path, UNIX_PATH_MAX);
      _path[UNIX_PATH_MAX-1] = '\0';
      _has_path = true;
    }
  }

  static void set_listener(int s)               { _listener = s; }

  // reads a request from the given connected socket
  static AixAttachOperation* read_request(int s);

 public:
  enum {
    ATTACH_PROTOCOL_VER = 1                     // protocol version
  };
  enum {
    ATTACH_ERROR_BADVERSION     = 101           // error codes
  };

  // initialize the listener, returns 0 if okay
  static int init();

  static char* path()                   { return _path; }
  static bool has_path()                { return _has_path; }
  static int listener()                 { return _listener; }
  // Shutdown marker to prevent accept blocking during clean-up
  static void set_shutdown(bool shutdown) { _shutdown = shutdown; }
  static bool is_shutdown()     { return _shutdown; }

  // write the given buffer to a socket
  static int write_fully(int s, char* buf, int len);

  static AixAttachOperation* dequeue();
};

class AixAttachOperation: public AttachOperation {
 private:
  // the connection to the client
  int _socket;

 public:
  void complete(jint res, bufferedStream* st);

  void set_socket(int s)                                { _socket = s; }
  int socket() const                                    { return _socket; }

  AixAttachOperation(char* name) : AttachOperation(name) {
    set_socket(-1);
  }
};

// statics
char AixAttachListener::_path[UNIX_PATH_MAX];
bool AixAttachListener::_has_path;
int AixAttachListener::_listener = -1;
// Shutdown marker to prevent accept blocking during clean-up
bool AixAttachListener::_shutdown = false;

// Supporting class to help split a buffer into individual components
class ArgumentIterator : public StackObj {
 private:
  char* _pos;
  char* _end;
 public:
  ArgumentIterator(char* arg_buffer, size_t arg_size) {
    _pos = arg_buffer;
    _end = _pos + arg_size - 1;
  }
  char* next() {
    if (*_pos == '\0') {
      return NULL;
    }
    char* res = _pos;
    char* next_pos = strchr(_pos, '\0');
    if (next_pos < _end)  {
      next_pos++;
    }
    _pos = next_pos;
    return res;
  }
};

// On AIX if sockets block until all data has been transmitted
// successfully in some communication domains a socket "close" may
// never complete. We have to take care that after the socket shutdown
// the listener never enters accept state.

// atexit hook to stop listener and unlink the file that it is
// bound too.

// Some modifications to the listener logic to prevent deadlocks on exit.
// 1. We Shutdown the socket here instead. AixAttachOperation::complete() is not the right place
//    since more than one agent in a sequence in JPLIS live tests wouldn't work (Listener thread
//    would be dead after the first operation completion).
// 2. close(s) may never return if the listener thread is in socket accept(). Unlinking the file
//    should be sufficient for cleanup.
extern "C" {
  static void listener_cleanup() {
    static int cleanup_done;
    if (!cleanup_done) {
      cleanup_done = 1;
      AixAttachListener::set_shutdown(true);
      int s = AixAttachListener::listener();
      if (s != -1) {
        ::shutdown(s, 2);
      }
      if (AixAttachListener::has_path()) {
        ::unlink(AixAttachListener::path());
      }
    }
  }
}

// Initialization - create a listener socket and bind it to a file

int AixAttachListener::init() {
  char path[UNIX_PATH_MAX];          // socket file
  char initial_path[UNIX_PATH_MAX];  // socket file during setup
  int listener;                      // listener socket (file descriptor)

  // register function to cleanup
  ::atexit(listener_cleanup);

  int n = snprintf(path, UNIX_PATH_MAX, "%s/.java_pid%d",
                   os::get_temp_directory(), os::current_process_id());
  if (n < (int)UNIX_PATH_MAX) {
    n = snprintf(initial_path, UNIX_PATH_MAX, "%s.tmp", path);
  }
  if (n >= (int)UNIX_PATH_MAX) {
    return -1;
  }

  // create the listener socket
  listener = ::socket(PF_UNIX, SOCK_STREAM, 0);
  if (listener == -1) {
    return -1;
  }

  // bind socket
  struct sockaddr_un addr;
  addr.sun_family = AF_UNIX;
  strcpy(addr.sun_path, initial_path);
  ::unlink(initial_path);
  // We must call bind with the actual socketaddr length. This is obligatory for AS400.
  int res = ::bind(listener, (struct sockaddr*)&addr, SUN_LEN(&addr));
  if (res == -1) {
    RESTARTABLE(::close(listener), res);
    return -1;
  }

  // put in listen mode, set permissions, and rename into place
  res = ::listen(listener, 5);
  if (res == 0) {
      RESTARTABLE(::chmod(initial_path, (S_IREAD|S_IWRITE) & ~(S_IRGRP|S_IWGRP|S_IROTH|S_IWOTH)), res);
      if (res == 0) {
          res = ::rename(initial_path, path);
      }
  }
  if (res == -1) {
    RESTARTABLE(::close(listener), res);
    ::unlink(initial_path);
    return -1;
  }
  set_path(path);
  set_listener(listener);
  set_shutdown(false);

  return 0;
}

// Given a socket that is connected to a peer we read the request and
// create an AttachOperation. As the socket is blocking there is potential
// for a denial-of-service if the peer does not response. However this happens
// after the peer credentials have been checked and in the worst case it just
// means that the attach listener thread is blocked.
//
AixAttachOperation* AixAttachListener::read_request(int s) {
  char ver_str[8];
  sprintf(ver_str, "%d", ATTACH_PROTOCOL_VER);

  // The request is a sequence of strings so we first figure out the
  // expected count and the maximum possible length of the request.
  // The request is:
  //   <ver>0<cmd>0<arg>0<arg>0<arg>0
  // where <ver> is the protocol version (1), <cmd> is the command
  // name ("load", "datadump", ...), and <arg> is an argument
  int expected_str_count = 2 + AttachOperation::arg_count_max;
  const int max_len = (sizeof(ver_str) + 1) + (AttachOperation::name_length_max + 1) +
    AttachOperation::arg_count_max*(AttachOperation::arg_length_max + 1);

  char buf[max_len];
  int str_count = 0;

  // Read until all (expected) strings have been read, the buffer is
  // full, or EOF.

  int off = 0;
  int left = max_len;

  do {
    int n;
    // Don't block on interrupts because this will
    // hang in the clean-up when shutting down.
    n = read(s, buf+off, left);
    if (n == -1) {
      return NULL;      // reset by peer or other error
    }
    if (n == 0) {       // end of file reached
      break;
    }
    for (int i=0; i<n; i++) {
      if (buf[off+i] == 0) {
        // EOS found
        str_count++;

        // The first string is <ver> so check it now to
        // check for protocol mis-match
        if (str_count == 1) {
          if ((strlen(buf) != strlen(ver_str)) ||
              (atoi(buf) != ATTACH_PROTOCOL_VER)) {
            char msg[32];
            sprintf(msg, "%d\n", ATTACH_ERROR_BADVERSION);
            write_fully(s, msg, strlen(msg));
            return NULL;
          }
        }
      }
    }
    off += n;
    left -= n;
  } while (left > 0 && str_count < expected_str_count);

  if (str_count != expected_str_count) {
    return NULL;        // incomplete request
  }

  // parse request

  ArgumentIterator args(buf, (max_len)-left);

  // version already checked
  char* v = args.next();

  char* name = args.next();
  if (name == NULL || strlen(name) > AttachOperation::name_length_max) {
    return NULL;
  }

  AixAttachOperation* op = new AixAttachOperation(name);

  for (int i=0; i<AttachOperation::arg_count_max; i++) {
    char* arg = args.next();
    if (arg == NULL) {
      op->set_arg(i, NULL);
    } else {
      if (strlen(arg) > AttachOperation::arg_length_max) {
        delete op;
        return NULL;
      }
      op->set_arg(i, arg);
    }
  }

  op->set_socket(s);
  return op;
}


// Dequeue an operation
//
// In the Linux implementation there is only a single operation and clients
// cannot queue commands (except at the socket level).
//
AixAttachOperation* AixAttachListener::dequeue() {
  for (;;) {
    int s;

    // wait for client to connect
    struct sockaddr addr;
    socklen_t len = sizeof(addr);
    memset(&addr, 0, len);
    // We must prevent accept blocking on the socket if it has been shut down.
    // Therefore we allow interrups and check whether we have been shut down already.
    if (AixAttachListener::is_shutdown()) {
      return NULL;
    }
    s=::accept(listener(), &addr, &len);
    if (s == -1) {
      return NULL;      // log a warning?
    }

    // Added timeouts for read and write.  If we get no request within the
    // next AttachListenerTimeout milliseconds we just finish the connection.
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = AttachListenerTimeout * 1000;
    ::setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, (char*)&tv, sizeof(tv));
    ::setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, (char*)&tv, sizeof(tv));

    // get the credentials of the peer and check the effective uid/guid
    // - check with jeff on this.
    struct peercred_struct cred_info;
    socklen_t optlen = sizeof(cred_info);
    if (::getsockopt(s, SOL_SOCKET, SO_PEERID, (void*)&cred_info, &optlen) == -1) {
      int res;
      RESTARTABLE(::close(s), res);
      continue;
    }
    uid_t euid = geteuid();
    gid_t egid = getegid();

    if (cred_info.euid != euid || cred_info.egid != egid) {
      int res;
      RESTARTABLE(::close(s), res);
      continue;
    }

    // peer credential look okay so we read the request
    AixAttachOperation* op = read_request(s);
    if (op == NULL) {
      int res;
      RESTARTABLE(::close(s), res);
      continue;
    } else {
      return op;
    }
  }
}

// write the given buffer to the socket
int AixAttachListener::write_fully(int s, char* buf, int len) {
  do {
    int n = ::write(s, buf, len);
    if (n == -1) {
      if (errno != EINTR) return -1;
    } else {
      buf += n;
      len -= n;
    }
  }
  while (len > 0);
  return 0;
}

// Complete an operation by sending the operation result and any result
// output to the client. At this time the socket is in blocking mode so
// potentially we can block if there is a lot of data and the client is
// non-responsive. For most operations this is a non-issue because the
// default send buffer is sufficient to buffer everything. In the future
// if there are operations that involves a very big reply then it the
// socket could be made non-blocking and a timeout could be used.

void AixAttachOperation::complete(jint result, bufferedStream* st) {
  JavaThread* thread = JavaThread::current();
  ThreadBlockInVM tbivm(thread);

  thread->set_suspend_equivalent();
  // cleared by handle_special_suspend_equivalent_condition() or
  // java_suspend_self() via check_and_wait_while_suspended()

  // write operation result
  char msg[32];
  sprintf(msg, "%d\n", result);
  int rc = AixAttachListener::write_fully(this->socket(), msg, strlen(msg));

  // write any result data
  if (rc == 0) {
    // Shutdown the socket in the cleanup function to enable more than
    // one agent attach in a sequence (see comments to listener_cleanup()).
    AixAttachListener::write_fully(this->socket(), (char*) st->base(), st->size());
  }

  // done
  RESTARTABLE(::close(this->socket()), rc);

  // were we externally suspended while we were waiting?
  thread->check_and_wait_while_suspended();

  delete this;
}


// AttachListener functions

AttachOperation* AttachListener::dequeue() {
  JavaThread* thread = JavaThread::current();
  ThreadBlockInVM tbivm(thread);

  thread->set_suspend_equivalent();
  // cleared by handle_special_suspend_equivalent_condition() or
  // java_suspend_self() via check_and_wait_while_suspended()

  AttachOperation* op = AixAttachListener::dequeue();

  // were we externally suspended while we were waiting?
  thread->check_and_wait_while_suspended();

  return op;
}

// Performs initialization at vm startup
// For AIX we remove any stale .java_pid file which could cause
// an attaching process to think we are ready to receive on the
// domain socket before we are properly initialized

void AttachListener::vm_start() {
  char fn[UNIX_PATH_MAX];
  struct stat64 st;
  int ret;

  int n = snprintf(fn, UNIX_PATH_MAX, "%s/.java_pid%d",
           os::get_temp_directory(), os::current_process_id());
  assert(n < (int)UNIX_PATH_MAX, "java_pid file name buffer overflow");

  RESTARTABLE(::stat64(fn, &st), ret);
  if (ret == 0) {
    ret = ::unlink(fn);
    if (ret == -1) {
      debug_only(warning("failed to remove stale attach pid file at %s", fn));
    }
  }
}

int AttachListener::pd_init() {
  JavaThread* thread = JavaThread::current();
  ThreadBlockInVM tbivm(thread);

  thread->set_suspend_equivalent();
  // cleared by handle_special_suspend_equivalent_condition() or
  // java_suspend_self() via check_and_wait_while_suspended()

  int ret_code = AixAttachListener::init();

  // were we externally suspended while we were waiting?
  thread->check_and_wait_while_suspended();

  return ret_code;
}

// Attach Listener is started lazily except in the case when
// +ReduseSignalUsage is used
bool AttachListener::init_at_startup() {
  if (ReduceSignalUsage) {
    return true;
  } else {
    return false;
  }
}

// If the file .attach_pid<pid> exists in the working directory
// or /tmp then this is the trigger to start the attach mechanism
bool AttachListener::is_init_trigger() {
  if (init_at_startup() || is_initialized()) {
    return false;               // initialized at startup or already initialized
  }
  char fn[PATH_MAX+1];
  sprintf(fn, ".attach_pid%d", os::current_process_id());
  int ret;
  struct stat64 st;
  RESTARTABLE(::stat64(fn, &st), ret);
  if (ret == -1) {
    snprintf(fn, sizeof(fn), "%s/.attach_pid%d",
             os::get_temp_directory(), os::current_process_id());
    RESTARTABLE(::stat64(fn, &st), ret);
  }
  if (ret == 0) {
    // simple check to avoid starting the attach mechanism when
    // a bogus user creates the file
    if (st.st_uid == geteuid()) {
      init();
      return true;
    }
  }
  return false;
}

// if VM aborts then remove listener
void AttachListener::abort() {
  listener_cleanup();
}

void AttachListener::pd_data_dump() {
  os::signal_notify(SIGQUIT);
}

AttachOperationFunctionInfo* AttachListener::pd_find_operation(const char* n) {
  return NULL;
}

jint AttachListener::pd_set_flag(AttachOperation* op, outputStream* out) {
  out->print_cr("flag '%s' cannot be changed", op->arg(0));
  return JNI_ERR;
}

void AttachListener::pd_detachall() {
  // Cleanup server socket to detach clients.
  listener_cleanup();
}
