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
package com.oracle.max.graal.hotspot;

import com.sun.cri.ri.*;


public class HotSpotExceptionHandler extends CompilerObject implements RiExceptionHandler {
    /**
     * 
     */
    private static final long serialVersionUID = 7110038548061733686L;
    private int startBci;
    private int endBci;
    private int handlerBci;
    private int catchClassIndex;
    private RiType catchClass;

    public HotSpotExceptionHandler() {
        super(null);
    }

    @Override
    public int startBCI() {
        return startBci;
    }

    @Override
    public int endBCI() {
        return endBci;
    }

    @Override
    public int handlerBCI() {
        return handlerBci;
    }

    @Override
    public int catchTypeCPI() {
        return catchClassIndex;
    }

    @Override
    public boolean isCatchAll() {
        return catchClassIndex == 0;
    }

    @Override
    public RiType catchType() {
        return catchClass;
    }

    @Override
    public String toString() {
        return String.format("HotSpotExceptionHandler[startBci=%d, endBci=%d, handlerBci=%d, catchClassIndex=%d, catchClass=%s", startBci, endBci, handlerBci, catchClassIndex, catchClass);
    }
}
