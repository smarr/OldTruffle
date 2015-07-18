/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.dsl.test;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.debug.DebugSupportProvider;
import com.oracle.truffle.api.instrument.ToolSupportProvider;
import com.oracle.truffle.api.source.Source;
import java.io.IOException;

public final class TestingLanguage extends TruffleLanguage {
    public static final TruffleLanguage INSTANCE = new TestingLanguage();

    private TestingLanguage() {
        super(null);
    }

    @Override
    protected Object eval(Source code) throws IOException {
        throw new IOException();
    }

    @Override
    protected Object findExportedSymbol(String globalName, boolean onlyExplicit) {
        return null;
    }

    @Override
    protected Object getLanguageGlobal() {
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

}
