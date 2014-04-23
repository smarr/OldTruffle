/*
 * Copyright (c) 1997, 2012, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_CODE_SCOPEDESC_HPP
#define SHARE_VM_CODE_SCOPEDESC_HPP

#include "code/debugInfo.hpp"
#include "code/pcDesc.hpp"
#include "oops/method.hpp"
#include "utilities/growableArray.hpp"

// SimpleScopeDesc is used when all you need to extract from
// a given pc,nmethod pair is a Method* and a bci. This is
// quite a bit faster than allocating a full ScopeDesc, but
// very limited in abilities.

class SimpleScopeDesc : public StackObj {
 private:
  Method* _method;
  int _bci;

 public:
  SimpleScopeDesc(nmethod* code,address pc) {
    PcDesc* pc_desc = code->pc_desc_at(pc);
    assert(pc_desc != NULL, "Must be able to find matching PcDesc");
    DebugInfoReadStream buffer(code, pc_desc->scope_decode_offset());
    int ignore_sender = buffer.read_int();
    _method           = buffer.read_method();
    _bci              = buffer.read_bci();
  }

  Method* method() { return _method; }
  int bci() { return _bci; }
};

// ScopeDescs contain the information that makes source-level debugging of
// nmethods possible; each scopeDesc describes a method activation

class ScopeDesc : public ResourceObj {
 public:
  // Constructor
  ScopeDesc(const nmethod* code, int decode_offset, int obj_decode_offset, bool reexecute, bool rethrow_exception, bool return_oop);

  // Calls above, giving default value of "serialized_null" to the
  // "obj_decode_offset" argument.  (We don't use a default argument to
  // avoid a .hpp-.hpp dependency.)
  ScopeDesc(const nmethod* code, int decode_offset, bool reexecute, bool rethrow_exception, bool return_oop);

  // JVM state
  Method* method()      const { return _method; }
  int          bci()      const { return _bci;    }
  bool should_reexecute() const { return _reexecute; }
  bool rethrow_exception() const { return _rethrow_exception; }
  bool return_oop()       const { return _return_oop; }

  GrowableArray<ScopeValue*>*   locals();
  GrowableArray<ScopeValue*>*   expressions();
  GrowableArray<MonitorValue*>* monitors();
  GrowableArray<ScopeValue*>*   objects();

  // Stack walking, returns NULL if this is the outer most scope.
  ScopeDesc* sender() const;

  // Returns where the scope was decoded
  int decode_offset() const { return _decode_offset; }

  // Tells whether sender() returns NULL
  bool is_top() const;
  // Tells whether sd is equal to this
  bool is_equal(ScopeDesc* sd) const;

 private:
  // Alternative constructor
  ScopeDesc(const ScopeDesc* parent);

  // JVM state
  Method*       _method;
  int           _bci;
  bool          _reexecute;
  bool          _rethrow_exception;
  bool          _return_oop;

  // Decoding offsets
  int _decode_offset;
  int _sender_decode_offset;
  int _locals_decode_offset;
  int _expressions_decode_offset;
  int _monitors_decode_offset;

  // Object pool
  GrowableArray<ScopeValue*>* _objects;

  // Nmethod information
  const nmethod* _code;

  // Decoding operations
  void decode_body();
  GrowableArray<ScopeValue*>* decode_scope_values(int decode_offset);
  GrowableArray<MonitorValue*>* decode_monitor_values(int decode_offset);
  GrowableArray<ScopeValue*>* decode_object_values(int decode_offset);

  DebugInfoReadStream* stream_at(int decode_offset) const;


 public:
  // Verification
  void verify();

#ifndef PRODUCT
 public:
  // Printing support
  void print_on(outputStream* st) const;
  void print_on(outputStream* st, PcDesc* pd) const;
  void print_value_on(outputStream* st) const;
#endif
};

#endif // SHARE_VM_CODE_SCOPEDESC_HPP
