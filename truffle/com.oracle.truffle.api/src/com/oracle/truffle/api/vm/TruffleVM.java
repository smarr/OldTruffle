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
package com.oracle.truffle.api.vm;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLanguage.Registration;
import com.oracle.truffle.api.debug.*;
import com.oracle.truffle.api.impl.*;
import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.source.*;

/**
 * <em>Virtual machine</em> for Truffle based languages. Term virtual machine is a bit overloaded,
 * so don't think of <em>Java virtual machine</em> here - while we are running and using
 * {@link TruffleVM} inside of a <em>JVM</em> there can be multiple instances (some would say
 * tenants) of {@link TruffleVM} running next to each other in a single <em>JVM</em> with a complete
 * mutual isolation. There is 1:N mapping between <em>JVM</em> and {@link TruffleVM}.
 * <p>
 * It would not be correct to think of a {@link TruffleVM} as a runtime for a single Truffle
 * language (Ruby, Python, R, C, JavaScript, etc.) either. {@link TruffleVM} can host as many of
 * Truffle languages as {@link Registration registered on a class path} of your <em>JVM</em>
 * application. {@link TruffleVM} orchestrates these languages, manages exchange of objects and
 * calls among them. While it may happen that there is just one activated language inside of a
 * {@link TruffleVM}, the greatest strength of {@link TruffleVM} is in interoperability between all
 * Truffle languages. There is 1:N mapping between {@link TruffleVM} and {@link TruffleLanguage
 * Truffle language implementations}.
 * <p>
 * Use {@link #newVM()} to create new isolated virtual machine ready for execution of various
 * languages. All the languages in a single virtual machine see each other exported global symbols
 * and can cooperate. Use {@link #newVM()} multiple times to create different, isolated virtual
 * machines completely separated from each other.
 * <p>
 * Once instantiated use {@link #eval(java.net.URI)} with a reference to a file or URL or directly
 * pass code snippet into the virtual machine via {@link #eval(java.lang.String, java.lang.String)}.
 * Support for individual languages is initialized on demand - e.g. once a file of certain MIME type
 * is about to be processed, its appropriate engine (if found), is initialized. Once an engine gets
 * initialized, it remains so, until the virtual machine isn't garbage collected.
 * <p>
 * The <code>TruffleVM</code> is single-threaded and tries to enforce that. It records the thread it
 * has been {@link Builder#build() created} by and checks that all subsequent calls are coming from
 * the same thread. There is 1:1 mapping between {@link TruffleVM} and a thread that can tell it
 * what to do.
 */
@SuppressWarnings("rawtypes")
public final class TruffleVM {
    private static final Logger LOG = Logger.getLogger(TruffleVM.class.getName());
    private static final SPIAccessor SPI = new SPIAccessor();

    static final class LanguageData {
        final String name;
        final String version;
        final Set<String> mimeTypes;
        final TruffleLanguage<?> language;

        LanguageData(String prefix, Properties props) {
            this.name = props.getProperty(prefix + "name");
            this.version = props.getProperty(prefix + "version");

            TreeSet<String> ts = new TreeSet<>();
            for (int i = 0;; i++) {
                String mt = props.getProperty(prefix + "mimeType." + i);
                if (mt == null) {
                    break;
                }
                ts.add(mt);
            }
            this.mimeTypes = Collections.unmodifiableSet(ts);

            String n = props.getProperty(prefix + "className");
            try {
                Class<?> langClazz = Class.forName(n, true, loader());
                this.language = (TruffleLanguage<?>) langClazz.getField("INSTANCE").get(null);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot initialize " + name + " language with implementation " + n, ex);
            }
        }
    }

    private static final List<LanguageData> ALL_LANGUAGE_DATA;
    static {
        ALL_LANGUAGE_DATA = new ArrayList<>();
        Enumeration<URL> en;
        try {
            en = loader().getResources("META-INF/truffle/language");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read list of Truffle languages", ex);
        }
        while (en.hasMoreElements()) {
            URL u = en.nextElement();
            Properties p;
            try {
                p = new Properties();
                try (InputStream is = u.openStream()) {
                    p.load(is);
                }
            } catch (IOException ex) {
                LOG.log(Level.CONFIG, "Cannot process " + u + " as language definition", ex);
                continue;
            }
            for (int cnt = 1;; cnt++) {
                String prefix = "language" + cnt + ".";
                if (p.getProperty(prefix + "name") == null) {
                    break;
                }
                ALL_LANGUAGE_DATA.add(new LanguageData(prefix, p));
            }
        }
    }

