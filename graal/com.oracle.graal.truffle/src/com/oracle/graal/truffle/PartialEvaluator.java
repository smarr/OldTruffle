/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle;

import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.truffle.OptimizedCallTargetLog.*;
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.internal.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.loop.*;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.common.CanonicalizerPhase.CustomCanonicalizer;
import com.oracle.graal.phases.common.inlining.*;
import com.oracle.graal.phases.common.inlining.info.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.truffle.ContextSensitiveInlining.InliningDecision;
import com.oracle.graal.truffle.nodes.asserts.*;
import com.oracle.graal.truffle.nodes.frame.*;
import com.oracle.graal.truffle.nodes.frame.NewFrameNode.VirtualOnlyInstanceNode;
import com.oracle.graal.truffle.phases.*;
import com.oracle.graal.virtual.phases.ea.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Class performing the partial evaluation starting from the root node of an AST.
 */
public class PartialEvaluator {

    private final Providers providers;
    private final CanonicalizerPhase canonicalizer;
    private Set<Constant> constantReceivers;
    private final TruffleCache truffleCache;
    private final SnippetReflectionProvider snippetReflection;
    private final ResolvedJavaMethod callDirectMethod;
    private final ResolvedJavaMethod callSiteProxyMethod;

    public PartialEvaluator(Providers providers, TruffleCache truffleCache) {
        this.providers = providers;
        CustomCanonicalizer customCanonicalizer = new PartialEvaluatorCanonicalizer(providers.getMetaAccess(), providers.getConstantReflection());
        this.canonicalizer = new CanonicalizerPhase(!ImmutableCode.getValue(), customCanonicalizer);
        this.snippetReflection = Graal.getRequiredCapability(SnippetReflectionProvider.class);
        this.truffleCache = truffleCache;
        this.callDirectMethod = providers.getMetaAccess().lookupJavaMethod(OptimizedCallTarget.getCallDirectMethod());
        this.callSiteProxyMethod = providers.getMetaAccess().lookupJavaMethod(GraalFrameInstance.CallNodeFrame.METHOD);
    }

