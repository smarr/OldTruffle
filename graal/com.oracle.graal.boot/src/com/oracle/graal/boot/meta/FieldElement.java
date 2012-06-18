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
package com.oracle.graal.boot.meta;

import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.boot.*;


public class FieldElement extends Element {

    protected ResolvedJavaField javaField;

    public FieldElement(ResolvedJavaField javaField) {
        super(javaField.type().resolve(javaField.holder()));
        this.javaField = javaField;
    }

    public boolean isStatic() {
        return Modifier.isStatic(javaField.accessFlags());
    }

    public ResolvedJavaField getJavaField() {
        return javaField;
    }

    @Override
    public String toString() {
        return "Field[" + javaField + "]";
    }

    public synchronized void registerNewValue(BigBang bb, Object value) {
        if (value != null) {
            Class<?> clazz = value.getClass();
            ResolvedJavaType resolvedType = bb.getMetaAccess().getResolvedJavaType(clazz);
            if (seenTypes.add(resolvedType)) {
                Set<ResolvedJavaType> newSeenTypes = new HashSet<>();
                newSeenTypes.add(resolvedType);
                super.propagateTypes(bb, newSeenTypes);
            }
        }
    }
}
