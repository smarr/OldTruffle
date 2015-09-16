/*
 * Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.sl;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebugSupportException;
import com.oracle.truffle.api.debug.DebugSupportProvider;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.UnsupportedSpecializationException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrument.ASTProber;
import com.oracle.truffle.api.instrument.AdvancedInstrumentResultListener;
import com.oracle.truffle.api.instrument.AdvancedInstrumentRootFactory;
import com.oracle.truffle.api.instrument.Probe;
import com.oracle.truffle.api.instrument.ToolSupportProvider;
import com.oracle.truffle.api.instrument.Visualizer;
import com.oracle.truffle.api.nodes.GraphPrintVisitor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.vm.TruffleVM;
import com.oracle.truffle.api.vm.TruffleVM.Symbol;
import com.oracle.truffle.sl.builtins.SLBuiltinNode;
import com.oracle.truffle.sl.builtins.SLDefineFunctionBuiltin;
import com.oracle.truffle.sl.builtins.SLNanoTimeBuiltin;
import com.oracle.truffle.sl.builtins.SLPrintlnBuiltin;
import com.oracle.truffle.sl.builtins.SLReadlnBuiltin;
import com.oracle.truffle.sl.nodes.SLRootNode;
import com.oracle.truffle.sl.nodes.SLTypes;
import com.oracle.truffle.sl.nodes.call.SLDispatchNode;
import com.oracle.truffle.sl.nodes.call.SLInvokeNode;
import com.oracle.truffle.sl.nodes.call.SLUndefinedFunctionException;
import com.oracle.truffle.sl.nodes.controlflow.SLBlockNode;
import com.oracle.truffle.sl.nodes.controlflow.SLBreakNode;
import com.oracle.truffle.sl.nodes.controlflow.SLContinueNode;
import com.oracle.truffle.sl.nodes.controlflow.SLIfNode;
import com.oracle.truffle.sl.nodes.controlflow.SLReturnNode;
import com.oracle.truffle.sl.nodes.controlflow.SLWhileNode;
import com.oracle.truffle.sl.nodes.expression.SLAddNode;
import com.oracle.truffle.sl.nodes.expression.SLBigIntegerLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLDivNode;
import com.oracle.truffle.sl.nodes.expression.SLEqualNode;
import com.oracle.truffle.sl.nodes.expression.SLFunctionLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLLessOrEqualNode;
import com.oracle.truffle.sl.nodes.expression.SLLessThanNode;
import com.oracle.truffle.sl.nodes.expression.SLLogicalAndNode;
import com.oracle.truffle.sl.nodes.expression.SLLogicalOrNode;
import com.oracle.truffle.sl.nodes.expression.SLMulNode;
import com.oracle.truffle.sl.nodes.expression.SLStringLiteralNode;
import com.oracle.truffle.sl.nodes.expression.SLSubNode;
import com.oracle.truffle.sl.nodes.instrument.SLDefaultVisualizer;
import com.oracle.truffle.sl.nodes.instrument.SLStandardASTProber;
import com.oracle.truffle.sl.nodes.local.SLReadLocalVariableNode;
import com.oracle.truffle.sl.nodes.local.SLWriteLocalVariableNode;
import com.oracle.truffle.sl.parser.Parser;
import com.oracle.truffle.sl.parser.SLNodeFactory;
import com.oracle.truffle.sl.parser.Scanner;
import com.oracle.truffle.sl.runtime.SLContext;
import com.oracle.truffle.sl.runtime.SLFunction;
import com.oracle.truffle.sl.runtime.SLFunctionRegistry;
import com.oracle.truffle.sl.runtime.SLNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * SL is a simple language to demonstrate and showcase features of Truffle. The implementation is as
 * simple and clean as possible in order to help understanding the ideas and concepts of Truffle.
 * The language has first class functions, but no object model.
 * <p>
 * SL is dynamically typed, i.e., there are no type names specified by the programmer. SL is
 * strongly typed, i.e., there is no automatic conversion between types. If an operation is not
 * available for the types encountered at run time, a type error is reported and execution is
 * stopped. For example, {@code 4 - "2"} results in a type error because subtraction is only defined
 * for numbers.
 *
 * <p>
 * <b>Types:</b>
 * <ul>
 * <li>Number: arbitrary precision integer numbers. The implementation uses the Java primitive type
 * {@code long} to represent numbers that fit into the 64 bit range, and {@link BigInteger} for
 * numbers that exceed the range. Using a primitive type such as {@code long} is crucial for
 * performance.
 * <li>Boolean: implemented as the Java primitive type {@code boolean}.
 * <li>String: implemented as the Java standard type {@link String}.
 * <li>Function: implementation type {@link SLFunction}.
 * <li>Null (with only one value {@code null}): implemented as the singleton
 * {@link SLNull#SINGLETON}.
 * </ul>
 * The class {@link SLTypes} lists these types for the Truffle DSL, i.e., for type-specialized
 * operations that are specified using Truffle DSL annotations.
 *
 * <p>
 * <b>Language concepts:</b>
 * <ul>
 * <li>Literals for {@link SLBigIntegerLiteralNode numbers} , {@link SLStringLiteralNode strings},
 * and {@link SLFunctionLiteralNode functions}.
 * <li>Basic arithmetic, logical, and comparison operations: {@link SLAddNode +}, {@link SLSubNode
 * -}, {@link SLMulNode *}, {@link SLDivNode /}, {@link SLLogicalAndNode logical and},
 * {@link SLLogicalOrNode logical or}, {@link SLEqualNode ==}, !=, {@link SLLessThanNode &lt;},
 * {@link SLLessOrEqualNode &le;}, &gt;, &ge;.
 * <li>Local variables: local variables must be defined (via a {@link SLWriteLocalVariableNode
 * write}) before they can be used (by a {@link SLReadLocalVariableNode read}). Local variables are
 * not visible outside of the block where they were first defined.
 * <li>Basic control flow statements: {@link SLBlockNode blocks}, {@link SLIfNode if},
 * {@link SLWhileNode while} with {@link SLBreakNode break} and {@link SLContinueNode continue},
 * {@link SLReturnNode return}.
 * <li>Function calls: {@link SLInvokeNode invocations} are efficiently implemented with
 * {@link SLDispatchNode polymorphic inline caches}.
 * </ul>
 *
 * <p>
 * <b>Syntax and parsing:</b><br>
 * The syntax is described as an attributed grammar. The {@link Parser} and {@link Scanner} are
 * automatically generated by the parser generator Coco/R (available from <a
 * href="http://ssw.jku.at/coco/">http://ssw.jku.at/coco/</a>). The grammar contains semantic
 * actions that build the AST for a method. To keep these semantic actions short, they are mostly
 * calls to the {@link SLNodeFactory} that performs the actual node creation. All functions found in
 * the SL source are added to the {@link SLFunctionRegistry}, which is accessible from the
 * {@link SLContext}.
 *
 * <p>
 * <b>Builtin functions:</b><br>
 * Library functions that are available to every SL source without prior definition are called
 * builtin functions. They are added to the {@link SLFunctionRegistry} when the {@link SLContext} is
 * created. There current builtin functions are
 * <ul>
 * <li>{@link SLReadlnBuiltin readln}: Read a String from the {@link SLContext#getInput() standard
 * input}.
 * <li>{@link SLPrintlnBuiltin println}: Write a value to the {@link SLContext#getOutput() standard
 * output}.
 * <li>{@link SLNanoTimeBuiltin nanoTime}: Returns the value of a high-resolution time, in
 * nanoseconds.
 * <li>{@link SLDefineFunctionBuiltin defineFunction}: Parses the functions provided as a String
 * argument and adds them to the function registry. Functions that are already defined are replaced
 * with the new version.
 * </ul>
 */