    public StructuredGraph createGraph(final OptimizedCallTarget callTarget, final Assumptions assumptions, ContextSensitiveInlining inlining) {
        if (TraceTruffleCompilationHistogram.getValue() || TraceTruffleCompilationDetails.getValue()) {
            constantReceivers = new HashSet<>();
        }

        try (Scope c = Debug.scope("TruffleTree")) {
            Debug.dump(callTarget, "truffle tree");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
        final StructuredGraph graph = truffleCache.createRootGraph(callTarget.toString());
        assert graph != null : "no graph for root method";

        try (Scope s = Debug.scope("CreateGraph", graph); Indent indent = Debug.logAndIndent("createGraph %s", graph)) {
            // Canonicalize / constant propagate.
            PhaseContext baseContext = new PhaseContext(providers, assumptions);

            injectConstantCallTarget(graph, callTarget, baseContext);

            Debug.dump(graph, "Before expansion");

            expandTree(graph, assumptions);

            expandDirectCalls(graph, assumptions, inlining != null ? new TruffleInliningCache() : null, inlining);

            if (Thread.currentThread().isInterrupted()) {
                return null;
            }

            new VerifyFrameDoesNotEscapePhase().apply(graph, false);

            if (TraceTruffleCompilationHistogram.getValue() && constantReceivers != null) {
                createHistogram();
            }

            canonicalizer.apply(graph, baseContext);
            Map<ResolvedJavaMethod, StructuredGraph> graphCache = null;
            if (CacheGraphs.getValue()) {
                graphCache = new HashMap<>();
            }
            HighTierContext tierContext = new HighTierContext(providers, assumptions, graphCache, new PhaseSuite<HighTierContext>(), OptimisticOptimizations.NONE);

            // EA frame and clean up.
            try (Scope pe = Debug.scope("TrufflePartialEscape", graph)) {
                new PartialEscapePhase(true, canonicalizer).apply(graph, tierContext);
            } catch (Throwable t) {
                Debug.handle(t);
            }

            // to make frame propagations visible retry expandTree
            while (expandTree(graph, assumptions)) {
                try (Scope pe = Debug.scope("TrufflePartialEscape", graph)) {
                    new PartialEscapePhase(true, canonicalizer).apply(graph, tierContext);
                } catch (Throwable t) {
                    Debug.handle(t);
                }
            }

            for (NeverPartOfCompilationNode neverPartOfCompilationNode : graph.getNodes(NeverPartOfCompilationNode.class)) {
                Throwable exception = new VerificationError(neverPartOfCompilationNode.getMessage());
                throw GraphUtil.approxSourceException(neverPartOfCompilationNode, exception);
            }

            new VerifyNoIntrinsicsLeftPhase().apply(graph, false);
            for (MaterializeFrameNode materializeNode : graph.getNodes(MaterializeFrameNode.class).snapshot()) {
                materializeNode.replaceAtUsages(materializeNode.getFrame());
                graph.removeFixed(materializeNode);
            }
            for (VirtualObjectNode virtualObjectNode : graph.getNodes(VirtualObjectNode.class)) {
                if (virtualObjectNode instanceof VirtualOnlyInstanceNode) {
                    VirtualOnlyInstanceNode virtualOnlyInstanceNode = (VirtualOnlyInstanceNode) virtualObjectNode;
                    virtualOnlyInstanceNode.setAllowMaterialization(true);
                } else if (virtualObjectNode instanceof VirtualInstanceNode) {
                    VirtualInstanceNode virtualInstanceNode = (VirtualInstanceNode) virtualObjectNode;
                    ResolvedJavaType type = virtualInstanceNode.type();
                    if (type.getAnnotation(CompilerDirectives.ValueType.class) != null) {
                        virtualInstanceNode.setIdentity(false);
                    }
                }
            }

        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        return graph;
    }

    private void expandDirectCalls(StructuredGraph graph, Assumptions assumptions, TruffleInliningCache inliningCache, ContextSensitiveInlining inlining) {
        if (inlining == null) {
            return;
        }
        PhaseContext phaseContext = new PhaseContext(providers, assumptions);
        TruffleExpansionLogger expansionLogger = new TruffleExpansionLogger(providers, graph);

        for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.class).snapshot()) {
            StructuredGraph inlineGraph = parseDirectCallGraph(phaseContext, assumptions, inliningCache, inlining, methodCallTargetNode);

            if (inlineGraph != null) {
                expandTreeInline(graph, phaseContext, expansionLogger, methodCallTargetNode, inlineGraph);
            }
        }
        // non inlined direct calls need to be expanded until TruffleCallBoundary.
        expandTree(graph, assumptions);
        assert noDirectCallsLeft(graph);
    }

