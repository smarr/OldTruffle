/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.impl.*;
import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.source.*;

/**
 * A root node is a node with a method to execute it given only a frame as a parameter. Therefore, a
 * root node can be used to create a call target using
 * {@link TruffleRuntime#createCallTarget(RootNode)}.
 */
@SuppressWarnings("rawtypes")
public abstract class RootNode extends Node {
    final Class<? extends TruffleLanguage> language;
    private RootCallTarget callTarget;
    @CompilationFinal private FrameDescriptor frameDescriptor;

    /**
     * @deprecated each RootNode should be associated with a {@link TruffleLanguage} use constructor
     *             that allows you to specify it. This method will be removed on Aug 15, 2015.
     */
    @Deprecated
    protected RootNode() {
        this(null, null, null, false);
    }

    /**
     * @deprecated each RootNode should be associated with a {@link TruffleLanguage} use constructor
     *             that allows you to specify it. This method will be removed on Aug 15, 2015.
     */
    @Deprecated
    protected RootNode(SourceSection sourceSection) {
        this(null, sourceSection, null, false);
    }

    /**
     * @deprecated each RootNode should be associated with a {@link TruffleLanguage} use constructor
     *             that allows you to specify it. This method will be removed on Aug 15, 2015.
     */
    @Deprecated
    protected RootNode(SourceSection sourceSection, FrameDescriptor frameDescriptor) {
        this(null, sourceSection, frameDescriptor, false);
    }

    /**
     * Creates new root node. Each {@link RootNode} is associated with a particular language - if
     * the root node represents a method it is assumed the method is written in such language.
     *
     * @param language the language of the node, <b>cannot be</b> <code>null</code>
     * @param sourceSection a part of source associated with this node, can be <code>null</code>
     * @param frameDescriptor descriptor of slots, can be <code>null</code>
     */
    protected RootNode(Class<? extends TruffleLanguage> language, SourceSection sourceSection, FrameDescriptor frameDescriptor) {
        this(language, sourceSection, frameDescriptor, true);
    }

    private RootNode(Class<? extends TruffleLanguage> language, SourceSection sourceSection, FrameDescriptor frameDescriptor, boolean checkLanguage) {
        super(sourceSection);
        if (checkLanguage) {
            if (!TruffleLanguage.class.isAssignableFrom(language)) {
                throw new IllegalStateException();
            }
        }
        this.language = language;
        if (frameDescriptor == null) {
            this.frameDescriptor = new FrameDescriptor();
        } else {
            this.frameDescriptor = frameDescriptor;
        }
    }

    @Override
    public Node copy() {
        RootNode root = (RootNode) super.copy();
        root.frameDescriptor = frameDescriptor;
        return root;
    }

    /**
     * Returns <code>true</code> if this {@link RootNode} is allowed to be cloned. The runtime
     * system might decide to create deep copies of the {@link RootNode} in order to gather context
     * sensitive profiling feedback. The default implementation returns <code>false</code>. Guest
     * language specific implementations may want to return <code>true</code> here to indicate that
     * gathering call site specific profiling information might make sense for this {@link RootNode}
     * .
     *
     * @return <code>true</code> if cloning is allowed else <code>false</code>.
     */
    public boolean isCloningAllowed() {
        return false;
    }

    /**
     * Reports the execution count of a loop that is a child of this node. The optimization
     * heuristics can use the loop count to guide compilation and inlining.
     */
    public final void reportLoopCount(int count) {
        if (getCallTarget() instanceof LoopCountReceiver) {
            ((LoopCountReceiver) getCallTarget()).reportLoopCount(count);
        }
    }

    /**
     * Executes this function using the specified frame and returns the result value.
     *
     * @param frame the frame of the currently executing guest language method
     * @return the value of the execution
     */
    public abstract Object execute(VirtualFrame frame);

    public final RootCallTarget getCallTarget() {
        return callTarget;
    }

    public final FrameDescriptor getFrameDescriptor() {
        return frameDescriptor;
    }

    public final void setCallTarget(RootCallTarget callTarget) {
        this.callTarget = callTarget;
    }

    /**
     * Returns the {@link ExecutionContext} associated with this <code>RootNode</code>. This allows
     * the correct <code>ExecutionContext</code> to be determined for a <code>RootNode</code> (and
     * so also for a {@link RootCallTarget} and a {@link FrameInstance} obtained from the call
     * stack) without prior knowledge of the language it has come from.
     *
     * Used for instance to determine the language of a <code>RootNode<code>:
     * 
     * <pre>
     * <code>
     * rootNode.getExecutionContext().getLanguageShortName();
     * </code> </pre>
     *
     * Returns <code>null</code> by default.
     */
    public ExecutionContext getExecutionContext() {
        return null;
    }

    /**
     * Get compiler options specific to this <code>RootNode</code>.
     */
    public CompilerOptions getCompilerOptions() {
        final ExecutionContext context = getExecutionContext();

        if (context == null) {
            return DefaultCompilerOptions.INSTANCE;
        } else {
            return context.getCompilerOptions();
        }
    }

    /**
     * Apply to the AST all instances of {@link ASTProber} specified for the language, if any, held
     * by this root node. This can only be done once the AST is complete, notably once all parent
     * pointers are correctly assigned. But it also must be done before any AST cloning or
     * execution.
     * <p>
     * If this is not done, then the AST will not be subject to debugging or any other
     * instrumentation-supported tooling.
     * <p>
     * Implementations should ensure that instrumentation is never applied more than once to an AST,
     * as this is not guaranteed to be error-free.
     *
     * @see TruffleLanguage
     */
    public void applyInstrumentation() {
    }

    /**
     * Helper method to create a root node that always returns the same value. Certain operations
     * (expecially {@link com.oracle.api.truffle.api.interop inter-operability} API) require return
     * of stable {@link RootNode root nodes}. To simplify creation of such nodes, here is a factory
     * method that can create {@link RootNode} that returns always the same value.
     *
     * @param constant the constant to return
     * @return root node returning the constant
     */
    public static RootNode createConstantNode(Object constant) {
        return new Constant(constant);
    }

    private static final class Constant extends RootNode {

        private final Object value;

        public Constant(Object value) {
            super(TruffleLanguage.class, null, null);
            this.value = value;
        }

        @Override
        public Object execute(VirtualFrame vf) {
            return value;
        }
    }

}