/*
 * 
 * <p> <b>Tools:</b><br> The use of some of Truffle's support for developer tools (based on the
 * Truffle Instrumentation Framework) are demonstrated in this file, for example: <ul> <li>a
 * {@linkplain NodeExecCounter counter for node executions}, tabulated by node type; and</li> <li>a
 * simple {@linkplain CoverageTracker code coverage engine}.</li> </ul> In each case, the tool is
 * enabled if a corresponding local boolean variable in this file is set to {@code true}. Results
 * are printed at the end of the execution using each tool's <em>default printer</em>.
 */
@TruffleLanguage.Registration(name = "SL", version = "0.5", mimeType = "application/x-sl")
public final class SLLanguage extends TruffleLanguage<SLContext> {
    private static List<NodeFactory<? extends SLBuiltinNode>> builtins = Collections.emptyList();
    private static Visualizer visualizer = new SLDefaultVisualizer();
    private static ASTProber registeredASTProber; // non-null if prober already registered
    private DebugSupportProvider debugSupport;

    private SLLanguage() {
    }

    public static final SLLanguage INSTANCE = new SLLanguage();

    @Override
    protected SLContext createContext(Env env) {
        SLContext context = new SLContext(this, new BufferedReader(env.stdIn()), new PrintWriter(env.stdOut(), true));
        for (NodeFactory<? extends SLBuiltinNode> builtin : builtins) {
            context.installBuiltin(builtin, true);
        }
        return context;
    }

