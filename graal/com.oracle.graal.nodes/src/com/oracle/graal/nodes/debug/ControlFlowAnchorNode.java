/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.debug;

import com.oracle.graal.compiler.common.type.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;

/**
 * This node prevents control flow optimizations. It is never duplicated or merged with other
 * control flow anchors.
 */
@NodeInfo
public final class ControlFlowAnchorNode extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<ControlFlowAnchorNode> TYPE = NodeClass.get(ControlFlowAnchorNode.class);

    private static class Unique {
    }

    protected Unique unique;

    public ControlFlowAnchorNode() {
        super(TYPE, StampFactory.forVoid());
        this.unique = new Unique();
    }

    /**
     * Used by MacroSubstitution.
     */
    public ControlFlowAnchorNode(@SuppressWarnings("unused") Invoke invoke) {
        this();
    }

    public void generate(NodeLIRBuilderTool generator) {
        // do nothing
    }

    @Override
    protected void afterClone(Node other) {
        assert false : this + " should never be cloned";
    }
}
