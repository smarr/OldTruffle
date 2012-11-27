/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.api.meta;

import java.lang.annotation.*;
import java.lang.reflect.*;

/**
 * Represents a resolved Java types. Types include primitives, objects, {@code void}, and arrays thereof. Types, like
 * fields and methods, are resolved through {@link ConstantPool constant pools}.
 */
public interface ResolvedJavaType extends JavaType {

    /**
     * Represents each of the several different parts of the runtime representation of a type which compiled code may
     * need to reference individually. These may or may not be different objects or data structures, depending on the
     * runtime system.
     */
    public enum Representation {
        /**
         * The runtime representation of the data structure containing the static primitive fields of this type.
         */
        StaticPrimitiveFields,

        /**
         * The runtime representation of the data structure containing the static object fields of this type.
         */
        StaticObjectFields,

        /**
         * The runtime representation of the Java class object of this type.
         */
        JavaClass,

        /**
         * The runtime representation of the "hub" of this type--that is, the closest part of the type representation
         * which is typically stored in the object header.
         */
        ObjectHub
    }

    /**
     * Gets the encoding of (that is, a constant representing the value of) the specified part of this type.
     *
     * @param r the part of this type
     * @return a constant representing a reference to the specified part of this type
     */
    Constant getEncoding(Representation r);

    /**
     * Checks whether this type has a finalizer method.
     *
     * @return {@code true} if this class has a finalizer
     */
    boolean hasFinalizer();

    /**
     * Checks whether this type has any finalizable subclasses so far. Any decisions based on this information require
     * the registration of a dependency, since this information may change.
     *
     * @return {@code true} if this class has any subclasses with finalizers
     */
    boolean hasFinalizableSubclass();

    /**
     * Checks whether this type is an interface.
     *
     * @return {@code true} if this type is an interface
     */
    boolean isInterface();

    /**
     * Checks whether this type is an instance class.
     *
     * @return {@code true} if this type is an instance class
     */
    boolean isInstanceClass();

    /**
     * Checks whether this type is an array class.
     *
     * @return {@code true} if this type is an array class
     */
    boolean isArrayClass();

    /**
     * Returns the Java language modifiers for this type, as an integer. The {@link Modifier} class should be used to
     * decode the modifiers. Only the flags specified in the JVM specification will be included in the returned mask.
     * This method is identical to {@link Class#getModifiers()} in terms of the value return for this type.
     */
    int getModifiers();

    /**
     * Checks whether this type is initialized.
     *
     * @return {@code true} if this type is initialized
     */
    boolean isInitialized();

    /**
     * Initializes this type.
     */
    void initialize();

    /**
     * Checks whether this type is a subtype of another type.
     *
     * @param other the type to test
     * @return {@code true} if this type a subtype of the specified type
     * @see Class#isAssignableFrom(Class)
     */
    boolean isAssignableTo(ResolvedJavaType other);

    /**
     * Checks whether the specified object is an instance of this type.
     *
     * @param obj the object to test
     * @return {@code true} if the object is an instance of this type
     */
    boolean isInstance(Constant obj);

    /**
     * Returns this type if it is an exact type otherwise returns null.
     * This type is exact if it is void, primitive, final, or an array of a final or primitive type.
     *
     * @return this type if it is exact; {@code null} otherwise
     */
    ResolvedJavaType asExactType();

    /**
     * Gets the super class of this type.
     * If this type represents either the {@code Object} class, a primitive type, or void, then
     * null is returned.  If this object represents an array class or an interface then the
     * type object representing the {@code Object} class is returned.
     */
    ResolvedJavaType getSuperclass();

    /**
     * Gets the interfaces implemented or extended by this type. This method is analogous to {@link Class#getInterfaces()}
     * and as such, only returns the interfaces directly implemented or extended by this type.
     */
    ResolvedJavaType[] getInterfaces();

    /**
     * Walks the class hierarchy upwards and returns the least common class that is a superclass of both the current and
     * the given type.
     *
     * @return the least common type that is a super type of both the current and the given type, or {@code null} if
     *         primitive types are involved.
     */
    ResolvedJavaType findLeastCommonAncestor(ResolvedJavaType otherType);

    /**
     * Gets the unique concrete subclass of this type.
     *
     * <p>
     * If the compiler uses the result of this method for its compilation, it must register an assumption because
     * dynamic class loading can invalidate the result of this method.
     *
     * @return the exact type of this type, if it exists; {@code null} otherwise
     */
    ResolvedJavaType findUniqueConcreteSubtype();

    ResolvedJavaType getComponentType();

    ResolvedJavaType getArrayClass();

    /**
     * Resolves the method implementation for virtual dispatches on objects of this dynamic type.
     *
     * @param method the method to select the implementation of
     * @return the method implementation that would be selected at runtime
     */
    ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method);

    /**
     * Given a {@link ResolvedJavaMethod} A, returns a concrete {@link ResolvedJavaMethod} B that is the only possible
     * unique target for a virtual call on A(). Returns {@code null} if either no such concrete method or more than one
     * such method exists. Returns the method A if A is a concrete method that is not overridden.
     * <p>
     * If the compiler uses the result of this method for its compilation, it must register an assumption because
     * dynamic class loading can invalidate the result of this method.
     *
     * @param method the method A for which a unique concrete target is searched
     * @return the unique concrete target or {@code null} if no such target exists or assumptions are not supported by
     *         this runtime
     */
    ResolvedJavaMethod findUniqueConcreteMethod(ResolvedJavaMethod method);

    /**
     * Returns the instance fields of this class, including {@linkplain ResolvedJavaField#isInternal() internal} fields.
     * A zero-length array is returned for array and primitive types. The order of fields returned by this method is
     * stable. That is, for a single JVM execution the same order is returned each time this method is called.
     *
     * @param includeSuperclasses if true, then instance fields for the complete hierarchy of this type are included in the result
     * @return an array of instance fields
     */
    ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses);

    /**
     * Returns the annotation for the specified type of this class, if such an annotation is present.
     *
     * @param annotationClass the Class object corresponding to the annotation type
     * @return this element's annotation for the specified annotation type if present on this class, else {@code null}
     */
    <T extends Annotation> T getAnnotation(Class<T> annotationClass);

    /**
     * Determines if this type is the same as that represented by a given {@link Class}.
     */
    boolean isClass(Class c);

    /**
     * Returns the {@link java.lang.Class} object representing this type.
     */
    Class< ? > toJava();
}
