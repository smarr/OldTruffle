/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.sparc;

import static com.oracle.graal.api.code.ValueUtil.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.*;
import com.oracle.graal.lir.gen.*;

public class SPARCHotSpotLIRGenerationResult extends LIRGenerationResultBase {

    /**
     * The slot reserved for storing the original return address when a frame is marked for
     * deoptimization. The return address slot in the callee is overwritten with the address of a
     * deoptimization stub.
     */
    private VirtualStackSlot deoptimizationRescueSlot;
    private final Object stub;

    /**
     * Map from debug infos that need to be updated with callee save information to the operations
     * that provide the information.
     */
    private Map<LIRFrameState, SaveRegistersOp> calleeSaveInfo = new HashMap<>();

    public SPARCHotSpotLIRGenerationResult(LIR lir, FrameMapBuilder frameMapBuilder, Object stub) {
        super(lir, frameMapBuilder);
        this.stub = stub;
    }

    StackSlot getDeoptimizationRescueSlot() {
        if (deoptimizationRescueSlot == null) {
            return null;
        }
        assert isStackSlot(deoptimizationRescueSlot);
        return asStackSlot(deoptimizationRescueSlot);
    }

    public final void setDeoptimizationRescueSlot(VirtualStackSlot stackSlot) {
        this.deoptimizationRescueSlot = stackSlot;
    }

    Stub getStub() {
        return (Stub) stub;
    }

    Map<LIRFrameState, SaveRegistersOp> getCalleeSaveInfo() {
        return calleeSaveInfo;
    }

}
