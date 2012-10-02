/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.target.amd64;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.api.meta.Value.*;
import static com.oracle.max.asm.target.amd64.AMD64.*;

import java.lang.reflect.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.target.amd64.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.*;
import com.oracle.graal.hotspot.counters.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hotspot.target.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.amd64.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.max.asm.*;
import com.oracle.max.asm.target.amd64.*;
import com.oracle.max.asm.target.amd64.AMD64Assembler.ConditionFlag;

/**
 * HotSpot AMD64 specific backend.
 */
public class HotSpotAMD64Backend extends HotSpotBackend {

    public HotSpotAMD64Backend(CodeCacheProvider runtime, TargetDescription target) {
        super(runtime, target);
        HotSpotRuntime hs = (HotSpotRuntime) runtime;
        HotSpotVMConfig c = hs.config;
        Kind word = target.wordKind;

        addStub("monitorenter", c.fastMonitorEnterStub,
                /*        temps */ new Register[] {rax, rbx},
                /*          ret */ IllegalValue,
                /* arg0: object */ rsi.asValue(Kind.Object),
                /* arg1:   lock */ rdx.asValue(word));

        addStub("monitorexit", c.fastMonitorExitStub,
                /*        temps */ new Register[] {rax, rbx},
                /*          ret */ IllegalValue,
                /* arg0: object */ rsi.asValue(Kind.Object),
                /* arg1:   lock */ rdx.asValue(word));

        addStub("new_object_array", c.newObjectArrayStub,
                /*        temps */ new Register[] {rcx, rdi, rsi},
                /*          ret */ rax.asValue(Kind.Object),
                /* arg0:    hub */ rdx.asValue(Kind.Object),
                /* arg1: length */ rbx.asValue(Kind.Int));

        addStub("new_type_array", c.newTypeArrayStub,
                /*        temps */ new Register[] {rcx, rdi, rsi},
                /*          ret */ rax.asValue(Kind.Object),
                /* arg0:    hub */ rdx.asValue(Kind.Object),
                /* arg1: length */ rbx.asValue(Kind.Int));

        addStub("new_instance", c.newInstanceStub,
                /*        temps */ null,
                /*          ret */ rax.asValue(Kind.Object),
                /* arg0:    hub */ rdx.asValue(Kind.Object));

        addStub("new_multi_array", c.newMultiArrayStub,
                        /*        temps */ null,
                        /*          ret */ rax.asValue(Kind.Object),
                        /* arg0:    hub */ rax.asValue(Kind.Object),
                        /* arg1:   rank */ rbx.asValue(Kind.Int),
                        /* arg2:   dims */ rcx.asValue(word));
    }

    @Override
    public LIRGenerator newLIRGenerator(Graph graph, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
        return new HotSpotAMD64LIRGenerator(graph, runtime, target, frameMap, method, lir);
    }

    static final class HotSpotAMD64LIRGenerator extends AMD64LIRGenerator {

        private HotSpotAMD64LIRGenerator(Graph graph, CodeCacheProvider runtime, TargetDescription target, FrameMap frameMap, ResolvedJavaMethod method, LIR lir) {
            super(graph, runtime, target, frameMap, method, lir);
        }

        @Override
        public void visitSafepointNode(SafepointNode i) {
            LIRFrameState info = state();
            append(new AMD64SafepointOp(info, ((HotSpotRuntime) runtime).config));
        }

        @Override
        public void visitBreakpointNode(BreakpointNode i) {
            Kind[] sig = new Kind[i.arguments.size()];
            int pos = 0;
            for (ValueNode arg : i.arguments) {
                sig[pos++] = arg.kind();
            }

            CallingConvention cc = frameMap.registerConfig.getCallingConvention(CallingConvention.Type.JavaCall, Kind.Void, sig, target(), false);
            Value[] parameters = visitInvokeArguments(cc, i.arguments);
            append(new AMD64BreakpointOp(parameters));
        }

