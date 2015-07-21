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
package com.oracle.truffle.tools.debug.shell;

import com.oracle.truffle.api.debug.Breakpoint;
import java.util.*;

/**
 * The server side of a simple message-based protocol for a possibly remote language
 * Read-Eval-Print-Loop.
 */
public abstract class REPLServer {

    /**
     * Starts up a server; status returned in a message.
     */
    public abstract REPLMessage start();

    /**
     * Ask the server to handle a request. Return a non-empty array of messages to simulate remote
     * operation where the protocol has possibly multiple messages being returned asynchronously in
     * response to each request.
     */
    public abstract REPLMessage[] receive(REPLMessage request);

    private int breakpointCounter;
    private Map<Breakpoint, Integer> breakpoints = new WeakHashMap<>();

    protected final synchronized void registerBreakpoint(Breakpoint breakpoint) {
        breakpoints.put(breakpoint, breakpointCounter++);
    }

    protected final synchronized Breakpoint findBreakpoint(int id) {
        for (Map.Entry<Breakpoint, Integer> entrySet : breakpoints.entrySet()) {
            if (id == entrySet.getValue()) {
                return entrySet.getKey();
            }
        }
        return null;
    }

    protected final synchronized int getBreakpointID(Breakpoint breakpoint) {
        final Integer id = breakpoints.get(breakpoint);
        return id == null ? -1 : id;
    }

}
