/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.test.vm;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.debug.*;
import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.*;
import com.oracle.truffle.api.vm.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.*;
import static org.junit.Assert.*;

public class ImplicitExplicitExportTest {
    private static Thread mainThread;
    private Portaal vm;

    @Before
    public void initializeVM() {
        mainThread = Thread.currentThread();
        vm = Portaal.createNew().executor(Executors.newSingleThreadExecutor()).build();
        assertTrue("Found " + L1 + " language", vm.getLanguages().containsKey(L1));
        assertTrue("Found " + L2 + " language", vm.getLanguages().containsKey(L2));
        assertTrue("Found " + L3 + " language", vm.getLanguages().containsKey(L3));
    }

    @After
    public void cleanThread() {
        mainThread = null;
    }

    @Test
    public void explicitExportFound() throws IOException {
        // @formatter:off
        vm.eval(Source.fromText("explicit.ahoj=42", "Fourty two").withMimeType(L1));
        Object ret = vm.eval(
            Source.fromText("return=ahoj", "Return").withMimeType(L3)
        ).get();
        // @formatter:on
        assertEquals("42", ret);
    }

    @Test
    public void implicitExportFound() throws IOException {
        // @formatter:off
        vm.eval(
            Source.fromText("implicit.ahoj=42", "Fourty two").withMimeType(L1)
        );
        Object ret = vm.eval(
            Source.fromText("return=ahoj", "Return").withMimeType(L3)
        ).get();
        // @formatter:on
        assertEquals("42", ret);
    }

    @Test
    public void explicitExportPreferred2() throws IOException {
        // @formatter:off
        vm.eval(
            Source.fromText("implicit.ahoj=42", "Fourty two").withMimeType(L1)
        );
        vm.eval(
            Source.fromText("explicit.ahoj=43", "Fourty three").withMimeType(L2)
        );
        Object ret = vm.eval(
            Source.fromText("return=ahoj", "Return").withMimeType(L3)
        ).get();
        // @formatter:on
        assertEquals("Explicit import from L2 is used", "43", ret);
        assertEquals("Global symbol is also 43", "43", vm.findGlobalSymbol("ahoj").get());
    }

    @Test
    public void explicitExportPreferred1() throws IOException {
        // @formatter:off
        vm.eval(
            Source.fromText("explicit.ahoj=43", "Fourty three").withMimeType(L1)
        );
        vm.eval(
            Source.fromText("implicit.ahoj=42", "Fourty two").withMimeType(L2)
        );
        Object ret = vm.eval(
            Source.fromText("return=ahoj", "Return").withMimeType(L3)
        ).get();
        // @formatter:on
        assertEquals("Explicit import from L2 is used", "43", ret);
        assertEquals("Global symbol is also 43", "43", vm.findGlobalSymbol("ahoj").invoke(null).get());
    }

    private static final class Ctx {
        final Map<String, String> explicit = new HashMap<>();
        final Map<String, String> implicit = new HashMap<>();
        final Env env;

        public Ctx(Env env) {
            this.env = env;
        }
    }

    private abstract static class AbstractExportImportLanguage extends TruffleLanguage<Ctx> {

        @Override
        protected Ctx createContext(Env env) {
            if (mainThread != null) {
                assertNotEquals("Should run asynchronously", Thread.currentThread(), mainThread);
            }
            return new Ctx(env);
        }

        @Override
        protected CallTarget parse(Source code, Node context, String... argumentNames) throws IOException {
            if (code.getCode().startsWith("parse=")) {
                throw new IOException(code.getCode().substring(6));
            }
            return new ValueCallTarget(code, this);
        }

        @Override
        protected Object findExportedSymbol(Ctx context, String globalName, boolean onlyExplicit) {
            if (context.explicit.containsKey(globalName)) {
                return context.explicit.get(globalName);
            }
            if (!onlyExplicit && context.implicit.containsKey(globalName)) {
                return context.implicit.get(globalName);
            }
            return null;
        }

        @Override
        protected Object getLanguageGlobal(Ctx context) {
            return null;
        }

        @Override
        protected boolean isObjectOfLanguage(Object object) {
            return false;
        }

        @Override
        protected ToolSupportProvider getToolSupport() {
            return null;
        }

        @Override
        protected DebugSupportProvider getDebugSupport() {
            return null;
        }

        private Object importExport(Source code) {
            assertNotEquals("Should run asynchronously", Thread.currentThread(), mainThread);
            final Node node = createFindContextNode();
            Ctx ctx = findContext(node);
            Properties p = new Properties();
            try (Reader r = code.getReader()) {
                p.load(r);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            Enumeration<Object> en = p.keys();
            while (en.hasMoreElements()) {
                Object n = en.nextElement();
                if (n instanceof String) {
                    String k = (String) n;
                    if (k.startsWith("explicit.")) {
                        ctx.explicit.put(k.substring(9), p.getProperty(k));
                    }
                    if (k.startsWith("implicit.")) {
                        ctx.implicit.put(k.substring(9), p.getProperty(k));
                    }
                    if (k.equals("return")) {
                        return ctx.env.importSymbol(p.getProperty(k));
                    }
                }
            }
            return null;
        }
    }

    private static final class ValueCallTarget implements RootCallTarget {
        private final Source code;
        private final AbstractExportImportLanguage language;

        private ValueCallTarget(Source code, AbstractExportImportLanguage language) {
            this.code = code;
            this.language = language;
        }

        @Override
        public RootNode getRootNode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object call(Object... arguments) {
            return language.importExport(code);
        }
    }

    static final String L1 = "application/x-test-import-export-1";
    static final String L2 = "application/x-test-import-export-2";
    static final String L3 = "application/x-test-import-export-3";

    @TruffleLanguage.Registration(mimeType = L1, name = "ImportExport1", version = "0")
    public static final class ExportImportLanguage1 extends AbstractExportImportLanguage {
        public static final AbstractExportImportLanguage INSTANCE = new ExportImportLanguage1();

        public ExportImportLanguage1() {
        }
    }

    @TruffleLanguage.Registration(mimeType = L2, name = "ImportExport2", version = "0")
    public static final class ExportImportLanguage2 extends AbstractExportImportLanguage {
        public static final AbstractExportImportLanguage INSTANCE = new ExportImportLanguage2();

        public ExportImportLanguage2() {
        }
    }

    @TruffleLanguage.Registration(mimeType = L3, name = "ImportExport3", version = "0")
    public static final class ExportImportLanguage3 extends AbstractExportImportLanguage {
        public static final AbstractExportImportLanguage INSTANCE = new ExportImportLanguage3();

        private ExportImportLanguage3() {
        }
    }

}