    private boolean noDirectCallsLeft(StructuredGraph graph) {
        for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.class).snapshot()) {
            if (methodCallTargetNode.targetMethod().equals(callDirectMethod)) {
                return false;
            }
        }
        return true;
    }

    private void injectConstantCallTarget(final StructuredGraph graph, final OptimizedCallTarget constantCallTarget, PhaseContext baseContext) {
        ParameterNode thisNode = graph.getParameter(0);

        /*
         * Converting the call target to a Constant using the SnippetReflectionProvider is a
         * workaround, we should think about a better solution. Since object constants are
         * VM-specific, only the hosting VM knows how to do the conversion.
         */
        thisNode.replaceAndDelete(ConstantNode.forConstant(snippetReflection.forObject(constantCallTarget), providers.getMetaAccess(), graph));

        canonicalizer.apply(graph, baseContext);

        new IncrementalCanonicalizerPhase<>(canonicalizer, new ReplaceIntrinsicsPhase(providers.getReplacements())).apply(graph, baseContext);
    }

    private void createHistogram() {
        DebugHistogram histogram = Debug.createHistogram("Expanded Truffle Nodes");
        for (Constant c : constantReceivers) {
            String javaName = providers.getMetaAccess().lookupJavaType(c).toJavaName(false);

            // The DSL uses nested classes with redundant names - only show the inner class
            int index = javaName.indexOf('$');
            if (index != -1) {
                javaName = javaName.substring(index + 1);
            }

            histogram.add(javaName);

        }
        new DebugHistogramAsciiPrinter(TTY.out().out()).print(histogram);
    }

    private boolean expandTree(StructuredGraph graph, Assumptions assumptions) {
        PhaseContext phaseContext = new PhaseContext(providers, assumptions);
        TruffleExpansionLogger expansionLogger = null;
        if (TraceTruffleExpansion.getValue()) {
            expansionLogger = new TruffleExpansionLogger(providers, graph);
        }
        boolean changed = false;
        boolean changedInIteration;
        do {
            changedInIteration = false;
            for (MethodCallTargetNode methodCallTargetNode : graph.getNodes(MethodCallTargetNode.class)) {
                InvokeKind kind = methodCallTargetNode.invokeKind();
                try (Indent id1 = Debug.logAndIndent("try inlining %s, kind = %s", methodCallTargetNode.targetMethod(), kind)) {
                    if (kind == InvokeKind.Static || kind == InvokeKind.Special) {
                        if ((TraceTruffleCompilationHistogram.getValue() || TraceTruffleCompilationDetails.getValue()) && kind == InvokeKind.Special && methodCallTargetNode.receiver().isConstant()) {
                            constantReceivers.add(methodCallTargetNode.receiver().asConstant());
                        }

                        Replacements replacements = providers.getReplacements();
                        Class<? extends FixedWithNextNode> macroSubstitution = replacements.getMacroSubstitution(methodCallTargetNode.targetMethod());
                        if (macroSubstitution != null) {
                            InliningUtil.inlineMacroNode(methodCallTargetNode.invoke(), methodCallTargetNode.targetMethod(), macroSubstitution);
                            changed = changedInIteration = true;
                            continue;
                        }
                        StructuredGraph inlineGraph = replacements.getMethodSubstitution(methodCallTargetNode.targetMethod());

                        ResolvedJavaMethod targetMethod = methodCallTargetNode.targetMethod();
                        if (inlineGraph == null && !targetMethod.isNative() && targetMethod.canBeInlined()) {
                            inlineGraph = parseGraph(methodCallTargetNode.targetMethod(), methodCallTargetNode.arguments(), assumptions, phaseContext);
                        }

                        if (inlineGraph != null) {
                            expandTreeInline(graph, phaseContext, expansionLogger, methodCallTargetNode, inlineGraph);
                            changed = changedInIteration = true;
                        }
                    }
                }

                if (graph.getNodeCount() > TruffleCompilerOptions.TruffleGraphMaxNodes.getValue()) {
                    throw new BailoutException("Truffle compilation is exceeding maximum node count: " + graph.getNodeCount());
                }
            }

        } while (changedInIteration);

        if (TraceTruffleExpansion.getValue()) {
            expansionLogger.print();
        }
        return changed;
    }

    private StructuredGraph parseDirectCallGraph(PhaseContext phaseContext, Assumptions assumptions, TruffleInliningCache inliningCache, ContextSensitiveInlining inlining,
                    MethodCallTargetNode methodCallTargetNode) {
        OptimizedDirectCallNode callNode = resolveConstantCallNode(methodCallTargetNode);
        if (callNode == null) {
            return null;
        }

        InliningDecision decision = inlining.findByCall(callNode);
        if (decision == null) {
            if (TruffleCompilerOptions.PrintTrufflePerformanceWarnings.getValue()) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("callNode", callNode);
                logPerformanceWarning("A direct call within the Truffle AST is not reachable anymore. Call node was not inlined.", properties);
            }
            return null;
        }

        assert decision.getProfile().getCallNode() == callNode;

        OptimizedCallTarget currentTarget = decision.getProfile().getCallNode().getCurrentCallTarget();
        if (decision.getTarget() != currentTarget) {
            if (TruffleCompilerOptions.PrintTrufflePerformanceWarnings.getValue()) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("originalTarget", decision.getTarget());
                properties.put("callNode", callNode);
                logPerformanceWarning(String.format("CallTarget changed during compilation. Call node was not inlined."), properties);
            }
            return null;
        }

        StructuredGraph graph;
        if (decision.isInline()) {
            if (inliningCache == null) {
                graph = createInlineGraph(phaseContext, assumptions, null, decision);
            } else {
                graph = inliningCache.getCachedGraph(phaseContext, assumptions, decision);
            }
            decision.getProfile().setGraalDeepNodeCount(graph.getNodeCount());
        } else {
            graph = parseGraph(methodCallTargetNode.targetMethod(), methodCallTargetNode.arguments(), assumptions, phaseContext);
        }

        return graph;
    }

    private StructuredGraph createInlineGraph(PhaseContext phaseContext, Assumptions assumptions, TruffleInliningCache cache, InliningDecision decision) {
        try (Scope s = Debug.scope("GuestLanguageInlinedGraph", new DebugDumpScope(decision.getTarget().toString()))) {
            OptimizedCallTarget target = decision.getTarget();
            StructuredGraph inlineGraph = truffleCache.createInlineGraph(target.toString());
            injectConstantCallTarget(inlineGraph, decision.getTarget(), phaseContext);
            expandTree(inlineGraph, assumptions);
            expandDirectCalls(inlineGraph, assumptions, cache, decision);
            return inlineGraph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    private OptimizedDirectCallNode resolveConstantCallNode(MethodCallTargetNode methodCallTargetNode) {
        if (!methodCallTargetNode.targetMethod().equals(callDirectMethod)) {
            return null;
        }

        Invoke invoke = methodCallTargetNode.invoke();
        if (invoke == null) {
            return null;
        }

        FrameState directCallState = invoke.stateAfter();
        while (directCallState != null && directCallState.method() != callSiteProxyMethod) {
            directCallState = directCallState.outerFrameState();
        }

        if (directCallState == null) {
            // not a direct call. May be indirect call.
            return null;
        }

        if (directCallState.values().isEmpty()) {
            throw new AssertionError(String.format("Frame state of method '%s' is invalid.", callDirectMethod.toString()));
        }

        ValueNode node = directCallState.values().get(0);
        if (!node.isConstant()) {
            throw new AssertionError(String.format("Method argument for method '%s' is not constant.", callDirectMethod.toString()));
        }

        Constant constantCallNode = node.asConstant();
        Object value = snippetReflection.asObject(constantCallNode);

        if (!(value instanceof OptimizedDirectCallNode)) {
            // might be an indirect call.
            return null;
        }

        return (OptimizedDirectCallNode) value;
    }

    private void expandTreeInline(StructuredGraph graph, PhaseContext phaseContext, TruffleExpansionLogger expansionLogger, MethodCallTargetNode methodCallTargetNode, StructuredGraph inlineGraph) {
        try (Indent indent = Debug.logAndIndent("expand graph %s", methodCallTargetNode.targetMethod())) {
            int nodeCountBefore = graph.getNodeCount();
            if (TraceTruffleExpansion.getValue()) {
                expansionLogger.preExpand(methodCallTargetNode, inlineGraph);
            }
            List<Node> canonicalizedNodes = methodCallTargetNode.invoke().asNode().usages().snapshot();

            Map<Node, Node> inlined = InliningUtil.inline(methodCallTargetNode.invoke(), inlineGraph, false, canonicalizedNodes);
            if (TraceTruffleExpansion.getValue()) {
                expansionLogger.postExpand(inlined);
            }
            if (Debug.isDumpEnabled()) {
                int nodeCountAfter = graph.getNodeCount();
                Debug.dump(graph, "After expand %s %+d (%d)", methodCallTargetNode.targetMethod().toString(), nodeCountAfter - nodeCountBefore, nodeCountAfter);
            }
            AbstractInlineInfo.getInlinedParameterUsages(canonicalizedNodes, inlineGraph, inlined);
            canonicalizer.applyIncremental(graph, phaseContext, canonicalizedNodes);
        }
    }

    private StructuredGraph parseGraph(final ResolvedJavaMethod targetMethod, final NodeInputList<ValueNode> arguments, final Assumptions assumptions, final PhaseContext phaseContext) {

        StructuredGraph graph = truffleCache.lookup(targetMethod, arguments, assumptions, canonicalizer);

        if (graph != null && targetMethod.getAnnotation(ExplodeLoop.class) != null) {
            assert graph.hasLoops() : graph + " does not contain a loop";
            final StructuredGraph graphCopy = graph.copy();
            final List<Node> modifiedNodes = new ArrayList<>();
            for (ParameterNode param : graphCopy.getNodes(ParameterNode.class)) {
                ValueNode arg = arguments.get(param.index());
                if (arg.isConstant()) {
                    Constant constant = arg.asConstant();
                    for (Node usage : param.usages()) {
                        if (usage instanceof Canonicalizable) {
                            modifiedNodes.add(usage);
                        }
                    }
                    param.replaceAndDelete(ConstantNode.forConstant(constant, phaseContext.getMetaAccess(), graphCopy));
                }
            }
            try (Scope s = Debug.scope("TruffleUnrollLoop", targetMethod)) {

                canonicalizer.applyIncremental(graphCopy, phaseContext, modifiedNodes);
                boolean unrolled;
                do {
                    unrolled = false;
                    LoopsData loopsData = new LoopsData(graphCopy);
                    loopsData.detectedCountedLoops();
                    for (LoopEx ex : innerLoopsFirst(loopsData.countedLoops())) {
                        if (ex.counted().isConstantMaxTripCount()) {
                            long constant = ex.counted().constantMaxTripCount();
                            LoopTransformations.fullUnroll(ex, phaseContext, canonicalizer);
                            Debug.dump(graphCopy, "After loop unrolling %d times", constant);
                            unrolled = true;
                            break;
                        }
                    }
                    loopsData.deleteUnusedNodes();
                } while (unrolled);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }

            return graphCopy;
        } else {
            return graph;
        }
    }

    private static List<LoopEx> innerLoopsFirst(Collection<LoopEx> loops) {
        ArrayList<LoopEx> sortedLoops = new ArrayList<>(loops);
        Collections.sort(sortedLoops, new Comparator<LoopEx>() {

            @Override
            public int compare(LoopEx o1, LoopEx o2) {
                return o2.loop().getDepth() - o1.loop().getDepth();
            }
        });
        return sortedLoops;
    }

    private final class TruffleInliningCache {

        private final Map<CacheKey, StructuredGraph> cache;

        public TruffleInliningCache() {
            this.cache = new HashMap<>();
        }

        public StructuredGraph getCachedGraph(PhaseContext phaseContext, Assumptions assumptions, InliningDecision decision) {
            CacheKey cacheKey = new CacheKey(decision);
            StructuredGraph inlineGraph = cache.get(cacheKey);
            if (inlineGraph == null) {
                inlineGraph = createInlineGraph(phaseContext, assumptions, this, decision);
                cache.put(cacheKey, inlineGraph);
            }
            return inlineGraph;
        }

        private final class CacheKey {

            public final InliningDecision decision;

            public CacheKey(InliningDecision decision) {
                this.decision = decision;
                /*
                 * If decision.isInline() is not true CacheKey#hashCode does not match
                 * CacheKey#equals
                 */
                assert decision.isInline();
            }

            @Override
            public int hashCode() {
                return decision.getTarget().hashCode();
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof CacheKey)) {
                    return false;
                }
                CacheKey other = (CacheKey) obj;
                return decision.isSameAs(other.decision);
            }
        }
    }

}
