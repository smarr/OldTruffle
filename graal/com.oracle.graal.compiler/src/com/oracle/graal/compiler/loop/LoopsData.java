/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.loop;

import java.util.*;

import com.oracle.graal.lir.cfg.*;
import com.oracle.graal.nodes.*;

public class LoopsData {
    private Map<Loop, LoopEx> lirLoopToEx = new IdentityHashMap<>();
    private Map<LoopBeginNode, LoopEx> loopBeginToEx = new IdentityHashMap<>();

    public LoopsData(StructuredGraph graph) {
        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, false);
        for (Loop lirLoop : cfg.getLoops()) {
            LoopEx ex = new LoopEx(lirLoop, this);
            lirLoopToEx.put(lirLoop, ex);
            loopBeginToEx.put(ex.loopBegin(), ex);
        }
    }

    public LoopEx loop(Loop lirLoop) {
        return lirLoopToEx.get(lirLoop);
    }

    public LoopEx loop(LoopBeginNode loopBegin) {
        return loopBeginToEx.get(loopBegin);
    }

    public Collection<LoopEx> loops() {
        return lirLoopToEx.values();
    }

    public List<LoopEx> outterFirst() {
        ArrayList<LoopEx> loops = new ArrayList<>(loops());
        Collections.sort(loops, new Comparator<LoopEx>() {
            @Override
            public int compare(LoopEx o1, LoopEx o2) {
                return o1.lirLoop().depth - o2.lirLoop().depth;
            }
        });
        return loops;
    }

    public Collection<LoopEx> countedLoops() {
        List<LoopEx> counted = new LinkedList<>();
        for (LoopEx loop : loops()) {
            if (loop.isCounted()) {
                counted.add(loop);
            }
        }
        return counted;
    }
}
