/*
 * Copyright (c) 2013, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.test;

import com.oracle.jvmci.code.InvalidInstalledCodeException;
import com.oracle.jvmci.meta.ResolvedJavaMethod;
import org.junit.*;

import com.oracle.graal.compiler.test.*;
import com.oracle.graal.nodes.StructuredGraph.AllowAssumptions;
import com.oracle.jvmci.hotspot.*;

public class HotSpotNmethodTest extends GraalCompilerTest {

    private static final int ITERATION_COUNT = 100000;

    @Test
    public void testInstallCodeInvalidation() {
        final ResolvedJavaMethod testJavaMethod = getResolvedJavaMethod("foo");
        final HotSpotNmethod nmethod = (HotSpotNmethod) getCode(testJavaMethod, parseEager("otherFoo", AllowAssumptions.YES));
        Assert.assertTrue(nmethod.isValid());
        Object result;
        try {
            result = nmethod.executeVarargs(null, "b", "c");
            assertDeepEquals(43, result);
        } catch (InvalidInstalledCodeException e) {
            Assert.fail("Code was invalidated");
        }
        Assert.assertTrue(nmethod.isValid());
        nmethod.invalidate();
        Assert.assertFalse(nmethod.isValid());
        try {
            result = nmethod.executeVarargs(null, null, null);
            Assert.fail("Code was not invalidated");
        } catch (InvalidInstalledCodeException e) {
        }
        Assert.assertFalse(nmethod.isValid());
    }

    @Test
    public void testInstallCodeInvalidationWhileRunning() {
        final ResolvedJavaMethod testJavaMethod = getResolvedJavaMethod("foo");
        final HotSpotNmethod nmethod = (HotSpotNmethod) getCode(testJavaMethod, parseEager("otherFoo", AllowAssumptions.YES));
        Object result;
        try {
            result = nmethod.executeVarargs(nmethod, null, null);
            assertDeepEquals(43, result);
        } catch (InvalidInstalledCodeException e) {
            Assert.fail("Code was invalidated");
        }
        Assert.assertFalse(nmethod.isValid());
    }

    @Test
    public void testInstalledCodeCalledFromCompiledCode() {
        final ResolvedJavaMethod testJavaMethod = getResolvedJavaMethod("foo");
        final HotSpotNmethod nmethod = (HotSpotNmethod) getCode(testJavaMethod, parseEager("otherFoo", AllowAssumptions.YES));
        Assert.assertTrue(nmethod.isValid());
        try {
            for (int i = 0; i < ITERATION_COUNT; ++i) {
                nmethod.executeVarargs(null, "b", "c");
            }
        } catch (InvalidInstalledCodeException e) {
            Assert.fail("Code was invalidated");
        }
    }

    @SuppressWarnings("unused")
    public static Object foo(HotSpotNmethod method, Object a2, Object a3) {
        return 42;
    }

    @SuppressWarnings("unused")
    public static Object otherFoo(HotSpotNmethod method, Object a2, Object a3) {
        if (method != null) {
            method.invalidate();
        }
        return 43;
    }
}
