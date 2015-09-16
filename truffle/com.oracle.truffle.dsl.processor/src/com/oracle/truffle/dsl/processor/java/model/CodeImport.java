/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor.java.model;

import java.util.Objects;

public class CodeImport implements Comparable<CodeImport> {

    private final String packageName;
    private final String symbolName;
    private final boolean staticImport;

    public CodeImport(String packageName, String symbolName, boolean staticImport) {
        this.packageName = packageName;
        this.symbolName = symbolName;
        this.staticImport = staticImport;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public boolean isStaticImport() {
        return staticImport;
    }

    @Override
    public int compareTo(CodeImport o) {
        if (staticImport && !o.staticImport) {
            return 1;
        } else if (!staticImport && o.staticImport) {
            return -1;
        } else {
            int result = getPackageName().compareTo(o.getPackageName());
            if (result == 0) {
                return getSymbolName().compareTo(o.getSymbolName());
            }
            return result;
        }
    }

    public <P> void accept(CodeElementScanner<?, P> s, P p) {
        s.visitImport(this, p);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, symbolName, staticImport);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CodeImport) {
            CodeImport otherImport = (CodeImport) obj;
            return getPackageName().equals(otherImport.getPackageName()) && getSymbolName().equals(otherImport.getSymbolName()) //
                            && staticImport == otherImport.staticImport;
        }
        return super.equals(obj);
    }
}
