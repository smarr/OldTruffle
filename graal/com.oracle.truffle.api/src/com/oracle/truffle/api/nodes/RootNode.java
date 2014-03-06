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

import java.util.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;

/**
 * A root node is a node with a method to execute it given only a frame as a parameter. Therefore, a
 * root node can be used to create a call target using
 * {@link TruffleRuntime#createCallTarget(RootNode)}.
 */
public abstract class RootNode extends Node {

    private CallTarget callTarget;
    private final FrameDescriptor frameDescriptor;

    /*
     * Internal set to keep back-references to the call-sites.
     */
    private final Set<CallNode> cachedCallNodes = Collections.newSetFromMap(new WeakHashMap<CallNode, Boolean>());

    protected RootNode() {
        this(null, null);
    }

    protected RootNode(SourceSection sourceSection) {
        this(sourceSection, null);
    }

    protected RootNode(SourceSection sourceSection, FrameDescriptor frameDescriptor) {
        super(sourceSection);
        if (frameDescriptor == null) {
            this.frameDescriptor = new FrameDescriptor();
        } else {
            this.frameDescriptor = frameDescriptor;
        }
    }

    /**
     * @deprecated Not required anymore. Do not use.
     */
    @Deprecated
    public RootNode inline() {
        if (!isInlinable()) {
            throw new UnsupportedOperationException("Inlining is not enabled.");
        }
        return split();
    }

    /**
     * @deprecated Not required anymore. Do not use.
     */
    @Deprecated
    public int getInlineNodeCount() {
        return 0;
    }

    /**
     * @deprecated Not required anymore. Do not use.
     */
    @Deprecated
    public boolean isInlinable() {
        return true;
    }

    public RootNode split() {
        return NodeUtil.cloneNode(this);
    }

    public boolean isSplittable() {
        return false;
    }

    /**
     * Reports the execution count of a loop that is a child of this node. The optimization
     * heuristics can use the loop count to guide compilation and inlining.
     */
    public void reportLoopCount(int count) {
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

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public final FrameDescriptor getFrameDescriptor() {
        return frameDescriptor;
    }

    public final void setCallTarget(CallTarget callTarget) {
        this.callTarget = callTarget;
    }

    /* Internal API. Do not use. */
    void addCachedCallNode(CallNode callSite) {
        this.cachedCallNodes.add(callSite);
    }

    /* Internal API. Do not use. */
    void removeCachedCallNode(CallNode callSite) {
        this.cachedCallNodes.remove(callSite);
    }

    /**
     * Returns a {@link Set} of {@link CallNode} nodes which are created to invoke this RootNode.
     * This method does not make any guarantees to contain all the {@link CallNode} nodes that are
     * invoking this method. Due to its weak nature the elements returned by this method may change
     * with each consecutive call.
     * 
     * @return a set of {@link CallNode} nodes
     */
    public final Set<CallNode> getCachedCallNodes() {
        return Collections.unmodifiableSet(cachedCallNodes);
    }

    /**
     * @deprecated use {@link #getCachedCallNodes()} instead.
     */
    @Deprecated
    public final CallNode getParentInlinedCall() {
        return cachedCallNodes.isEmpty() ? null : cachedCallNodes.iterator().next();
    }
}
