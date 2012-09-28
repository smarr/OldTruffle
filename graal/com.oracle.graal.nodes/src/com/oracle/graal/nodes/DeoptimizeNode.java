/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;

@NodeInfo(shortName = "Deopt", nameTemplate = "Deopt {p#reason/s}")
public class DeoptimizeNode extends FixedNode implements Node.IterableNodeType, LIRLowerable {

    private String message;
    private final DeoptimizationAction action;
    private final DeoptimizationReason reason;
    private final long leafGraphId;


    public DeoptimizeNode(DeoptimizationAction action, DeoptimizationReason reason) {
        this(action, reason, -1);
    }

    public DeoptimizeNode(DeoptimizationAction action, DeoptimizationReason reason, long leafGraphId) {
        super(StampFactory.forVoid());
        this.action = action;
        this.reason = reason;
        this.leafGraphId = leafGraphId;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public DeoptimizationAction action() {
        return action;
    }

    public DeoptimizationReason reason() {
        return reason;
    }

    public long leafGraphId() {
        return leafGraphId;
    }

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.emitDeoptimize(action, reason, message, leafGraphId);
    }

    @NodeIntrinsic
    public static native void deopt(@ConstantNodeParameter DeoptimizationAction action, @ConstantNodeParameter DeoptimizationReason reason);
}