    private final Thread initThread;
    private final Map<String, Language> langs;
    private final Reader in;
    private final Writer err;
    private final Writer out;
    private final EventConsumer<?>[] handlers;
    private final Map<String, Object> globals;
    private Debugger debugger;

    /**
     * Private & temporary only constructor.
     */
    private TruffleVM() {
        this.initThread = null;
        this.in = null;
        this.err = null;
        this.out = null;
        this.langs = null;
        this.handlers = null;
        this.globals = null;
    }

    /**
     * Real constructor used from the builder.
     *
     * @param out stdout
     * @param err stderr
     * @param in stdin
     */
    private TruffleVM(Map<String, Object> globals, Writer out, Writer err, Reader in, EventConsumer<?>[] handlers) {
        this.out = out;
        this.err = err;
        this.in = in;
        this.handlers = handlers;
        this.initThread = Thread.currentThread();
        this.globals = new HashMap<>(globals);
        this.langs = new HashMap<>();

        for (LanguageData data : ALL_LANGUAGE_DATA) {
            Language l = new Language(data);
            for (String mimeType : l.getMimeTypes()) {
                langs.put(mimeType, l);
            }
        }
    }

    static ClassLoader loader() {
        ClassLoader l = TruffleVM.class.getClassLoader();
        if (l == null) {
            l = ClassLoader.getSystemClassLoader();
        }
        return l;
    }

    /**
     * Creation of new Truffle virtual machine. Use the {@link Builder} methods to configure your
     * virtual machine and then create one using {@link Builder#build()}:
     *
     * <pre>
     * {@link TruffleVM} vm = {@link TruffleVM}.{@link TruffleVM#newVM() newVM()}
     *     .{@link Builder#stdOut(java.io.Writer) stdOut}({@link Writer yourWriter})
     *     .{@link Builder#stdErr(java.io.Writer) stdErr}({@link Writer yourWriter})
     *     .{@link Builder#stdIn(java.io.Reader) stdIn}({@link Reader yourReader})
     *     .{@link Builder#build() build()};
     * </pre>
     *
     * It searches for {@link Registration languages registered} in the system class loader and
     * makes them available for later evaluation via
     * {@link #eval(java.lang.String, java.lang.String)} methods.
     *
     * @return new, isolated virtual machine with pre-registered languages
     */
    public static TruffleVM.Builder newVM() {
        // making Builder non-static inner class is a
        // nasty trick to avoid the Builder class to appear
        // in Javadoc next to TruffleVM class
        TruffleVM vm = new TruffleVM();
        return vm.new Builder();
    }

    /**
     * Builder for a new {@link TruffleVM}. Call various configuration methods in a chain and at the
     * end create new {@link TruffleVM virtual machine}:
     *
     * <pre>
     * {@link TruffleVM} vm = {@link TruffleVM}.{@link TruffleVM#newVM() newVM()}
     *     .{@link Builder#stdOut(java.io.Writer) stdOut}({@link Writer yourWriter})
     *     .{@link Builder#stdErr(java.io.Writer) stdErr}({@link Writer yourWriter})
     *     .{@link Builder#stdIn(java.io.Reader) stdIn}({@link Reader yourReader})
     *     .{@link Builder#build() build()};
     * </pre>
     */
    public final class Builder {
        private Writer out;
        private Writer err;
        private Reader in;
        private final List<EventConsumer<?>> handlers = new ArrayList<>();
        private final Map<String, Object> globals = new HashMap<>();

        Builder() {
        }

        /**
         * Changes the default output for languages running in <em>to be created</em>
         * {@link TruffleVM virtual machine}. The default is to use {@link System#out}.
         *
         * @param w the writer to use as output
         * @return instance of this builder
         */
        public Builder stdOut(Writer w) {
            out = w;
            return this;
        }

        /**
         * Changes the error output for languages running in <em>to be created</em>
         * {@link TruffleVM virtual machine}. The default is to use {@link System#err}.
         *
         * @param w the writer to use as output
         * @return instance of this builder
         */
        public Builder stdErr(Writer w) {
            err = w;
            return this;
        }

