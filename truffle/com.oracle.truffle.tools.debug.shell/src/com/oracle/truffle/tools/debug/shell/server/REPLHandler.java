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
package com.oracle.truffle.tools.debug.shell.server;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.instrument.ASTPrinter;
import com.oracle.truffle.api.instrument.KillException;
import com.oracle.truffle.api.instrument.QuitException;
import com.oracle.truffle.api.instrument.StandardSyntaxTag;
import com.oracle.truffle.api.instrument.Visualizer;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.tools.debug.shell.REPLMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side REPL implementation of an {@linkplain REPLMessage "op"}.
 * <p>
 * The language-agnostic handlers are implemented here.
 */
public abstract class REPLHandler {

    // TODO (mlvdv) add support for setting/using ignore count
    private static final int DEFAULT_IGNORE_COUNT = 0;

    private final String op;

    protected REPLHandler(String op) {
        this.op = op;
    }

    /**
     * Gets the "op" implemented by this handler.
     */
    public final String getOp() {
        return op;
    }

    /**
     * Passes a request to this handler.
     */
    public abstract REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext);

    /**
     * Creates skeleton for a reply message that identifies the operation currently being handled.
     */
    protected final REPLMessage createReply() {
        return new REPLMessage(REPLMessage.OP, op);
    }

    /**
     * Creates skeleton for a reply message that identifies a specified operation.
     */
    protected static final REPLMessage createReply(String opString) {
        return new REPLMessage(REPLMessage.OP, opString);
    }

    /**
     * Completes a reply, reporting and explaining successful handling.
     */
    protected static final REPLMessage[] finishReplySucceeded(REPLMessage reply, String explanation) {
        reply.put(REPLMessage.STATUS, REPLMessage.SUCCEEDED);
        reply.put(REPLMessage.DISPLAY_MSG, explanation);
        final REPLMessage[] replies = new REPLMessage[]{reply};
        return replies;
    }

    /**
     * Completes a reply, reporting and explaining failed handling.
     */
    protected static final REPLMessage[] finishReplyFailed(REPLMessage reply, String explanation) {
        reply.put(REPLMessage.STATUS, REPLMessage.FAILED);
        reply.put(REPLMessage.DISPLAY_MSG, explanation);
        final REPLMessage[] replies = new REPLMessage[]{reply};
        return replies;
    }

    protected static final REPLMessage[] finishReplyFailed(REPLMessage reply, Exception ex) {
        reply.put(REPLMessage.STATUS, REPLMessage.FAILED);
        String message = ex.getMessage();
        reply.put(REPLMessage.DISPLAY_MSG, message == null ? ex.getClass().getSimpleName() : message);
        final REPLMessage[] replies = new REPLMessage[]{reply};
        return replies;
    }

    static final REPLMessage createBreakpointInfoMessage(Breakpoint breakpoint, REPLServerContext serverContext) {
        final REPLMessage infoMessage = new REPLMessage(REPLMessage.OP, REPLMessage.BREAKPOINT_INFO);
        infoMessage.put(REPLMessage.BREAKPOINT_ID, Integer.toString(serverContext.getBreakpointID(breakpoint)));
        infoMessage.put(REPLMessage.BREAKPOINT_STATE, breakpoint.getState().getName());
        infoMessage.put(REPLMessage.BREAKPOINT_HIT_COUNT, Integer.toString(breakpoint.getHitCount()));
        infoMessage.put(REPLMessage.BREAKPOINT_IGNORE_COUNT, Integer.toString(breakpoint.getIgnoreCount()));
        infoMessage.put(REPLMessage.INFO_VALUE, breakpoint.getLocationDescription().toString());
        if (breakpoint.getCondition() != null) {
            infoMessage.put(REPLMessage.BREAKPOINT_CONDITION, breakpoint.getCondition().getCode());
        }
        infoMessage.put(REPLMessage.STATUS, REPLMessage.SUCCEEDED);
        return infoMessage;
    }

    protected static REPLMessage createFrameInfoMessage(final REPLServerContext serverContext, FrameDebugDescription frame) {
        final Visualizer visualizer = serverContext.getVisualizer();
        final REPLMessage infoMessage = new REPLMessage(REPLMessage.OP, REPLMessage.FRAME_INFO);
        infoMessage.put(REPLMessage.FRAME_NUMBER, Integer.toString(frame.index()));
        final Node node = frame.node();

        infoMessage.put(REPLMessage.SOURCE_LOCATION, visualizer.displaySourceLocation(node));
        infoMessage.put(REPLMessage.METHOD_NAME, visualizer.displayMethodName(node));

        if (node != null) {
            SourceSection section = node.getSourceSection();
            if (section == null) {
                section = node.getEncapsulatingSourceSection();
            }
            if (section != null) {
                infoMessage.put(REPLMessage.FILE_PATH, section.getSource().getPath());
                infoMessage.put(REPLMessage.LINE_NUMBER, Integer.toString(section.getStartLine()));
                infoMessage.put(REPLMessage.SOURCE_LINE_TEXT, section.getSource().getCode(section.getStartLine()));
            }
        }
        infoMessage.put(REPLMessage.STATUS, REPLMessage.SUCCEEDED);
        return infoMessage;
    }

    public static final REPLHandler BACKTRACE_HANDLER = new REPLHandler(REPLMessage.BACKTRACE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final ArrayList<REPLMessage> frameMessages = new ArrayList<>();
            for (FrameDebugDescription frame : serverContext.getStack()) {
                frameMessages.add(createFrameInfoMessage(serverContext, frame));
            }
            if (frameMessages.size() > 0) {
                return frameMessages.toArray(new REPLMessage[0]);
            }
            return finishReplyFailed(reply, "No stack");
        }
    };

    public static final REPLHandler BREAK_AT_LINE_HANDLER = new REPLHandler(REPLMessage.BREAK_AT_LINE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final String path = request.get(REPLMessage.FILE_PATH);
            final String fileName = request.get(REPLMessage.SOURCE_NAME);
            final String lookupFile = (path == null || path.isEmpty()) ? fileName : path;
            Source source = null;
            try {
                source = Source.fromFileName(lookupFile, true);
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
            if (source == null) {
                return finishReplyFailed(reply, fileName + " not found");
            }
            Integer lineNumber = request.getIntValue(REPLMessage.LINE_NUMBER);
            if (lineNumber == null) {
                return finishReplyFailed(reply, "missing line number");
            }
            Integer ignoreCount = request.getIntValue(REPLMessage.BREAKPOINT_IGNORE_COUNT);
            if (ignoreCount == null) {
                ignoreCount = 0;
            }
            final Debugger db = serverContext.db();
            if (db == null) {
                return finishReplyFailed(reply, "debugger not initialized");
            }
            Breakpoint breakpoint;
            try {
                breakpoint = db.setLineBreakpoint(DEFAULT_IGNORE_COUNT, source.createLineLocation(lineNumber), false);
                serverContext.registerBreakpoint(breakpoint);
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
            reply.put(REPLMessage.SOURCE_NAME, fileName);
            reply.put(REPLMessage.FILE_PATH, source.getPath());
            reply.put(REPLMessage.BREAKPOINT_ID, Integer.toString(serverContext.getBreakpointID(breakpoint)));
            reply.put(REPLMessage.LINE_NUMBER, Integer.toString(lineNumber));
            reply.put(REPLMessage.BREAKPOINT_IGNORE_COUNT, ignoreCount.toString());
            return finishReplySucceeded(reply, "Breakpoint set");
        }
    };

    public static final REPLHandler BREAK_AT_LINE_ONCE_HANDLER = new REPLHandler(REPLMessage.BREAK_AT_LINE_ONCE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final String path = request.get(REPLMessage.FILE_PATH);
            final String fileName = request.get(REPLMessage.SOURCE_NAME);
            final String lookupFile = (path == null || path.isEmpty()) ? fileName : path;
            Source source = null;
            try {
                source = Source.fromFileName(lookupFile, true);
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
            if (source == null) {
                return finishReplyFailed(reply, fileName + " not found");
            }
            Integer lineNumber = request.getIntValue(REPLMessage.LINE_NUMBER);
            if (lineNumber == null) {
                return finishReplyFailed(reply, "missing line number");
            }
            try {
                Breakpoint b = serverContext.db().setLineBreakpoint(DEFAULT_IGNORE_COUNT, source.createLineLocation(lineNumber), true);
                serverContext.registerBreakpoint(b);
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
            reply.put(REPLMessage.SOURCE_NAME, fileName);
            reply.put(REPLMessage.FILE_PATH, source.getPath());
            reply.put(REPLMessage.LINE_NUMBER, Integer.toString(lineNumber));
            return finishReplySucceeded(reply, "One-shot line breakpoint set");
        }
    };

    public static final REPLHandler BREAK_AT_THROW_HANDLER = new REPLHandler(REPLMessage.BREAK_AT_THROW) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            try {
                Breakpoint b = serverContext.db().setTagBreakpoint(DEFAULT_IGNORE_COUNT, StandardSyntaxTag.THROW, false);
                serverContext.registerBreakpoint(b);
                return finishReplySucceeded(reply, "Breakpoint at any throw set");
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
        }
    };

    public static final REPLHandler BREAK_AT_THROW_ONCE_HANDLER = new REPLHandler(REPLMessage.BREAK_AT_THROW_ONCE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            try {
                serverContext.db().setTagBreakpoint(DEFAULT_IGNORE_COUNT, StandardSyntaxTag.THROW, true);
                return finishReplySucceeded(reply, "One-shot breakpoint at any throw set");
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
        }
    };

    public static final REPLHandler BREAKPOINT_INFO_HANDLER = new REPLHandler(REPLMessage.BREAKPOINT_INFO) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final ArrayList<REPLMessage> infoMessages = new ArrayList<>();
            for (Breakpoint breakpoint : serverContext.db().getBreakpoints()) {
                infoMessages.add(createBreakpointInfoMessage(breakpoint, serverContext));
            }
            if (infoMessages.size() > 0) {
                return infoMessages.toArray(new REPLMessage[0]);
            }
            return finishReplyFailed(reply, "No breakpoints");
        }
    };

    public static final REPLHandler CLEAR_BREAK_HANDLER = new REPLHandler(REPLMessage.CLEAR_BREAK) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            Integer breakpointNumber = request.getIntValue(REPLMessage.BREAKPOINT_ID);
            if (breakpointNumber == null) {
                return finishReplyFailed(reply, "missing breakpoint number");
            }
            final Breakpoint breakpoint = serverContext.findBreakpoint(breakpointNumber);
            if (breakpoint == null) {
                return finishReplyFailed(reply, "no breakpoint number " + breakpointNumber);
            }
            breakpoint.dispose();
            reply.put(REPLMessage.BREAKPOINT_ID, Integer.toString(breakpointNumber));
            return finishReplySucceeded(reply, "Breakpoint " + breakpointNumber + " cleared");
        }
    };

    public static final REPLHandler CONTINUE_HANDLER = new REPLHandler(REPLMessage.CONTINUE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            serverContext.prepareContinue();
            return finishReplySucceeded(reply, "Continue mode entered");
        }
    };

    public static final REPLHandler DELETE_HANDLER = new REPLHandler(REPLMessage.DELETE_BREAK) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            int deleteCount = 0;
            for (Breakpoint breakpoint : serverContext.db().getBreakpoints()) {
                breakpoint.dispose();
                deleteCount++;
            }
            if (deleteCount == 0) {
                return finishReplyFailed(reply, "no breakpoints to delete");
            }
            return finishReplySucceeded(reply, Integer.toString(deleteCount) + " breakpoints deleted");
        }
    };

    public static final REPLHandler DISABLE_BREAK_HANDLER = new REPLHandler(REPLMessage.DISABLE_BREAK) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            Integer breakpointNumber = request.getIntValue(REPLMessage.BREAKPOINT_ID);
            if (breakpointNumber == null) {
                return finishReplyFailed(reply, "missing breakpoint number");
            }
            final Breakpoint breakpoint = serverContext.findBreakpoint(breakpointNumber);
            if (breakpoint == null) {
                return finishReplyFailed(reply, "no breakpoint number " + breakpointNumber);
            }
            breakpoint.setEnabled(false);
            reply.put(REPLMessage.BREAKPOINT_ID, Integer.toString(breakpointNumber));
            return finishReplySucceeded(reply, "Breakpoint " + breakpointNumber + " disabled");
        }
    };

    public static final REPLHandler ENABLE_BREAK_HANDLER = new REPLHandler(REPLMessage.ENABLE_BREAK) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            Integer breakpointNumber = request.getIntValue(REPLMessage.BREAKPOINT_ID);
            if (breakpointNumber == null) {
                return finishReplyFailed(reply, "missing breakpoint number");
            }
            final Breakpoint breakpoint = serverContext.findBreakpoint(breakpointNumber);
            if (breakpoint == null) {
                return finishReplyFailed(reply, "no breakpoint number " + breakpointNumber);
            }
            breakpoint.setEnabled(true);
            reply.put(REPLMessage.BREAKPOINT_ID, Integer.toString(breakpointNumber));
            return finishReplySucceeded(reply, "Breakpoint " + breakpointNumber + " enabled");
        }
    };

    public static final REPLHandler FILE_HANDLER = new REPLHandler(REPLMessage.FILE) {
        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final String fileName = request.get(REPLMessage.SOURCE_NAME);
            if (fileName == null) {
                return finishReplyFailed(reply, "no file specified");
            }
            reply.put(REPLMessage.SOURCE_NAME, fileName);
            try {
                Source source = Source.fromFileName(fileName);
                if (source == null) {
                    return finishReplyFailed(reply, "file \"" + fileName + "\" not found");
                } else {
                    reply.put(REPLMessage.FILE_PATH, source.getPath());
                    reply.put(REPLMessage.CODE, source.getCode());
                    return finishReplySucceeded(reply, "file found");
                }
            } catch (IOException ex) {
                return finishReplyFailed(reply, "can't read file \"" + fileName + "\"");
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
        }
    };

    // TODO (mlvdv) deal with slot locals explicitly
    /**
     * Returns a general description of the frame, plus a textual summary of the slot values: one
     * per line.
     */
    public static final REPLHandler FRAME_HANDLER = new REPLHandler(REPLMessage.FRAME) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final Integer frameNumber = request.getIntValue(REPLMessage.FRAME_NUMBER);
            if (frameNumber == null) {
                return finishReplyFailed(reply, "no frame number specified");
            }
            final List<FrameDebugDescription> stack = serverContext.getStack();
            if (frameNumber < 0 || frameNumber >= stack.size()) {
                return finishReplyFailed(reply, "frame number " + frameNumber + " out of range");
            }
            final FrameDebugDescription frameDescription = stack.get(frameNumber);
            final REPLMessage frameMessage = createFrameInfoMessage(serverContext, frameDescription);
            final Frame frame = frameDescription.frameInstance().getFrame(FrameInstance.FrameAccess.READ_ONLY, true);
            final Visualizer visualizer = serverContext.getVisualizer();
            final FrameDescriptor frameDescriptor = frame.getFrameDescriptor();
            try {
                final StringBuilder sb = new StringBuilder();
                for (FrameSlot slot : frameDescriptor.getSlots()) {
                    sb.append(Integer.toString(slot.getIndex()) + ": " + visualizer.displayIdentifier(slot) + " = ");
                    try {
                        final Object value = frame.getValue(slot);
                        sb.append(visualizer.displayValue(value, 0));
                    } catch (Exception ex) {
                        sb.append("???");
                    }
                    sb.append("\n");
                }
                return finishReplySucceeded(frameMessage, sb.toString());
            } catch (Exception ex) {
                return finishReplyFailed(frameMessage, ex);
            }
        }
    };

    public static final REPLHandler KILL_HANDLER = new REPLHandler(REPLMessage.KILL) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            if (serverContext.getLevel() == 0) {
                return finishReplyFailed(createReply(), "nothing to kill");
            }
            throw new KillException();
        }
    };

    public static final REPLHandler QUIT_HANDLER = new REPLHandler(REPLMessage.QUIT) {
        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            throw new QuitException();
        }
    };

    public static final REPLHandler SET_BREAK_CONDITION_HANDLER = new REPLHandler(REPLMessage.SET_BREAK_CONDITION) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage message = new REPLMessage(REPLMessage.OP, REPLMessage.SET_BREAK_CONDITION);
            Integer breakpointNumber = request.getIntValue(REPLMessage.BREAKPOINT_ID);
            if (breakpointNumber == null) {
                return finishReplyFailed(message, "missing breakpoint number");
            }
            message.put(REPLMessage.BREAKPOINT_ID, Integer.toString(breakpointNumber));
            final Breakpoint breakpoint = serverContext.findBreakpoint(breakpointNumber);
            if (breakpoint == null) {
                return finishReplyFailed(message, "no breakpoint number " + breakpointNumber);
            }
            final String expr = request.get(REPLMessage.BREAKPOINT_CONDITION);
            if (expr == null || expr.isEmpty()) {
                return finishReplyFailed(message, "missing condition for " + breakpointNumber);
            }
            try {
                breakpoint.setCondition(expr);
            } catch (IOException ex) {
                return finishReplyFailed(message, "invalid condition for " + breakpointNumber);
            } catch (UnsupportedOperationException ex) {
                return finishReplyFailed(message, "conditions not unsupported by breakpoint " + breakpointNumber);
            } catch (Exception ex) {
                return finishReplyFailed(message, ex);
            }
            message.put(REPLMessage.BREAKPOINT_CONDITION, expr);
            return finishReplySucceeded(message, "Breakpoint " + breakpointNumber + " condition=\"" + expr + "\"");
        }
    };

    public static final REPLHandler STEP_INTO_HANDLER = new REPLHandler(REPLMessage.STEP_INTO) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            Integer repeat = request.getIntValue(REPLMessage.REPEAT);
            if (repeat == null) {
                repeat = 1;
            }
            serverContext.prepareStepInto(repeat);
            return finishReplySucceeded(reply, "StepInto <" + repeat + "> enabled");
        }
    };

    public static final REPLHandler STEP_OUT_HANDLER = new REPLHandler(REPLMessage.STEP_OUT) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            serverContext.prepareStepOut();
            return finishReplySucceeded(createReply(), "StepOut enabled");
        }
    };

    public static final REPLHandler STEP_OVER_HANDLER = new REPLHandler(REPLMessage.STEP_OVER) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext debugServerContextFrame) {
            final REPLMessage reply = createReply();
            Integer repeat = request.getIntValue(REPLMessage.REPEAT);
            if (repeat == null) {
                repeat = 1;
            }
            debugServerContextFrame.prepareStepOver(repeat);
            return finishReplySucceeded(reply, "StepOver <" + repeat + "> enabled");
        }
    };

    public static final REPLHandler TRUFFLE_HANDLER = new REPLHandler(REPLMessage.TRUFFLE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final ASTPrinter astPrinter = serverContext.getVisualizer().getASTPrinter();
            final String topic = request.get(REPLMessage.TOPIC);
            reply.put(REPLMessage.TOPIC, topic);
            Node node = serverContext.getNodeAtHalt();
            if (node == null) {
                return finishReplyFailed(reply, "no current AST node");
            }
            final Integer depth = request.getIntValue(REPLMessage.AST_DEPTH);
            if (depth == null) {
                return finishReplyFailed(reply, "missing AST depth");
            }
            try {
                switch (topic) {
                    case REPLMessage.AST:
                        while (node.getParent() != null) {
                            node = node.getParent();
                        }
                        final String astText = astPrinter.printTreeToString(node, depth, serverContext.getNodeAtHalt());
                        return finishReplySucceeded(reply, astText);
                    case REPLMessage.SUBTREE:
                    case REPLMessage.SUB:
                        final String subTreeText = astPrinter.printTreeToString(node, depth);
                        return finishReplySucceeded(reply, subTreeText);
                    default:
                        return finishReplyFailed(reply, "Unknown \"" + REPLMessage.TRUFFLE.toString() + "\" topic");
                }

            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
        }
    };

    public static final REPLHandler UNSET_BREAK_CONDITION_HANDLER = new REPLHandler(REPLMessage.UNSET_BREAK_CONDITION) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage message = new REPLMessage(REPLMessage.OP, REPLMessage.UNSET_BREAK_CONDITION);
            Integer breakpointNumber = request.getIntValue(REPLMessage.BREAKPOINT_ID);
            if (breakpointNumber == null) {
                return finishReplyFailed(message, "missing breakpoint number");
            }
            message.put(REPLMessage.BREAKPOINT_ID, Integer.toString(breakpointNumber));
            final Breakpoint breakpoint = serverContext.findBreakpoint(breakpointNumber);
            if (breakpoint == null) {
                return finishReplyFailed(message, "no breakpoint number " + breakpointNumber);
            }
            try {
                breakpoint.setCondition(null);
            } catch (Exception ex) {
                return finishReplyFailed(message, ex);
            }
            return finishReplySucceeded(message, "Breakpoint " + breakpointNumber + " condition cleared");
        }
    };

    public static final REPLHandler TRUFFLE_NODE_HANDLER = new REPLHandler(REPLMessage.TRUFFLE_NODE) {

        @Override
        public REPLMessage[] receive(REPLMessage request, REPLServerContext serverContext) {
            final REPLMessage reply = createReply();
            final ASTPrinter astPrinter = serverContext.getVisualizer().getASTPrinter();
            final Node node = serverContext.getNodeAtHalt();
            if (node == null) {
                return finishReplyFailed(reply, "no current AST node");
            }

            try {
                final StringBuilder sb = new StringBuilder();
                sb.append(astPrinter.printNodeWithInstrumentation(node));

                final SourceSection sourceSection = node.getSourceSection();
                if (sourceSection != null) {
                    final String code = sourceSection.getCode();
                    sb.append(" \"");
                    sb.append(code.substring(0, Math.min(code.length(), 15)));
                    sb.append("...\"");
                }
                return finishReplySucceeded(reply, sb.toString());
            } catch (Exception ex) {
                return finishReplyFailed(reply, ex);
            }
        }
    };
}
