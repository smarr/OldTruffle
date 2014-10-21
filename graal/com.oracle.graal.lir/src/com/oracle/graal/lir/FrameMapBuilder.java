/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.lir;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;

public interface FrameMapBuilder {

    /**
     * Computes the final size of this frame. After this method has been called, methods that change
     * the frame size cannot be called anymore, e.g., no more spill slots or outgoing arguments can
     * be requested.
     */
    void finish();

    /**
     * Reserves a spill slot in the frame of the method being compiled. The returned slot is aligned
     * on its natural alignment, i.e., an 8-byte spill slot is aligned at an 8-byte boundary, unless
     * overridden by a subclass.
     *
     * @param kind The kind of the spill slot to be reserved.
     * @return A spill slot denoting the reserved memory area.
     */
    StackSlot allocateSpillSlot(LIRKind kind);

    /**
     * Reserves a number of contiguous slots in the frame of the method being compiled. If the
     * requested number of slots is 0, this method returns {@code null}.
     *
     * @param slots the number of slots to reserve
     * @param objects specifies the indexes of the object pointer slots. The caller is responsible
     *            for guaranteeing that each such object pointer slot is initialized before any
     *            instruction that uses a reference map. Without this guarantee, the garbage
     *            collector could see garbage object values.
     * @param outObjectStackSlots if non-null, the object pointer slots allocated are added to this
     *            list
     * @return the first reserved stack slot (i.e., at the lowest address)
     */
    StackSlot allocateStackSlots(int slots, BitSet objects, List<StackSlot> outObjectStackSlots);

    RegisterConfig getRegisterConfig();

    /**
     * Frees a spill slot that was obtained via {@link #allocateSpillSlot(LIRKind)} such that it can
     * be reused for the next allocation request for the same kind of slot.
     */
    void freeSpillSlot(StackSlot slot);

    /**
     * Informs the frame map that the compiled code calls a particular method, which may need stack
     * space for outgoing arguments.
     *
     * @param cc The calling convention for the called method.
     */
    void callsMethod(CallingConvention cc);
}