        /**
         * Changes the default input for languages running in <em>to be created</em>
         * {@link TruffleVM virtual machine}. The default is to use {@link System#out}.
         *
         * @param r the reader to use as input
         * @return instance of this builder
         */
        public Builder stdIn(Reader r) {
            in = r;
            return this;
        }

        /**
         * Registers another instance of {@link EventConsumer} into the to be created
         * {@link TruffleVM}.
         *
         * @param handler the handler to register
         * @return instance of this builder
         */
        public Builder onEvent(EventConsumer<?> handler) {
            handler.getClass();
            handlers.add(handler);
            return this;
        }

        /**
         * Adds global named symbol into the configuration of to-be-built {@link TruffleVM}. This
         * symbol will be accessible to all languages via {@link Env#importSymbol(java.lang.String)}
         * and will take precedence over {@link TruffleLanguage#findExportedSymbol symbols exported
         * by languages itself}. Repeated use of <code>globalSymbol</code> is possible; later
         * definition of the same name overrides the previous one.
         * 
         * @param name name of the symbol to register
         * @param obj value of the object - expected to be primitive wrapper, {@link String} or
         *            <code>TruffleObject</code> for mutual inter-operability
         * @return instance of this builder
         * @see TruffleVM#findGlobalSymbol(java.lang.String)
         */
        public Builder globalSymbol(String name, Object obj) {
            globals.put(name, obj);
            return this;
        }

        /**
         * Creates the {@link TruffleVM Truffle virtual machine}. The configuration is taken from
         * values passed into configuration methods in this class.
         *
         * @return new, isolated virtual machine with pre-registered languages
         */
        public TruffleVM build() {
            if (out == null) {
                out = new OutputStreamWriter(System.out);
            }
            if (err == null) {
                err = new OutputStreamWriter(System.err);
            }
            if (in == null) {
                in = new InputStreamReader(System.in);
            }
            return new TruffleVM(globals, out, err, in, handlers.toArray(new EventConsumer[0]));
        }
    }

    /**
     * Descriptions of languages supported in this Truffle virtual machine.
     *
     * @return an immutable map with keys being MIME types and values the {@link Language
     *         descriptions} of associated languages
     */
    public Map<String, Language> getLanguages() {
        return Collections.unmodifiableMap(langs);
    }

    /**
     * Evaluates file located on a given URL. Is equivalent to loading the content of a file and
     * executing it via {@link #eval(java.lang.String, java.lang.String)} with a MIME type guess
     * based on the file's extension and/or content.
     *
     * @param location the location of a file to execute
     * @return result of a processing the file, possibly <code>null</code>
     * @throws IOException exception to signal I/O problems or problems with processing the file's
     *             content
     * @deprecated use {@link #eval(com.oracle.truffle.api.source.Source)}
     */
    @Deprecated
    public Object eval(URI location) throws IOException {
        checkThread();
        Source s;
        String mimeType;
        if (location.getScheme().equals("file")) {
            File file = new File(location);
            s = Source.fromFileName(file.getPath(), true);
            if (file.getName().endsWith(".c")) {
                mimeType = "text/x-c";
            } else if (file.getName().endsWith(".sl")) {
                mimeType = "application/x-sl";
            } else if (file.getName().endsWith(".R") || file.getName().endsWith(".r")) {
                mimeType = "application/x-r";
            } else {
                mimeType = Files.probeContentType(file.toPath());
            }
        } else {
            URL url = location.toURL();
            s = Source.fromURL(url, location.toString());
            URLConnection conn = url.openConnection();
            mimeType = conn.getContentType();
        }
        TruffleLanguage<?> l = getTruffleLang(mimeType);
        if (l == null) {
            throw new IOException("No language for " + location + " with MIME type " + mimeType + " found. Supported types: " + langs.keySet());
        }
        return eval(l, s);
    }

    /**
     * Evaluates code snippet. Chooses a language registered for a given MIME type (throws
     * {@link IOException} if there is none). And passes the specified code to it for execution.
     *
     * @param mimeType MIME type of the code snippet - chooses the right language
     * @param reader the source of code snippet to execute
     * @return result of an execution, possibly <code>null</code>
     * @throws IOException thrown to signal errors while processing the code
     * @deprecated use {@link #eval(com.oracle.truffle.api.source.Source)}
     */
    @Deprecated
    public Object eval(String mimeType, Reader reader) throws IOException {
        checkThread();
        TruffleLanguage<?> l = getTruffleLang(mimeType);
        if (l == null) {
            throw new IOException("No language for MIME type " + mimeType + " found. Supported types: " + langs.keySet());
        }
        return eval(l, Source.fromReader(reader, mimeType));
    }

