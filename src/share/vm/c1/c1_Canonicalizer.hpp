/*
 * Copyright (c) 1999, 2006, Oracle and/or its affiliates. All rights reserved.
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

class Canonicalizer: InstructionVisitor {
 private:
  Instruction* _canonical;
  int _bci;

  void set_canonical(Value x);
  void set_bci(int bci)                          { _bci = bci; }
  void set_constant(jint x)                      { set_canonical(new Constant(new IntConstant(x))); }
  void set_constant(jlong x)                     { set_canonical(new Constant(new LongConstant(x))); }
  void set_constant(jfloat x)                    { set_canonical(new Constant(new FloatConstant(x))); }
  void set_constant(jdouble x)                   { set_canonical(new Constant(new DoubleConstant(x))); }
  void move_const_to_right(Op2* x);
  void do_Op2(Op2* x);
  void do_UnsafeRawOp(UnsafeRawOp* x);

  void unsafe_raw_match(UnsafeRawOp* x,
                        Instruction** base,
                        Instruction** index,
                        int* scale);

 public:
  Canonicalizer(Value x, int bci)                { _canonical = x; _bci = bci; if (CanonicalizeNodes) x->visit(this); }
  Value canonical() const                        { return _canonical; }
  int bci() const                                { return _bci; }

  virtual void do_Phi            (Phi*             x);
  virtual void do_Constant       (Constant*        x);
  virtual void do_Local          (Local*           x);
  virtual void do_LoadField      (LoadField*       x);
  virtual void do_StoreField     (StoreField*      x);
  virtual void do_ArrayLength    (ArrayLength*     x);
  virtual void do_LoadIndexed    (LoadIndexed*     x);
  virtual void do_StoreIndexed   (StoreIndexed*    x);
  virtual void do_NegateOp       (NegateOp*        x);
  virtual void do_ArithmeticOp   (ArithmeticOp*    x);
  virtual void do_ShiftOp        (ShiftOp*         x);
  virtual void do_LogicOp        (LogicOp*         x);
  virtual void do_CompareOp      (CompareOp*       x);
  virtual void do_IfOp           (IfOp*            x);
  virtual void do_IfInstanceOf   (IfInstanceOf*    x);
  virtual void do_Convert        (Convert*         x);
  virtual void do_NullCheck      (NullCheck*       x);
  virtual void do_Invoke         (Invoke*          x);
  virtual void do_NewInstance    (NewInstance*     x);
  virtual void do_NewTypeArray   (NewTypeArray*    x);
  virtual void do_NewObjectArray (NewObjectArray*  x);
  virtual void do_NewMultiArray  (NewMultiArray*   x);
  virtual void do_CheckCast      (CheckCast*       x);
  virtual void do_InstanceOf     (InstanceOf*      x);
  virtual void do_MonitorEnter   (MonitorEnter*    x);
  virtual void do_MonitorExit    (MonitorExit*     x);
  virtual void do_Intrinsic      (Intrinsic*       x);
  virtual void do_BlockBegin     (BlockBegin*      x);
  virtual void do_Goto           (Goto*            x);
  virtual void do_If             (If*              x);
  virtual void do_TableSwitch    (TableSwitch*     x);
  virtual void do_LookupSwitch   (LookupSwitch*    x);
  virtual void do_Return         (Return*          x);
  virtual void do_Throw          (Throw*           x);
  virtual void do_Base           (Base*            x);
  virtual void do_OsrEntry       (OsrEntry*        x);
  virtual void do_ExceptionObject(ExceptionObject* x);
  virtual void do_RoundFP        (RoundFP*         x);
  virtual void do_UnsafeGetRaw   (UnsafeGetRaw*    x);
  virtual void do_UnsafePutRaw   (UnsafePutRaw*    x);
  virtual void do_UnsafeGetObject(UnsafeGetObject* x);
  virtual void do_UnsafePutObject(UnsafePutObject* x);
  virtual void do_UnsafePrefetchRead (UnsafePrefetchRead*  x);
  virtual void do_UnsafePrefetchWrite(UnsafePrefetchWrite* x);
  virtual void do_ProfileCall    (ProfileCall*     x);
  virtual void do_ProfileCounter (ProfileCounter*  x);
};
