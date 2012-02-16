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
 */
package com.oracle.max.graal.jtt.lang;

import org.junit.*;

@SuppressWarnings("static-method")
public final class Long_lessEqual02 {

    public static boolean test(long i) {
        if (i <= 5L) {
            return true;
        }
        return false;
    }

    @Test
    public void run0() throws Throwable {
        Assert.assertEquals(true, test(-9223372036854775808L));
    }

    @Test
    public void run1() throws Throwable {
        Assert.assertEquals(true, test(-2L));
    }

    @Test
    public void run2() throws Throwable {
        Assert.assertEquals(true, test(-1L));
    }

    @Test
    public void run3() throws Throwable {
        Assert.assertEquals(true, test(0L));
    }

    @Test
    public void run4() throws Throwable {
        Assert.assertEquals(true, test(4L));
    }

    @Test
    public void run5() throws Throwable {
        Assert.assertEquals(true, test(5L));
    }

    @Test
    public void run6() throws Throwable {
        Assert.assertEquals(false, test(6L));
    }

    @Test
    public void run7() throws Throwable {
        Assert.assertEquals(false, test(9223372036854775807L));
    }

}