    /**
     * Evaluates code snippet. Chooses a language registered for a given MIME type (throws
     * {@link IOException} if there is none). And passes the specified code to it for execution.
     *
     * @param mimeType MIME type of the code snippet - chooses the right language
     * @param code the code snippet to execute
     * @return result of an execution, possibly <code>null</code>
     * @throws IOException thrown to signal errors while processing the code
     * @deprecated use {@link #eval(com.oracle.truffle.api.source.Source)}
     */
    @Deprecated
    public Object eval(String mimeType, String code) throws IOException {
        checkThread();
        TruffleLanguage<?> l = getTruffleLang(mimeType);
        if (l == null) {
            throw new IOException("No language for MIME type " + mimeType + " found. Supported types: " + langs.keySet());
        }
        return eval(l, Source.fromText(code, mimeType));
    }

    /**
     * Evaluates provided source. Chooses language registered for a particular
     * {@link Source#getMimeType() MIME type} (throws {@link IOException} if there is none). The
     * language is then allowed to parse and execute the source.
     *
     * @param source code snippet to execute
     * @return result of an execution, possibly <code>null</code>
     * @throws IOException thrown to signal errors while processing the code
     */
    public Object eval(Source source) throws IOException {
        String mimeType = source.getMimeType();
        checkThread();
        TruffleLanguage<?> l = getTruffleLang(mimeType);
        if (l == null) {
            throw new IOException("No language for MIME type " + mimeType + " found. Supported types: " + langs.keySet());
        }
        return eval(l, source);
    }

    private Object eval(TruffleLanguage<?> l, Source s) throws IOException {
        TruffleVM.findDebuggerSupport(l);
        Debugger[] fillIn = {debugger};
        try (Closeable d = SPI.executionStart(this, fillIn, s)) {
            if (debugger == null) {
                debugger = fillIn[0];
            }
            return SPI.eval(l, s);
        }
    }

    /**
     * Looks global symbol provided by one of initialized languages up. First of all execute your
     * program via one of your {@link #eval(java.lang.String, java.lang.String)} and then look
     * expected symbol up using this method.
     * <p>
     * The names of the symbols are language dependent, but for example the Java language bindings
     * follow the specification for method references:
     * <ul>
     * <li>"java.lang.Exception::new" is a reference to constructor of {@link Exception}
     * <li>"java.lang.Integer::valueOf" is a reference to static method in {@link Integer} class
     * </ul>
     * Once an symbol is obtained, it remembers values for fast access and is ready for being
     * invoked.
     *
     * @param globalName the name of the symbol to find
     * @return found symbol or <code>null</code> if it has not been found
     */
    public Symbol findGlobalSymbol(String globalName) {
        checkThread();
        TruffleLanguage<?> lang = null;
        Object obj = globals.get(globalName);
        Object global = null;
        if (obj == null) {
            for (Language dl : langs.values()) {
                TruffleLanguage<?> l = dl.getImpl();
                obj = SPI.findExportedSymbol(dl.env, globalName, true);
                if (obj != null) {
                    lang = l;
                    global = SPI.languageGlobal(dl.env);
                    break;
                }
            }
        }
        if (obj == null) {
            for (Language dl : langs.values()) {
                TruffleLanguage<?> l = dl.getImpl();
                obj = SPI.findExportedSymbol(dl.env, globalName, false);
                if (obj != null) {
                    lang = l;
                    global = SPI.languageGlobal(dl.env);
                    break;
                }
            }
        }
        return obj == null ? null : new Symbol(lang, obj, global);
    }

    private void checkThread() {
        if (initThread != Thread.currentThread()) {
            throw new IllegalStateException("TruffleVM created on " + initThread.getName() + " but used on " + Thread.currentThread().getName());
        }
    }

    private TruffleLanguage<?> getTruffleLang(String mimeType) {
        checkThread();
        Language l = langs.get(mimeType);
        return l == null ? null : l.getImpl();
    }

