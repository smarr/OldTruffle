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
package com.oracle.truffle.api.codegen.test;

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.api.codegen.test.RuntimeStringTest.RuntimeString;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

public class TypeSystemTest {

    @TypeSystem({int.class, RuntimeString.class})
    static class SimpleTypes {
    }

    @TypeSystemReference(SimpleTypes.class)
    abstract static class ValueNode extends Node {

        int executeInt() throws UnexpectedResultException {
            return SimpleTypesGen.SIMPLETYPES.expectInteger(execute());
        }

        RuntimeString executeString() {
            return new RuntimeString(execute().toString());
        }

        @SuppressWarnings("static-method")
        final long executeSpecial() {
            return 42L;
        }

        abstract Object execute();
    }

    @TypeSystemReference(SimpleTypes.class)
    static class TestRootNode extends RootNode {

        @Child private ValueNode node;

        public TestRootNode(ValueNode node) {
            this.node = adoptChild(node);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return node.execute();
        }
    }

}
