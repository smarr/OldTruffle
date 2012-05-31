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
package com.oracle.graal.nodes.java;

import java.lang.reflect.*;

import sun.misc.*;

import com.oracle.graal.cri.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.max.cri.ci.*;
import com.oracle.max.cri.ri.*;

/**
 * The {@code LoadIndexedNode} represents a read from an element of an array.
 */
public final class LoadIndexedNode extends AccessIndexedNode implements Canonicalizable, Lowerable, Node.IterableNodeType {

    /**
     * Creates a new LoadIndexedNode.
     * @param array the instruction producing the array
     * @param index the instruction producing the index
     * @param elementKind the element type
     */
    public LoadIndexedNode(ValueNode array, ValueNode index, CiKind elementKind, long leafGraphId) {
        super(createStamp(array, elementKind), array, index, elementKind, leafGraphId);
    }

    private static Stamp createStamp(ValueNode array, CiKind kind) {
        if (kind == CiKind.Object && array.objectStamp().type() != null) {
            return StampFactory.declared(array.objectStamp().type().componentType());
        } else {
            return StampFactory.forKind(kind);
        }
    }

    @Override
    public void lower(CiLoweringTool tool) {
        tool.getRuntime().lower(this, tool);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        RiRuntime runtime = tool.runtime();
        if (runtime != null && index().isConstant() && array().isConstant() && !array().isNullConstant()) {
            CiConstant arrayConst = array().asConstant();
            if (tool.isImmutable(arrayConst)) {
                int index = index().asConstant().asInt();
                Object array = arrayConst.asObject();
                int length = Array.getLength(array);
                if (index >= 0 && index < length) {
                    return ConstantNode.forCiConstant(elementKind().readUnsafeConstant(array,
                                    Unsafe.ARRAY_OBJECT_BASE_OFFSET + index * Unsafe.ARRAY_OBJECT_INDEX_SCALE), runtime, graph());
                }
            }
        }
        return this;
    }
}
