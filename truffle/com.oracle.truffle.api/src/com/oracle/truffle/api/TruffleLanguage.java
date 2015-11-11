/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.impl.FindContextNode;
import com.oracle.truffle.api.instrument.ASTProber;
import com.oracle.truffle.api.instrument.Instrumenter;
import com.oracle.truffle.api.instrument.KillException;
import com.oracle.truffle.api.instrument.QuitException;
import com.oracle.truffle.api.instrument.SyntaxTag;
import com.oracle.truffle.api.instrument.Visualizer;
import com.oracle.truffle.api.instrument.WrapperNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

import java.util.Objects;

/**
 * An entry point for everyone who wants to implement a Truffle based language. By providing an
 * implementation of this type and registering it using {@link Registration} annotation, your
 * language becomes accessible to users of the {@link com.oracle.truffle.api.vm.PolyglotEngine
 * polyglot execution engine} - all they will need to do is to include your JAR into their
 * application and all the Truffle goodies (multi-language support, multitenant hosting, debugging,
 * etc.) will be made available to them.
 * <p>
 * The use of {@linkplain Instrumenter Instrument-based services} requires that the language
 * {@linkplain Instrumenter#registerASTProber(com.oracle.truffle.api.instrument.ASTProber) register}
 * an instance of {@link ASTProber} suitable for the language implementation that can be applied to
 * "mark up" each newly created AST with {@link SyntaxTag "tags"} that identify standard syntactic
 * constructs in order to configure tool behavior. See also {@linkplain #createContext(Env)
 * createContext(Env)}.
 *
 * @param <C> internal state of the language associated with every thread that is executing program
 *            {@link #parse(com.oracle.truffle.api.source.Source, com.oracle.truffle.api.nodes.Node, java.lang.String...)
 *            parsed} by the language
 */
@SuppressWarnings("javadoc")
public abstract class TruffleLanguage<C> {
    private final Map<Source, CallTarget> compiled = Collections.synchronizedMap(new WeakHashMap<Source, CallTarget>());

    /**
     * Constructor to be called by subclasses.
     */
    protected TruffleLanguage() {
    }

    /**
     * The annotation to use to register your language to the
     * {@link com.oracle.truffle.api.vm.PolyglotEngine Truffle} system. By annotating your
     * implementation of {@link TruffleLanguage} by this annotation you are just a
     * <em>one JAR drop to the class path</em> away from your users. Once they include your JAR in
     * their application, your language will be available to the
     * {@link com.oracle.truffle.api.vm.PolyglotEngine Truffle virtual machine}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    public @interface Registration {
        /**
         * Unique name of your language. This name will be exposed to users via the
         * {@link com.oracle.truffle.api.vm.PolyglotEngine.Language#getName()} getter.
         *
         * @return identifier of your language
         */
        String name();

        /**
         * Unique string identifying the language version. This name will be exposed to users via
         * the {@link com.oracle.truffle.api.vm.PolyglotEngine.Language#getVersion()} getter.
         *
         * @return version of your language
         */
        String version();

