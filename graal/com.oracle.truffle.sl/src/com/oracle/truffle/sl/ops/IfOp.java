/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl.ops;

import java.math.*;

import com.oracle.truffle.api.codegen.*;
import com.oracle.truffle.sl.types.*;

@SuppressWarnings("unused")
@Operation(typeSystem = Types.class, values = {"condition"}, shortCircuitValues = {"ifPart", "elsePart"})
public class IfOp {

    @ShortCircuit("ifPart")
    public boolean needsIfPart(boolean condition) {
        return condition;
    }

    @ShortCircuit("ifPart")
    public boolean needsIfPart(Object condition) {
        if (TypesGen.TYPES.isBoolean(condition)) {
            return TypesGen.TYPES.asBoolean(condition);
        }
        throw new RuntimeException("operation not defined for type " + condition.getClass().getSimpleName());
    }

    @ShortCircuit("elsePart")
    public boolean needsElsePart(Object condition, boolean hasIfPart, Object ifPart) {
        return !hasIfPart;
    }

    @ShortCircuit("elsePart")
    public boolean needsElsePart(boolean condition, boolean hasIfPart, int ifPart) {
        return !hasIfPart;
    }

    @ShortCircuit("elsePart")
    public boolean needsElsePart(boolean condition, boolean hasIfPart, BigInteger ifPart) {
        return !hasIfPart;
    }

    @Specialization
    public int doInteger(boolean condition, boolean hasIfPart, int ifPart, boolean hasElsePart, int elsePart) {
        return hasIfPart ? ifPart : elsePart;
    }

    @Specialization
    public BigInteger doBigInteger(boolean condition, boolean hasIfPart, BigInteger ifPart, boolean hasElsePart, BigInteger elsePart) {
        return hasIfPart ? ifPart : elsePart;
    }

    @Generic
    public Object doGeneric(Object condition, boolean hasIfPart, Object ifPart, boolean hasElsePart, Object elsePart) {
        return hasIfPart ? ifPart : elsePart;
    }
}
