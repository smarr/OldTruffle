/*
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package com.oracle.truffle.ruby.nodes.methods;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.ruby.nodes.*;
import com.oracle.truffle.ruby.runtime.*;

/**
 * Produce a {@link ShellResult} object from a return value and the resulting frame.
 */
public class ShellResultNode extends RubyNode {

    @Child protected RubyNode body;

    public ShellResultNode(RubyContext context, SourceSection sourceSection, RubyNode body) {
        super(context, sourceSection);
        this.body = adoptChild(body);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return new ShellResult(body.execute(frame), frame.materialize());
    }

}