    /* Small tools that can be installed for demonstration */
    // private static NodeExecCounter nodeExecCounter = null;
    // private static NodeExecCounter statementExecCounter = null;
    // private static CoverageTracker coverageTracker = null;

    /**
     * The main entry point. Use the mx command "mx sl" to run it with the correct class path setup.
     */
    public static void main(String[] args) throws IOException {
        TruffleVM vm = TruffleVM.newVM().build();
        assert vm.getLanguages().containsKey("application/x-sl");

        setupToolDemos();

        int repeats = 1;
        if (args.length >= 2) {
            repeats = Integer.parseInt(args[1]);
        }

        Source source;
        if (args.length == 0) {
            source = Source.fromReader(new InputStreamReader(System.in), "<stdin>").withMimeType("application/x-sl");
        } else {
            source = Source.fromFileName(args[0]);
        }
        vm.eval(source);
        Symbol main = vm.findGlobalSymbol("main");
        if (main == null) {
            throw new SLException("No function main() defined in SL source file.");
        }
        while (repeats-- > 0) {
            main.invoke(null);
        }
        reportToolDemos();
    }

    /**
     * Temporary method during API evolution, supports debugger integration.
     */
    public static void run(Source source) throws IOException {
        TruffleVM vm = TruffleVM.newVM().build();
        assert vm.getLanguages().containsKey("application/x-sl");
        vm.eval(source);
        Symbol main = vm.findGlobalSymbol("main");
        if (main == null) {
            throw new SLException("No function main() defined in SL source file.");
        }
        main.invoke(null);
    }

    /**
     * Parse and run the specified SL source. Factored out in a separate method so that it can also
     * be used by the unit test harness.
     */
    public static long run(TruffleVM context, Path path, PrintWriter logOutput, PrintWriter out, int repeats, List<NodeFactory<? extends SLBuiltinNode>> currentBuiltins) throws IOException {
        builtins = currentBuiltins;

        if (logOutput != null) {
            logOutput.println("== running on " + Truffle.getRuntime().getName());
            // logOutput.println("Source = " + source.getCode());
        }

        Source src = Source.fromFileName(path.toString());
        /* Parse the SL source file. */
        Object result = context.eval(src.withMimeType("application/x-sl")).get();
        if (result != null) {
            out.println(result);
        }

        /* Lookup our main entry point, which is per definition always named "main". */
        Symbol main = context.findGlobalSymbol("main");
        if (main == null) {
            throw new SLException("No function main() defined in SL source file.");
        }

        /* Change to true if you want to see the AST on the console. */
        boolean printASTToLog = false;
        /* Change to true if you want to see source attribution for the AST to the console */
        boolean printSourceAttributionToLog = false;
        /* Change to dump the AST to IGV over the network. */
        boolean dumpASTToIGV = false;

        printScript("before execution", null, logOutput, printASTToLog, printSourceAttributionToLog, dumpASTToIGV);
        long totalRuntime = 0;
        try {
            for (int i = 0; i < repeats; i++) {
                long start = System.nanoTime();
                /* Call the main entry point, without any arguments. */
                try {
                    result = main.invoke(null).get();
                    if (result != null) {
                        out.println(result);
                    }
                } catch (UnsupportedSpecializationException ex) {
                    out.println(formatTypeError(ex));
                } catch (SLUndefinedFunctionException ex) {
                    out.println(String.format("Undefined function: %s", ex.getFunctionName()));
                }
                long end = System.nanoTime();
                totalRuntime += end - start;

                if (logOutput != null && repeats > 1) {
                    logOutput.println("== iteration " + (i + 1) + ": " + ((end - start) / 1000000) + " ms");
                }
            }

        } finally {
            printScript("after execution", null, logOutput, printASTToLog, printSourceAttributionToLog, dumpASTToIGV);
        }
        return totalRuntime;
    }