    @SuppressWarnings("all")
    void dispatch(Object ev) {
        Class type = ev.getClass();
        if (type == SuspendedEvent.class) {
            dispatchSuspendedEvent((SuspendedEvent) ev);
        }
        if (type == ExecutionEvent.class) {
            dispatchExecutionEvent((ExecutionEvent) ev);
        }
        dispatch(type, ev);
    }

    @SuppressWarnings("unused")
    void dispatchSuspendedEvent(SuspendedEvent event) {
    }

    @SuppressWarnings("unused")
    void dispatchExecutionEvent(ExecutionEvent event) {
    }

    @SuppressWarnings("all")
    <Event> void dispatch(Class<Event> type, Event event) {
        for (EventConsumer handler : handlers) {
            if (handler.type == type) {
                handler.on(event);
            }
        }
    }

    /**
     * Represents {@link TruffleVM#findGlobalSymbol(java.lang.String) global symbol} provided by one
     * of the initialized languages in {@link TruffleVM Truffle virtual machine}.
     */
    public class Symbol {
        private final TruffleLanguage<?> language;
        private final Object obj;
        private final Object global;
        private CallTarget target;

        Symbol(TruffleLanguage<?> language, Object obj, Object global) {
            this.language = language;
            this.obj = obj;
            this.global = global;
        }

        /**
         * Invokes the symbol. If the symbol represents a function, then it should be invoked with
         * provided arguments. If the symbol represents a field, then first argument (if provided)
         * should set the value to the field; the return value should be the actual value of the
         * field when the <code>invoke</code> method returns.
         *
         * @param thiz this/self in language that support such concept; use <code>null</code> to let
         *            the language use default this/self or ignore the value
         * @param args arguments to pass when invoking the symbol
         * @return the value returned by invoking the symbol
         * @throws IOException signals problem during execution
         */
        public Object invoke(Object thiz, Object... args) throws IOException {
            checkThread();
            Debugger[] fillIn = {debugger};
            try (Closeable c = SPI.executionStart(TruffleVM.this, fillIn, null)) {
                if (debugger == null) {
                    debugger = fillIn[0];
                }
                List<Object> arr = new ArrayList<>();
                if (thiz == null) {
                    if (global != null) {
                        arr.add(global);
                    }
                } else {
                    arr.add(thiz);
                }
                arr.addAll(Arrays.asList(args));
                for (;;) {
                    if (target == null) {
                        target = SPI.createCallTarget(language, obj, arr.toArray());
                    }
                    try {
                        return target.call(arr.toArray());
                    } catch (SymbolInvoker.ArgumentsMishmashException ex) {
                        target = null;
                    }
                }
            }
        }
    }

    /**
     * Description of a language registered in {@link TruffleVM Truffle virtual machine}. Languages
     * are registered by {@link Registration} annotation which stores necessary information into a
     * descriptor inside of the language's JAR file. When a new {@link TruffleVM} is created, it
     * reads all available descriptors and creates {@link Language} objects to represent them. One
     * can obtain a {@link #getName() name} or list of supported {@link #getMimeTypes() MIME types}
     * for each language. The actual language implementation is not initialized until
     * {@link TruffleVM#eval(java.lang.String, java.lang.String) a code is evaluated} in it.
     */
    public final class Language {
        private final LanguageData data;
        private TruffleLanguage<?> impl;
        private TruffleLanguage.Env env;
        private String shortName;

        Language(LanguageData data) {
            this.data = data;
        }

        /**
         * MIME types recognized by the language.
         *
         * @return returns immutable set of recognized MIME types
         */
        public Set<String> getMimeTypes() {
            return data.mimeTypes;
        }

        /**
         * Human readable name of the language. Think of C, Ruby, JS, etc.
         *
         * @return string giving the language a name
         */
        public String getName() {
            return data.name;
        }

        /**
         * Name of the language version.
         *
         * @return string specifying the language version
         */
        public String getVersion() {
            return data.version;
        }

        /**
         * Human readable string that identifies the language and version.
         *
         * @return string describing the specific language version
         */
        public String getShortName() {
            if (shortName == null) {
                shortName = getName() + "(" + getVersion() + ")";
            }
            return shortName;
        }

