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
package com.oracle.truffle.api.debug;

import static com.oracle.truffle.api.debug.Breakpoint.State.DISABLED;
import static com.oracle.truffle.api.debug.Breakpoint.State.DISPOSED;
import static com.oracle.truffle.api.debug.Breakpoint.State.ENABLED;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.debug.Debugger.BreakpointCallback;
import com.oracle.truffle.api.debug.Debugger.WarningLog;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrument.AdvancedInstrumentResultListener;
import com.oracle.truffle.api.instrument.ProbeInstrument;
import com.oracle.truffle.api.instrument.Instrumenter;
import com.oracle.truffle.api.instrument.Probe;
import com.oracle.truffle.api.instrument.SyntaxTag;
import com.oracle.truffle.api.instrument.SyntaxTagTrap;
import com.oracle.truffle.api.instrument.impl.DefaultProbeListener;
import com.oracle.truffle.api.instrument.impl.DefaultStandardInstrumentListener;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.utilities.CyclicAssumption;

// TODO (mlvdv) some common functionality could be factored out of this and LineBreakpointSupport

/**
 * Support class for creating and managing "Tag Breakpoints". A Tag Breakpoint halts execution just
 * before reaching any node whose Probe carries a specified {@linkplain SyntaxTag Tag}.
 * <p>
 * The {@linkplain Instrumenter#setBeforeTagTrap(SyntaxTagTrap) Tag Trap}, which is built directly
 * into the Instrumentation Framework, does the same thing more efficiently, but there may only be
 * one Tag Trap active at a time. Any number of tag breakpoints may coexist with the Tag Trap, but
 * it would be confusing to have a Tag Breakpoint set for the same Tag as the current Tag Trap.
 * <p>
 * Notes:
 * <ol>
 * <li>Only one Tag Breakpoint can be active for a specific {@linkplain SyntaxTag Tag}.</li>
 * <li>A newly created breakpoint looks for probes matching the tag, attaches to them if found by
 * installing an {@link ProbeInstrument}.</li>
 * <li>When Truffle "splits" or otherwise copies an AST, any attached {@link ProbeInstrument} will be
 * copied along with the rest of the AST and will call back to the same breakpoint.</li>
 * <li>When notification is received that the breakpoint's Tag has been newly added to a Node, then
 * the breakpoint will attach a new Instrument at the probe to activate the breakpoint at that
 * location.</li>
 * <li>A breakpoint may have multiple Instruments deployed, one attached to each Probe that holds
 * the breakpoint's tag; this might happen when a source is reloaded.</li>
 * </ol>
 */
final class TagBreakpointFactory {

    private static final boolean TRACE = false;
    private static final PrintStream OUT = System.out;

    private static final String BREAKPOINT_NAME = "TAG BREAKPOINT";

    @TruffleBoundary
    private static void trace(String format, Object... args) {
        if (TRACE) {
            OUT.println(String.format("%s: %s", BREAKPOINT_NAME, String.format(format, args)));
        }
    }

    private static final Comparator<Entry<SyntaxTag, TagBreakpointImpl>> BREAKPOINT_COMPARATOR = new Comparator<Entry<SyntaxTag, TagBreakpointImpl>>() {

        @Override
        public int compare(Entry<SyntaxTag, TagBreakpointImpl> entry1, Entry<SyntaxTag, TagBreakpointImpl> entry2) {
            return entry1.getKey().name().compareTo(entry2.getKey().name());
        }
    };

    private final Debugger debugger;
    private final BreakpointCallback breakpointCallback;
    private final WarningLog warningLog;

    /**
     * Map: Tags ==> Tag Breakpoints. There may be no more than one breakpoint per Tag.
     */
    private final Map<SyntaxTag, TagBreakpointImpl> tagToBreakpoint = new HashMap<>();

    /**
     * Globally suspends all line breakpoint activity when {@code false}, ignoring whether
     * individual breakpoints are enabled.
     */
    @CompilationFinal private boolean breakpointsActive = true;
    private final CyclicAssumption breakpointsActiveUnchanged = new CyclicAssumption(BREAKPOINT_NAME + " globally active");

    TagBreakpointFactory(Debugger debugger, BreakpointCallback breakpointCallback, final WarningLog warningLog) {
        this.debugger = debugger;
        this.breakpointCallback = breakpointCallback;
        this.warningLog = warningLog;

        debugger.getInstrumenter().addProbeListener(new DefaultProbeListener() {

            @Override
            public void probeTaggedAs(Probe probe, SyntaxTag tag, Object tagValue) {
                final TagBreakpointImpl breakpoint = tagToBreakpoint.get(tag);
                if (breakpoint != null) {
                    try {
                        breakpoint.attach(probe);
                    } catch (IOException e) {
                        warningLog.addWarning(BREAKPOINT_NAME + " failure attaching to newly tagged Probe: " + e.getMessage());
                        if (TRACE) {
                            OUT.println(BREAKPOINT_NAME + " failure: " + e.getMessage());
                        }
                    }
                }
            }
        });
    }

