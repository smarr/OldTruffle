/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.compiler.test;

import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.compiler.GraalCompiler.*;
import static com.oracle.graal.compiler.common.GraalOptions.*;
import static com.oracle.graal.nodes.ConstantNode.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import org.junit.*;
import org.junit.internal.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.replacements.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.baseline.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.GraalCompiler.Request;
import com.oracle.graal.compiler.common.*;
import com.oracle.graal.compiler.common.remote.*;
import com.oracle.graal.compiler.common.remote.Context.Mode;
import com.oracle.graal.compiler.target.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.nodeinfo.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.virtual.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.schedule.*;
import com.oracle.graal.phases.tiers.*;
import com.oracle.graal.phases.util.*;
import com.oracle.graal.printer.*;
import com.oracle.graal.runtime.*;
import com.oracle.graal.test.*;

/**
 * Base class for Graal compiler unit tests.
 * <p>
 * White box tests for Graal compiler transformations use this pattern:
 * <ol>
 * <li>Create a graph by {@linkplain #parseEager(String) parsing} a method.</li>
 * <li>Manually modify the graph (e.g. replace a parameter node with a constant).</li>
 * <li>Apply a transformation to the graph.</li>
 * <li>Assert that the transformed graph is equal to an expected graph.</li>
 * </ol>
 * <p>
 * See {@link InvokeHintsTest} as an example of a white box test.
 * <p>
 * Black box tests use the {@link #test(String, Object...)} or
 * {@link #testN(int, String, Object...)} to execute some method in the interpreter and compare its
 * result against that produced by a Graal compiled version of the method.
 * <p>
 * These tests will be run by the {@code mx unittest} command.
 */
public abstract class GraalCompilerTest extends GraalTest {

    private final Providers providers;
    private final Backend backend;
    private final Suites suites;

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of HighTier
     */
    protected boolean checkHighTierGraph(StructuredGraph graph) {
        return true;
    }

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of MidTier
     */
    protected boolean checkMidTierGraph(StructuredGraph graph) {
        return true;
    }

    /**
     * Can be overridden by unit tests to verify properties of the graph.
     *
     * @param graph the graph at the end of LowTier
     */
    protected boolean checkLowTierGraph(StructuredGraph graph) {
        return true;
    }

    private static boolean substitutionsInstalled;

    private void installSubstitutions() {
        if (!substitutionsInstalled) {
            this.providers.getReplacements().registerSubstitutions(GraalCompilerTest.class, GraalCompilerTestSubstitutions.class);
            substitutionsInstalled = true;
        }
    }

    protected static void breakpoint() {
    }

