/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.sl.nodes.access;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.ConditionProfile;
import com.oracle.truffle.sl.SLException;
import com.oracle.truffle.sl.nodes.SLExpressionNode;
import com.oracle.truffle.sl.nodes.interop.SLForeignToSLTypeNode;
import com.oracle.truffle.sl.nodes.interop.SLForeignToSLTypeNodeGen;
import com.oracle.truffle.sl.runtime.SLContext;

/**
 * The node for accessing a property of an object. When executed, this node first evaluates the
 * object expression on the left side of the dot operator and then reads the named property.
 */
@NodeInfo(shortName = ".")
@NodeChild(value = "receiver", type = SLExpressionNode.class)
public abstract class SLReadPropertyNode extends SLExpressionNode {

    @Child private SLReadPropertyCacheNode cacheNode;
    private final String propertyName;
    private final ConditionProfile receiverTypeCondition = ConditionProfile.createBinaryProfile();

    public SLReadPropertyNode(SourceSection src, String propertyName) {
        super(src);
        this.propertyName = propertyName;
        this.cacheNode = SLReadPropertyCacheNode.create(propertyName);
    }

    @Specialization(guards = "isSLObject(object)")
    public Object doSLObject(DynamicObject object) {
        if (receiverTypeCondition.profile(SLContext.isSLObject(object))) {
            return cacheNode.executeObject(SLContext.castSLObject(object));
        } else {
            CompilerDirectives.transferToInterpreter();
            throw new SLException("unexpected receiver type");
        }
    }

    @Child private Node foreignRead;
    @Child private SLForeignToSLTypeNode toSLType;

    @Specialization
    public Object doForeignObject(VirtualFrame frame, TruffleObject object) {
        if (foreignRead == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            this.foreignRead = insert(Message.READ.createNode());
            this.toSLType = insert(SLForeignToSLTypeNodeGen.create(getSourceSection(), null));
        }
        Object result = ForeignAccess.execute(foreignRead, frame, object, new Object[]{propertyName});
        Object slValue = toSLType.executeWithTarget(frame, result);
        return slValue;
    }
}