    /**
     * Globally enables tag breakpoint activity; all breakpoints are ignored when set to
     * {@code false}. When set to {@code true}, the enabled/disabled status of each breakpoint
     * determines whether it will trigger when flow of execution reaches it.
     *
     * @param breakpointsActive
     */
    void setActive(boolean breakpointsActive) {
        if (this.breakpointsActive != breakpointsActive) {
            breakpointsActiveUnchanged.invalidate();
            this.breakpointsActive = breakpointsActive;
        }
    }

    /**
     * Gets all current tag breakpoints,regardless of status; sorted and modification safe.
     */
    List<TagBreakpoint> getAll() {
        ArrayList<Entry<SyntaxTag, TagBreakpointImpl>> entries = new ArrayList<>(tagToBreakpoint.entrySet());
        Collections.sort(entries, BREAKPOINT_COMPARATOR);

        final ArrayList<TagBreakpoint> breakpoints = new ArrayList<>(entries.size());
        for (Entry<SyntaxTag, TagBreakpointImpl> entry : entries) {
            breakpoints.add(entry.getValue());
        }
        return breakpoints;
    }

    /**
     * Creates a new tag breakpoint if one doesn't already exist. If one does exist, then resets the
     * <em>ignore count</em>.
     *
     * @param tag where to set the breakpoint
     * @param ignoreCount number of initial hits before the breakpoint starts causing breaks.
     * @param oneShot whether the breakpoint should dispose itself after one hit
     * @return a possibly new breakpoint
     * @throws IOException if a breakpoint already exists for the tag and the ignore count is the
     *             same
     */
    TagBreakpoint create(int ignoreCount, SyntaxTag tag, boolean oneShot) throws IOException {

        TagBreakpointImpl breakpoint = tagToBreakpoint.get(tag);

        if (breakpoint == null) {
            breakpoint = new TagBreakpointImpl(ignoreCount, tag, oneShot);

            if (TRACE) {
                trace("NEW " + breakpoint.getShortDescription());
            }

            tagToBreakpoint.put(tag, breakpoint);

            for (Probe probe : debugger.getInstrumenter().findProbesTaggedAs(tag)) {
                breakpoint.attach(probe);
            }
        } else {
            if (ignoreCount == breakpoint.getIgnoreCount()) {
                throw new IOException(BREAKPOINT_NAME + " already set for tag " + tag.name());
            }
            breakpoint.setIgnoreCount(ignoreCount);
            if (TRACE) {
                trace("CHANGED ignoreCount %s", breakpoint.getShortDescription());
            }
        }
        return breakpoint;
    }

    /**
     * Returns the {@link TagBreakpoint} for a given tag, {@code null} if none.
     */
    TagBreakpoint get(SyntaxTag tag) {
        return tagToBreakpoint.get(tag);
    }

    /**
     * Removes the associated instrumentation for all one-shot breakpoints only.
     */
    void disposeOneShots() {
        List<TagBreakpointImpl> breakpoints = new ArrayList<>(tagToBreakpoint.values());
        for (TagBreakpointImpl breakpoint : breakpoints) {
            if (breakpoint.isOneShot()) {
                breakpoint.dispose();
            }
        }
    }

    /**
     * Removes all knowledge of a breakpoint, presumed disposed.
     */
    private void forget(TagBreakpointImpl breakpoint) {
        tagToBreakpoint.remove(breakpoint.getTag());
    }

    /**
     * Concrete representation of a line breakpoint, implemented by attaching an instrument to a
     * probe at the designated source location.
     */
    private final class TagBreakpointImpl extends TagBreakpoint implements AdvancedInstrumentResultListener {

        private static final String SHOULD_NOT_HAPPEN = "TagBreakpointImpl:  should not happen";

        private final SyntaxTag tag;

        // Cached assumption that the global status of tag breakpoint activity has not changed.
        private Assumption breakpointsActiveAssumption;

        // Whether this breakpoint is enable/disabled
        @CompilationFinal private boolean isEnabled;
        private Assumption enabledUnchangedAssumption;

        private String conditionExpr;

        /**
         * The instrument(s) that this breakpoint currently has attached to a {@link Probe}:
         * {@code null} if not attached.
         */
        private List<ProbeInstrument> instruments = new ArrayList<>();

        private TagBreakpointImpl(int ignoreCount, SyntaxTag tag, boolean oneShot) {
            super(ENABLED, ignoreCount, oneShot);
            this.tag = tag;
            this.breakpointsActiveAssumption = TagBreakpointFactory.this.breakpointsActiveUnchanged.getAssumption();
            this.isEnabled = true;
            this.enabledUnchangedAssumption = Truffle.getRuntime().createAssumption(BREAKPOINT_NAME + " enabled state unchanged");
        }

        @Override
        public boolean isEnabled() {
            return isEnabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            // Tag Breakpoints are never unresolved
            if (enabled != isEnabled) {
                switch (getState()) {
                    case ENABLED:
                        assert !enabled : SHOULD_NOT_HAPPEN;
                        doSetEnabled(false);
                        changeState(DISABLED);
                        break;
                    case DISABLED:
                        assert enabled : SHOULD_NOT_HAPPEN;
                        doSetEnabled(true);
                        changeState(ENABLED);
                        break;
                    case DISPOSED:
                        assert false : "breakpoint disposed";
                        break;
                    case ENABLED_UNRESOLVED:
                    case DISABLED_UNRESOLVED:
                    default:
                        assert false : SHOULD_NOT_HAPPEN;
                        break;
                }
            }
        }