        /**
         * List of MIME types associated with your language. Users will use them (directly or
         * indirectly) when
         * {@link com.oracle.truffle.api.vm.PolyglotEngine#eval(com.oracle.truffle.api.source.Source)
         * executing} their code snippets or their {@link Source files}.
         *
         * @return array of MIME types assigned to your language files
         */
        String[] mimeType();
    }

    /**
     * Creates internal representation of the executing context suitable for given environment. Each
     * time the {@link TruffleLanguage language} is used by a new
     * {@link com.oracle.truffle.api.vm.PolyglotEngine} or in a new thread, the system calls this
     * method to let the {@link TruffleLanguage language} prepare for <em>execution</em>. The
     * returned execution context is completely language specific; it is however expected it will
     * contain reference to here-in provided <code>env</code> and adjust itself according to
     * parameters provided by the <code>env</code> object.
     * <p>
     * The standard way of accessing the here-in generated context is to create a {@link Node} and
     * insert it into own AST hierarchy - use {@link #createFindContextNode()} to obtain the
     * {@link Node findNode} and later {@link #findContext(com.oracle.truffle.api.nodes.Node)
     * findContext(findNode)} to get back your language context.
     *
     * If it is expected that any {@linkplain Instrumenter Instrumentation Services} or tools that
     * depend on those services (e.g. the {@link Debugger}, then part of the preparation in the new
     * context is to
     * {@linkplain Instrumenter#registerASTProber(com.oracle.truffle.api.instrument.ASTProber)
     * register} a "default" {@link ASTProber} for the language implementation. Instrumentation
     * requires that this be available to "mark up" each newly created AST with
     * {@linkplain SyntaxTag "tags"} that identify standard syntactic constructs in order to
     * configure tool behavior.
     *
     * @param env the environment the language is supposed to operate in
     * @return internal data of the language in given environment
     */
    protected abstract C createContext(Env env);

    /**
     * Disposes the context created by
     * {@link #createContext(com.oracle.truffle.api.TruffleLanguage.Env)}. A language can be asked
     * by its user to <em>clean-up</em>. In such case the language is supposed to dispose any
     * resources acquired before and <em>dispose</em> the <code>context</code> - e.g. render it
     * useless for any future calls.
     *
     * @param context the context {@link #createContext(com.oracle.truffle.api.TruffleLanguage.Env)
     *            created by the language}
     */
    protected void disposeContext(C context) {
    }

    /**
     * Parses the provided source and generates appropriate AST. The parsing should execute no user
     * code, it should only create the {@link Node} tree to represent the source. The parsing may be
     * performed in a context (specified as another {@link Node}) or without context. The
     * {@code argumentNames} may contain symbolic names for actual parameters of the call to the
     * returned value. The result should be a call target with method
     * {@link CallTarget#call(java.lang.Object...)} that accepts as many arguments as were provided
     * via the {@code argumentNames} array.
     *
     * @param code source code to parse
     * @param context a {@link Node} defining context for the parsing
     * @param argumentNames symbolic names for parameters of
     *            {@link CallTarget#call(java.lang.Object...)}
     * @return a call target to invoke which also keeps in memory the {@link Node} tree representing
     *         just parsed <code>code</code>
     * @throws IOException thrown when I/O or parsing goes wrong. Here-in thrown exception is
     *             propagate to the user who called one of <code>eval</code> methods of
     *             {@link com.oracle.truffle.api.vm.PolyglotEngine}
     */
    protected abstract CallTarget parse(Source code, Node context, String... argumentNames) throws IOException;

    /**
     * Called when some other language is seeking for a global symbol. This method is supposed to do
     * lazy binding, e.g. there is no need to export symbols in advance, it is fine to wait until
     * somebody asks for it (by calling this method).
     * <p>
     * The exported object can either be <code>TruffleObject</code> (e.g. a native object from the
     * other language) to support interoperability between languages, {@link String} or one of Java
     * primitive wrappers ( {@link Integer}, {@link Double}, {@link Short}, {@link Boolean}, etc.).
     * <p>
     * The way a symbol becomes <em>exported</em> is language dependent. In general it is preferred
     * to make the export explicit - e.g. call some function or method to register an object under
     * specific name. Some languages may however decide to support implicit export of symbols (for
     * example from global scope, if they have one). However explicit exports should always be
     * preferred. Implicitly exported object of some name should only be used when there is no
     * explicit export under such <code>globalName</code>. To ensure so the infrastructure first
     * asks all known languages for <code>onlyExplicit</code> symbols and only when none is found,
     * it does one more round with <code>onlyExplicit</code> set to <code>false</code>.
     *
     * @param globalName the name of the global symbol to find
     * @param onlyExplicit should the language seek for implicitly exported object or only consider
     *            the explicitly exported ones?
     * @return an exported object or <code>null</code>, if the symbol does not represent anything
     *         meaningful in this language
     */
    protected abstract Object findExportedSymbol(C context, String globalName, boolean onlyExplicit);

    /**
     * Returns global object for the language.
     * <p>
     * The object is expected to be <code>TruffleObject</code> (e.g. a native object from the other
     * language) but technically it can be one of Java primitive wrappers ({@link Integer},
     * {@link Double}, {@link Short}, etc.).
     *
     * @return the global object or <code>null</code> if the language does not support such concept
     */
    protected abstract Object getLanguageGlobal(C context);

    /**
     * Checks whether the object is provided by this language.
     *
     * @param object the object to check
     * @return <code>true</code> if this language can deal with such object in native way
     */
    protected abstract boolean isObjectOfLanguage(Object object);

    /**
     * Gets visualization services for language-specific information.
     */
    protected abstract Visualizer getVisualizer();

    /**
     * Returns {@code true} for a node can be "instrumented" by
     * {@linkplain Instrumenter#probe(Node) probing}.
     * <p>
     * <b>Note:</b> instrumentation requires a appropriate {@link WrapperNode}
     *
     * @see WrapperNode
     */
    protected abstract boolean isInstrumentable(Node node);

    /**
     * For nodes in this language that are <em>instrumentable</em>, this method returns an
     * {@linkplain Node AST node} that:
     * <ol>
     * <li>implements {@link WrapperNode};</li>
     * <li>has the node argument as it's child; and</li>
     * <li>whose type is safe for replacement of the node in the parent.</li>
     * </ol>
     *
     * @return an appropriately typed {@link WrapperNode}
     */
    protected abstract WrapperNode createWrapperNode(Node node);

    /**
     * Runs source code in a halted execution context, or at top level.
     *
     * @param source the code to run
     * @param node node where execution halted, {@code null} if no execution context
     * @param mFrame frame where execution halted, {@code null} if no execution context
     * @return result of running the code in the context, or at top level if no execution context.
     * @throws IOException if the evaluation cannot be performed
     */
    protected abstract Object evalInContext(Source source, Node node, MaterializedFrame mFrame) throws IOException;

    /**
     * Generates language specific textual representation of a value. Each language may have special
     * formating conventions - even primitive values may not follow the traditional Java formating
     * rules. As such when
     * {@link com.oracle.truffle.api.vm.PolyglotEngine.Value#as(java.lang.Class)
     * value.as(String.class)} is requested, it consults the language that produced the value by
     * calling this method. By default this method calls {@link Objects#toString(java.lang.Object)}.
     *
     * @param context the execution context for doing the conversion
     * @param value the value to convert. Either primitive type or
     *            {@link com.oracle.truffle.api.interop.TruffleObject}
     * @return textual representation of the value in this language
     */
    protected String toString(C context, Object value) {
        return Objects.toString(value);
    }

    /**
     * Allows a language implementor to create a node that can effectively lookup up the context
     * associated with current execution. The context is created by
     * {@link #createContext(com.oracle.truffle.api.TruffleLanguage.Env)} method.
     *
     * @return node to be inserted into program to effectively find out current execution context
     *         for this language
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected final Node createFindContextNode() {
        final Class<? extends TruffleLanguage<?>> c = (Class<? extends TruffleLanguage<?>>) getClass();
        return new FindContextNode(c);
    }

    /**
     * Uses the {@link #createFindContextNode()} node to obtain the current context. In case you
     * don't care about performance (e.g. your are on a slow execution path), you can chain the
     * calls directly as <code>findContext({@link #createFindContextNode()})</code> and forget the
     * node all together.
     *
     * @param n the node created by this language's {@link #createFindContextNode()}
     * @return the context created by
     *         {@link #createContext(com.oracle.truffle.api.TruffleLanguage.Env)} method at the
     *         beginning of the language execution
     * @throws ClassCastException if the node has not been created by <code>this</code>.
     *             {@link #createFindContextNode()} method.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected final C findContext(Node n) {
        FindContextNode fcn = (FindContextNode) n;
        if (fcn.getLanguageClass() != getClass()) {
            throw new ClassCastException();
        }
        return (C) fcn.executeFindContext();
    }

    private static final class LangCtx<C> {
        final TruffleLanguage<C> lang;
        final C ctx;

        public LangCtx(TruffleLanguage<C> lang, Env env) {
            this.lang = lang;
            // following call verifies that Accessor.CURRENT_VM is provided
            assert API.findLanguage(null, null) == null;
            this.ctx = lang.createContext(env);
        }

        Object findExportedSymbol(String globalName, boolean onlyExplicit) {
            return lang.findExportedSymbol(ctx, globalName, onlyExplicit);
        }

        Object getLanguageGlobal() {
            return lang.getLanguageGlobal(ctx);
        }

        void dispose() {
            lang.disposeContext(ctx);
        }

        String toString(TruffleLanguage<?> language, Object obj) {
            assert lang == language;
            return lang.toString(ctx, obj);
        }
    }

    /**
     * Represents execution environment of the {@link TruffleLanguage}. Each active
     * {@link TruffleLanguage} receives instance of the environment before any code is executed upon
     * it. The environment has knowledge of all active languages and can exchange symbols between
     * them.
     */
    public static final class Env {
        private final Object vm;
        private final TruffleLanguage<?> lang;
        private final LangCtx<?> langCtx;
        private final InputStream in;
        private final OutputStream err;
        private final OutputStream out;
        private final Instrumenter instrumenter;

        Env(Object vm, TruffleLanguage<?> lang, OutputStream out, OutputStream err, InputStream in, Instrumenter instrumenter) {
            this.vm = vm;
            this.in = in;
            this.err = err;
            this.out = out;
            this.lang = lang;
            this.instrumenter = instrumenter;
            this.langCtx = new LangCtx<>(lang, this);
        }

        /**
         * Asks the environment to go through other registered languages and find whether they
         * export global symbol of specified name. The expected return type is either
         * <code>TruffleObject</code>, or one of wrappers of Java primitive types ({@link Integer},
         * {@link Double}).
         *
         * @param globalName the name of the symbol to search for
         * @return object representing the symbol or <code>null</code>
         */
        public Object importSymbol(String globalName) {
            return API.importSymbol(vm, lang, globalName);
        }

        /**
         * Input associated with {@link com.oracle.truffle.api.vm.PolyglotEngine} this language is
         * being executed in.
         *
         * @return reader, never <code>null</code>
         */
        public InputStream in() {
            return in;
        }

        /**
         * Standard output writer for {@link com.oracle.truffle.api.vm.PolyglotEngine} this language
         * is being executed in.
         *
         * @return writer, never <code>null</code>
         */
        public OutputStream out() {
            return out;
        }

        /**
         * Standard error writer for {@link com.oracle.truffle.api.vm.PolyglotEngine} this language
         * is being executed in.
         *
         * @return writer, never <code>null</code>
         */
        public OutputStream err() {
            return err;
        }

        public Instrumenter instrumenter() {
            return instrumenter;
        }
    }

    private static final AccessAPI API = new AccessAPI();

    @SuppressWarnings("rawtypes")
    private static final class AccessAPI extends Accessor {
        @Override
        protected Env attachEnv(Object vm, TruffleLanguage<?> language, OutputStream stdOut, OutputStream stdErr, InputStream stdIn, Instrumenter instrumenter) {
            Env env = new Env(vm, language, stdOut, stdErr, stdIn, instrumenter);
            return env;
        }

        @Override
        protected Object importSymbol(Object vm, TruffleLanguage<?> queryingLang, String globalName) {
            return super.importSymbol(vm, queryingLang, globalName);
        }

        @Override
        protected CallTarget parse(TruffleLanguage<?> truffleLanguage, Source code, Node context, String... argumentNames) throws IOException {
            return truffleLanguage.parse(code, context, argumentNames);
        }

        @Override
        protected Object eval(TruffleLanguage<?> language, Source source) throws IOException {
            CallTarget target = language.compiled.get(source);
            if (target == null) {
                target = language.parse(source, null);
                if (target == null) {
                    throw new IOException("Parsing has not produced a CallTarget for " + source);
                }
                language.compiled.put(source, target);
            }
            try {
                return target.call();
            } catch (KillException | QuitException ex) {
                throw ex;
            } catch (Throwable ex) {
                throw new IOException(ex);
            }
        }

        @Override
        protected Object evalInContext(Object vm, SuspendedEvent ev, String code, FrameInstance frame) throws IOException {
            Node n = ev.getNode();
            if (n == null && frame != null) {
                n = frame.getCallNode();
            }
            if (n == null) {
                throw new IOException("Can't determine language for text \"" + code + "\"");
            }
            RootNode rootNode = n.getRootNode();
            Class<? extends TruffleLanguage> languageType = findLanguage(rootNode);
            final Env env = findLanguage(vm, languageType);
            final TruffleLanguage<?> lang = findLanguage(env);
            final Source source = Source.fromText(code, "eval in context");
            return lang.evalInContext(source, n, frame.getFrame(null, true).materialize());
        }

        @Override
        protected Object findExportedSymbol(TruffleLanguage.Env env, String globalName, boolean onlyExplicit) {
            return env.langCtx.findExportedSymbol(globalName, onlyExplicit);
        }

        @Override
        protected Env findLanguage(Object vm, Class<? extends TruffleLanguage> languageClass) {
            return super.findLanguage(vm, languageClass);
        }

        @Override
        protected TruffleLanguage<?> findLanguage(Env env) {
            return env.lang;
        }

        @Override
        protected Object languageGlobal(TruffleLanguage.Env env) {
            return env.langCtx.getLanguageGlobal();
        }

        @Override
        protected Object findContext(Env env) {
            return env.langCtx.ctx;
        }

        @Override
        protected boolean isInstrumentable(Node node, TruffleLanguage language) {
            return language.isInstrumentable(node);
        }

        @Override
        protected WrapperNode createWrapperNode(Node node, TruffleLanguage language) {
            return language.createWrapperNode(node);
        }

        @Override
        protected void dispose(TruffleLanguage<?> impl, Env env) {
            assert impl == env.langCtx.lang;
            env.langCtx.dispose();
        }

        @Override
        protected String toString(TruffleLanguage<?> language, Env env, Object obj) {
            return env.langCtx.toString(language, obj);
        }
    }

}
