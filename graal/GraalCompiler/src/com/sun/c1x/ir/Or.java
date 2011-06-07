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
package com.sun.c1x.ir;

import com.oracle.graal.graph.*;
import com.oracle.max.graal.opt.CanonicalizerPhase.CanonicalizerOp;
import com.sun.cri.bytecode.*;
import com.sun.cri.ci.*;


/**
 *
 */
public final class Or extends Logic {
    private static final OrCanonicalizerOp CANONICALIZER = new OrCanonicalizerOp();

    /**
     * @param opcode
     * @param kind
     * @param x
     * @param y
     * @param graph
     */
    public Or(CiKind kind, Value x, Value y, Graph graph) {
        super(kind, kind == CiKind.Int ? Bytecodes.IOR : Bytecodes.LOR, x, y, graph);
    }

    @Override
    public String shortName() {
        return "|";
    }

    @Override
    public Node copy(Graph into) {
        Or x = new Or(kind, null, null, into);
        return x;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Op> T lookup(Class<T> clazz) {
        if (clazz == CanonicalizerOp.class) {
            return (T) CANONICALIZER;
        }
        return super.lookup(clazz);
    }

    private static class OrCanonicalizerOp implements CanonicalizerOp {
        @Override
        public Node canonical(Node node) {
            assert node instanceof Or;
            Or or = (Or) node;
            CiKind kind = or.kind;
            Graph graph = or.graph();
            Value x = or.x();
            Value y = or.y();
            if (x == y) {
                return x;
            }
            if (x.isConstant() && !y.isConstant()) {
                or.swapOperands();
                Value t = y;
                y = x;
                x = t;
            }
            if (x.isConstant()) {
                if (kind == CiKind.Int) {
                    return Constant.forInt(x.asConstant().asInt() | y.asConstant().asInt(), graph);
                } else {
                    assert kind == CiKind.Long;
                    return Constant.forLong(x.asConstant().asLong() | y.asConstant().asLong(), graph);
                }
            } else if (y.isConstant()) {
                if (kind == CiKind.Int) {
                    int c = y.asConstant().asInt();
                    if (c == -1) {
                        return Constant.forInt(-1, graph);
                    }
                    if (c == 0) {
                        return x;
                    }
                } else {
                    assert kind == CiKind.Long;
                    long c = y.asConstant().asLong();
                    if (c == -1) {
                        return Constant.forLong(-1, graph);
                    }
                    if (c == 0) {
                        return x;
                    }
                }
            }
            return or;
        }
    }
}
