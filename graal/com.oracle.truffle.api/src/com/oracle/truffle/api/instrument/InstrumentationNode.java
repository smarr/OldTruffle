/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.instrument;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

/**
 * A marker interface for Truffle {@linkplain Node nodes} that internally implement the
 * <em>Instrumentation Framework</em>. Such nodes should not be part of any Guest Language execution
 * semantics, and should in general not be visible to ordinary Instrumentation clients.
 */
public interface InstrumentationNode {

    /**
     * A short description of the particular role played by the node, intended to support debugging.
     */
    String instrumentationInfo();

    /**
     * Events that propagate through the {@linkplain InstrumentationNode implementation nodes} of
     * the Instrumentation Framework, not visible in this form to Instrumentation clients.
     */
    interface TruffleEvents {

        /**
         * An AST node's execute method is about to be called.
         */
        void enter(Node node, VirtualFrame vFrame);

        /**
         * An AST Node's {@code void}-valued execute method has just returned.
         */
        void returnVoid(Node node, VirtualFrame vFrame);

        /**
         * An AST Node's execute method has just returned a value (boxed if primitive).
         */
        void returnValue(Node node, VirtualFrame vFrame, Object result);

        /**
         * An AST Node's execute method has just thrown an exception.
         */
        void returnExceptional(Node node, VirtualFrame vFrame, Exception exception);
    }
}
