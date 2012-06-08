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
package com.oracle.graal.compiler.types;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.spi.types.*;


public class NegateScalarTypeFeedback implements ScalarTypeFeedbackTool {

    private final ScalarTypeFeedbackTool delegate;

    public NegateScalarTypeFeedback(ScalarTypeFeedbackTool delegate) {
        this.delegate = delegate;
    }

    @Override
    public void constantBound(Condition condition, RiConstant constant) {
        delegate.constantBound(condition.negate(), constant);
    }

    @Override
    public void valueBound(Condition condition, ValueNode otherValue, ScalarTypeQuery type) {
        delegate.valueBound(condition.negate(), otherValue, type);
    }

    @Override
    public void setTranslated(RiConstant delta, ScalarTypeQuery old) {
        throw new UnsupportedOperationException();
    }
}