    /**
     * When dumpASTToIGV is true: dumps the AST of all functions to the IGV visualizer, via a socket
     * connection. IGV can be started with the mx command "mx igv".
     * <p>
     * When printASTToLog is true: prints the ASTs to the console.
     */
    private static void printScript(String groupName, SLContext context, PrintWriter logOutput, boolean printASTToLog, boolean printSourceAttributionToLog, boolean dumpASTToIGV) {
        if (dumpASTToIGV) {
            GraphPrintVisitor graphPrinter = new GraphPrintVisitor();
            graphPrinter.beginGroup(groupName);
            for (SLFunction function : context.getFunctionRegistry().getFunctions()) {
                RootCallTarget callTarget = function.getCallTarget();
                if (callTarget != null) {
                    graphPrinter.beginGraph(function.toString()).visit(callTarget.getRootNode());
                }
            }
            graphPrinter.printToNetwork(true);
        }
        if (printASTToLog && logOutput != null) {
            for (SLFunction function : context.getFunctionRegistry().getFunctions()) {
                RootCallTarget callTarget = function.getCallTarget();
                if (callTarget != null) {
                    logOutput.println("=== " + function);
                    NodeUtil.printTree(logOutput, callTarget.getRootNode());
                }
            }
        }
        if (printSourceAttributionToLog && logOutput != null) {
            for (SLFunction function : context.getFunctionRegistry().getFunctions()) {
                RootCallTarget callTarget = function.getCallTarget();
                if (callTarget != null) {
                    logOutput.println("=== " + function);
                    NodeUtil.printSourceAttributionTree(logOutput, callTarget.getRootNode());
                }
            }
        }
    }

    /**
     * Provides a user-readable message for run-time type errors. SL is strongly typed, i.e., there
     * are no automatic type conversions of values. Therefore, Truffle does the type checking for
     * us: if no matching node specialization for the actual values is found, then we have a type
     * error. Specialized nodes use the {@link UnsupportedSpecializationException} to report that no
     * specialization was found. We therefore just have to convert the information encapsulated in
     * this exception in a user-readable form.
     */
    private static String formatTypeError(UnsupportedSpecializationException ex) {
        StringBuilder result = new StringBuilder();
        result.append("Type error");
        if (ex.getNode() != null && ex.getNode().getSourceSection() != null) {
            SourceSection ss = ex.getNode().getSourceSection();
            if (ss != null && ss.getSource() != null) {
                result.append(" at ").append(ss.getSource().getShortName()).append(" line ").append(ss.getStartLine()).append(" col ").append(ss.getStartColumn());
            }
        }
        result.append(": operation");
        if (ex.getNode() != null) {
            NodeInfo nodeInfo = SLContext.lookupNodeInfo(ex.getNode().getClass());
            if (nodeInfo != null) {
                result.append(" \"").append(nodeInfo.shortName()).append("\"");
            }
        }
        result.append(" not defined for");

        String sep = " ";
        for (int i = 0; i < ex.getSuppliedValues().length; i++) {
            Object value = ex.getSuppliedValues()[i];
            Node node = ex.getSuppliedNodes()[i];
            if (node != null) {
                result.append(sep);
                sep = ", ";

                if (value instanceof Long || value instanceof BigInteger) {
                    result.append("Number ").append(value);
                } else if (value instanceof Boolean) {
                    result.append("Boolean ").append(value);
                } else if (value instanceof String) {
                    result.append("String \"").append(value).append("\"");
                } else if (value instanceof SLFunction) {
                    result.append("Function ").append(value);
                } else if (value == SLNull.SINGLETON) {
                    result.append("NULL");
                } else if (value == null) {
                    // value is not evaluated because of short circuit evaluation
                    result.append("ANY");
                } else {
                    result.append(value);
                }
            }
        }
        return result.toString();
    }

