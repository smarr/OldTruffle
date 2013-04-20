/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.virtual;

import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;

@NodeInfo(nameTemplate = "VirtualInstance {p#type}")
public class VirtualInstanceNode extends VirtualObjectNode {

    private final ResolvedJavaType type;
    private final ResolvedJavaField[] fields;

    public VirtualInstanceNode(ResolvedJavaType type) {
        this.type = type;
        this.fields = type.getInstanceFields(true);
    }

    @Override
    public ResolvedJavaType type() {
        return type;
    }

    @Override
    public int entryCount() {
        return fields.length;
    }

    public ResolvedJavaField field(int index) {
        return fields[index];
    }

    public ResolvedJavaField[] getFields() {
        return fields;
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Name) {
            return super.toString(Verbosity.Name) + " " + MetaUtil.toJavaName(type, false);
        } else {
            return super.toString(verbosity);
        }
    }

    @Override
    public String fieldName(int index) {
        return fields[index].getName();
    }

    public int fieldIndex(ResolvedJavaField field) {
        // on average fields.length == ~6, so a linear search is fast enough
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == field) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int entryIndexForOffset(long constantOffset) {
        return fieldIndex(type.findInstanceFieldWithOffset(constantOffset));
    }

    @Override
    public Kind entryKind(int index) {
        assert index >= 0 && index < fields.length;
        return fields[index].getKind();
    }

    @Override
    public VirtualInstanceNode duplicate() {
        return new VirtualInstanceNode(type);
    }

    @Override
    public void materializeAt(FixedWithNextNode materializeNode, List<ValueNode> values, boolean defaultValuesOnly, int lockCount) {
        StructuredGraph graph = (StructuredGraph) graph();
        NewInstanceNode newInstance = graph.add(new NewInstanceNode(type(), defaultValuesOnly, lockCount > 0));
        materializeNode.replaceAtUsages(newInstance);
        graph.addBeforeFixed(materializeNode, newInstance);
        if (!defaultValuesOnly) {
            for (int i = 0; i < entryCount(); i++) {
                graph.addBeforeFixed(materializeNode, graph.add(new StoreFieldNode(newInstance, field(i), values.get(i))));
            }
        }
        graph.removeFixed(materializeNode);
    }
}
