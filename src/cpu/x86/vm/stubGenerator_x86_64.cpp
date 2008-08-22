/*
 * Copyright 2003-2008 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

#include "incls/_precompiled.incl"
#include "incls/_stubGenerator_x86_64.cpp.incl"

// Declaration and definition of StubGenerator (no .hpp file).
// For a more detailed description of the stub routine structure
// see the comment in stubRoutines.hpp

#define __ _masm->
#define TIMES_OOP (UseCompressedOops ? Address::times_4 : Address::times_8)

#ifdef PRODUCT
#define BLOCK_COMMENT(str) /* nothing */
#else
#define BLOCK_COMMENT(str) __ block_comment(str)
#endif

#define BIND(label) bind(label); BLOCK_COMMENT(#label ":")
const int MXCSR_MASK = 0xFFC0;  // Mask out any pending exceptions

// Stub Code definitions

static address handle_unsafe_access() {
  JavaThread* thread = JavaThread::current();
  address pc = thread->saved_exception_pc();
  // pc is the instruction which we must emulate
  // doing a no-op is fine:  return garbage from the load
  // therefore, compute npc
  address npc = Assembler::locate_next_instruction(pc);

  // request an async exception
  thread->set_pending_unsafe_access_error();

  // return address of next instruction to execute
  return npc;
}

