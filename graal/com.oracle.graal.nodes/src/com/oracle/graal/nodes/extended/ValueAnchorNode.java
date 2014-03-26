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
package com.oracle.graal.nodes.extended;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

/**
 * The ValueAnchor instruction keeps non-CFG (floating) nodes above a certain point in the graph.
 */
public final class ValueAnchorNode extends FixedWithNextNode implements Canonicalizable, LIRLowerable, Virtualizable, GuardingNode {

    @Input private ValueNode anchored;

    public ValueAnchorNode(ValueNode value) {
        super(StampFactory.dependency());
        this.anchored = value;
    }

    @Override
    public void generate(NodeLIRGeneratorTool gen) {
        // Nothing to emit, since this node is used for structural purposes only.
    }

    public ValueNode getAnchoredNode() {
        return anchored;
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (anchored != null && !anchored.isConstant() && !(anchored instanceof FixedNode)) {
            // Found entry that needs this anchor.
            return this;
        }

        if (usages().isNotEmpty()) {
            // A not uses this anchor => anchor is necessary.
            return this;
        }

        // Anchor is not necessary any more => remove.
        return null;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        if (anchored != null && !(anchored instanceof AbstractBeginNode)) {
            State state = tool.getObjectState(anchored);
            if (state == null || state.getState() != EscapeState.Virtual) {
                return;
            }
        }
        tool.delete();
    }

    public void removeAnchoredNode() {
        this.updateUsages(anchored, null);
        this.anchored = null;
    }
}
