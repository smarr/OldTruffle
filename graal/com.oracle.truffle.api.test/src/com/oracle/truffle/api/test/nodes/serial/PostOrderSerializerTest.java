/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.nodes.serial;

import org.junit.*;

import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.nodes.serial.*;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.EmptyNode;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.NodeWithArray;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.NodeWithFields;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.NodeWithThreeChilds;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.NodeWithTwoArray;
import com.oracle.truffle.api.test.nodes.serial.TestNodes.StringNode;

public class PostOrderSerializerTest {

    private PostOrderSerializer s;
    private TestSerializerConstantPool cp;

    @Before
    public void setUp() {
        cp = new TestSerializerConstantPool();
        s = new PostOrderSerializer(cp);
    }

    @After
    public void tearDown() {
        cp = null;
        s = null;
    }

    @Test
    public void testNull() {
        Node ast = null;
        assertBytes(s.serialize(ast), VariableLengthIntBuffer.NULL);
        assertCP();
    }

    @Test
    public void testSingleEmptyNode() {
        Node ast = new EmptyNode();
        assertBytes(s.serialize(ast), 0);
        assertCP(EmptyNode.class);
    }

    @Test
    public void testThreeChilds() {
        Node ast = new NodeWithThreeChilds(new EmptyNode(), null, new EmptyNode());
        assertBytes(s.serialize(ast), 0, VariableLengthIntBuffer.NULL, 0, 1);
        assertCP(EmptyNode.class, NodeWithThreeChilds.class);
    }

    @Test
    public void testFields() {
        NodeWithFields ast = new NodeWithFields("test", Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Float.MIN_VALUE, Float.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE,
                        Character.MIN_VALUE, Character.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Byte.MIN_VALUE, Byte.MAX_VALUE, Boolean.TRUE, Boolean.FALSE);
        assertBytes(s.serialize(ast), 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 10);
        assertCP(NodeWithFields.class, "test", Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Float.MIN_VALUE, Float.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE,
                        (int) Character.MIN_VALUE, (int) Character.MAX_VALUE, (int) Short.MIN_VALUE, (int) Short.MAX_VALUE, (int) Byte.MIN_VALUE, (int) Byte.MAX_VALUE, 1);
    }

    @Test
    public void testFieldsNull() {
        NodeWithFields ast = new NodeWithFields("test", 0, null, 0L, null, 0f, null, 0d, null, (char) 0, null, (short) 0, null, (byte) 0, null, false, null);
        int nil = VariableLengthIntBuffer.NULL;
        assertBytes(s.serialize(ast), 0, 1, 2, nil, 3, nil, 4, nil, 5, nil, 2, nil, 2, nil, 2, nil, 2, nil);
        assertCP(NodeWithFields.class, "test", 0, 0L, 0.0F, 0.0D);
    }

    @Test
    public void testNChilds() {
        Node ast = new NodeWithArray(new Node[]{new EmptyNode(), new NodeWithArray(new Node[]{new EmptyNode(), new EmptyNode(), new EmptyNode()}), new EmptyNode(), new EmptyNode()});
        assertBytes(s.serialize(ast), 0, 1, 2, 0, 3, 2, 2, 2, 4, 2, 2, 4);
        assertCP(Node[].class, 4, EmptyNode.class, 3, NodeWithArray.class);
    }

    @Test
    public void testNullChilds() {
        Node ast = new NodeWithArray(null);
        assertBytes(s.serialize(ast), 0, VariableLengthIntBuffer.NULL, 1);
        assertCP(Node[].class, NodeWithArray.class);
    }

    @Test
    public void test2xNChilds() {
        Node ast = new NodeWithTwoArray(new Node[]{new StringNode("a0"), new StringNode("a1")}, new Node[]{new StringNode("b0"), new StringNode("b1"), new StringNode("b2")});
        assertBytes(s.serialize(ast), 0, 1, 2, 3, 2, 4, 0, 5, 2, 6, 2, 7, 2, 8, 9);
        assertCP(Node[].class, 2, StringNode.class, "a0", "a1", 3, "b0", "b1", "b2", NodeWithTwoArray.class);
    }

    private static void assertBytes(byte[] actualBytes, int... expectedIndexes) {
        VariableLengthIntBuffer buf = new VariableLengthIntBuffer(actualBytes);
        for (int i = 0; i < expectedIndexes.length; i++) {
            Assert.assertTrue("Unexpected EOF " + i, buf.hasRemaining());
            Assert.assertEquals("Index at pos " + i + ".", expectedIndexes[i], buf.get());
        }
        Assert.assertFalse(buf.hasRemaining());
    }

    private void assertCP(Object... object) {
        for (int i = 0; i < object.length; i++) {
            Object cpvalue = object[i];
            Assert.assertNotNull("CP at index " + i, cpvalue);
            Assert.assertEquals("CP at index " + i, cpvalue, cp.getObject(cpvalue.getClass(), i));
        }
        Assert.assertEquals(object.length, cp.getIndex());
    }

}