        @Override
        public void visitExceptionObject(ExceptionObjectNode x) {
            HotSpotVMConfig config = ((HotSpotRuntime) runtime).config;
            RegisterValue thread = config.threadRegister.asValue();
            Address exceptionAddress = new Address(Kind.Object, thread, config.threadExceptionOopOffset);
            Address pcAddress = new Address(Kind.Long, thread, config.threadExceptionPcOffset);
            Value exception = emitLoad(exceptionAddress, false);
            emitStore(exceptionAddress, Constant.NULL_OBJECT, false);
            emitStore(pcAddress, Constant.LONG_0, false);
            setResult(x, exception);
        }

        @Override
        protected void emitPrologue() {
            super.emitPrologue();
            MethodEntryCounters.emitCounter(this, method);
        }

        @Override
        protected void emitDirectCall(DirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
            append(new AMD64DirectCallOp(callTarget.target(), result, parameters, temps, callState, ((HotSpotDirectCallTargetNode) callTarget).invokeKind(), lir));
        }

        @Override
        protected void emitIndirectCall(IndirectCallTargetNode callTarget, Value result, Value[] parameters, Value[] temps, LIRFrameState callState) {
            Value methodOop = AMD64.rbx.asValue();
            emitMove(operand(((HotSpotIndirectCallTargetNode) callTarget).methodOop()), methodOop);
            Value targetAddress = AMD64.rax.asValue();
            emitMove(operand(callTarget.computedAddress()), targetAddress);
            append(new AMD64IndirectCallOp(callTarget.target(), result, parameters, temps, methodOop, targetAddress, callState));
        }
    }

    class HotSpotFrameContext implements FrameContext {

        @Override
        public void enter(TargetMethodAssembler tasm) {
            FrameMap frameMap = tasm.frameMap;
            int frameSize = frameMap.frameSize();

            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
            emitStackOverflowCheck(tasm, false);
            asm.push(rbp);
            asm.movq(rbp, rsp);
            asm.decrementq(rsp, frameSize - 8); // account for the push of RBP above
            if (GraalOptions.ZapStackOnMethodEntry) {
                final int intSize = 4;
                for (int i = 0; i < frameSize / intSize; ++i) {
                    asm.movl(new Address(Kind.Int, rsp.asValue(), i * intSize), 0xC1C1C1C1);
                }
            }
            CalleeSaveLayout csl = frameMap.registerConfig.getCalleeSaveLayout();
            if (csl != null && csl.size != 0) {
                int frameToCSA = frameMap.offsetToCalleeSaveArea();
                assert frameToCSA >= 0;
                asm.save(csl, frameToCSA);
            }
        }

        @Override
        public void leave(TargetMethodAssembler tasm) {
            int frameSize = tasm.frameMap.frameSize();
            AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
            CalleeSaveLayout csl = tasm.frameMap.registerConfig.getCalleeSaveLayout();
            RegisterConfig regConfig = tasm.frameMap.registerConfig;

            if (csl != null && csl.size != 0) {
                tasm.targetMethod.setRegisterRestoreEpilogueOffset(asm.codeBuffer.position());
                // saved all registers, restore all registers
                int frameToCSA = tasm.frameMap.offsetToCalleeSaveArea();
                asm.restore(csl, frameToCSA);
            }

            asm.incrementq(rsp, frameSize - 8); // account for the pop of RBP below
            asm.pop(rbp);

            if (GraalOptions.GenSafepoints) {
                HotSpotVMConfig config = ((HotSpotRuntime) runtime).config;

                // If at the return point, then the frame has already been popped
                // so deoptimization cannot be performed here. The HotSpot runtime
                // detects this case - see the definition of frame::should_be_deoptimized()

                Register scratch = regConfig.getScratchRegister();
                if (config.isPollingPageFar) {
                    asm.movq(scratch, config.safepointPollingAddress);
                    tasm.recordMark(Marks.MARK_POLL_RETURN_FAR);
                    asm.movq(scratch, new Address(tasm.target.wordKind, scratch.asValue()));
                } else {
                    tasm.recordMark(Marks.MARK_POLL_RETURN_NEAR);
                    asm.movq(scratch, new Address(tasm.target.wordKind, rip.asValue()));
                }
            }
        }
    }