    protected Suites createSuites() {
        Suites ret = backend.getSuites().createSuites();
        ListIterator<BasePhase<? super HighTierContext>> iter = ret.getHighTier().findPhase(CleanTypeProfileProxyPhase.class);
        PhaseSuite.findNextPhase(iter, CanonicalizerPhase.class);
        iter.add(new Phase("ComputeLoopFrequenciesPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                ComputeLoopFrequenciesClosure.compute(graph);
            }
        });
        ret.getHighTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkHighTierGraph(graph);
            }
        });
        ret.getMidTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkMidTierGraph(graph);
            }
        });
        ret.getLowTier().appendPhase(new Phase("CheckGraphPhase") {

            @Override
            protected void run(StructuredGraph graph) {
                assert checkLowTierGraph(graph);
            }
        });
        return ret;
    }

    public GraalCompilerTest() {
        this.backend = Graal.getRequiredCapability(RuntimeProvider.class).getHostBackend();
        this.providers = getBackend().getProviders();
        this.suites = createSuites();
        installSubstitutions();
    }

    /**
     * Set up a test for a non-default backend. The test should check (via {@link #getBackend()} )
     * whether the desired backend is available.
     *
     * @param arch the name of the desired backend architecture
     */
    public GraalCompilerTest(Class<? extends Architecture> arch) {
        RuntimeProvider runtime = Graal.getRequiredCapability(RuntimeProvider.class);
        Backend b = runtime.getBackend(arch);
        if (b != null) {
            this.backend = b;
        } else {
            // Fall back to the default/host backend
            this.backend = runtime.getHostBackend();
        }
        this.providers = backend.getProviders();
        this.suites = createSuites();
        installSubstitutions();
    }

    @BeforeClass
    public static void initializeDebugging() {
        DebugEnvironment.initialize(System.out);
    }

    private Scope debugScope;

    @Before
    public void beforeTest() {
        assert debugScope == null;
        debugScope = Debug.scope(getClass());
    }

    @After
    public void afterTest() {
        if (debugScope != null) {
            debugScope.close();
        }
        debugScope = null;
    }

    protected void assertEquals(StructuredGraph expected, StructuredGraph graph) {
        assertEquals(expected, graph, false, true);
    }

    protected int countUnusedConstants(StructuredGraph graph) {
        int total = 0;
        for (ConstantNode node : getConstantNodes(graph)) {
            if (node.usages().isEmpty()) {
                total++;
            }
        }
        return total;
    }

    protected int getNodeCountExcludingUnusedConstants(StructuredGraph graph) {
        return graph.getNodeCount() - countUnusedConstants(graph);
    }

    protected void assertEquals(StructuredGraph expected, StructuredGraph graph, boolean excludeVirtual, boolean checkConstants) {
        String expectedString = getCanonicalGraphString(expected, excludeVirtual, checkConstants);
        String actualString = getCanonicalGraphString(graph, excludeVirtual, checkConstants);
        String mismatchString = "mismatch in graphs:\n========= expected (" + expected + ") =========\n" + expectedString + "\n\n========= actual (" + graph + ") =========\n" + actualString;

        if (!excludeVirtual && getNodeCountExcludingUnusedConstants(expected) != getNodeCountExcludingUnusedConstants(graph)) {
            Debug.dump(expected, "Node count not matching - expected");
            Debug.dump(graph, "Node count not matching - actual");
            Assert.fail("Graphs do not have the same number of nodes: " + expected.getNodeCount() + " vs. " + graph.getNodeCount() + "\n" + mismatchString);
        }
        if (!expectedString.equals(actualString)) {
            Debug.dump(expected, "mismatching graphs - expected");
            Debug.dump(graph, "mismatching graphs - actual");
            Assert.fail(mismatchString);
        }
    }

    protected void assertConstantReturn(StructuredGraph graph, int value) {
        String graphString = getCanonicalGraphString(graph, false, true);
        Assert.assertEquals("unexpected number of ReturnNodes: " + graphString, graph.getNodes(ReturnNode.class).count(), 1);
        ValueNode result = graph.getNodes(ReturnNode.class).first().result();
        Assert.assertTrue("unexpected ReturnNode result node: " + graphString, result.isConstant());
        Assert.assertEquals("unexpected ReturnNode result kind: " + graphString, result.asJavaConstant().getKind(), Kind.Int);
        Assert.assertEquals("unexpected ReturnNode result: " + graphString, result.asJavaConstant().asInt(), value);
    }

    protected static String getCanonicalGraphString(StructuredGraph graph, boolean excludeVirtual, boolean checkConstants) {
        SchedulePhase schedule = new SchedulePhase();
        schedule.apply(graph);

        NodeMap<Integer> canonicalId = graph.createNodeMap();
        int nextId = 0;

        StringBuilder result = new StringBuilder();
        for (Block block : schedule.getCFG().getBlocks()) {
            result.append("Block " + block + " ");
            if (block == schedule.getCFG().getStartBlock()) {
                result.append("* ");
            }
            result.append("-> ");
            for (Block succ : block.getSuccessors()) {
                result.append(succ + " ");
            }
            result.append("\n");
            for (Node node : schedule.getBlockToNodesMap().get(block)) {
                if (node.isAlive()) {
                    if (!excludeVirtual || !(node instanceof VirtualObjectNode || node instanceof ProxyNode)) {
                        int id;
                        if (canonicalId.get(node) != null) {
                            id = canonicalId.get(node);
                        } else {
                            id = nextId++;
                            canonicalId.set(node, id);
                        }
                        String name = node instanceof ConstantNode && checkConstants ? node.toString(Verbosity.Name) : node.getClass().getSimpleName();
                        result.append("  " + id + "|" + name + (excludeVirtual ? "\n" : "    (" + node.usages().count() + ")\n"));
                    }
                }
            }
        }
        return result.toString();
    }

    protected Backend getBackend() {
        return backend;
    }

    protected Suites getSuites() {
        return suites;
    }

    protected Providers getProviders() {
        return providers;
    }

    protected SnippetReflectionProvider getSnippetReflection() {
        return Graal.getRequiredCapability(SnippetReflectionProvider.class);
    }

    protected TargetDescription getTarget() {
        return getProviders().getCodeCache().getTarget();
    }

    protected CodeCacheProvider getCodeCache() {
        return getProviders().getCodeCache();
    }

    protected ConstantReflectionProvider getConstantReflection() {
        return getProviders().getConstantReflection();
    }

    protected MetaAccessProvider getMetaAccess() {
        return getProviders().getMetaAccess();
    }

    protected LoweringProvider getLowerer() {
        return getProviders().getLowerer();
    }

    private static AtomicInteger compilationId = new AtomicInteger();

    protected void testN(int n, final String name, final Object... args) {
        final List<Throwable> errors = new ArrayList<>(n);
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(i + ":" + name) {

                @Override
                public void run() {
                    try {
                        test(name, args);
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            };
            threads[i] = t;
            t.start();
        }
        for (int i = 0; i < n; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            throw new MultiCauseAssertionError(errors.size() + " failures", errors.toArray(new Throwable[errors.size()]));
        }
    }

    protected Object referenceInvoke(ResolvedJavaMethod method, Object receiver, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        return invoke(method, receiver, args);
    }

    protected static class Result {

        final Object returnValue;
        final Throwable exception;

        public Result(Object returnValue, Throwable exception) {
            this.returnValue = returnValue;
            this.exception = exception;
        }

        @Override
        public String toString() {
            return exception == null ? returnValue == null ? "null" : returnValue.toString() : "!" + exception;
        }
    }

    /**
     * Called before a test is executed.
     */
    protected void before(@SuppressWarnings("unused") ResolvedJavaMethod method) {
    }

    /**
     * Called after a test is executed.
     */
    protected void after() {
    }

    protected Result executeExpected(ResolvedJavaMethod method, Object receiver, Object... args) {
        before(method);
        try {
            // This gives us both the expected return value as well as ensuring that the method to
            // be compiled is fully resolved
            return new Result(referenceInvoke(method, receiver, args), null);
        } catch (InvocationTargetException e) {
            return new Result(null, e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            after();
        }
    }

    protected Result executeActual(ResolvedJavaMethod method, Object receiver, Object... args) {
        before(method);
        Object[] executeArgs = argsWithReceiver(receiver, args);

        checkArgs(method, executeArgs);

        InstalledCode compiledMethod = null;
        if (UseBaselineCompiler.getValue()) {
            compiledMethod = getCodeBaseline(method);
        } else {
            compiledMethod = getCode(method);
        }
        try {
            return new Result(compiledMethod.executeVarargs(executeArgs), null);
        } catch (Throwable e) {
            return new Result(null, e);
        } finally {
            after();
        }
    }

    protected InstalledCode getCodeBaseline(ResolvedJavaMethod javaMethod) {
        return getCodeBaseline(javaMethod, false);
    }

    protected InstalledCode getCodeBaseline(ResolvedJavaMethod javaMethod, boolean forceCompile) {
        assert javaMethod.getAnnotation(Test.class) == null : "shouldn't parse method with @Test annotation: " + javaMethod;

        try (Scope bds = Debug.scope("Baseline")) {
            Debug.log("getCodeBaseline()");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        if (!forceCompile) {
            InstalledCode cached = cache.get(javaMethod);
            if (cached != null) {
                if (cached.isValid()) {
                    return cached;
                }
            }
        }

        final int id = compilationId.incrementAndGet();

        InstalledCode installedCode = null;
        try (Scope ds = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(id), true))) {
            final boolean printCompilation = PrintCompilation.getValue() && !TTY.isSuppressed();

            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s ...", id, javaMethod.getDeclaringClass().getName(), javaMethod.getName(), javaMethod.getSignature()));
            }
            long start = System.currentTimeMillis();

            CompilationResult compResult = compileBaseline(javaMethod);

            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s | %4dms %5dB", id, "", "", "", System.currentTimeMillis() - start, compResult.getTargetCodeSize()));
            }

            try (Scope s = Debug.scope("CodeInstall", getCodeCache(), javaMethod)) {
                installedCode = addMethod(javaMethod, compResult);
                if (installedCode == null) {
                    throw new GraalInternalError("Could not install code for " + javaMethod.format("%H.%n(%p)"));
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        if (!forceCompile) {
            cache.put(javaMethod, installedCode);
        }
        return installedCode;
    }

    private CompilationResult compileBaseline(ResolvedJavaMethod javaMethod) {
        try (Scope bds = Debug.scope("CompileBaseline", javaMethod, providers.getCodeCache())) {
            BaselineCompiler baselineCompiler = new BaselineCompiler(GraphBuilderConfiguration.getDefault(), providers.getMetaAccess());
            OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL;
            return baselineCompiler.generate(javaMethod, -1, getBackend(), new CompilationResult(), javaMethod, CompilationResultBuilderFactory.Default, optimisticOpts);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected void checkArgs(ResolvedJavaMethod method, Object[] args) {
        JavaType[] sig = method.toParameterTypes();
        Assert.assertEquals(sig.length, args.length);
        for (int i = 0; i < args.length; i++) {
            JavaType javaType = sig[i];
            Kind kind = javaType.getKind();
            Object arg = args[i];
            if (kind == Kind.Object) {
                if (arg != null && javaType instanceof ResolvedJavaType) {
                    ResolvedJavaType resolvedJavaType = (ResolvedJavaType) javaType;
                    Assert.assertTrue(resolvedJavaType + " from " + getMetaAccess().lookupJavaType(arg.getClass()), resolvedJavaType.isAssignableFrom(getMetaAccess().lookupJavaType(arg.getClass())));
                }
            } else {
                Assert.assertNotNull(arg);
                Assert.assertEquals(kind.toBoxedJavaClass(), arg.getClass());
            }
        }
    }

    /**
     * Prepends a non-null receiver argument to a given list or args.
     *
     * @param receiver the receiver argument to prepend if it is non-null
     */
    protected Object[] argsWithReceiver(Object receiver, Object... args) {
        Object[] executeArgs;
        if (receiver == null) {
            executeArgs = args;
        } else {
            executeArgs = new Object[args.length + 1];
            executeArgs[0] = receiver;
            for (int i = 0; i < args.length; i++) {
                executeArgs[i + 1] = args[i];
            }
        }
        return executeArgs;
    }

    protected void test(String name, Object... args) {
        try {
            ResolvedJavaMethod method = getResolvedJavaMethod(name);
            Object receiver = method.isStatic() ? null : this;
            test(method, receiver, args);
        } catch (AssumptionViolatedException e) {
            // Suppress so that subsequent calls to this method within the
            // same Junit @Test annotated method can proceed.
        }
    }

    protected void test(ResolvedJavaMethod method, Object receiver, Object... args) {
        Result expect = executeExpected(method, receiver, args);
        if (getCodeCache() == null) {
            return;
        }
        testAgainstExpected(method, expect, receiver, args);
    }

    protected void testAgainstExpected(ResolvedJavaMethod method, Result expect, Object receiver, Object... args) {
        testAgainstExpected(method, expect, Collections.<DeoptimizationReason> emptySet(), receiver, args);
    }

    protected Result executeActualCheckDeopt(ResolvedJavaMethod method, Set<DeoptimizationReason> shouldNotDeopt, Object receiver, Object... args) {
        Map<DeoptimizationReason, Integer> deoptCounts = new EnumMap<>(DeoptimizationReason.class);
        ProfilingInfo profile = method.getProfilingInfo();
        for (DeoptimizationReason reason : shouldNotDeopt) {
            deoptCounts.put(reason, profile.getDeoptimizationCount(reason));
        }
        Result actual = executeActual(method, receiver, args);
        profile = method.getProfilingInfo(); // profile can change after execution
        for (DeoptimizationReason reason : shouldNotDeopt) {
            Assert.assertEquals((int) deoptCounts.get(reason), profile.getDeoptimizationCount(reason));
        }
        return actual;
    }

    protected void assertEquals(Result expect, Result actual) {
        if (expect.exception != null) {
            Assert.assertTrue("expected " + expect.exception, actual.exception != null);
            Assert.assertEquals("Exception class", expect.exception.getClass(), actual.exception.getClass());
            Assert.assertEquals("Exception message", expect.exception.getMessage(), actual.exception.getMessage());
        } else {
            if (actual.exception != null) {
                actual.exception.printStackTrace();
                Assert.fail("expected " + expect.returnValue + " but got an exception");
            }
            assertDeepEquals(expect.returnValue, actual.returnValue);
        }
    }

    protected void testAgainstExpected(ResolvedJavaMethod method, Result expect, Set<DeoptimizationReason> shouldNotDeopt, Object receiver, Object... args) {
        Result actual = executeActualCheckDeopt(method, shouldNotDeopt, receiver, args);
        assertEquals(expect, actual);
    }

    private Map<ResolvedJavaMethod, InstalledCode> cache = new HashMap<>();

    /**
     * Gets installed code for a given method, compiling it first if necessary. The graph is parsed
     * {@link #parseEager(ResolvedJavaMethod) eagerly}.
     */
    protected InstalledCode getCode(ResolvedJavaMethod method) {
        return getCode(method, null);
    }

    /**
     * Gets installed code for a given method, compiling it first if necessary.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled. If null, a graph will be obtained from
     *            {@code installedCodeOwner} via {@link #parseForCompile(ResolvedJavaMethod)}.
     */
    protected InstalledCode getCode(ResolvedJavaMethod installedCodeOwner, StructuredGraph graph) {
        return getCode(installedCodeOwner, graph, false);
    }

    /**
     * Gets installed code for a given method and graph, compiling it first if necessary.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled. If null, a graph will be obtained from
     *            {@code installedCodeOwner} via {@link #parseForCompile(ResolvedJavaMethod)}.
     * @param forceCompile specifies whether to ignore any previous code cached for the (method,
     *            key) pair
     */
    protected InstalledCode getCode(final ResolvedJavaMethod installedCodeOwner, StructuredGraph graph, boolean forceCompile) {
        if (!forceCompile) {
            InstalledCode cached = cache.get(installedCodeOwner);
            if (cached != null) {
                if (cached.isValid()) {
                    return cached;
                }
            }
        }

        final int id = compilationId.incrementAndGet();

        InstalledCode installedCode = null;
        try (AllocSpy spy = AllocSpy.open(installedCodeOwner); Scope ds = Debug.scope("Compiling", new DebugDumpScope(String.valueOf(id), true))) {
            final boolean printCompilation = PrintCompilation.getValue() && !TTY.isSuppressed();
            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s ...", id, installedCodeOwner.getDeclaringClass().getName(), installedCodeOwner.getName(), installedCodeOwner.getSignature()));
            }
            long start = System.currentTimeMillis();
            CompilationResult compResult = compile(installedCodeOwner, graph);
            if (printCompilation) {
                TTY.println(String.format("@%-6d Graal %-70s %-45s %-50s | %4dms %5dB", id, "", "", "", System.currentTimeMillis() - start, compResult.getTargetCodeSize()));
            }

            try (Scope s = Debug.scope("CodeInstall", getCodeCache(), installedCodeOwner)) {
                installedCode = addMethod(installedCodeOwner, compResult);
                if (installedCode == null) {
                    throw new GraalInternalError("Could not install code for " + installedCodeOwner.format("%H.%n(%p)"));
                }
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        if (!forceCompile) {
            cache.put(installedCodeOwner, installedCode);
        }
        return installedCode;
    }

    /**
     * Used to produce a graph for a method about to be compiled by
     * {@link #compile(ResolvedJavaMethod, StructuredGraph)} if the second parameter to that method
     * is null.
     *
     * The default implementation in {@link GraalCompilerTest} is to call
     * {@link #parseEager(ResolvedJavaMethod)}.
     */
    protected StructuredGraph parseForCompile(ResolvedJavaMethod method) {
        return parseEager(method);
    }

    /**
     * Determines if every compilation should also be attempted in a replay {@link Context}.
     *
     * <pre>
     * -Dgraal.testReplay=true
     * </pre>
     */
    private static final boolean TEST_REPLAY = Boolean.getBoolean("graal.testReplay");

    /**
     * Directory into which tests can dump content useful in debugging test failures.
     */
    private static final File OUTPUT_DIR = new File(System.getProperty("graal.test.output"), "testOutput");

    protected static File getOutputDir() {
        if (!OUTPUT_DIR.exists()) {
            OUTPUT_DIR.mkdirs();
        }
        if (!OUTPUT_DIR.exists()) {
            throw new GraalInternalError("Could not create test output directory: %s", OUTPUT_DIR.getAbsolutePath());
        }
        return OUTPUT_DIR;
    }

    protected String dissasembleToFile(CompilationResult original, ResolvedJavaMethod installedCodeOwner, String fileSuffix) {
        String dis = getCodeCache().disassemble(original, null);
        File disFile = new File(getOutputDir(), installedCodeOwner.format("%H.%n_%p").replace(", ", "__") + "." + fileSuffix);
        try (PrintStream ps = new PrintStream(new FileOutputStream(disFile))) {
            ps.println(dis);
            return disFile.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    protected void assertCompilationResultsEqual(Context c, String prefix, CompilationResult original, CompilationResult derived, ResolvedJavaMethod installedCodeOwner) {
        if (!derived.equals(original)) {
            Mode mode = c.getMode();
            // Temporarily force capturing mode as dumping/printing/disassembling
            // may need to execute proxy methods that have not yet been executed
            c.setMode(Mode.Capturing);
            try {
                String originalDisFile = dissasembleToFile(original, installedCodeOwner, "original");
                String derivedDisFile = dissasembleToFile(derived, installedCodeOwner, "derived");
                String message = String.format("%s compilation result differs from original compilation result", prefix);
                if (originalDisFile != null && derivedDisFile != null) {
                    message += String.format(" [diff %s %s]", originalDisFile, derivedDisFile);
                }
                Assert.fail(message);
            } finally {
                c.setMode(mode);
            }
        }
    }

    protected void replayCompile(CompilationResult originalResult, ResolvedJavaMethod installedCodeOwner, StructuredGraph graph) {

        StructuredGraph graphToCompile = graph == null ? parseForCompile(installedCodeOwner) : graph;
        lastCompiledGraph = graphToCompile;

        CallingConvention cc = getCallingConvention(getCodeCache(), Type.JavaCallee, graphToCompile.method(), false);
        try (Context c = new Context(); Debug.Scope s = Debug.scope("ReplayCompiling", new DebugDumpScope("REPLAY", true))) {
            // Need to use an 'in context' copy of the original result when comparing for
            // equality against other 'in context' results so that proxies are compared
            // against proxies
            CompilationResult originalResultInContext = c.get(originalResult);

            // Capturing compilation
            Request<CompilationResult> capturingRequest = c.get(new GraalCompiler.Request<>(graphToCompile, null, cc, installedCodeOwner, getProviders(), getBackend(), getCodeCache().getTarget(),
                            null, getDefaultGraphBuilderSuite(), OptimisticOptimizations.ALL, getProfilingInfo(graphToCompile), getSpeculationLog(), getSuites(), new CompilationResult(),
                            CompilationResultBuilderFactory.Default));
            assertCompilationResultsEqual(c, "Capturing", originalResultInContext, GraalCompiler.compile(capturingRequest), installedCodeOwner);

            // Replay compilation
            Request<CompilationResult> replyRequest = c.get(new GraalCompiler.Request<>(graphToCompile.copy(), null, cc, capturingRequest.installedCodeOwner, capturingRequest.providers,
                            capturingRequest.backend, capturingRequest.target, null, capturingRequest.graphBuilderSuite, capturingRequest.optimisticOpts, capturingRequest.profilingInfo,
                            capturingRequest.speculationLog, capturingRequest.suites, new CompilationResult(), capturingRequest.factory));
            c.setMode(Mode.Replaying);

            assertCompilationResultsEqual(c, "Replay", originalResultInContext, GraalCompiler.compile(replyRequest), installedCodeOwner);
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    /**
     * Compiles a given method.
     *
     * @param installedCodeOwner the method the compiled code will be associated with when installed
     * @param graph the graph to be compiled for {@code installedCodeOwner}. If null, a graph will
     *            be obtained from {@code installedCodeOwner} via
     *            {@link #parseForCompile(ResolvedJavaMethod)}.
     */
    protected CompilationResult compile(ResolvedJavaMethod installedCodeOwner, StructuredGraph graph) {
        StructuredGraph graphToCompile = graph == null ? parseForCompile(installedCodeOwner) : graph;
        lastCompiledGraph = graphToCompile;
        CallingConvention cc = getCallingConvention(getCodeCache(), Type.JavaCallee, graphToCompile.method(), false);
        CompilationResult res = GraalCompiler.compileGraph(graphToCompile, null, cc, installedCodeOwner, getProviders(), getBackend(), getCodeCache().getTarget(), null, getDefaultGraphBuilderSuite(),
                        OptimisticOptimizations.ALL, getProfilingInfo(graphToCompile), getSpeculationLog(), getSuites(), new CompilationResult(), CompilationResultBuilderFactory.Default);

        if (TEST_REPLAY && graph == null) {
            replayCompile(res, installedCodeOwner, null);
        }
        return res;
    }

    protected StructuredGraph lastCompiledGraph;

    protected SpeculationLog getSpeculationLog() {
        return null;
    }

    protected InstalledCode addMethod(final ResolvedJavaMethod method, final CompilationResult compResult) {
        return getCodeCache().addMethod(method, compResult, null, null);
    }

    private final Map<ResolvedJavaMethod, Method> methodMap = new HashMap<>();

    /**
     * Converts a reflection {@link Method} to a {@link ResolvedJavaMethod}.
     */
    protected ResolvedJavaMethod asResolvedJavaMethod(Method method) {
        ResolvedJavaMethod javaMethod = getMetaAccess().lookupJavaMethod(method);
        methodMap.put(javaMethod, method);
        return javaMethod;
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(String methodName) {
        return asResolvedJavaMethod(getMethod(methodName));
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(Class<?> clazz, String methodName) {
        return asResolvedJavaMethod(getMethod(clazz, methodName));
    }

    protected ResolvedJavaMethod getResolvedJavaMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return asResolvedJavaMethod(getMethod(clazz, methodName, parameterTypes));
    }

    /**
     * Gets the reflection {@link Method} from which a given {@link ResolvedJavaMethod} was created
     * or null if {@code javaMethod} does not correspond to a reflection method.
     */
    protected Method lookupMethod(ResolvedJavaMethod javaMethod) {
        return methodMap.get(javaMethod);
    }

    protected Object invoke(ResolvedJavaMethod javaMethod, Object receiver, Object... args) throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Method method = lookupMethod(javaMethod);
        Assert.assertTrue(method != null);
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }
        return method.invoke(receiver, args);
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getDefault() default} mode to
     * produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parseProfiled(String methodName) {
        return parseProfiled(getResolvedJavaMethod(methodName));
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getDefault() default} mode to
     * produce a graph.
     */
    protected StructuredGraph parseProfiled(ResolvedJavaMethod m) {
        return parse1(m, getDefaultGraphBuilderSuite());
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getEagerDefault() eager} mode
     * to produce a graph.
     *
     * @param methodName the name of the method in {@code this.getClass()} to be parsed
     */
    protected StructuredGraph parseEager(String methodName) {
        return parseEager(getResolvedJavaMethod(methodName));
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getEagerDefault() eager} mode
     * to produce a graph.
     */
    protected StructuredGraph parseEager(ResolvedJavaMethod m) {
        return parse1(m, getCustomGraphBuilderSuite(GraphBuilderConfiguration.getEagerDefault()));
    }

    /**
     * Parses a Java method in {@linkplain GraphBuilderConfiguration#getFullDebugDefault() full
     * debug} mode to produce a graph.
     */
    protected StructuredGraph parseDebug(ResolvedJavaMethod m) {
        return parse1(m, getCustomGraphBuilderSuite(GraphBuilderConfiguration.getFullDebugDefault()));
    }

    private StructuredGraph parse1(ResolvedJavaMethod javaMethod, PhaseSuite<HighTierContext> graphBuilderSuite) {
        assert javaMethod.getAnnotation(Test.class) == null : "shouldn't parse method with @Test annotation: " + javaMethod;
        try (Scope ds = Debug.scope("Parsing", javaMethod)) {
            StructuredGraph graph = new StructuredGraph(javaMethod);
            graphBuilderSuite.apply(graph, new HighTierContext(providers, null, null, graphBuilderSuite, OptimisticOptimizations.ALL));
            return graph;
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    protected PhaseSuite<HighTierContext> getDefaultGraphBuilderSuite() {
        // defensive copying
        return backend.getSuites().getDefaultGraphBuilderSuite().copy();
    }

    protected PhaseSuite<HighTierContext> getCustomGraphBuilderSuite(GraphBuilderConfiguration gbConf) {
        PhaseSuite<HighTierContext> suite = getDefaultGraphBuilderSuite().copy();
        ListIterator<BasePhase<? super HighTierContext>> iterator = suite.findPhase(GraphBuilderPhase.class);
        iterator.remove();
        iterator.add(new GraphBuilderPhase(gbConf));
        return suite;
    }

    protected Replacements getReplacements() {
        return getProviders().getReplacements();
    }

    /**
     * Inject a probability for a branch condition into the profiling information of this test case.
     *
     * @param p the probability that cond is true
     * @param cond the condition of the branch
     * @return cond
     */
    protected static boolean branchProbability(double p, boolean cond) {
        return cond;
    }

    /**
     * Inject an iteration count for a loop condition into the profiling information of this test
     * case.
     *
     * @param i the iteration count of the loop
     * @param cond the condition of the loop
     * @return cond
     */
    protected static boolean iterationCount(double i, boolean cond) {
        return cond;
    }

    /**
     * Test if the current test runs on the given platform. The name must match the name given in
     * the {@link Architecture#getName()}.
     *
     * @param name The name to test
     * @return true if we run on the architecture given by name
     */
    protected boolean isArchitecture(String name) {
        return name.equals(backend.getTarget().arch.getName());
    }
}