        @Override
        public void setCondition(String expr) throws IOException {
            if (this.conditionExpr != null || expr != null) {
                // De-instrument the Probes instrumented by this breakpoint
                final ArrayList<Probe> probes = new ArrayList<>();
                for (ProbeInstrument instrument : instruments) {
                    probes.add(instrument.getProbe());
                    instrument.dispose();
                }
                instruments.clear();
                this.conditionExpr = expr;
                // Re-instrument the probes previously instrumented
                for (Probe probe : probes) {
                    attach(probe);
                }
            }
        }

        @Override
        public String getCondition() {
            return conditionExpr;
        }

        @TruffleBoundary
        @Override
        public void dispose() {
            if (getState() != DISPOSED) {
                for (ProbeInstrument instrument : instruments) {
                    instrument.dispose();
                }
                changeState(DISPOSED);
                TagBreakpointFactory.this.forget(this);
            }
        }

        private void attach(Probe newProbe) throws IOException {
            if (getState() == DISPOSED) {
                throw new IllegalStateException("Attempt to attach a disposed " + BREAKPOINT_NAME);
            }
            ProbeInstrument newInstrument = null;
            final Instrumenter instrumenter = debugger.getInstrumenter();
            if (conditionExpr == null) {
                newInstrument = instrumenter.attach(newProbe, new UnconditionalTagBreakInstrumentListener(), BREAKPOINT_NAME);
            } else {
                instrumenter.attach(newProbe, this, debugger.createAdvancedInstrumentRootFactory(newProbe, conditionExpr, this), Boolean.class, BREAKPOINT_NAME);
            }
            instruments.add(newInstrument);
            changeState(isEnabled ? ENABLED : DISABLED);
        }

        private void doSetEnabled(boolean enabled) {
            if (this.isEnabled != enabled) {
                enabledUnchangedAssumption.invalidate();
                this.isEnabled = enabled;
            }
        }

        @TruffleBoundary
        private String getShortDescription() {
            return BREAKPOINT_NAME + "@" + tag.name();
        }

        private void changeState(State after) {
            if (TRACE) {
                trace("STATE %s-->%s %s", getState().getName(), after.getName(), getShortDescription());
            }
            setState(after);
        }

        private void doBreak(Node node, VirtualFrame vFrame) {
            if (incrHitCountCheckIgnore()) {
                breakpointCallback.haltedAt(node, vFrame.materialize(), BREAKPOINT_NAME);
            }
        }

        /**
         * Receives notification from the attached instrument that execution is about to enter node
         * where the breakpoint is set. Designed so that when in the fast path, there is either an
         * unconditional "halt" call to the debugger or nothing.
         */
        private void nodeEnter(Node astNode, VirtualFrame vFrame) {

            // Deopt if the global active/inactive flag has changed
            try {
                this.breakpointsActiveAssumption.check();
            } catch (InvalidAssumptionException ex) {
                this.breakpointsActiveAssumption = TagBreakpointFactory.this.breakpointsActiveUnchanged.getAssumption();
            }

            // Deopt if the enabled/disabled state of this breakpoint has changed
            try {
                this.enabledUnchangedAssumption.check();
            } catch (InvalidAssumptionException ex) {
                this.enabledUnchangedAssumption = Truffle.getRuntime().createAssumption("LineBreakpoint enabled state unchanged");
            }

            if (TagBreakpointFactory.this.breakpointsActive && this.isEnabled) {
                if (isOneShot()) {
                    dispose();
                }
                TagBreakpointImpl.this.doBreak(astNode, vFrame);
            }

        }

        public void onExecution(Node node, VirtualFrame vFrame, Object result) {
            final boolean condition = (Boolean) result;
            if (TRACE) {
                trace("breakpoint condition = %b  %s", condition, getShortDescription());
            }
            if (condition) {
                nodeEnter(node, vFrame);
            }
        }

        public void onFailure(Node node, VirtualFrame vFrame, RuntimeException ex) {
            addExceptionWarning(ex);
            if (TRACE) {
                trace("breakpoint failure = %s  %s", ex, getShortDescription());
            }
            // Take the breakpoint if evaluation fails.
            nodeEnter(node, vFrame);
        }

        @TruffleBoundary
        private void addExceptionWarning(RuntimeException ex) {
            warningLog.addWarning(String.format("Exception in %s:  %s", getShortDescription(), ex.getMessage()));
        }

        @Override
        public String getLocationDescription() {
            return "Tag " + tag.name();
        }

        @Override
        public SyntaxTag getTag() {
            return tag;
        }

        private final class UnconditionalTagBreakInstrumentListener extends DefaultStandardInstrumentListener {

            @Override
            public void onEnter(Probe probe, Node node, VirtualFrame vFrame) {
                TagBreakpointImpl.this.nodeEnter(node, vFrame);
            }
        }

    }

}
