/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 */
package com.oracle.max.graal.jtt.bytecode;

import org.junit.*;

/*
 */
public class BC_iadd2 {

    public static int test(byte a, byte b) {
        return a + b;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(3, test(((byte) 1), ((byte) 2)));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(-1, test(((byte) 0), ((byte) -1)));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(100, test(((byte) 33), ((byte) 67)));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(0, test(((byte) 1), ((byte) -1)));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(-127, test(((byte) -128), ((byte) 1)));
    }

    @Test
    public void run5() throws Throwable {
        Assert.assertEquals(128, test(((byte) 127), ((byte) 1)));
    }

}