    @Override
    public TargetMethodAssembler newAssembler(FrameMap frameMap, LIR lir) {
        // Omit the frame if the method:
        //  - has no spill slots or other slots allocated during register allocation
        //  - has no callee-saved registers
        //  - has no incoming arguments passed on the stack
        //  - has no instructions with debug info
        boolean canOmitFrame =
            frameMap.frameSize() == frameMap.initialFrameSize &&
            frameMap.registerConfig.getCalleeSaveLayout().registers.length == 0 &&
            !lir.hasArgInCallerFrame() &&
            !lir.hasDebugInfo();

        AbstractAssembler masm = new AMD64MacroAssembler(target, frameMap.registerConfig);
        HotSpotFrameContext frameContext = canOmitFrame ? null : new HotSpotFrameContext();
        TargetMethodAssembler tasm = new TargetMethodAssembler(target, runtime, frameMap, masm, frameContext, lir.stubs);
        tasm.setFrameSize(frameMap.frameSize());
        tasm.targetMethod.setCustomStackAreaOffset(frameMap.offsetToCustomArea());
        return tasm;
    }

    @Override
    public void emitCode(TargetMethodAssembler tasm, ResolvedJavaMethod method, LIR lir) {
        AMD64MacroAssembler asm = (AMD64MacroAssembler) tasm.asm;
        FrameMap frameMap = tasm.frameMap;
        RegisterConfig regConfig = frameMap.registerConfig;
        HotSpotVMConfig config = ((HotSpotRuntime) runtime).config;
        Label unverifiedStub = new Label();

        // Emit the prefix
        tasm.recordMark(Marks.MARK_OSR_ENTRY);

        boolean isStatic = Modifier.isStatic(method.accessFlags());
        if (!isStatic) {
            tasm.recordMark(Marks.MARK_UNVERIFIED_ENTRY);
            CallingConvention cc = regConfig.getCallingConvention(JavaCallee, Kind.Void, new Kind[] {Kind.Object}, target, false);
            Register inlineCacheKlass = rax; // see definition of IC_Klass in c1_LIRAssembler_x86.cpp
            Register receiver = asRegister(cc.getArgument(0));
            Address src = new Address(target.wordKind, receiver.asValue(), config.hubOffset);

            asm.cmpq(inlineCacheKlass, src);
            asm.jcc(ConditionFlag.notEqual, unverifiedStub);
        }

        asm.align(config.codeEntryAlignment);
        tasm.recordMark(Marks.MARK_VERIFIED_ENTRY);

        // Emit code for the LIR
        lir.emitCode(tasm);

        boolean frameOmitted = tasm.frameContext == null;
        if (!frameOmitted) {
            tasm.recordMark(Marks.MARK_EXCEPTION_HANDLER_ENTRY);
            AMD64Call.directCall(tasm, asm, config.handleExceptionStub, null);
            AMD64Call.shouldNotReachHere(tasm, asm);

            tasm.recordMark(Marks.MARK_DEOPT_HANDLER_ENTRY);
            AMD64Call.directCall(tasm, asm, config.handleDeoptStub, null);
            AMD64Call.shouldNotReachHere(tasm, asm);
        } else {
            // No need to emit the stubs for entries back into the method since
            // it has no calls that can cause such "return" entries
            assert !frameMap.accessesCallerFrame();
        }

        if (!isStatic) {
            asm.bind(unverifiedStub);
            AMD64Call.directJmp(tasm, asm, config.inlineCacheMissStub);
        }

        for (int i = 0; i < GraalOptions.MethodEndBreakpointGuards; ++i) {
            asm.int3();
        }
    }
}
