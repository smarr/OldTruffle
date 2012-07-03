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
package com.oracle.graal.jtt.optimize;

import org.junit.*;

/*
 * Tests calls to the array copy method.
 */
public class ArrayCopy04 {

    public static byte[] array = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    public static byte[] array0 = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    static {
        // Ensure arraycopy is resolved
        System.arraycopy(array, 0, array, 0, array.length);
    }

    @Before
    public void setUp() {
        System.currentTimeMillis();
        for (int i = 0; i < array.length; i++) {
            array[i] = array0[i];
        }
    }

    public static byte[] test(int srcPos, int destPos, int length) {
        System.arraycopy(array, srcPos, array, destPos, length);
        return array;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(0, 0, 0));
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run1() throws Throwable {
        test(0, 0, -1);
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run2() throws Throwable {
        test(-1, 0, 0);
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run3() throws Throwable {
        test(0, -1, 0);
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(0, 0, 2));
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run5() throws Throwable {
        test(0, 1, 11);
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run6() throws Throwable {
        test(1, 0, 11);
    }

    @Test(expected = java.lang.IndexOutOfBoundsException.class)
    public void run7() throws Throwable {
        test(1, 1, -1);
    }

    @Test
    public void run8() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 0, 1, 3, 4, 5, 6, 7, 8, 9, 10}, test(0, 1, 2));
    }

    @Test
    public void run9() throws Throwable {
        Assert.assertArrayEquals(new byte[] {1, 2, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(1, 0, 2));
    }

    @Test
    public void run10() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(1, 1, 2));
    }

    @Test
    public void run11() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(0, 0, 6));
    }

    @Test
    public void run12() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 0, 1, 2, 3, 4, 6, 7, 8, 9, 10}, test(0, 1, 5));
    }

    @Test
    public void run13() throws Throwable {
        Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 5, 6, 7, 8, 9, 10}, test(1, 0, 5));
    }

    @Test
    public void run14() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(1, 1, 5));
    }

    @Test
    public void run15() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, test(0, 0, 11));
    }

    @Test
    public void run16() throws Throwable {
        Assert.assertArrayEquals(new byte[] {0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, test(0, 1, 10));
    }

    @Test
    public void run17() throws Throwable {
        Assert.assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10}, test(1, 0, 10));
    }
}
