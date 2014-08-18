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
package com.oracle.graal.hotspot.replacements;

import static com.oracle.graal.compiler.common.GraalOptions.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.replacements.nodes.*;

/**
 * Substitutions for improving the performance of {@link NodeClass#get}.
 */
@ClassSubstitution(NodeClass.class)
public class HotSpotNodeClassSubstitutions {

    /**
     * A macro node for calls to {@link NodeClass#get(Class)}. It can use the compiler's knowledge
     * about node classes to replace itself with a constant value for a constant {@link Class}
     * parameter.
     */
    @NodeInfo
    public static class NodeClassGetNode extends PureFunctionMacroNode {

        public static NodeClassGetNode create(Invoke invoke) {
            return new HotSpotNodeClassSubstitutions_NodeClassGetNodeGen(invoke);
        }

        protected NodeClassGetNode(Invoke invoke) {
            super(invoke);
        }

        @Override
        protected Constant evaluate(Constant param, MetaAccessProvider metaAccess) {
            if (param.isNull() || ImmutableCode.getValue()) {
                return null;
            }
            return HotSpotObjectConstant.forObject(NodeClass.get((Class<?>) HotSpotObjectConstant.asObject(param)));
        }
    }

    /**
     * NOTE: A {@link MethodSubstitution} similar to
     * {@link HotSpotNodeSubstitutions#getNodeClass(Node)} is not possible here because there is no
     * guarantee that {@code c} is initialized (accessing a Class literal in Java is not a class
     * initialization barrier).
     */
    @MacroSubstitution(isStatic = true, forced = true, macro = NodeClassGetNode.class)
    public static native NodeClass get(Class<?> c);
}
