/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
 */

#ifndef SHARE_VM_GRAAL_GRAAL_CODE_INSTALLER_HPP
#define SHARE_VM_GRAAL_GRAAL_CODE_INSTALLER_HPP

/*
 * This class handles the conversion from a InstalledCode to a CodeBlob or an nmethod.
 */
class CodeInstaller {
private:
  // these need to correspond to Marks.java
  enum MarkId {
    MARK_VERIFIED_ENTRY             = 1,
    MARK_UNVERIFIED_ENTRY           = 2,
    MARK_OSR_ENTRY                  = 3,
    MARK_EXCEPTION_HANDLER_ENTRY    = 4,
    MARK_DEOPT_HANDLER_ENTRY        = 5,
    MARK_INVOKEINTERFACE            = 6,
    MARK_INVOKEVIRTUAL              = 7,
    MARK_INVOKESTATIC               = 8,
    MARK_INVOKESPECIAL              = 9,
    MARK_INLINE_INVOKE              = 10,
    MARK_POLL_NEAR                  = 11,
    MARK_POLL_RETURN_NEAR           = 12,
    MARK_POLL_FAR                   = 13,
    MARK_POLL_RETURN_FAR            = 14,
    MARK_INVOKE_INVALID             = -1
  };

  Arena         _arena;

  oop           _comp_result;
  oop           _name;
  arrayOop      _sites;
  arrayOop      _exception_handlers;
  CodeOffsets   _offsets;

  arrayOop      _code;
  jint          _code_size;
  jint          _total_frame_size;
  jint          _custom_stack_area_offset;
  jint          _parameter_count;
  jint          _constants_size;
  jint          _total_size;

  MarkId        _next_call_type;
  address       _invoke_mark_pc;

  CodeSection*  _instructions;
  CodeSection*  _constants;

  OopRecorder*              _oop_recorder;
  DebugInformationRecorder* _debug_recorder;
  Dependencies*             _dependencies;
  ExceptionHandlerTable     _exception_handler_table;

public:

  // constructor used to create a method
  CodeInstaller(Handle& comp_result, methodHandle method, GraalEnv::CodeInstallResult& result, nmethod*& nm, Handle installed_code);

  // constructor used to create a stub
  CodeInstaller(Handle& target_method, BufferBlob*& blob, jlong& id);

  static address runtime_call_target_address(oop runtime_call);

private:
  // extract the fields of the CompilationResult
  void initialize_fields(oop target_method, methodHandle method);
  void initialize_assumptions(oop target_method);

  // perform data and call relocation on the CodeBuffer
  void initialize_buffer(CodeBuffer& buffer);

  void assumption_MethodContents(Handle assumption);
  void assumption_ConcreteSubtype(Handle assumption);
  void assumption_ConcreteMethod(Handle assumption);

  void site_Safepoint(CodeBuffer& buffer, jint pc_offset, oop site);
  void site_Call(CodeBuffer& buffer, jint pc_offset, oop site);
  void site_DataPatch(CodeBuffer& buffer, jint pc_offset, oop site);
  void site_Mark(CodeBuffer& buffer, jint pc_offset, oop site);

  void record_scope(jint pc_offset, oop code_pos, GrowableArray<ScopeValue*>* objects);

  void process_exception_handlers();

};

#endif // SHARE_VM_GRAAL_GRAAL_CODE_INSTALLER_HPP
