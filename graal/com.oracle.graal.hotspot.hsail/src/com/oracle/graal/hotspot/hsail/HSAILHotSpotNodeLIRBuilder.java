/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.hotspot.hsail;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.compiler.hsail.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.HotSpotVMConfig.CompressEncoding;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.nodes.*;

/**
 * The HotSpot specific portion of the HSAIL LIR generator.
 */
public class HSAILHotSpotNodeLIRBuilder extends HSAILNodeLIRBuilder {

    public HSAILHotSpotNodeLIRBuilder(StructuredGraph graph, LIRGenerator lirGen) {
        super(graph, lirGen);
    }

    @Override
    protected void emitNode(ValueNode node) {
        if (node instanceof CurrentJavaThreadNode) {
            throw new GraalInternalError("HSAILHotSpotLIRGenerator cannot handle node: " + node);
        } else {
            super.emitNode(node);
        }
    }

    /**
     * @return a compressed version of the incoming constant lifted from AMD64HotSpotLIRGenerator
     */
    protected static Constant compress(Constant c, CompressEncoding encoding) {
        if (c.getKind() == Kind.Long) {
            int compressedValue = (int) (((c.asLong() - encoding.base) >> encoding.shift) & 0xffffffffL);
            if (c instanceof HotSpotMetaspaceConstant) {
                return HotSpotMetaspaceConstant.forMetaspaceObject(Kind.Int, compressedValue, HotSpotMetaspaceConstant.getMetaspaceObject(c));
            } else {
                return Constant.forIntegerKind(Kind.Int, compressedValue);
            }
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
    }
}
