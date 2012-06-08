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
package com.oracle.graal.snippets;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.extended.*;

/**
 * Snippets for improving the performance of some critical methods in {@link NodeClass} methods.
 * These snippets improve the performance by forcing the relevant methods to be inlined
 * (intrinsification being a special form of inlining) and removing a checked cast.
 * The latter cannot be done directly in Java code as {@link UnsafeCastNode}
 * is not available to the project containing {@link NodeClass}.
 */
@SuppressWarnings("unused")
@ClassSubstitution(NodeClass.class)
public class NodeClassSnippets implements SnippetsInterface {

    private static Node getNode(Node node, long offset) {
        return UnsafeCastNode.cast(UnsafeLoadNode.load(node, 0, offset, Kind.Object), Node.class);
    }

    private static NodeList<Node> getNodeList(Node node, long offset) {
        return UnsafeCastNode.cast(UnsafeLoadNode.load(node, 0, offset, Kind.Object), NodeList.class);
    }

    private static void putNode(Node node, long offset, Node value) {
        UnsafeStoreNode.store(node, 0, offset, value, Kind.Object);
    }

    private static void putNodeList(Node node, long offset, NodeList value) {
        UnsafeStoreNode.store(node, 0, offset, value, Kind.Object);
    }

}