    @Override
    protected CallTarget parse(Source code, Node node, String... argumentNames) throws IOException {
        final SLContext c = new SLContext(this);
        final Exception[] failed = {null};
        try {
            c.evalSource(code);
            failed[0] = null;
        } catch (Exception e) {
            failed[0] = e;
        }
        return new CallTarget() {
            @Override
            public Object call(Object... arguments) {
                if (failed[0] instanceof RuntimeException) {
                    throw (RuntimeException) failed[0];
                }
                if (failed[0] != null) {
                    throw new IllegalStateException(failed[0]);
                }
                Node n = createFindContextNode();
                SLContext fillIn = findContext(n);
                final SLFunctionRegistry functionRegistry = fillIn.getFunctionRegistry();
                for (SLFunction f : c.getFunctionRegistry().getFunctions()) {
                    RootCallTarget callTarget = f.getCallTarget();
                    if (callTarget == null) {
                        continue;
                    }
                    functionRegistry.lookup(f.getName());
                    functionRegistry.register(f.getName(), (SLRootNode) f.getCallTarget().getRootNode());
                }
                return null;
            }
        };
    }

    @Override
    protected Object findExportedSymbol(SLContext context, String globalName, boolean onlyExplicit) {
        for (SLFunction f : context.getFunctionRegistry().getFunctions()) {
            if (globalName.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    @Override
    protected Object getLanguageGlobal(SLContext context) {
        return context;
    }

    @Override
    protected boolean isObjectOfLanguage(Object object) {
        return object instanceof SLFunction;
    }

    @Override
    protected ToolSupportProvider getToolSupport() {
        return getDebugSupport();
    }

    @Override
    protected DebugSupportProvider getDebugSupport() {
        if (debugSupport == null) {
            debugSupport = new SLDebugProvider();
        }
        return debugSupport;
    }

    // TODO (mlvdv) remove the static hack when we no longer have the static demo variables
    private static void setupToolDemos() {
        // if (statementCounts || coverage) {
        // if (registeredASTProber == null) {
        // final ASTProber newProber = new SLStandardASTProber();
        // // This should be registered on the TruffleVM
        // Probe.registerASTProber(newProber);
        // registeredASTProber = newProber;
        // }
        // }
        // if (nodeExecCounts) {
        // nodeExecCounter = new NodeExecCounter();
        // nodeExecCounter.install();
        // }
        //
        // if (statementCounts) {
        // statementExecCounter = new NodeExecCounter(StandardSyntaxTag.STATEMENT);
        // statementExecCounter.install();
        // }
        //
        // if (coverage) {
        // coverageTracker = new CoverageTracker();
        // coverageTracker.install();
        // }
    }

    private static void reportToolDemos() {
        // if (nodeExecCounter != null) {
        // nodeExecCounter.print(System.out);
        // nodeExecCounter.dispose();
        // }
        // if (statementExecCounter != null) {
        // statementExecCounter.print(System.out);
        // statementExecCounter.dispose();
        // }
        // if (coverageTracker != null) {
        // coverageTracker.print(System.out);
        // coverageTracker.dispose();
        // }
    }

    public Node createFindContextNode0() {
        return createFindContextNode();
    }

    public SLContext findContext0(Node contextNode) {
        return findContext(contextNode);
    }

    private final class SLDebugProvider implements DebugSupportProvider {

        public SLDebugProvider() {
            if (registeredASTProber == null) {
                registeredASTProber = new SLStandardASTProber();
                // This should be registered on the TruffleVM
                Probe.registerASTProber(registeredASTProber);
            }
        }

        public Visualizer getVisualizer() {
            if (visualizer == null) {
                visualizer = new SLDefaultVisualizer();
            }
            return visualizer;
        }

        public void enableASTProbing(ASTProber prober) {
            if (prober != null) {
                // This should be registered on the TruffleVM
                Probe.registerASTProber(prober);
            }
        }

        public Object evalInContext(Source source, Node node, MaterializedFrame mFrame) throws DebugSupportException {
            throw new DebugSupportException("evalInContext not supported in this language");
        }

        public AdvancedInstrumentRootFactory createAdvancedInstrumentRootFactory(String expr, AdvancedInstrumentResultListener resultListener) throws DebugSupportException {
            throw new DebugSupportException("createAdvancedInstrumentRootFactory not supported in this language");
        }

    }

}