class StubGenerator: public StubCodeGenerator {
 private:

#ifdef PRODUCT
#define inc_counter_np(counter) (0)
#else
  void inc_counter_np_(int& counter) {
    __ incrementl(ExternalAddress((address)&counter));
  }
#define inc_counter_np(counter) \
  BLOCK_COMMENT("inc_counter " #counter); \
  inc_counter_np_(counter);
#endif

  // Call stubs are used to call Java from C
  //
  // Linux Arguments:
  //    c_rarg0:   call wrapper address                   address
  //    c_rarg1:   result                                 address
  //    c_rarg2:   result type                            BasicType
  //    c_rarg3:   method                                 methodOop
  //    c_rarg4:   (interpreter) entry point              address
  //    c_rarg5:   parameters                             intptr_t*
  //    16(rbp): parameter size (in words)              int
  //    24(rbp): thread                                 Thread*
  //
  //     [ return_from_Java     ] <--- rsp
  //     [ argument word n      ]
  //      ...
  // -12 [ argument word 1      ]
  // -11 [ saved r15            ] <--- rsp_after_call
  // -10 [ saved r14            ]
  //  -9 [ saved r13            ]
  //  -8 [ saved r12            ]
  //  -7 [ saved rbx            ]
  //  -6 [ call wrapper         ]
  //  -5 [ result               ]
  //  -4 [ result type          ]
  //  -3 [ method               ]
  //  -2 [ entry point          ]
  //  -1 [ parameters           ]
  //   0 [ saved rbp            ] <--- rbp
  //   1 [ return address       ]
  //   2 [ parameter size       ]
  //   3 [ thread               ]
  //
  // Windows Arguments:
  //    c_rarg0:   call wrapper address                   address
  //    c_rarg1:   result                                 address
  //    c_rarg2:   result type                            BasicType
  //    c_rarg3:   method                                 methodOop
  //    48(rbp): (interpreter) entry point              address
  //    56(rbp): parameters                             intptr_t*
  //    64(rbp): parameter size (in words)              int
  //    72(rbp): thread                                 Thread*
  //
  //     [ return_from_Java     ] <--- rsp
  //     [ argument word n      ]
  //      ...
  //  -8 [ argument word 1      ]
  //  -7 [ saved r15            ] <--- rsp_after_call
  //  -6 [ saved r14            ]
  //  -5 [ saved r13            ]
  //  -4 [ saved r12            ]
  //  -3 [ saved rdi            ]
  //  -2 [ saved rsi            ]
  //  -1 [ saved rbx            ]
  //   0 [ saved rbp            ] <--- rbp
  //   1 [ return address       ]
  //   2 [ call wrapper         ]
  //   3 [ result               ]
  //   4 [ result type          ]
  //   5 [ method               ]
  //   6 [ entry point          ]
  //   7 [ parameters           ]
  //   8 [ parameter size       ]
  //   9 [ thread               ]
  //
  //    Windows reserves the callers stack space for arguments 1-4.
  //    We spill c_rarg0-c_rarg3 to this space.

  // Call stub stack layout word offsets from rbp
  enum call_stub_layout {
#ifdef _WIN64
    rsp_after_call_off = -7,
    r15_off            = rsp_after_call_off,
    r14_off            = -6,
    r13_off            = -5,
    r12_off            = -4,
    rdi_off            = -3,
    rsi_off            = -2,
    rbx_off            = -1,
    rbp_off            =  0,
    retaddr_off        =  1,
    call_wrapper_off   =  2,
    result_off         =  3,
    result_type_off    =  4,
    method_off         =  5,
    entry_point_off    =  6,
    parameters_off     =  7,
    parameter_size_off =  8,
    thread_off         =  9
#else
    rsp_after_call_off = -12,
    mxcsr_off          = rsp_after_call_off,
    r15_off            = -11,
    r14_off            = -10,
    r13_off            = -9,
    r12_off            = -8,
    rbx_off            = -7,
    call_wrapper_off   = -6,
    result_off         = -5,
    result_type_off    = -4,
    method_off         = -3,
    entry_point_off    = -2,
    parameters_off     = -1,
    rbp_off            =  0,
    retaddr_off        =  1,
    parameter_size_off =  2,
    thread_off         =  3
#endif
  };

  address generate_call_stub(address& return_address) {
    assert((int)frame::entry_frame_after_call_words == -(int)rsp_after_call_off + 1 &&
           (int)frame::entry_frame_call_wrapper_offset == (int)call_wrapper_off,
           "adjust this code");
    StubCodeMark mark(this, "StubRoutines", "call_stub");
    address start = __ pc();

    // same as in generate_catch_exception()!
    const Address rsp_after_call(rbp, rsp_after_call_off * wordSize);

    const Address call_wrapper  (rbp, call_wrapper_off   * wordSize);
    const Address result        (rbp, result_off         * wordSize);
    const Address result_type   (rbp, result_type_off    * wordSize);
    const Address method        (rbp, method_off         * wordSize);
    const Address entry_point   (rbp, entry_point_off    * wordSize);
    const Address parameters    (rbp, parameters_off     * wordSize);
    const Address parameter_size(rbp, parameter_size_off * wordSize);

    // same as in generate_catch_exception()!
    const Address thread        (rbp, thread_off         * wordSize);

    const Address r15_save(rbp, r15_off * wordSize);
    const Address r14_save(rbp, r14_off * wordSize);
    const Address r13_save(rbp, r13_off * wordSize);
    const Address r12_save(rbp, r12_off * wordSize);
    const Address rbx_save(rbp, rbx_off * wordSize);

    // stub code
    __ enter();
    __ subq(rsp, -rsp_after_call_off * wordSize);

    // save register parameters
#ifndef _WIN64
    __ movq(parameters,   c_rarg5); // parameters
    __ movq(entry_point,  c_rarg4); // entry_point
#endif

    __ movq(method,       c_rarg3); // method
    __ movl(result_type,  c_rarg2); // result type
    __ movq(result,       c_rarg1); // result
    __ movq(call_wrapper, c_rarg0); // call wrapper

    // save regs belonging to calling function
    __ movq(rbx_save, rbx);
    __ movq(r12_save, r12);
    __ movq(r13_save, r13);
    __ movq(r14_save, r14);
    __ movq(r15_save, r15);

#ifdef _WIN64
    const Address rdi_save(rbp, rdi_off * wordSize);
    const Address rsi_save(rbp, rsi_off * wordSize);

    __ movq(rsi_save, rsi);
    __ movq(rdi_save, rdi);
#else
    const Address mxcsr_save(rbp, mxcsr_off * wordSize);
    {
      Label skip_ldmx;
      __ stmxcsr(mxcsr_save);
      __ movl(rax, mxcsr_save);
      __ andl(rax, MXCSR_MASK);    // Only check control and mask bits
      ExternalAddress mxcsr_std(StubRoutines::amd64::mxcsr_std());
      __ cmp32(rax, mxcsr_std);
      __ jcc(Assembler::equal, skip_ldmx);
      __ ldmxcsr(mxcsr_std);
      __ bind(skip_ldmx);
    }
#endif

    // Load up thread register
    __ movq(r15_thread, thread);
    __ reinit_heapbase();

#ifdef ASSERT
    // make sure we have no pending exceptions
    {
      Label L;
      __ cmpq(Address(r15_thread, Thread::pending_exception_offset()), (int)NULL_WORD);
      __ jcc(Assembler::equal, L);
      __ stop("StubRoutines::call_stub: entered with pending exception");
      __ bind(L);
    }
#endif

    // pass parameters if any
    BLOCK_COMMENT("pass parameters if any");
    Label parameters_done;
    __ movl(c_rarg3, parameter_size);
    __ testl(c_rarg3, c_rarg3);
    __ jcc(Assembler::zero, parameters_done);

    Label loop;
    __ movq(c_rarg2, parameters);     // parameter pointer
    __ movl(c_rarg1, c_rarg3);        // parameter counter is in c_rarg1
    __ BIND(loop);
    if (TaggedStackInterpreter) {
      __ movq(rax, Address(c_rarg2, 0)); // get tag
      __ addq(c_rarg2, wordSize);     // advance to next tag
      __ pushq(rax);                  // pass tag
    }
    __ movq(rax, Address(c_rarg2, 0));  // get parameter
    __ addq(c_rarg2, wordSize);       // advance to next parameter
    __ decrementl(c_rarg1);           // decrement counter
    __ pushq(rax);                    // pass parameter
    __ jcc(Assembler::notZero, loop);

    // call Java function
    __ BIND(parameters_done);
    __ movq(rbx, method);             // get methodOop
    __ movq(c_rarg1, entry_point);    // get entry_point
    __ movq(r13, rsp);                // set sender sp
    BLOCK_COMMENT("call Java function");
    __ call(c_rarg1);

    BLOCK_COMMENT("call_stub_return_address:");
    return_address = __ pc();

    // store result depending on type (everything that is not
    // T_OBJECT, T_LONG, T_FLOAT or T_DOUBLE is treated as T_INT)
    __ movq(c_rarg0, result);
    Label is_long, is_float, is_double, exit;
    __ movl(c_rarg1, result_type);
    __ cmpl(c_rarg1, T_OBJECT);
    __ jcc(Assembler::equal, is_long);
    __ cmpl(c_rarg1, T_LONG);
    __ jcc(Assembler::equal, is_long);
    __ cmpl(c_rarg1, T_FLOAT);
    __ jcc(Assembler::equal, is_float);
    __ cmpl(c_rarg1, T_DOUBLE);
    __ jcc(Assembler::equal, is_double);

    // handle T_INT case
    __ movl(Address(c_rarg0, 0), rax);

    __ BIND(exit);

    // pop parameters
    __ leaq(rsp, rsp_after_call);

#ifdef ASSERT
    // verify that threads correspond
    {
      Label L, S;
      __ cmpq(r15_thread, thread);
      __ jcc(Assembler::notEqual, S);
      __ get_thread(rbx);
      __ cmpq(r15_thread, rbx);
      __ jcc(Assembler::equal, L);
      __ bind(S);
      __ jcc(Assembler::equal, L);
      __ stop("StubRoutines::call_stub: threads must correspond");
      __ bind(L);
    }
#endif

    // restore regs belonging to calling function
    __ movq(r15, r15_save);
    __ movq(r14, r14_save);
    __ movq(r13, r13_save);
    __ movq(r12, r12_save);
    __ movq(rbx, rbx_save);

#ifdef _WIN64
    __ movq(rdi, rdi_save);
    __ movq(rsi, rsi_save);
#else
    __ ldmxcsr(mxcsr_save);
#endif

    // restore rsp
    __ addq(rsp, -rsp_after_call_off * wordSize);

    // return
    __ popq(rbp);
    __ ret(0);

    // handle return types different from T_INT
    __ BIND(is_long);
    __ movq(Address(c_rarg0, 0), rax);
    __ jmp(exit);

    __ BIND(is_float);
    __ movflt(Address(c_rarg0, 0), xmm0);
    __ jmp(exit);

    __ BIND(is_double);
    __ movdbl(Address(c_rarg0, 0), xmm0);
    __ jmp(exit);

    return start;
  }

  // Return point for a Java call if there's an exception thrown in
  // Java code.  The exception is caught and transformed into a
  // pending exception stored in JavaThread that can be tested from
  // within the VM.
  //
  // Note: Usually the parameters are removed by the callee. In case
  // of an exception crossing an activation frame boundary, that is
  // not the case if the callee is compiled code => need to setup the
  // rsp.
  //
  // rax: exception oop

  address generate_catch_exception() {
    StubCodeMark mark(this, "StubRoutines", "catch_exception");
    address start = __ pc();

    // same as in generate_call_stub():
    const Address rsp_after_call(rbp, rsp_after_call_off * wordSize);
    const Address thread        (rbp, thread_off         * wordSize);

#ifdef ASSERT
    // verify that threads correspond
    {
      Label L, S;
      __ cmpq(r15_thread, thread);
      __ jcc(Assembler::notEqual, S);
      __ get_thread(rbx);
      __ cmpq(r15_thread, rbx);
      __ jcc(Assembler::equal, L);
      __ bind(S);
      __ stop("StubRoutines::catch_exception: threads must correspond");
      __ bind(L);
    }
#endif

    // set pending exception
    __ verify_oop(rax);

    __ movq(Address(r15_thread, Thread::pending_exception_offset()), rax);
    __ lea(rscratch1, ExternalAddress((address)__FILE__));
    __ movq(Address(r15_thread, Thread::exception_file_offset()), rscratch1);
    __ movl(Address(r15_thread, Thread::exception_line_offset()), (int)  __LINE__);

    // complete return to VM
    assert(StubRoutines::_call_stub_return_address != NULL,
           "_call_stub_return_address must have been generated before");
    __ jump(RuntimeAddress(StubRoutines::_call_stub_return_address));

    return start;
  }

  // Continuation point for runtime calls returning with a pending
  // exception.  The pending exception check happened in the runtime
  // or native call stub.  The pending exception in Thread is
  // converted into a Java-level exception.
  //
  // Contract with Java-level exception handlers:
  // rax: exception
  // rdx: throwing pc
  //
  // NOTE: At entry of this stub, exception-pc must be on stack !!

  address generate_forward_exception() {
    StubCodeMark mark(this, "StubRoutines", "forward exception");
    address start = __ pc();

    // Upon entry, the sp points to the return address returning into
    // Java (interpreted or compiled) code; i.e., the return address
    // becomes the throwing pc.
    //
    // Arguments pushed before the runtime call are still on the stack
    // but the exception handler will reset the stack pointer ->
    // ignore them.  A potential result in registers can be ignored as
    // well.

#ifdef ASSERT
    // make sure this code is only executed if there is a pending exception
    {
      Label L;
      __ cmpq(Address(r15_thread, Thread::pending_exception_offset()), (int) NULL);
      __ jcc(Assembler::notEqual, L);
      __ stop("StubRoutines::forward exception: no pending exception (1)");
      __ bind(L);
    }
#endif

    // compute exception handler into rbx
    __ movq(c_rarg0, Address(rsp, 0));
    BLOCK_COMMENT("call exception_handler_for_return_address");
    __ call_VM_leaf(CAST_FROM_FN_PTR(address,
                         SharedRuntime::exception_handler_for_return_address),
                    c_rarg0);
    __ movq(rbx, rax);

    // setup rax & rdx, remove return address & clear pending exception
    __ popq(rdx);
    __ movq(rax, Address(r15_thread, Thread::pending_exception_offset()));
    __ movptr(Address(r15_thread, Thread::pending_exception_offset()), (int)NULL_WORD);

#ifdef ASSERT
    // make sure exception is set
    {
      Label L;
      __ testq(rax, rax);
      __ jcc(Assembler::notEqual, L);
      __ stop("StubRoutines::forward exception: no pending exception (2)");
      __ bind(L);
    }
#endif

    // continue at exception handler (return address removed)
    // rax: exception
    // rbx: exception handler
    // rdx: throwing pc
    __ verify_oop(rax);
    __ jmp(rbx);

    return start;
  }

  // Support for jint atomic::xchg(jint exchange_value, volatile jint* dest)
  //
  // Arguments :
  //    c_rarg0: exchange_value
  //    c_rarg0: dest
  //
  // Result:
  //    *dest <- ex, return (orig *dest)
  address generate_atomic_xchg() {
    StubCodeMark mark(this, "StubRoutines", "atomic_xchg");
    address start = __ pc();

    __ movl(rax, c_rarg0); // Copy to eax we need a return value anyhow
    __ xchgl(rax, Address(c_rarg1, 0)); // automatic LOCK
    __ ret(0);

    return start;
  }

  // Support for intptr_t atomic::xchg_ptr(intptr_t exchange_value, volatile intptr_t* dest)
  //
  // Arguments :
  //    c_rarg0: exchange_value
  //    c_rarg1: dest
  //
  // Result:
  //    *dest <- ex, return (orig *dest)
  address generate_atomic_xchg_ptr() {
    StubCodeMark mark(this, "StubRoutines", "atomic_xchg_ptr");
    address start = __ pc();

    __ movq(rax, c_rarg0); // Copy to eax we need a return value anyhow
    __ xchgq(rax, Address(c_rarg1, 0)); // automatic LOCK
    __ ret(0);

    return start;
  }

  // Support for jint atomic::atomic_cmpxchg(jint exchange_value, volatile jint* dest,
  //                                         jint compare_value)
  //
  // Arguments :
  //    c_rarg0: exchange_value
  //    c_rarg1: dest
  //    c_rarg2: compare_value
  //
  // Result:
  //    if ( compare_value == *dest ) {
  //       *dest = exchange_value
  //       return compare_value;
  //    else
  //       return *dest;
  address generate_atomic_cmpxchg() {
    StubCodeMark mark(this, "StubRoutines", "atomic_cmpxchg");
    address start = __ pc();

    __ movl(rax, c_rarg2);
   if ( os::is_MP() ) __ lock();
    __ cmpxchgl(c_rarg0, Address(c_rarg1, 0));
    __ ret(0);

    return start;
  }

  // Support for jint atomic::atomic_cmpxchg_long(jlong exchange_value,
  //                                             volatile jlong* dest,
  //                                             jlong compare_value)
  // Arguments :
  //    c_rarg0: exchange_value
  //    c_rarg1: dest
  //    c_rarg2: compare_value
  //
  // Result:
  //    if ( compare_value == *dest ) {
  //       *dest = exchange_value
  //       return compare_value;
  //    else
  //       return *dest;
  address generate_atomic_cmpxchg_long() {
    StubCodeMark mark(this, "StubRoutines", "atomic_cmpxchg_long");
    address start = __ pc();

    __ movq(rax, c_rarg2);
   if ( os::is_MP() ) __ lock();
    __ cmpxchgq(c_rarg0, Address(c_rarg1, 0));
    __ ret(0);

    return start;
  }

  // Support for jint atomic::add(jint add_value, volatile jint* dest)
  //
  // Arguments :
  //    c_rarg0: add_value
  //    c_rarg1: dest
  //
  // Result:
  //    *dest += add_value
  //    return *dest;
  address generate_atomic_add() {
    StubCodeMark mark(this, "StubRoutines", "atomic_add");
    address start = __ pc();

    __ movl(rax, c_rarg0);
   if ( os::is_MP() ) __ lock();
    __ xaddl(Address(c_rarg1, 0), c_rarg0);
    __ addl(rax, c_rarg0);
    __ ret(0);

    return start;
  }

  // Support for intptr_t atomic::add_ptr(intptr_t add_value, volatile intptr_t* dest)
  //
  // Arguments :
  //    c_rarg0: add_value
  //    c_rarg1: dest
  //
  // Result:
  //    *dest += add_value
  //    return *dest;
  address generate_atomic_add_ptr() {
    StubCodeMark mark(this, "StubRoutines", "atomic_add_ptr");
    address start = __ pc();

    __ movq(rax, c_rarg0); // Copy to eax we need a return value anyhow
   if ( os::is_MP() ) __ lock();
    __ xaddl(Address(c_rarg1, 0), c_rarg0);
    __ addl(rax, c_rarg0);
    __ ret(0);

    return start;
  }

  // Support for intptr_t OrderAccess::fence()
  //
  // Arguments :
  //
  // Result:
  address generate_orderaccess_fence() {
    StubCodeMark mark(this, "StubRoutines", "orderaccess_fence");
    address start = __ pc();
    __ mfence();
    __ ret(0);

    return start;
  }

  // Support for intptr_t get_previous_fp()
  //
  // This routine is used to find the previous frame pointer for the
  // caller (current_frame_guess). This is used as part of debugging
  // ps() is seemingly lost trying to find frames.
  // This code assumes that caller current_frame_guess) has a frame.
  address generate_get_previous_fp() {
    StubCodeMark mark(this, "StubRoutines", "get_previous_fp");
    const Address old_fp(rbp, 0);
    const Address older_fp(rax, 0);
    address start = __ pc();

    __ enter();
    __ movq(rax, old_fp); // callers fp
    __ movq(rax, older_fp); // the frame for ps()
    __ popq(rbp);
    __ ret(0);

    return start;
  }

  //----------------------------------------------------------------------------------------------------
  // Support for void verify_mxcsr()
  //
  // This routine is used with -Xcheck:jni to verify that native
  // JNI code does not return to Java code without restoring the
  // MXCSR register to our expected state.

  address generate_verify_mxcsr() {
    StubCodeMark mark(this, "StubRoutines", "verify_mxcsr");
    address start = __ pc();

    const Address mxcsr_save(rsp, 0);

    if (CheckJNICalls) {
      Label ok_ret;
      __ pushq(rax);
      __ subq(rsp, wordSize);      // allocate a temp location
      __ stmxcsr(mxcsr_save);
      __ movl(rax, mxcsr_save);
      __ andl(rax, MXCSR_MASK);    // Only check control and mask bits
      __ cmpl(rax, *(int *)(StubRoutines::amd64::mxcsr_std()));
      __ jcc(Assembler::equal, ok_ret);

      __ warn("MXCSR changed by native JNI code, use -XX:+RestoreMXCSROnJNICall");

      __ ldmxcsr(ExternalAddress(StubRoutines::amd64::mxcsr_std()));

      __ bind(ok_ret);
      __ addq(rsp, wordSize);
      __ popq(rax);
    }

    __ ret(0);

    return start;
  }

  address generate_f2i_fixup() {
    StubCodeMark mark(this, "StubRoutines", "f2i_fixup");
    Address inout(rsp, 5 * wordSize); // return address + 4 saves

    address start = __ pc();

    Label L;

    __ pushq(rax);
    __ pushq(c_rarg3);
    __ pushq(c_rarg2);
    __ pushq(c_rarg1);

    __ movl(rax, 0x7f800000);
    __ xorl(c_rarg3, c_rarg3);
    __ movl(c_rarg2, inout);
    __ movl(c_rarg1, c_rarg2);
    __ andl(c_rarg1, 0x7fffffff);
    __ cmpl(rax, c_rarg1); // NaN? -> 0
    __ jcc(Assembler::negative, L);
    __ testl(c_rarg2, c_rarg2); // signed ? min_jint : max_jint
    __ movl(c_rarg3, 0x80000000);
    __ movl(rax, 0x7fffffff);
    __ cmovl(Assembler::positive, c_rarg3, rax);

    __ bind(L);
    __ movq(inout, c_rarg3);

    __ popq(c_rarg1);
    __ popq(c_rarg2);
    __ popq(c_rarg3);
    __ popq(rax);

    __ ret(0);

    return start;
  }

  address generate_f2l_fixup() {
    StubCodeMark mark(this, "StubRoutines", "f2l_fixup");
    Address inout(rsp, 5 * wordSize); // return address + 4 saves
    address start = __ pc();

    Label L;

    __ pushq(rax);
    __ pushq(c_rarg3);
    __ pushq(c_rarg2);
    __ pushq(c_rarg1);

    __ movl(rax, 0x7f800000);
    __ xorl(c_rarg3, c_rarg3);
    __ movl(c_rarg2, inout);
    __ movl(c_rarg1, c_rarg2);
    __ andl(c_rarg1, 0x7fffffff);
    __ cmpl(rax, c_rarg1); // NaN? -> 0
    __ jcc(Assembler::negative, L);
    __ testl(c_rarg2, c_rarg2); // signed ? min_jlong : max_jlong
    __ mov64(c_rarg3, 0x8000000000000000);
    __ mov64(rax, 0x7fffffffffffffff);
    __ cmovq(Assembler::positive, c_rarg3, rax);

    __ bind(L);
    __ movq(inout, c_rarg3);

    __ popq(c_rarg1);
    __ popq(c_rarg2);
    __ popq(c_rarg3);
    __ popq(rax);

    __ ret(0);

    return start;
  }

  address generate_d2i_fixup() {
    StubCodeMark mark(this, "StubRoutines", "d2i_fixup");
    Address inout(rsp, 6 * wordSize); // return address + 5 saves

    address start = __ pc();

    Label L;

    __ pushq(rax);
    __ pushq(c_rarg3);
    __ pushq(c_rarg2);
    __ pushq(c_rarg1);
    __ pushq(c_rarg0);

    __ movl(rax, 0x7ff00000);
    __ movq(c_rarg2, inout);
    __ movl(c_rarg3, c_rarg2);
    __ movq(c_rarg1, c_rarg2);
    __ movq(c_rarg0, c_rarg2);
    __ negl(c_rarg3);
    __ shrq(c_rarg1, 0x20);
    __ orl(c_rarg3, c_rarg2);
    __ andl(c_rarg1, 0x7fffffff);
    __ xorl(c_rarg2, c_rarg2);
    __ shrl(c_rarg3, 0x1f);
    __ orl(c_rarg1, c_rarg3);
    __ cmpl(rax, c_rarg1);
    __ jcc(Assembler::negative, L); // NaN -> 0
    __ testq(c_rarg0, c_rarg0); // signed ? min_jint : max_jint
    __ movl(c_rarg2, 0x80000000);
    __ movl(rax, 0x7fffffff);
    __ cmovl(Assembler::positive, c_rarg2, rax);

    __ bind(L);
    __ movq(inout, c_rarg2);

    __ popq(c_rarg0);
    __ popq(c_rarg1);
    __ popq(c_rarg2);
    __ popq(c_rarg3);
    __ popq(rax);

    __ ret(0);

    return start;
  }

  address generate_d2l_fixup() {
    StubCodeMark mark(this, "StubRoutines", "d2l_fixup");
    Address inout(rsp, 6 * wordSize); // return address + 5 saves

    address start = __ pc();

    Label L;

    __ pushq(rax);
    __ pushq(c_rarg3);
    __ pushq(c_rarg2);
    __ pushq(c_rarg1);
    __ pushq(c_rarg0);

    __ movl(rax, 0x7ff00000);
    __ movq(c_rarg2, inout);
    __ movl(c_rarg3, c_rarg2);
    __ movq(c_rarg1, c_rarg2);
    __ movq(c_rarg0, c_rarg2);
    __ negl(c_rarg3);
    __ shrq(c_rarg1, 0x20);
    __ orl(c_rarg3, c_rarg2);
    __ andl(c_rarg1, 0x7fffffff);
    __ xorl(c_rarg2, c_rarg2);
    __ shrl(c_rarg3, 0x1f);
    __ orl(c_rarg1, c_rarg3);
    __ cmpl(rax, c_rarg1);
    __ jcc(Assembler::negative, L); // NaN -> 0
    __ testq(c_rarg0, c_rarg0); // signed ? min_jlong : max_jlong
    __ mov64(c_rarg2, 0x8000000000000000);
    __ mov64(rax, 0x7fffffffffffffff);
    __ cmovq(Assembler::positive, c_rarg2, rax);

    __ bind(L);
    __ movq(inout, c_rarg2);

    __ popq(c_rarg0);
    __ popq(c_rarg1);
    __ popq(c_rarg2);
    __ popq(c_rarg3);
    __ popq(rax);

    __ ret(0);

    return start;
  }

  address generate_fp_mask(const char *stub_name, int64_t mask) {
    StubCodeMark mark(this, "StubRoutines", stub_name);

    __ align(16);
    address start = __ pc();

    __ emit_data64( mask, relocInfo::none );
    __ emit_data64( mask, relocInfo::none );

    return start;
  }

  // The following routine generates a subroutine to throw an
  // asynchronous UnknownError when an unsafe access gets a fault that
  // could not be reasonably prevented by the programmer.  (Example:
  // SIGBUS/OBJERR.)
  address generate_handler_for_unsafe_access() {
    StubCodeMark mark(this, "StubRoutines", "handler_for_unsafe_access");
    address start = __ pc();

    __ pushq(0);                      // hole for return address-to-be
    __ pushaq();                      // push registers
    Address next_pc(rsp, RegisterImpl::number_of_registers * BytesPerWord);

    __ subq(rsp, frame::arg_reg_save_area_bytes);
    BLOCK_COMMENT("call handle_unsafe_access");
    __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, handle_unsafe_access)));
    __ addq(rsp, frame::arg_reg_save_area_bytes);

    __ movq(next_pc, rax);            // stuff next address
    __ popaq();
    __ ret(0);                        // jump to next address

    return start;
  }

  // Non-destructive plausibility checks for oops
  //
  // Arguments:
  //    all args on stack!
  //
  // Stack after saving c_rarg3:
  //    [tos + 0]: saved c_rarg3
  //    [tos + 1]: saved c_rarg2
  //    [tos + 2]: saved r12 (several TemplateTable methods use it)
  //    [tos + 3]: saved flags
  //    [tos + 4]: return address
  //  * [tos + 5]: error message (char*)
  //  * [tos + 6]: object to verify (oop)
  //  * [tos + 7]: saved rax - saved by caller and bashed
  //  * = popped on exit
  address generate_verify_oop() {
    StubCodeMark mark(this, "StubRoutines", "verify_oop");
    address start = __ pc();

    Label exit, error;

    __ pushfq();
    __ incrementl(ExternalAddress((address) StubRoutines::verify_oop_count_addr()));

    __ pushq(r12);

    // save c_rarg2 and c_rarg3
    __ pushq(c_rarg2);
    __ pushq(c_rarg3);

    enum {
           // After previous pushes.
           oop_to_verify = 6 * wordSize,
           saved_rax     = 7 * wordSize,

           // Before the call to MacroAssembler::debug(), see below.
           return_addr   = 16 * wordSize,
           error_msg     = 17 * wordSize
    };

    // get object
    __ movq(rax, Address(rsp, oop_to_verify));

    // make sure object is 'reasonable'
    __ testq(rax, rax);
    __ jcc(Assembler::zero, exit); // if obj is NULL it is OK
    // Check if the oop is in the right area of memory
    __ movq(c_rarg2, rax);
    __ movptr(c_rarg3, (int64_t) Universe::verify_oop_mask());
    __ andq(c_rarg2, c_rarg3);
    __ movptr(c_rarg3, (int64_t) Universe::verify_oop_bits());
    __ cmpq(c_rarg2, c_rarg3);
    __ jcc(Assembler::notZero, error);

    // set r12 to heapbase for load_klass()
    __ reinit_heapbase();

    // make sure klass is 'reasonable'
    __ load_klass(rax, rax);  // get klass
    __ testq(rax, rax);
    __ jcc(Assembler::zero, error); // if klass is NULL it is broken
    // Check if the klass is in the right area of memory
    __ movq(c_rarg2, rax);
    __ movptr(c_rarg3, (int64_t) Universe::verify_klass_mask());
    __ andq(c_rarg2, c_rarg3);
    __ movptr(c_rarg3, (int64_t) Universe::verify_klass_bits());
    __ cmpq(c_rarg2, c_rarg3);
    __ jcc(Assembler::notZero, error);

    // make sure klass' klass is 'reasonable'
    __ load_klass(rax, rax);
    __ testq(rax, rax);
    __ jcc(Assembler::zero, error); // if klass' klass is NULL it is broken
    // Check if the klass' klass is in the right area of memory
    __ movptr(c_rarg3, (int64_t) Universe::verify_klass_mask());
    __ andq(rax, c_rarg3);
    __ movptr(c_rarg3, (int64_t) Universe::verify_klass_bits());
    __ cmpq(rax, c_rarg3);
    __ jcc(Assembler::notZero, error);

    // return if everything seems ok
    __ bind(exit);
    __ movq(rax, Address(rsp, saved_rax));       // get saved rax back
    __ popq(c_rarg3);                            // restore c_rarg3
    __ popq(c_rarg2);                            // restore c_rarg2
    __ popq(r12);                                // restore r12
    __ popfq();                                  // restore flags
    __ ret(3 * wordSize);                        // pop caller saved stuff

    // handle errors
    __ bind(error);
    __ movq(rax, Address(rsp, saved_rax));       // get saved rax back
    __ popq(c_rarg3);                            // get saved c_rarg3 back
    __ popq(c_rarg2);                            // get saved c_rarg2 back
    __ popq(r12);                                // get saved r12 back
    __ popfq();                                  // get saved flags off stack --
                                                 // will be ignored

    __ pushaq();                                 // push registers
                                                 // (rip is already
                                                 // already pushed)
    // debug(char* msg, int64_t pc, int64_t regs[])
    // We've popped the registers we'd saved (c_rarg3, c_rarg2 and flags), and
    // pushed all the registers, so now the stack looks like:
    //     [tos +  0] 16 saved registers
    //     [tos + 16] return address
    //   * [tos + 17] error message (char*)
    //   * [tos + 18] object to verify (oop)
    //   * [tos + 19] saved rax - saved by caller and bashed
    //   * = popped on exit

    __ movq(c_rarg0, Address(rsp, error_msg));   // pass address of error message
    __ movq(c_rarg1, Address(rsp, return_addr)); // pass return address
    __ movq(c_rarg2, rsp);                       // pass address of regs on stack
    __ movq(r12, rsp);                           // remember rsp
    __ subq(rsp, frame::arg_reg_save_area_bytes);// windows
    __ andq(rsp, -16);                           // align stack as required by ABI
    BLOCK_COMMENT("call MacroAssembler::debug");
    __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, MacroAssembler::debug)));
    __ movq(rsp, r12);                           // restore rsp
    __ popaq();                                  // pop registers (includes r12)
    __ ret(3 * wordSize);                        // pop caller saved stuff

    return start;
  }

  static address disjoint_byte_copy_entry;
  static address disjoint_short_copy_entry;
  static address disjoint_int_copy_entry;
  static address disjoint_long_copy_entry;
  static address disjoint_oop_copy_entry;

  static address byte_copy_entry;
  static address short_copy_entry;
  static address int_copy_entry;
  static address long_copy_entry;
  static address oop_copy_entry;

  static address checkcast_copy_entry;

  //
  // Verify that a register contains clean 32-bits positive value
  // (high 32-bits are 0) so it could be used in 64-bits shifts.
  //
  //  Input:
  //    Rint  -  32-bits value
  //    Rtmp  -  scratch
  //
  void assert_clean_int(Register Rint, Register Rtmp) {
#ifdef ASSERT
    Label L;
    assert_different_registers(Rtmp, Rint);
    __ movslq(Rtmp, Rint);
    __ cmpq(Rtmp, Rint);
    __ jcc(Assembler::equal, L);
    __ stop("high 32-bits of int value are not 0");
    __ bind(L);
#endif
  }

  //  Generate overlap test for array copy stubs
  //
  //  Input:
  //     c_rarg0 - from
  //     c_rarg1 - to
  //     c_rarg2 - element count
  //
  //  Output:
  //     rax   - &from[element count - 1]
  //
  void array_overlap_test(address no_overlap_target, Address::ScaleFactor sf) {
    assert(no_overlap_target != NULL, "must be generated");
    array_overlap_test(no_overlap_target, NULL, sf);
  }
  void array_overlap_test(Label& L_no_overlap, Address::ScaleFactor sf) {
    array_overlap_test(NULL, &L_no_overlap, sf);
  }
  void array_overlap_test(address no_overlap_target, Label* NOLp, Address::ScaleFactor sf) {
    const Register from     = c_rarg0;
    const Register to       = c_rarg1;
    const Register count    = c_rarg2;
    const Register end_from = rax;

    __ cmpq(to, from);
    __ leaq(end_from, Address(from, count, sf, 0));
    if (NOLp == NULL) {
      ExternalAddress no_overlap(no_overlap_target);
      __ jump_cc(Assembler::belowEqual, no_overlap);
      __ cmpq(to, end_from);
      __ jump_cc(Assembler::aboveEqual, no_overlap);
    } else {
      __ jcc(Assembler::belowEqual, (*NOLp));
      __ cmpq(to, end_from);
      __ jcc(Assembler::aboveEqual, (*NOLp));
    }
  }

  // Shuffle first three arg regs on Windows into Linux/Solaris locations.
  //
  // Outputs:
  //    rdi - rcx
  //    rsi - rdx
  //    rdx - r8
  //    rcx - r9
  //
  // Registers r9 and r10 are used to save rdi and rsi on Windows, which latter
  // are non-volatile.  r9 and r10 should not be used by the caller.
  //
  void setup_arg_regs(int nargs = 3) {
    const Register saved_rdi = r9;
    const Register saved_rsi = r10;
    assert(nargs == 3 || nargs == 4, "else fix");
#ifdef _WIN64
    assert(c_rarg0 == rcx && c_rarg1 == rdx && c_rarg2 == r8 && c_rarg3 == r9,
           "unexpected argument registers");
    if (nargs >= 4)
      __ movq(rax, r9);  // r9 is also saved_rdi
    __ movq(saved_rdi, rdi);
    __ movq(saved_rsi, rsi);
    __ movq(rdi, rcx); // c_rarg0
    __ movq(rsi, rdx); // c_rarg1
    __ movq(rdx, r8);  // c_rarg2
    if (nargs >= 4)
      __ movq(rcx, rax); // c_rarg3 (via rax)
#else
    assert(c_rarg0 == rdi && c_rarg1 == rsi && c_rarg2 == rdx && c_rarg3 == rcx,
           "unexpected argument registers");
#endif
  }

  void restore_arg_regs() {
    const Register saved_rdi = r9;
    const Register saved_rsi = r10;
#ifdef _WIN64
    __ movq(rdi, saved_rdi);
    __ movq(rsi, saved_rsi);
#endif
  }

  // Generate code for an array write pre barrier
  //
  //     addr    -  starting address
  //     count    -  element count
  //
  //     Destroy no registers!
  //
  void  gen_write_ref_array_pre_barrier(Register addr, Register count) {
    BarrierSet* bs = Universe::heap()->barrier_set();
    switch (bs->kind()) {
      case BarrierSet::G1SATBCT:
      case BarrierSet::G1SATBCTLogging:
        {
          __ pushaq();                      // push registers
          if (count == c_rarg0) {
            if (addr == c_rarg1) {
              // exactly backwards!!
              __ xchgq(c_rarg1, c_rarg0);
            } else {
              __ movq(c_rarg1, count);
              __ movq(c_rarg0, addr);
            }

          } else {
            __ movq(c_rarg0, addr);
            __ movq(c_rarg1, count);
          }
          __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, BarrierSet::static_write_ref_array_pre)));
          __ popaq();
        }
        break;
      case BarrierSet::CardTableModRef:
      case BarrierSet::CardTableExtension:
      case BarrierSet::ModRef:
        break;
      default:
        ShouldNotReachHere();

    }
  }

  //
  // Generate code for an array write post barrier
  //
  //  Input:
  //     start    - register containing starting address of destination array
  //     end      - register containing ending address of destination array
  //     scratch  - scratch register
  //
  //  The input registers are overwritten.
  //  The ending address is inclusive.
  void  gen_write_ref_array_post_barrier(Register start, Register end, Register scratch) {
    assert_different_registers(start, end, scratch);
    BarrierSet* bs = Universe::heap()->barrier_set();
    switch (bs->kind()) {
      case BarrierSet::G1SATBCT:
      case BarrierSet::G1SATBCTLogging:

        {
          __ pushaq();                      // push registers (overkill)
          // must compute element count unless barrier set interface is changed (other platforms supply count)
          assert_different_registers(start, end, scratch);
          __ leaq(scratch, Address(end, wordSize));
          __ subq(scratch, start);
          __ shrq(scratch, LogBytesPerWord);
          __ movq(c_rarg0, start);
          __ movq(c_rarg1, scratch);
          __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, BarrierSet::static_write_ref_array_post)));
          __ popaq();
        }
        break;
      case BarrierSet::CardTableModRef:
      case BarrierSet::CardTableExtension:
        {
          CardTableModRefBS* ct = (CardTableModRefBS*)bs;
          assert(sizeof(*ct->byte_map_base) == sizeof(jbyte), "adjust this code");

          Label L_loop;

           __ shrq(start, CardTableModRefBS::card_shift);
           __ shrq(end, CardTableModRefBS::card_shift);
           __ subq(end, start); // number of bytes to copy

          intptr_t disp = (intptr_t) ct->byte_map_base;
          if (__ is_simm32(disp)) {
            Address cardtable(noreg, noreg, Address::no_scale, disp);
            __ lea(scratch, cardtable);
          } else {
            ExternalAddress cardtable((address)disp);
            __ lea(scratch, cardtable);
          }

          const Register count = end; // 'end' register contains bytes count now
          __ addq(start, scratch);
        __ BIND(L_loop);
          __ movb(Address(start, count, Address::times_1), 0);
          __ decrementq(count);
          __ jcc(Assembler::greaterEqual, L_loop);
        }
        break;
      default:
        ShouldNotReachHere();

    }
  }

  // Copy big chunks forward
  //
  // Inputs:
  //   end_from     - source arrays end address
  //   end_to       - destination array end address
  //   qword_count  - 64-bits element count, negative
  //   to           - scratch
  //   L_copy_32_bytes - entry label
  //   L_copy_8_bytes  - exit  label
  //
  void copy_32_bytes_forward(Register end_from, Register end_to,
                             Register qword_count, Register to,
                             Label& L_copy_32_bytes, Label& L_copy_8_bytes) {
    DEBUG_ONLY(__ stop("enter at entry label, not here"));
    Label L_loop;
    __ align(16);
  __ BIND(L_loop);
    __ movq(to, Address(end_from, qword_count, Address::times_8, -24));
    __ movq(Address(end_to, qword_count, Address::times_8, -24), to);
    __ movq(to, Address(end_from, qword_count, Address::times_8, -16));
    __ movq(Address(end_to, qword_count, Address::times_8, -16), to);
    __ movq(to, Address(end_from, qword_count, Address::times_8, - 8));
    __ movq(Address(end_to, qword_count, Address::times_8, - 8), to);
    __ movq(to, Address(end_from, qword_count, Address::times_8, - 0));
    __ movq(Address(end_to, qword_count, Address::times_8, - 0), to);
  __ BIND(L_copy_32_bytes);
    __ addq(qword_count, 4);
    __ jcc(Assembler::lessEqual, L_loop);
    __ subq(qword_count, 4);
    __ jcc(Assembler::less, L_copy_8_bytes); // Copy trailing qwords
  }


  // Copy big chunks backward
  //
  // Inputs:
  //   from         - source arrays address
  //   dest         - destination array address
  //   qword_count  - 64-bits element count
  //   to           - scratch
  //   L_copy_32_bytes - entry label
  //   L_copy_8_bytes  - exit  label
  //
  void copy_32_bytes_backward(Register from, Register dest,
                              Register qword_count, Register to,
                              Label& L_copy_32_bytes, Label& L_copy_8_bytes) {
    DEBUG_ONLY(__ stop("enter at entry label, not here"));
    Label L_loop;
    __ align(16);
  __ BIND(L_loop);
    __ movq(to, Address(from, qword_count, Address::times_8, 24));
    __ movq(Address(dest, qword_count, Address::times_8, 24), to);
    __ movq(to, Address(from, qword_count, Address::times_8, 16));
    __ movq(Address(dest, qword_count, Address::times_8, 16), to);
    __ movq(to, Address(from, qword_count, Address::times_8,  8));
    __ movq(Address(dest, qword_count, Address::times_8,  8), to);
    __ movq(to, Address(from, qword_count, Address::times_8,  0));
    __ movq(Address(dest, qword_count, Address::times_8,  0), to);
  __ BIND(L_copy_32_bytes);
    __ subq(qword_count, 4);
    __ jcc(Assembler::greaterEqual, L_loop);
    __ addq(qword_count, 4);
    __ jcc(Assembler::greater, L_copy_8_bytes); // Copy trailing qwords
  }


  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4-, 2-, or 1-byte boundaries,
  // we let the hardware handle it.  The one to eight bytes within words,
  // dwords or qwords that span cache line boundaries will still be loaded
  // and stored atomically.
  //
  // Side Effects:
  //   disjoint_byte_copy_entry is set to the no-overlap entry point
  //   used by generate_conjoint_byte_copy().
  //
  address generate_disjoint_byte_copy(bool aligned, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_4_bytes, L_copy_2_bytes;
    Label L_copy_byte, L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register byte_count  = rcx;
    const Register qword_count = count;
    const Register end_from    = from; // source array end address
    const Register end_to      = to;   // destination array end address
    // End pointers are inclusive, and if count is not zero they point
    // to the last unit copied:  end_to[0] := end_from[0]

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    disjoint_byte_copy_entry = __ pc();
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'count' are now valid
    __ movq(byte_count, count);
    __ shrq(count, 3); // count => qword_count

    // Copy from low to high addresses.  Use 'to' as scratch.
    __ leaq(end_from, Address(from, qword_count, Address::times_8, -8));
    __ leaq(end_to,   Address(to,   qword_count, Address::times_8, -8));
    __ negq(qword_count); // make the count negative
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(end_from, qword_count, Address::times_8, 8));
    __ movq(Address(end_to, qword_count, Address::times_8, 8), rax);
    __ incrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    // Check for and copy trailing dword
  __ BIND(L_copy_4_bytes);
    __ testq(byte_count, 4);
    __ jccb(Assembler::zero, L_copy_2_bytes);
    __ movl(rax, Address(end_from, 8));
    __ movl(Address(end_to, 8), rax);

    __ addq(end_from, 4);
    __ addq(end_to, 4);

    // Check for and copy trailing word
  __ BIND(L_copy_2_bytes);
    __ testq(byte_count, 2);
    __ jccb(Assembler::zero, L_copy_byte);
    __ movw(rax, Address(end_from, 8));
    __ movw(Address(end_to, 8), rax);

    __ addq(end_from, 2);
    __ addq(end_to, 2);

    // Check for and copy trailing byte
  __ BIND(L_copy_byte);
    __ testq(byte_count, 1);
    __ jccb(Assembler::zero, L_exit);
    __ movb(rax, Address(end_from, 8));
    __ movb(Address(end_to, 8), rax);

  __ BIND(L_exit);
    inc_counter_np(SharedRuntime::_jbyte_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy in 32-bytes chunks
    copy_32_bytes_forward(end_from, end_to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);
    __ jmp(L_copy_4_bytes);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4-, 2-, or 1-byte boundaries,
  // we let the hardware handle it.  The one to eight bytes within words,
  // dwords or qwords that span cache line boundaries will still be loaded
  // and stored atomically.
  //
  address generate_conjoint_byte_copy(bool aligned, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_4_bytes, L_copy_2_bytes;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register byte_count  = rcx;
    const Register qword_count = count;

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    byte_copy_entry = __ pc();
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    array_overlap_test(disjoint_byte_copy_entry, Address::times_1);
    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'count' are now valid
    __ movq(byte_count, count);
    __ shrq(count, 3);   // count => qword_count

    // Copy from high to low addresses.

    // Check for and copy trailing byte
    __ testq(byte_count, 1);
    __ jcc(Assembler::zero, L_copy_2_bytes);
    __ movb(rax, Address(from, byte_count, Address::times_1, -1));
    __ movb(Address(to, byte_count, Address::times_1, -1), rax);
    __ decrementq(byte_count); // Adjust for possible trailing word

    // Check for and copy trailing word
  __ BIND(L_copy_2_bytes);
    __ testq(byte_count, 2);
    __ jcc(Assembler::zero, L_copy_4_bytes);
    __ movw(rax, Address(from, byte_count, Address::times_1, -2));
    __ movw(Address(to, byte_count, Address::times_1, -2), rax);

    // Check for and copy trailing dword
  __ BIND(L_copy_4_bytes);
    __ testq(byte_count, 4);
    __ jcc(Assembler::zero, L_copy_32_bytes);
    __ movl(rax, Address(from, qword_count, Address::times_8));
    __ movl(Address(to, qword_count, Address::times_8), rax);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(from, qword_count, Address::times_8, -8));
    __ movq(Address(to, qword_count, Address::times_8, -8), rax);
    __ decrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    inc_counter_np(SharedRuntime::_jbyte_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy in 32-bytes chunks
    copy_32_bytes_backward(from, to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);

    inc_counter_np(SharedRuntime::_jbyte_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4- or 2-byte boundaries, we
  // let the hardware handle it.  The two or four words within dwords
  // or qwords that span cache line boundaries will still be loaded
  // and stored atomically.
  //
  // Side Effects:
  //   disjoint_short_copy_entry is set to the no-overlap entry point
  //   used by generate_conjoint_short_copy().
  //
  address generate_disjoint_short_copy(bool aligned, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_4_bytes,L_copy_2_bytes,L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register word_count  = rcx;
    const Register qword_count = count;
    const Register end_from    = from; // source array end address
    const Register end_to      = to;   // destination array end address
    // End pointers are inclusive, and if count is not zero they point
    // to the last unit copied:  end_to[0] := end_from[0]

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    disjoint_short_copy_entry = __ pc();
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'count' are now valid
    __ movq(word_count, count);
    __ shrq(count, 2); // count => qword_count

    // Copy from low to high addresses.  Use 'to' as scratch.
    __ leaq(end_from, Address(from, qword_count, Address::times_8, -8));
    __ leaq(end_to,   Address(to,   qword_count, Address::times_8, -8));
    __ negq(qword_count);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(end_from, qword_count, Address::times_8, 8));
    __ movq(Address(end_to, qword_count, Address::times_8, 8), rax);
    __ incrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    // Original 'dest' is trashed, so we can't use it as a
    // base register for a possible trailing word copy

    // Check for and copy trailing dword
  __ BIND(L_copy_4_bytes);
    __ testq(word_count, 2);
    __ jccb(Assembler::zero, L_copy_2_bytes);
    __ movl(rax, Address(end_from, 8));
    __ movl(Address(end_to, 8), rax);

    __ addq(end_from, 4);
    __ addq(end_to, 4);

    // Check for and copy trailing word
  __ BIND(L_copy_2_bytes);
    __ testq(word_count, 1);
    __ jccb(Assembler::zero, L_exit);
    __ movw(rax, Address(end_from, 8));
    __ movw(Address(end_to, 8), rax);

  __ BIND(L_exit);
    inc_counter_np(SharedRuntime::_jshort_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy in 32-bytes chunks
    copy_32_bytes_forward(end_from, end_to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);
    __ jmp(L_copy_4_bytes);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4- or 2-byte boundaries, we
  // let the hardware handle it.  The two or four words within dwords
  // or qwords that span cache line boundaries will still be loaded
  // and stored atomically.
  //
  address generate_conjoint_short_copy(bool aligned, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_4_bytes;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register word_count  = rcx;
    const Register qword_count = count;

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    short_copy_entry = __ pc();
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    array_overlap_test(disjoint_short_copy_entry, Address::times_2);
    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'count' are now valid
    __ movq(word_count, count);
    __ shrq(count, 2); // count => qword_count

    // Copy from high to low addresses.  Use 'to' as scratch.

    // Check for and copy trailing word
    __ testq(word_count, 1);
    __ jccb(Assembler::zero, L_copy_4_bytes);
    __ movw(rax, Address(from, word_count, Address::times_2, -2));
    __ movw(Address(to, word_count, Address::times_2, -2), rax);

    // Check for and copy trailing dword
  __ BIND(L_copy_4_bytes);
    __ testq(word_count, 2);
    __ jcc(Assembler::zero, L_copy_32_bytes);
    __ movl(rax, Address(from, qword_count, Address::times_8));
    __ movl(Address(to, qword_count, Address::times_8), rax);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(from, qword_count, Address::times_8, -8));
    __ movq(Address(to, qword_count, Address::times_8, -8), rax);
    __ decrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    inc_counter_np(SharedRuntime::_jshort_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy in 32-bytes chunks
    copy_32_bytes_backward(from, to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);

    inc_counter_np(SharedRuntime::_jshort_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   is_oop  - true => oop array, so generate store check code
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4-byte boundaries, we let
  // the hardware handle it.  The two dwords within qwords that span
  // cache line boundaries will still be loaded and stored atomicly.
  //
  // Side Effects:
  //   disjoint_int_copy_entry is set to the no-overlap entry point
  //   used by generate_conjoint_int_oop_copy().
  //
  address generate_disjoint_int_oop_copy(bool aligned, bool is_oop, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_4_bytes, L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register dword_count = rcx;
    const Register qword_count = count;
    const Register end_from    = from; // source array end address
    const Register end_to      = to;   // destination array end address
    const Register saved_to    = r11;  // saved destination array address
    // End pointers are inclusive, and if count is not zero they point
    // to the last unit copied:  end_to[0] := end_from[0]

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    (is_oop ? disjoint_oop_copy_entry : disjoint_int_copy_entry) = __ pc();

    if (is_oop) {
      // no registers are destroyed by this call
      gen_write_ref_array_pre_barrier(/* dest */ c_rarg1, /* count */ c_rarg2);
    }

    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    if (is_oop) {
      __ movq(saved_to, to);
    }

    // 'from', 'to' and 'count' are now valid
    __ movq(dword_count, count);
    __ shrq(count, 1); // count => qword_count

    // Copy from low to high addresses.  Use 'to' as scratch.
    __ leaq(end_from, Address(from, qword_count, Address::times_8, -8));
    __ leaq(end_to,   Address(to,   qword_count, Address::times_8, -8));
    __ negq(qword_count);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(end_from, qword_count, Address::times_8, 8));
    __ movq(Address(end_to, qword_count, Address::times_8, 8), rax);
    __ incrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    // Check for and copy trailing dword
  __ BIND(L_copy_4_bytes);
    __ testq(dword_count, 1); // Only byte test since the value is 0 or 1
    __ jccb(Assembler::zero, L_exit);
    __ movl(rax, Address(end_from, 8));
    __ movl(Address(end_to, 8), rax);

  __ BIND(L_exit);
    if (is_oop) {
      __ leaq(end_to, Address(saved_to, dword_count, Address::times_4, -4));
      gen_write_ref_array_post_barrier(saved_to, end_to, rax);
    }
    inc_counter_np(SharedRuntime::_jint_array_copy_ctr);
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy 32-bytes chunks
    copy_32_bytes_forward(end_from, end_to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);
    __ jmp(L_copy_4_bytes);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord == 8-byte boundary
  //             ignored
  //   is_oop  - true => oop array, so generate store check code
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  // If 'from' and/or 'to' are aligned on 4-byte boundaries, we let
  // the hardware handle it.  The two dwords within qwords that span
  // cache line boundaries will still be loaded and stored atomicly.
  //
  address generate_conjoint_int_oop_copy(bool aligned, bool is_oop, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_copy_2_bytes, L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register count       = rdx;  // elements count
    const Register dword_count = rcx;
    const Register qword_count = count;

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    if (is_oop) {
      // no registers are destroyed by this call
      gen_write_ref_array_pre_barrier(/* dest */ c_rarg1, /* count */ c_rarg2);
    }

    (is_oop ? oop_copy_entry : int_copy_entry) = __ pc();
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    array_overlap_test(is_oop ? disjoint_oop_copy_entry : disjoint_int_copy_entry,
                       Address::times_4);
    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    assert_clean_int(count, rax); // Make sure 'count' is clean int.
    // 'from', 'to' and 'count' are now valid
    __ movq(dword_count, count);
    __ shrq(count, 1); // count => qword_count

    // Copy from high to low addresses.  Use 'to' as scratch.

    // Check for and copy trailing dword
    __ testq(dword_count, 1);
    __ jcc(Assembler::zero, L_copy_32_bytes);
    __ movl(rax, Address(from, dword_count, Address::times_4, -4));
    __ movl(Address(to, dword_count, Address::times_4, -4), rax);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(from, qword_count, Address::times_8, -8));
    __ movq(Address(to, qword_count, Address::times_8, -8), rax);
    __ decrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    inc_counter_np(SharedRuntime::_jint_array_copy_ctr);
    if (is_oop) {
      __ jmp(L_exit);
    }
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    // Copy in 32-bytes chunks
    copy_32_bytes_backward(from, to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);

   inc_counter_np(SharedRuntime::_jint_array_copy_ctr);
   __ bind(L_exit);
     if (is_oop) {
       Register end_to = rdx;
       __ leaq(end_to, Address(to, dword_count, Address::times_4, -4));
       gen_write_ref_array_post_barrier(to, end_to, rax);
     }
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord boundary == 8 bytes
  //             ignored
  //   is_oop  - true => oop array, so generate store check code
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
 // Side Effects:
  //   disjoint_oop_copy_entry or disjoint_long_copy_entry is set to the
  //   no-overlap entry point used by generate_conjoint_long_oop_copy().
  //
  address generate_disjoint_long_oop_copy(bool aligned, bool is_oop, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register qword_count = rdx;  // elements count
    const Register end_from    = from; // source array end address
    const Register end_to      = rcx;  // destination array end address
    const Register saved_to    = to;
    // End pointers are inclusive, and if count is not zero they point
    // to the last unit copied:  end_to[0] := end_from[0]

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    // Save no-overlap entry point for generate_conjoint_long_oop_copy()
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    if (is_oop) {
      disjoint_oop_copy_entry  = __ pc();
      // no registers are destroyed by this call
      gen_write_ref_array_pre_barrier(/* dest */ c_rarg1, /* count */ c_rarg2);
    } else {
      disjoint_long_copy_entry = __ pc();
    }
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'qword_count' are now valid

    // Copy from low to high addresses.  Use 'to' as scratch.
    __ leaq(end_from, Address(from, qword_count, Address::times_8, -8));
    __ leaq(end_to,   Address(to,   qword_count, Address::times_8, -8));
    __ negq(qword_count);
    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(end_from, qword_count, Address::times_8, 8));
    __ movq(Address(end_to, qword_count, Address::times_8, 8), rax);
    __ incrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    if (is_oop) {
      __ jmp(L_exit);
    } else {
      inc_counter_np(SharedRuntime::_jlong_array_copy_ctr);
      restore_arg_regs();
      __ xorq(rax, rax); // return 0
      __ leave(); // required for proper stackwalking of RuntimeStub frame
      __ ret(0);
    }

    // Copy 64-byte chunks
    copy_32_bytes_forward(end_from, end_to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);

    if (is_oop) {
    __ BIND(L_exit);
      gen_write_ref_array_post_barrier(saved_to, end_to, rax);
      inc_counter_np(SharedRuntime::_oop_array_copy_ctr);
    } else {
      inc_counter_np(SharedRuntime::_jlong_array_copy_ctr);
    }
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

  // Arguments:
  //   aligned - true => Input and output aligned on a HeapWord boundary == 8 bytes
  //             ignored
  //   is_oop  - true => oop array, so generate store check code
  //   name    - stub name string
  //
  // Inputs:
  //   c_rarg0   - source array address
  //   c_rarg1   - destination array address
  //   c_rarg2   - element count, treated as ssize_t, can be zero
  //
  address generate_conjoint_long_oop_copy(bool aligned, bool is_oop, const char *name) {
    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    Label L_copy_32_bytes, L_copy_8_bytes, L_exit;
    const Register from        = rdi;  // source array address
    const Register to          = rsi;  // destination array address
    const Register qword_count = rdx;  // elements count
    const Register saved_count = rcx;

    __ enter(); // required for proper stackwalking of RuntimeStub frame
    assert_clean_int(c_rarg2, rax);    // Make sure 'count' is clean int.

    address disjoint_copy_entry = NULL;
    if (is_oop) {
      assert(!UseCompressedOops, "shouldn't be called for compressed oops");
      disjoint_copy_entry = disjoint_oop_copy_entry;
      oop_copy_entry  = __ pc();
      array_overlap_test(disjoint_oop_copy_entry, Address::times_8);
    } else {
      disjoint_copy_entry = disjoint_long_copy_entry;
      long_copy_entry = __ pc();
      array_overlap_test(disjoint_long_copy_entry, Address::times_8);
    }
    BLOCK_COMMENT("Entry:");
    // caller can pass a 64-bit byte count here (from Unsafe.copyMemory)

    array_overlap_test(disjoint_copy_entry, Address::times_8);
    setup_arg_regs(); // from => rdi, to => rsi, count => rdx
                      // r9 and r10 may be used to save non-volatile registers

    // 'from', 'to' and 'qword_count' are now valid

    if (is_oop) {
      // Save to and count for store barrier
      __ movq(saved_count, qword_count);
      // No registers are destroyed by this call
      gen_write_ref_array_pre_barrier(to, saved_count);
    }

    __ jmp(L_copy_32_bytes);

    // Copy trailing qwords
  __ BIND(L_copy_8_bytes);
    __ movq(rax, Address(from, qword_count, Address::times_8, -8));
    __ movq(Address(to, qword_count, Address::times_8, -8), rax);
    __ decrementq(qword_count);
    __ jcc(Assembler::notZero, L_copy_8_bytes);

    if (is_oop) {
      __ jmp(L_exit);
    } else {
      inc_counter_np(SharedRuntime::_jlong_array_copy_ctr);
      restore_arg_regs();
      __ xorq(rax, rax); // return 0
      __ leave(); // required for proper stackwalking of RuntimeStub frame
      __ ret(0);
    }

    // Copy in 32-bytes chunks
    copy_32_bytes_backward(from, to, qword_count, rax, L_copy_32_bytes, L_copy_8_bytes);

    if (is_oop) {
    __ BIND(L_exit);
      __ leaq(rcx, Address(to, saved_count, Address::times_8, -8));
      gen_write_ref_array_post_barrier(to, rcx, rax);
      inc_counter_np(SharedRuntime::_oop_array_copy_ctr);
    } else {
      inc_counter_np(SharedRuntime::_jlong_array_copy_ctr);
    }
    restore_arg_regs();
    __ xorq(rax, rax); // return 0
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }


  // Helper for generating a dynamic type check.
  // Smashes no registers.
  void generate_type_check(Register sub_klass,
                           Register super_check_offset,
                           Register super_klass,
                           Label& L_success) {
    assert_different_registers(sub_klass, super_check_offset, super_klass);

    BLOCK_COMMENT("type_check:");

    Label L_miss;

    // a couple of useful fields in sub_klass:
    int ss_offset = (klassOopDesc::header_size() * HeapWordSize +
                     Klass::secondary_supers_offset_in_bytes());
    int sc_offset = (klassOopDesc::header_size() * HeapWordSize +
                     Klass::secondary_super_cache_offset_in_bytes());
    Address secondary_supers_addr(sub_klass, ss_offset);
    Address super_cache_addr(     sub_klass, sc_offset);

    // if the pointers are equal, we are done (e.g., String[] elements)
    __ cmpq(super_klass, sub_klass);
    __ jcc(Assembler::equal, L_success);

    // check the supertype display:
    Address super_check_addr(sub_klass, super_check_offset, Address::times_1, 0);
    __ cmpq(super_klass, super_check_addr); // test the super type
    __ jcc(Assembler::equal, L_success);

    // if it was a primary super, we can just fail immediately
    __ cmpl(super_check_offset, sc_offset);
    __ jcc(Assembler::notEqual, L_miss);

    // Now do a linear scan of the secondary super-klass chain.
    // The repne_scan instruction uses fixed registers, which we must spill.
    // (We need a couple more temps in any case.)
    // This code is rarely used, so simplicity is a virtue here.
    inc_counter_np(SharedRuntime::_partial_subtype_ctr);
    {
      __ pushq(rax);
      __ pushq(rcx);
      __ pushq(rdi);
      assert_different_registers(sub_klass, super_klass, rax, rcx, rdi);

      __ movq(rdi, secondary_supers_addr);
      // Load the array length.
      __ movl(rcx, Address(rdi, arrayOopDesc::length_offset_in_bytes()));
      // Skip to start of data.
      __ addq(rdi, arrayOopDesc::base_offset_in_bytes(T_OBJECT));
      // Scan rcx words at [rdi] for occurance of rax
      // Set NZ/Z based on last compare
      __ movq(rax, super_klass);
      if (UseCompressedOops) {
        // Compare against compressed form.  Don't need to uncompress because
        // looks like orig rax is restored in popq below.
        __ encode_heap_oop(rax);
        __ repne_scanl();
      } else {
         __ repne_scanq();
      }

      // Unspill the temp. registers:
      __ popq(rdi);
      __ popq(rcx);
      __ popq(rax);

      __ jcc(Assembler::notEqual, L_miss);
    }

    // Success.  Cache the super we found and proceed in triumph.
    __ movq(super_cache_addr, super_klass); // note: rax is dead
    __ jmp(L_success);

    // Fall through on failure!
    __ BIND(L_miss);
  }

  //
  //  Generate checkcasting array copy stub
  //
  //  Input:
  //    c_rarg0   - source array address
  //    c_rarg1   - destination array address
  //    c_rarg2   - element count, treated as ssize_t, can be zero
  //    c_rarg3   - size_t ckoff (super_check_offset)
  // not Win64
  //    c_rarg4   - oop ckval (super_klass)
  // Win64
  //    rsp+40    - oop ckval (super_klass)
  //
  //  Output:
  //    rax ==  0  -  success
  //    rax == -1^K - failure, where K is partial transfer count
  //
  address generate_checkcast_copy(const char *name) {

    Label L_load_element, L_store_element, L_do_card_marks, L_done;

    // Input registers (after setup_arg_regs)
    const Register from        = rdi;   // source array address
    const Register to          = rsi;   // destination array address
    const Register length      = rdx;   // elements count
    const Register ckoff       = rcx;   // super_check_offset
    const Register ckval       = r8;    // super_klass

    // Registers used as temps (r13, r14 are save-on-entry)
    const Register end_from    = from;  // source array end address
    const Register end_to      = r13;   // destination array end address
    const Register count       = rdx;   // -(count_remaining)
    const Register r14_length  = r14;   // saved copy of length
    // End pointers are inclusive, and if length is not zero they point
    // to the last unit copied:  end_to[0] := end_from[0]

    const Register rax_oop    = rax;    // actual oop copied
    const Register r11_klass  = r11;    // oop._klass

    //---------------------------------------------------------------
    // Assembler stub will be used for this call to arraycopy
    // if the two arrays are subtypes of Object[] but the
    // destination array type is not equal to or a supertype
    // of the source type.  Each element must be separately
    // checked.

    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    __ enter(); // required for proper stackwalking of RuntimeStub frame

    checkcast_copy_entry  = __ pc();
    BLOCK_COMMENT("Entry:");

#ifdef ASSERT
    // caller guarantees that the arrays really are different
    // otherwise, we would have to make conjoint checks
    { Label L;
      array_overlap_test(L, TIMES_OOP);
      __ stop("checkcast_copy within a single array");
      __ bind(L);
    }
#endif //ASSERT

    // allocate spill slots for r13, r14
    enum {
      saved_r13_offset,
      saved_r14_offset,
      saved_rbp_offset,
      saved_rip_offset,
      saved_rarg0_offset
    };
    __ subq(rsp, saved_rbp_offset * wordSize);
    __ movq(Address(rsp, saved_r13_offset * wordSize), r13);
    __ movq(Address(rsp, saved_r14_offset * wordSize), r14);
    setup_arg_regs(4); // from => rdi, to => rsi, length => rdx
                       // ckoff => rcx, ckval => r8
                       // r9 and r10 may be used to save non-volatile registers
#ifdef _WIN64
    // last argument (#4) is on stack on Win64
    const int ckval_offset = saved_rarg0_offset + 4;
    __ movq(ckval, Address(rsp, ckval_offset * wordSize));
#endif

    // check that int operands are properly extended to size_t
    assert_clean_int(length, rax);
    assert_clean_int(ckoff, rax);

#ifdef ASSERT
    BLOCK_COMMENT("assert consistent ckoff/ckval");
    // The ckoff and ckval must be mutually consistent,
    // even though caller generates both.
    { Label L;
      int sco_offset = (klassOopDesc::header_size() * HeapWordSize +
                        Klass::super_check_offset_offset_in_bytes());
      __ cmpl(ckoff, Address(ckval, sco_offset));
      __ jcc(Assembler::equal, L);
      __ stop("super_check_offset inconsistent");
      __ bind(L);
    }
#endif //ASSERT

    // Loop-invariant addresses.  They are exclusive end pointers.
    Address end_from_addr(from, length, TIMES_OOP, 0);
    Address   end_to_addr(to,   length, TIMES_OOP, 0);
    // Loop-variant addresses.  They assume post-incremented count < 0.
    Address from_element_addr(end_from, count, TIMES_OOP, 0);
    Address   to_element_addr(end_to,   count, TIMES_OOP, 0);

    gen_write_ref_array_pre_barrier(to, count);

    // Copy from low to high addresses, indexed from the end of each array.
    __ leaq(end_from, end_from_addr);
    __ leaq(end_to,   end_to_addr);
    __ movq(r14_length, length);        // save a copy of the length
    assert(length == count, "");        // else fix next line:
    __ negq(count);                     // negate and test the length
    __ jcc(Assembler::notZero, L_load_element);

    // Empty array:  Nothing to do.
    __ xorq(rax, rax);                  // return 0 on (trivial) success
    __ jmp(L_done);

    // ======== begin loop ========
    // (Loop is rotated; its entry is L_load_element.)
    // Loop control:
    //   for (count = -count; count != 0; count++)
    // Base pointers src, dst are biased by 8*(count-1),to last element.
    __ align(16);

    __ BIND(L_store_element);
    __ store_heap_oop(to_element_addr, rax_oop);  // store the oop
    __ incrementq(count);               // increment the count toward zero
    __ jcc(Assembler::zero, L_do_card_marks);

    // ======== loop entry is here ========
    __ BIND(L_load_element);
    __ load_heap_oop(rax_oop, from_element_addr); // load the oop
    __ testq(rax_oop, rax_oop);
    __ jcc(Assembler::zero, L_store_element);

    __ load_klass(r11_klass, rax_oop);// query the object klass
    generate_type_check(r11_klass, ckoff, ckval, L_store_element);
    // ======== end loop ========

    // It was a real error; we must depend on the caller to finish the job.
    // Register rdx = -1 * number of *remaining* oops, r14 = *total* oops.
    // Emit GC store barriers for the oops we have copied (r14 + rdx),
    // and report their number to the caller.
    assert_different_registers(rax, r14_length, count, to, end_to, rcx);
    __ leaq(end_to, to_element_addr);
    gen_write_ref_array_post_barrier(to, end_to, rcx);
    __ movq(rax, r14_length);           // original oops
    __ addq(rax, count);                // K = (original - remaining) oops
    __ notq(rax);                       // report (-1^K) to caller
    __ jmp(L_done);

    // Come here on success only.
    __ BIND(L_do_card_marks);
    __ addq(end_to, -wordSize);         // make an inclusive end pointer
    gen_write_ref_array_post_barrier(to, end_to, rcx);
    __ xorq(rax, rax);                  // return 0 on success

    // Common exit point (success or failure).
    __ BIND(L_done);
    __ movq(r13, Address(rsp, saved_r13_offset * wordSize));
    __ movq(r14, Address(rsp, saved_r14_offset * wordSize));
    inc_counter_np(SharedRuntime::_checkcast_array_copy_ctr);
    restore_arg_regs();
    __ leave(); // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

  //
  //  Generate 'unsafe' array copy stub
  //  Though just as safe as the other stubs, it takes an unscaled
  //  size_t argument instead of an element count.
  //
  //  Input:
  //    c_rarg0   - source array address
  //    c_rarg1   - destination array address
  //    c_rarg2   - byte count, treated as ssize_t, can be zero
  //
  // Examines the alignment of the operands and dispatches
  // to a long, int, short, or byte copy loop.
  //
  address generate_unsafe_copy(const char *name) {

    Label L_long_aligned, L_int_aligned, L_short_aligned;

    // Input registers (before setup_arg_regs)
    const Register from        = c_rarg0;  // source array address
    const Register to          = c_rarg1;  // destination array address
    const Register size        = c_rarg2;  // byte count (size_t)

    // Register used as a temp
    const Register bits        = rax;      // test copy of low bits

    __ align(CodeEntryAlignment);
    StubCodeMark mark(this, "StubRoutines", name);
    address start = __ pc();

    __ enter(); // required for proper stackwalking of RuntimeStub frame

    // bump this on entry, not on exit:
    inc_counter_np(SharedRuntime::_unsafe_array_copy_ctr);

    __ movq(bits, from);
    __ orq(bits, to);
    __ orq(bits, size);

    __ testb(bits, BytesPerLong-1);
    __ jccb(Assembler::zero, L_long_aligned);

    __ testb(bits, BytesPerInt-1);
    __ jccb(Assembler::zero, L_int_aligned);

    __ testb(bits, BytesPerShort-1);
    __ jump_cc(Assembler::notZero, RuntimeAddress(byte_copy_entry));

    __ BIND(L_short_aligned);
    __ shrq(size, LogBytesPerShort); // size => short_count
    __ jump(RuntimeAddress(short_copy_entry));

    __ BIND(L_int_aligned);
    __ shrq(size, LogBytesPerInt); // size => int_count
    __ jump(RuntimeAddress(int_copy_entry));

    __ BIND(L_long_aligned);
    __ shrq(size, LogBytesPerLong); // size => qword_count
    __ jump(RuntimeAddress(long_copy_entry));

    return start;
  }

  // Perform range checks on the proposed arraycopy.
  // Kills temp, but nothing else.
  // Also, clean the sign bits of src_pos and dst_pos.
  void arraycopy_range_checks(Register src,     // source array oop (c_rarg0)
                              Register src_pos, // source position (c_rarg1)
                              Register dst,     // destination array oo (c_rarg2)
                              Register dst_pos, // destination position (c_rarg3)
                              Register length,
                              Register temp,
                              Label& L_failed) {
    BLOCK_COMMENT("arraycopy_range_checks:");

    //  if (src_pos + length > arrayOop(src)->length())  FAIL;
    __ movl(temp, length);
    __ addl(temp, src_pos);             // src_pos + length
    __ cmpl(temp, Address(src, arrayOopDesc::length_offset_in_bytes()));
    __ jcc(Assembler::above, L_failed);

    //  if (dst_pos + length > arrayOop(dst)->length())  FAIL;
    __ movl(temp, length);
    __ addl(temp, dst_pos);             // dst_pos + length
    __ cmpl(temp, Address(dst, arrayOopDesc::length_offset_in_bytes()));
    __ jcc(Assembler::above, L_failed);

    // Have to clean up high 32-bits of 'src_pos' and 'dst_pos'.
    // Move with sign extension can be used since they are positive.
    __ movslq(src_pos, src_pos);
    __ movslq(dst_pos, dst_pos);

    BLOCK_COMMENT("arraycopy_range_checks done");
  }

  //
  //  Generate generic array copy stubs
  //
  //  Input:
  //    c_rarg0    -  src oop
  //    c_rarg1    -  src_pos (32-bits)
  //    c_rarg2    -  dst oop
  //    c_rarg3    -  dst_pos (32-bits)
  // not Win64
  //    c_rarg4    -  element count (32-bits)
  // Win64
  //    rsp+40     -  element count (32-bits)
  //
  //  Output:
  //    rax ==  0  -  success
  //    rax == -1^K - failure, where K is partial transfer count
  //
  address generate_generic_copy(const char *name) {

    Label L_failed, L_failed_0, L_objArray;
    Label L_copy_bytes, L_copy_shorts, L_copy_ints, L_copy_longs;

    // Input registers
    const Register src        = c_rarg0;  // source array oop
    const Register src_pos    = c_rarg1;  // source position
    const Register dst        = c_rarg2;  // destination array oop
    const Register dst_pos    = c_rarg3;  // destination position
    // elements count is on stack on Win64
#ifdef _WIN64
#define C_RARG4 Address(rsp, 6 * wordSize)
#else
#define C_RARG4 c_rarg4
#endif

    { int modulus = CodeEntryAlignment;
      int target  = modulus - 5; // 5 = sizeof jmp(L_failed)
      int advance = target - (__ offset() % modulus);
      if (advance < 0)  advance += modulus;
      if (advance > 0)  __ nop(advance);
    }
    StubCodeMark mark(this, "StubRoutines", name);

    // Short-hop target to L_failed.  Makes for denser prologue code.
    __ BIND(L_failed_0);
    __ jmp(L_failed);
    assert(__ offset() % CodeEntryAlignment == 0, "no further alignment needed");

    __ align(CodeEntryAlignment);
    address start = __ pc();

    __ enter(); // required for proper stackwalking of RuntimeStub frame

    // bump this on entry, not on exit:
    inc_counter_np(SharedRuntime::_generic_array_copy_ctr);

    //-----------------------------------------------------------------------
    // Assembler stub will be used for this call to arraycopy
    // if the following conditions are met:
    //
    // (1) src and dst must not be null.
    // (2) src_pos must not be negative.
    // (3) dst_pos must not be negative.
    // (4) length  must not be negative.
    // (5) src klass and dst klass should be the same and not NULL.
    // (6) src and dst should be arrays.
    // (7) src_pos + length must not exceed length of src.
    // (8) dst_pos + length must not exceed length of dst.
    //

    //  if (src == NULL) return -1;
    __ testq(src, src);         // src oop
    size_t j1off = __ offset();
    __ jccb(Assembler::zero, L_failed_0);

    //  if (src_pos < 0) return -1;
    __ testl(src_pos, src_pos); // src_pos (32-bits)
    __ jccb(Assembler::negative, L_failed_0);

    //  if (dst == NULL) return -1;
    __ testq(dst, dst);         // dst oop
    __ jccb(Assembler::zero, L_failed_0);

    //  if (dst_pos < 0) return -1;
    __ testl(dst_pos, dst_pos); // dst_pos (32-bits)
    size_t j4off = __ offset();
    __ jccb(Assembler::negative, L_failed_0);

    // The first four tests are very dense code,
    // but not quite dense enough to put four
    // jumps in a 16-byte instruction fetch buffer.
    // That's good, because some branch predicters
    // do not like jumps so close together.
    // Make sure of this.
    guarantee(((j1off ^ j4off) & ~15) != 0, "I$ line of 1st & 4th jumps");

    // registers used as temp
    const Register r11_length    = r11; // elements count to copy
    const Register r10_src_klass = r10; // array klass
    const Register r9_dst_klass  = r9;  // dest array klass

    //  if (length < 0) return -1;
    __ movl(r11_length, C_RARG4);       // length (elements count, 32-bits value)
    __ testl(r11_length, r11_length);
    __ jccb(Assembler::negative, L_failed_0);

    __ load_klass(r10_src_klass, src);
#ifdef ASSERT
    //  assert(src->klass() != NULL);
    BLOCK_COMMENT("assert klasses not null");
    { Label L1, L2;
      __ testq(r10_src_klass, r10_src_klass);
      __ jcc(Assembler::notZero, L2);   // it is broken if klass is NULL
      __ bind(L1);
      __ stop("broken null klass");
      __ bind(L2);
      __ load_klass(r9_dst_klass, dst);
      __ cmpq(r9_dst_klass, 0);
      __ jcc(Assembler::equal, L1);     // this would be broken also
      BLOCK_COMMENT("assert done");
    }
#endif

    // Load layout helper (32-bits)
    //
    //  |array_tag|     | header_size | element_type |     |log2_element_size|
    // 32        30    24            16              8     2                 0
    //
    //   array_tag: typeArray = 0x3, objArray = 0x2, non-array = 0x0
    //

    int lh_offset = klassOopDesc::header_size() * HeapWordSize +
                    Klass::layout_helper_offset_in_bytes();

    const Register rax_lh = rax;  // layout helper

    __ movl(rax_lh, Address(r10_src_klass, lh_offset));

    // Handle objArrays completely differently...
    jint objArray_lh = Klass::array_layout_helper(T_OBJECT);
    __ cmpl(rax_lh, objArray_lh);
    __ jcc(Assembler::equal, L_objArray);

    //  if (src->klass() != dst->klass()) return -1;
    __ load_klass(r9_dst_klass, dst);
    __ cmpq(r10_src_klass, r9_dst_klass);
    __ jcc(Assembler::notEqual, L_failed);

    //  if (!src->is_Array()) return -1;
    __ cmpl(rax_lh, Klass::_lh_neutral_value);
    __ jcc(Assembler::greaterEqual, L_failed);

    // At this point, it is known to be a typeArray (array_tag 0x3).
#ifdef ASSERT
    { Label L;
      __ cmpl(rax_lh, (Klass::_lh_array_tag_type_value << Klass::_lh_array_tag_shift));
      __ jcc(Assembler::greaterEqual, L);
      __ stop("must be a primitive array");
      __ bind(L);
    }
#endif

    arraycopy_range_checks(src, src_pos, dst, dst_pos, r11_length,
                           r10, L_failed);

    // typeArrayKlass
    //
    // src_addr = (src + array_header_in_bytes()) + (src_pos << log2elemsize);
    // dst_addr = (dst + array_header_in_bytes()) + (dst_pos << log2elemsize);
    //

    const Register r10_offset = r10;    // array offset
    const Register rax_elsize = rax_lh; // element size

    __ movl(r10_offset, rax_lh);
    __ shrl(r10_offset, Klass::_lh_header_size_shift);
    __ andq(r10_offset, Klass::_lh_header_size_mask);   // array_offset
    __ addq(src, r10_offset);           // src array offset
    __ addq(dst, r10_offset);           // dst array offset
    BLOCK_COMMENT("choose copy loop based on element size");
    __ andl(rax_lh, Klass::_lh_log2_element_size_mask); // rax_lh -> rax_elsize

    // next registers should be set before the jump to corresponding stub
    const Register from     = c_rarg0;  // source array address
    const Register to       = c_rarg1;  // destination array address
    const Register count    = c_rarg2;  // elements count

    // 'from', 'to', 'count' registers should be set in such order
    // since they are the same as 'src', 'src_pos', 'dst'.

  __ BIND(L_copy_bytes);
    __ cmpl(rax_elsize, 0);
    __ jccb(Assembler::notEqual, L_copy_shorts);
    __ leaq(from, Address(src, src_pos, Address::times_1, 0));// src_addr
    __ leaq(to,   Address(dst, dst_pos, Address::times_1, 0));// dst_addr
    __ movslq(count, r11_length); // length
    __ jump(RuntimeAddress(byte_copy_entry));

  __ BIND(L_copy_shorts);
    __ cmpl(rax_elsize, LogBytesPerShort);
    __ jccb(Assembler::notEqual, L_copy_ints);
    __ leaq(from, Address(src, src_pos, Address::times_2, 0));// src_addr
    __ leaq(to,   Address(dst, dst_pos, Address::times_2, 0));// dst_addr
    __ movslq(count, r11_length); // length
    __ jump(RuntimeAddress(short_copy_entry));

  __ BIND(L_copy_ints);
    __ cmpl(rax_elsize, LogBytesPerInt);
    __ jccb(Assembler::notEqual, L_copy_longs);
    __ leaq(from, Address(src, src_pos, Address::times_4, 0));// src_addr
    __ leaq(to,   Address(dst, dst_pos, Address::times_4, 0));// dst_addr
    __ movslq(count, r11_length); // length
    __ jump(RuntimeAddress(int_copy_entry));

  __ BIND(L_copy_longs);
#ifdef ASSERT
    { Label L;
      __ cmpl(rax_elsize, LogBytesPerLong);
      __ jcc(Assembler::equal, L);
      __ stop("must be long copy, but elsize is wrong");
      __ bind(L);
    }
#endif
    __ leaq(from, Address(src, src_pos, Address::times_8, 0));// src_addr
    __ leaq(to,   Address(dst, dst_pos, Address::times_8, 0));// dst_addr
    __ movslq(count, r11_length); // length
    __ jump(RuntimeAddress(long_copy_entry));

    // objArrayKlass
  __ BIND(L_objArray);
    // live at this point:  r10_src_klass, src[_pos], dst[_pos]

    Label L_plain_copy, L_checkcast_copy;
    //  test array classes for subtyping
    __ load_klass(r9_dst_klass, dst);
    __ cmpq(r10_src_klass, r9_dst_klass); // usual case is exact equality
    __ jcc(Assembler::notEqual, L_checkcast_copy);

    // Identically typed arrays can be copied without element-wise checks.
    arraycopy_range_checks(src, src_pos, dst, dst_pos, r11_length,
                           r10, L_failed);

    __ leaq(from, Address(src, src_pos, TIMES_OOP,
                 arrayOopDesc::base_offset_in_bytes(T_OBJECT))); // src_addr
    __ leaq(to,   Address(dst, dst_pos, TIMES_OOP,
                  arrayOopDesc::base_offset_in_bytes(T_OBJECT))); // dst_addr
    __ movslq(count, r11_length); // length
  __ BIND(L_plain_copy);
    __ jump(RuntimeAddress(oop_copy_entry));

  __ BIND(L_checkcast_copy);
    // live at this point:  r10_src_klass, !r11_length
    {
      // assert(r11_length == C_RARG4); // will reload from here
      Register r11_dst_klass = r11;
      __ load_klass(r11_dst_klass, dst);

      // Before looking at dst.length, make sure dst is also an objArray.
      __ cmpl(Address(r11_dst_klass, lh_offset), objArray_lh);
      __ jcc(Assembler::notEqual, L_failed);

      // It is safe to examine both src.length and dst.length.
#ifndef _WIN64
      arraycopy_range_checks(src, src_pos, dst, dst_pos, C_RARG4,
                             rax, L_failed);
#else
      __ movl(r11_length, C_RARG4);     // reload
      arraycopy_range_checks(src, src_pos, dst, dst_pos, r11_length,
                             rax, L_failed);
      __ load_klass(r11_dst_klass, dst); // reload
#endif

      // Marshal the base address arguments now, freeing registers.
      __ leaq(from, Address(src, src_pos, TIMES_OOP,
                   arrayOopDesc::base_offset_in_bytes(T_OBJECT)));
      __ leaq(to,   Address(dst, dst_pos, TIMES_OOP,
                   arrayOopDesc::base_offset_in_bytes(T_OBJECT)));
      __ movl(count, C_RARG4);          // length (reloaded)
      Register sco_temp = c_rarg3;      // this register is free now
      assert_different_registers(from, to, count, sco_temp,
                                 r11_dst_klass, r10_src_klass);
      assert_clean_int(count, sco_temp);

      // Generate the type check.
      int sco_offset = (klassOopDesc::header_size() * HeapWordSize +
                        Klass::super_check_offset_offset_in_bytes());
      __ movl(sco_temp, Address(r11_dst_klass, sco_offset));
      assert_clean_int(sco_temp, rax);
      generate_type_check(r10_src_klass, sco_temp, r11_dst_klass, L_plain_copy);

      // Fetch destination element klass from the objArrayKlass header.
      int ek_offset = (klassOopDesc::header_size() * HeapWordSize +
                       objArrayKlass::element_klass_offset_in_bytes());
      __ movq(r11_dst_klass, Address(r11_dst_klass, ek_offset));
      __ movl(sco_temp,      Address(r11_dst_klass, sco_offset));
      assert_clean_int(sco_temp, rax);

      // the checkcast_copy loop needs two extra arguments:
      assert(c_rarg3 == sco_temp, "#3 already in place");
      __ movq(C_RARG4, r11_dst_klass);  // dst.klass.element_klass
      __ jump(RuntimeAddress(checkcast_copy_entry));
    }

  __ BIND(L_failed);
    __ xorq(rax, rax);
    __ notq(rax); // return -1
    __ leave();   // required for proper stackwalking of RuntimeStub frame
    __ ret(0);

    return start;
  }

#undef length_arg

  void generate_arraycopy_stubs() {
    // Call the conjoint generation methods immediately after
    // the disjoint ones so that short branches from the former
    // to the latter can be generated.
    StubRoutines::_jbyte_disjoint_arraycopy  = generate_disjoint_byte_copy(false, "jbyte_disjoint_arraycopy");
    StubRoutines::_jbyte_arraycopy           = generate_conjoint_byte_copy(false, "jbyte_arraycopy");

    StubRoutines::_jshort_disjoint_arraycopy = generate_disjoint_short_copy(false, "jshort_disjoint_arraycopy");
    StubRoutines::_jshort_arraycopy          = generate_conjoint_short_copy(false, "jshort_arraycopy");

    StubRoutines::_jint_disjoint_arraycopy   = generate_disjoint_int_oop_copy(false, false, "jint_disjoint_arraycopy");
    StubRoutines::_jint_arraycopy            = generate_conjoint_int_oop_copy(false, false, "jint_arraycopy");

    StubRoutines::_jlong_disjoint_arraycopy  = generate_disjoint_long_oop_copy(false, false, "jlong_disjoint_arraycopy");
    StubRoutines::_jlong_arraycopy           = generate_conjoint_long_oop_copy(false, false, "jlong_arraycopy");


    if (UseCompressedOops) {
      StubRoutines::_oop_disjoint_arraycopy  = generate_disjoint_int_oop_copy(false, true, "oop_disjoint_arraycopy");
      StubRoutines::_oop_arraycopy           = generate_conjoint_int_oop_copy(false, true, "oop_arraycopy");
    } else {
      StubRoutines::_oop_disjoint_arraycopy  = generate_disjoint_long_oop_copy(false, true, "oop_disjoint_arraycopy");
      StubRoutines::_oop_arraycopy           = generate_conjoint_long_oop_copy(false, true, "oop_arraycopy");
    }

    StubRoutines::_checkcast_arraycopy = generate_checkcast_copy("checkcast_arraycopy");
    StubRoutines::_unsafe_arraycopy    = generate_unsafe_copy("unsafe_arraycopy");
    StubRoutines::_generic_arraycopy   = generate_generic_copy("generic_arraycopy");

    // We don't generate specialized code for HeapWord-aligned source
    // arrays, so just use the code we've already generated
    StubRoutines::_arrayof_jbyte_disjoint_arraycopy  = StubRoutines::_jbyte_disjoint_arraycopy;
    StubRoutines::_arrayof_jbyte_arraycopy           = StubRoutines::_jbyte_arraycopy;

    StubRoutines::_arrayof_jshort_disjoint_arraycopy = StubRoutines::_jshort_disjoint_arraycopy;
    StubRoutines::_arrayof_jshort_arraycopy          = StubRoutines::_jshort_arraycopy;

    StubRoutines::_arrayof_jint_disjoint_arraycopy   = StubRoutines::_jint_disjoint_arraycopy;
    StubRoutines::_arrayof_jint_arraycopy            = StubRoutines::_jint_arraycopy;

    StubRoutines::_arrayof_jlong_disjoint_arraycopy  = StubRoutines::_jlong_disjoint_arraycopy;
    StubRoutines::_arrayof_jlong_arraycopy           = StubRoutines::_jlong_arraycopy;

    StubRoutines::_arrayof_oop_disjoint_arraycopy    = StubRoutines::_oop_disjoint_arraycopy;
    StubRoutines::_arrayof_oop_arraycopy             = StubRoutines::_oop_arraycopy;
  }

#undef __
#define __ masm->

  // Continuation point for throwing of implicit exceptions that are
  // not handled in the current activation. Fabricates an exception
  // oop and initiates normal exception dispatching in this
  // frame. Since we need to preserve callee-saved values (currently
  // only for C2, but done for C1 as well) we need a callee-saved oop
  // map and therefore have to make these stubs into RuntimeStubs
  // rather than BufferBlobs.  If the compiler needs all registers to
  // be preserved between the fault point and the exception handler
  // then it must assume responsibility for that in
  // AbstractCompiler::continuation_for_implicit_null_exception or
  // continuation_for_implicit_division_by_zero_exception. All other
  // implicit exceptions (e.g., NullPointerException or
  // AbstractMethodError on entry) are either at call sites or
  // otherwise assume that stack unwinding will be initiated, so
  // caller saved registers were assumed volatile in the compiler.
  address generate_throw_exception(const char* name,
                                   address runtime_entry,
                                   bool restore_saved_exception_pc) {
    // Information about frame layout at time of blocking runtime call.
    // Note that we only have to preserve callee-saved registers since
    // the compilers are responsible for supplying a continuation point
    // if they expect all registers to be preserved.
    enum layout {
      rbp_off = frame::arg_reg_save_area_bytes/BytesPerInt,
      rbp_off2,
      return_off,
      return_off2,
      framesize // inclusive of return address
    };

    int insts_size = 512;
    int locs_size  = 64;

    CodeBuffer code(name, insts_size, locs_size);
    OopMapSet* oop_maps  = new OopMapSet();
    MacroAssembler* masm = new MacroAssembler(&code);

    address start = __ pc();

    // This is an inlined and slightly modified version of call_VM
    // which has the ability to fetch the return PC out of
    // thread-local storage and also sets up last_Java_sp slightly
    // differently than the real call_VM
    if (restore_saved_exception_pc) {
      __ movq(rax,
              Address(r15_thread,
                      in_bytes(JavaThread::saved_exception_pc_offset())));
      __ pushq(rax);
    }

    __ enter(); // required for proper stackwalking of RuntimeStub frame

    assert(is_even(framesize/2), "sp not 16-byte aligned");

    // return address and rbp are already in place
    __ subq(rsp, (framesize-4) << LogBytesPerInt); // prolog

    int frame_complete = __ pc() - start;

    // Set up last_Java_sp and last_Java_fp
    __ set_last_Java_frame(rsp, rbp, NULL);

    // Call runtime
    __ movq(c_rarg0, r15_thread);
    BLOCK_COMMENT("call runtime_entry");
    __ call(RuntimeAddress(runtime_entry));

    // Generate oop map
    OopMap* map = new OopMap(framesize, 0);

    oop_maps->add_gc_map(__ pc() - start, map);

    __ reset_last_Java_frame(true, false);

    __ leave(); // required for proper stackwalking of RuntimeStub frame

    // check for pending exceptions
#ifdef ASSERT
    Label L;
    __ cmpq(Address(r15_thread, Thread::pending_exception_offset()),
            (int) NULL);
    __ jcc(Assembler::notEqual, L);
    __ should_not_reach_here();
    __ bind(L);
#endif // ASSERT
    __ jump(RuntimeAddress(StubRoutines::forward_exception_entry()));


    // codeBlob framesize is in words (not VMRegImpl::slot_size)
    RuntimeStub* stub =
      RuntimeStub::new_runtime_stub(name,
                                    &code,
                                    frame_complete,
                                    (framesize >> (LogBytesPerWord - LogBytesPerInt)),
                                    oop_maps, false);
    return stub->entry_point();
  }

  // Initialization
  void generate_initial() {
    // Generates all stubs and initializes the entry points

    // This platform-specific stub is needed by generate_call_stub()
    StubRoutines::amd64::_mxcsr_std        = generate_fp_mask("mxcsr_std",        0x0000000000001F80);

    // entry points that exist in all platforms Note: This is code
    // that could be shared among different platforms - however the
    // benefit seems to be smaller than the disadvantage of having a
    // much more complicated generator structure. See also comment in
    // stubRoutines.hpp.

    StubRoutines::_forward_exception_entry = generate_forward_exception();

    StubRoutines::_call_stub_entry =
      generate_call_stub(StubRoutines::_call_stub_return_address);

    // is referenced by megamorphic call
    StubRoutines::_catch_exception_entry = generate_catch_exception();

    // atomic calls
    StubRoutines::_atomic_xchg_entry         = generate_atomic_xchg();
    StubRoutines::_atomic_xchg_ptr_entry     = generate_atomic_xchg_ptr();
    StubRoutines::_atomic_cmpxchg_entry      = generate_atomic_cmpxchg();
    StubRoutines::_atomic_cmpxchg_long_entry = generate_atomic_cmpxchg_long();
    StubRoutines::_atomic_add_entry          = generate_atomic_add();
    StubRoutines::_atomic_add_ptr_entry      = generate_atomic_add_ptr();
    StubRoutines::_fence_entry               = generate_orderaccess_fence();

    StubRoutines::_handler_for_unsafe_access_entry =
      generate_handler_for_unsafe_access();

    // platform dependent
    StubRoutines::amd64::_get_previous_fp_entry = generate_get_previous_fp();

    StubRoutines::amd64::_verify_mxcsr_entry    = generate_verify_mxcsr();
  }

  void generate_all() {
    // Generates all stubs and initializes the entry points

    // These entry points require SharedInfo::stack0 to be set up in
    // non-core builds and need to be relocatable, so they each
    // fabricate a RuntimeStub internally.
    StubRoutines::_throw_AbstractMethodError_entry =
      generate_throw_exception("AbstractMethodError throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_AbstractMethodError),
                               false);

    StubRoutines::_throw_IncompatibleClassChangeError_entry =
      generate_throw_exception("IncompatibleClassChangeError throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_IncompatibleClassChangeError),
                               false);

    StubRoutines::_throw_ArithmeticException_entry =
      generate_throw_exception("ArithmeticException throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_ArithmeticException),
                               true);

    StubRoutines::_throw_NullPointerException_entry =
      generate_throw_exception("NullPointerException throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_NullPointerException),
                               true);

    StubRoutines::_throw_NullPointerException_at_call_entry =
      generate_throw_exception("NullPointerException at call throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_NullPointerException_at_call),
                               false);

    StubRoutines::_throw_StackOverflowError_entry =
      generate_throw_exception("StackOverflowError throw_exception",
                               CAST_FROM_FN_PTR(address,
                                                SharedRuntime::
                                                throw_StackOverflowError),
                               false);

    // entry points that are platform specific
    StubRoutines::amd64::_f2i_fixup = generate_f2i_fixup();
    StubRoutines::amd64::_f2l_fixup = generate_f2l_fixup();
    StubRoutines::amd64::_d2i_fixup = generate_d2i_fixup();
    StubRoutines::amd64::_d2l_fixup = generate_d2l_fixup();

    StubRoutines::amd64::_float_sign_mask  = generate_fp_mask("float_sign_mask",  0x7FFFFFFF7FFFFFFF);
    StubRoutines::amd64::_float_sign_flip  = generate_fp_mask("float_sign_flip",  0x8000000080000000);
    StubRoutines::amd64::_double_sign_mask = generate_fp_mask("double_sign_mask", 0x7FFFFFFFFFFFFFFF);
    StubRoutines::amd64::_double_sign_flip = generate_fp_mask("double_sign_flip", 0x8000000000000000);

    // support for verify_oop (must happen after universe_init)
    StubRoutines::_verify_oop_subroutine_entry = generate_verify_oop();

    // arraycopy stubs used by compilers
    generate_arraycopy_stubs();
  }

 public:
  StubGenerator(CodeBuffer* code, bool all) : StubCodeGenerator(code) {
    if (all) {
      generate_all();
    } else {
      generate_initial();
    }
  }
}; // end class declaration

address StubGenerator::disjoint_byte_copy_entry  = NULL;
address StubGenerator::disjoint_short_copy_entry = NULL;
address StubGenerator::disjoint_int_copy_entry   = NULL;
address StubGenerator::disjoint_long_copy_entry  = NULL;
address StubGenerator::disjoint_oop_copy_entry   = NULL;

address StubGenerator::byte_copy_entry  = NULL;
address StubGenerator::short_copy_entry = NULL;
address StubGenerator::int_copy_entry   = NULL;
address StubGenerator::long_copy_entry  = NULL;
address StubGenerator::oop_copy_entry   = NULL;

address StubGenerator::checkcast_copy_entry = NULL;

void StubGenerator_generate(CodeBuffer* code, bool all) {
  StubGenerator g(code, all);
}
