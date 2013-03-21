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
package com.oracle.graal.compiler.test.ea;

import junit.framework.*;

import org.junit.Test;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.test.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.virtual.nodes.*;
import com.oracle.graal.virtual.phases.ea.*;

/**
 * In these test cases the probability of all invokes is set to a high value, such that an
 * InliningPhase should inline them all. After that, the EscapeAnalysisPhase is expected to remove
 * all allocations and return the correct values.
 */
public class EscapeAnalysisTest extends GraalCompilerTest {

    @Test
    public void test1() {
        testEscapeAnalysis("test1Snippet", Constant.forInt(101), false);
    }

    public static int test1Snippet() {
        Integer x = new Integer(101);
        return x.intValue();
    }

    @Test
    public void test2() {
        testEscapeAnalysis("test2Snippet", Constant.forInt(0), false);
    }

    public static int test2Snippet() {
        Integer[] x = new Integer[0];
        return x.length;
    }

    @Test
    public void test3() {
        testEscapeAnalysis("test3Snippet", Constant.forObject(null), false);
    }

    public static Object test3Snippet() {
        Integer[] x = new Integer[1];
        return x[0];
    }

    @Test
    public void testMonitor() {
        testEscapeAnalysis("testMonitorSnippet", Constant.forInt(0), false);
    }

    private static native void notInlineable();

    public static int testMonitorSnippet() {
        Integer x = new Integer(0);
        Integer[] y = new Integer[0];
        Integer[] z = new Integer[1];
        synchronized (x) {
            synchronized (y) {
                synchronized (z) {
                    notInlineable();
                }
            }
        }
        return x.intValue();
    }

    @Test
    public void testMonitor2() {
        testEscapeAnalysis("testMonitor2Snippet", Constant.forInt(0), false);
    }

    /**
     * This test case differs from the last one in that it requires inlining within a synchronized
     * region.
     */
    public static int testMonitor2Snippet() {
        Integer x = new Integer(0);
        Integer[] y = new Integer[0];
        Integer[] z = new Integer[1];
        synchronized (x) {
            synchronized (y) {
                synchronized (z) {
                    notInlineable();
                    return x.intValue();
                }
            }
        }
    }

    @Test
    public void testMerge() {
        testEscapeAnalysis("testMerge1Snippet", Constant.forInt(0), true);
    }

    public static class TestObject {

        int x;
        int y;

        public TestObject(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static int testMerge1Snippet(int a) {
        TestObject obj = new TestObject(1, 0);
        if (a < 0) {
            obj.x = obj.x + 1;
        } else {
            obj.x = obj.x + 2;
            obj.y = 0;
        }
        if (obj.x > 1000) {
            return 1;
        }
        return obj.y;
    }

    @Test
    public void testSimpleLoop() {
        testEscapeAnalysis("testSimpleLoopSnippet", Constant.forInt(1), false);
    }

    public int testSimpleLoopSnippet(int a) {
        TestObject obj = new TestObject(1, 2);
        for (int i = 0; i < a; i++) {
            notInlineable();
        }
        return obj.x;
    }

    public static class TestObject2 {

        Object o;

        public TestObject2(Object o) {
            this.o = o;
        }
    }

    @Test
    public void testCheckCast() {
        testEscapeAnalysis("testCheckCastSnippet", Constant.forObject(TestObject2.class), false);
    }

    public Object testCheckCastSnippet() {
        TestObject2 obj = new TestObject2(TestObject2.class);
        TestObject2 obj2 = new TestObject2(obj);
        return ((TestObject2) obj2.o).o;
    }

    @Test
    public void testInstanceOf() {
        ReturnNode returnNode = testEscapeAnalysis("testInstanceOfSnippet", null, false);
        ValueNode result = returnNode.result();
        Assert.assertTrue(result instanceof ConditionalNode);
        ConditionalNode conditional = (ConditionalNode) result;
        Assert.assertTrue(conditional.condition() instanceof LogicConstantNode);
        Assert.assertEquals(true, ((LogicConstantNode) conditional.condition()).getValue());
    }

    public boolean testInstanceOfSnippet() {
        TestObject2 obj = new TestObject2(TestObject2.class);
        TestObject2 obj2 = new TestObject2(obj);
        return obj2.o instanceof TestObject2;
    }

    private ReturnNode testEscapeAnalysis(String snippet, Constant expectedConstantResult, boolean iterativeEscapeAnalysis) {
        StructuredGraph graph = parse(snippet);
        try {
            for (Invoke n : graph.getInvokes()) {
                n.setInliningRelevance(1);
            }

            Assumptions assumptions = new Assumptions(false);
            new InliningPhase(runtime(), null, assumptions, null, getDefaultPhasePlan(), OptimisticOptimizations.ALL).apply(graph);
            new DeadCodeEliminationPhase().apply(graph);
            new PartialEscapeAnalysisPhase(runtime(), assumptions, iterativeEscapeAnalysis).apply(graph);
            Assert.assertEquals(1, graph.getNodes(ReturnNode.class).count());
            ReturnNode returnNode = graph.getNodes(ReturnNode.class).first();
            if (expectedConstantResult != null) {
                Assert.assertTrue(returnNode.result().toString(), returnNode.result().isConstant());
                Assert.assertEquals(expectedConstantResult, returnNode.result().asConstant());
            }
            int newInstanceCount = graph.getNodes(NewInstanceNode.class).count() + graph.getNodes(NewArrayNode.class).count() + graph.getNodes(MaterializeObjectNode.class).count();
            Assert.assertEquals(0, newInstanceCount);
            return returnNode;
        } catch (AssertionFailedError t) {
            throw new RuntimeException(t.getMessage() + "\n" + getCanonicalGraphString(graph), t);
        }
    }
}
