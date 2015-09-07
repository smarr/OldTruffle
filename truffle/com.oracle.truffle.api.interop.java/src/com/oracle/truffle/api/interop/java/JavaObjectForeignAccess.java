/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.interop.java;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.nodes.RootNode;

final class JavaObjectForeignAccess implements ForeignAccess.Factory10 {
    @Override
    public CallTarget accessIsNull() {
        return Truffle.getRuntime().createCallTarget(new NullCheckNode());
    }

    @Override
    public CallTarget accessIsExecutable() {
        return Truffle.getRuntime().createCallTarget(RootNode.createConstantNode(Boolean.FALSE));
    }

    @Override
    public CallTarget accessIsBoxed() {
        return null;
    }

    @Override
    public CallTarget accessHasSize() {
        return Truffle.getRuntime().createCallTarget(new ArrayHasSizeNode());
    }

    @Override
    public CallTarget accessGetSize() {
        return Truffle.getRuntime().createCallTarget(new ArrayGetSizeNode());
    }

    @Override
    public CallTarget accessUnbox() {
        return null;
    }

    @Override
    public CallTarget accessRead() {
        return Truffle.getRuntime().createCallTarget(new ReadFieldNode());
    }

    @Override
    public CallTarget accessWrite() {
        return Truffle.getRuntime().createCallTarget(new WriteFieldNode());
    }

    @Override
    public CallTarget accessExecute(int argumentsLength) {
        return null;
    }

    @Override
    public CallTarget accessInvoke(int argumentsLength) {
        return Truffle.getRuntime().createCallTarget(new JavaObjectMethodNode());
    }

    @Override
    public CallTarget accessNew(int argumentsLength) {
        return Truffle.getRuntime().createCallTarget(new JavaNewNode());
    }

    @Override
    public CallTarget accessMessage(Message unknown) {
        return null;
    }
}
