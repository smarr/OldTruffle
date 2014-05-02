/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.compiler.hsail.test.lambda;

import static com.oracle.graal.debug.Debug.*;

import java.util.*;

import org.junit.*;

import com.oracle.graal.compiler.hsail.test.infra.*;
import com.oracle.graal.debug.*;

/**
 * Tests calling ArrayList.get().
 */
public class ArrayListGetTest extends GraalKernelTester {

    static final int NUM = 20;
    @Result public int[] outArray = new int[NUM];
    public ArrayList<Integer> inList = new ArrayList<>();

    void setupArrays() {
        for (int i = 0; i < NUM; i++) {
            inList.add(i);
            outArray[i] = -i;
        }
    }

    @Override
    public void runTest() {
        setupArrays();

        dispatchLambdaKernel(NUM, (gid) -> {
            int val = inList.get(gid);
            outArray[gid] = val * val + 1;
        });
    }

    // NYI emitForeignCall charAlignedDisjointArraycopy
    @Test(expected = com.oracle.graal.compiler.common.GraalInternalError.class)
    @Ignore
    public void testUsingLambdaMethod() {
        try (DebugConfigScope s = disableIntercept()) {
            testGeneratedHsailUsingLambdaMethod();
        }
    }

}
