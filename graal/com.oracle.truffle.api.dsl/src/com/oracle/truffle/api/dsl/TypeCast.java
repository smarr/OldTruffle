/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.dsl;

import java.lang.annotation.*;

/**
 * Overrides the standard way of casting a certain type in a {@link TypeSystem}. This is useful for
 * types where the guest language specific type cast can be implemented more efficiently than an
 * instanceof check. The annotated method must be contained in a {@link TypeSystem} annotated class.
 * Type checks must conform to the following signature: <code>public static Type as{TypeName}(Object
 * value)</code>. The casted type must be a type declared in the {@link TypeSystem}.
 *
 * <p>
 * If no {@link TypeCast} is declared then the type system implicitly uses a type cast that can be
 * declared as follows:
 *
 * <pre>
 * {@literal @}TypeCast(Type.class)
 * public static Type asType(Object value) {
 *         return (Type) value;
 * }
 * </pre>
 *
 * @see TypeCheck
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface TypeCast {

    Class<?> value();

}