        TruffleLanguage<?> getImpl() {
            if (impl == null) {
                try {
                    TruffleLanguage<?> language = data.language;
                    impl = language;
                    env = SPI.attachEnv(TruffleVM.this, language, out, err, in);
                } catch (Exception ex) {
                    throw new IllegalStateException("Cannot initialize " + getShortName() + " language with implementation " + data.language.getClass().getName(), ex);
                }
            }
            return impl;
        }

        @Override
        public String toString() {
            return "[" + getShortName() + " for " + getMimeTypes() + "]";
        }
    } // end of Language

    //
    // Accessor helper methods
    //

    TruffleLanguage<?> findLanguage(Probe probe) {
        Class<? extends TruffleLanguage> languageClazz = SPI.findLanguage(probe);
        for (Map.Entry<String, Language> entrySet : langs.entrySet()) {
            Language languageDescription = entrySet.getValue();
            if (languageClazz.isInstance(languageDescription.impl)) {
                return languageDescription.impl;
            }
        }
        throw new IllegalStateException("Cannot find language " + languageClazz + " among " + langs);
    }

    Env findEnv(Class<? extends TruffleLanguage> languageClazz) {
        for (Map.Entry<String, Language> entrySet : langs.entrySet()) {
            Language languageDescription = entrySet.getValue();
            if (languageClazz.isInstance(languageDescription.impl)) {
                return languageDescription.env;
            }
        }
        throw new IllegalStateException("Cannot find language " + languageClazz + " among " + langs);
    }

    static DebugSupportProvider findDebuggerSupport(TruffleLanguage<?> l) {
        return SPI.getDebugSupport(l);
    }

    private static class SPIAccessor extends Accessor {
        @Override
        public Object importSymbol(TruffleVM vm, TruffleLanguage<?> ownLang, String globalName) {
            Object g = vm.globals.get(globalName);
            if (g != null) {
                return g;
            }
            Set<Language> uniqueLang = new LinkedHashSet<>(vm.langs.values());
            for (Language dl : uniqueLang) {
                TruffleLanguage<?> l = dl.getImpl();
                if (l == ownLang) {
                    continue;
                }
                Object obj = SPI.findExportedSymbol(dl.env, globalName, true);
                if (obj != null) {
                    return obj;
                }
            }
            for (Language dl : uniqueLang) {
                TruffleLanguage<?> l = dl.getImpl();
                if (l == ownLang) {
                    continue;
                }
                Object obj = SPI.findExportedSymbol(dl.env, globalName, false);
                if (obj != null) {
                    return obj;
                }
            }
            return null;
        }

        @Override
        public Env attachEnv(TruffleVM vm, TruffleLanguage<?> language, Writer stdOut, Writer stdErr, Reader stdIn) {
            return super.attachEnv(vm, language, stdOut, stdErr, stdIn);
        }

        @Override
        public Object eval(TruffleLanguage<?> l, Source s) throws IOException {
            return super.eval(l, s);
        }

        @Override
        public Object findExportedSymbol(TruffleLanguage.Env env, String globalName, boolean onlyExplicit) {
            return super.findExportedSymbol(env, globalName, onlyExplicit);
        }

        @Override
        protected Object languageGlobal(TruffleLanguage.Env env) {
            return super.languageGlobal(env);
        }

        @Override
        protected CallTarget createCallTarget(TruffleLanguage<?> lang, Object obj, Object[] args) throws IOException {
            return super.createCallTarget(lang, obj, args);
        }

        @Override
        public ToolSupportProvider getToolSupport(TruffleLanguage<?> l) {
            return super.getToolSupport(l);
        }

        @Override
        public DebugSupportProvider getDebugSupport(TruffleLanguage<?> l) {
            return super.getDebugSupport(l);
        }

        @Override
        protected Class<? extends TruffleLanguage> findLanguage(Probe probe) {
            return super.findLanguage(probe);
        }

        @Override
        protected Env findLanguage(TruffleVM vm, Class<? extends TruffleLanguage> languageClass) {
            return vm.findEnv(languageClass);
        }

        @Override
        protected Closeable executionStart(TruffleVM aThis, Debugger[] fillIn, Source s) {
            return super.executionStart(aThis, fillIn, s);
        }

        @Override
        protected void dispatchEvent(TruffleVM vm, Object event) {
            vm.dispatch(event);
        }
    } // end of SPIAccessor
}
