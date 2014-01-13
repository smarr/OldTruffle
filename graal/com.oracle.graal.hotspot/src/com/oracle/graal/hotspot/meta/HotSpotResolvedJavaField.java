/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.api.meta.MetaUtil.*;
import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;
import static com.oracle.graal.phases.GraalOptions.*;

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.options.*;
import com.oracle.graal.replacements.*;
import com.oracle.graal.replacements.Snippet.SnippetInliningPolicy;
import com.oracle.graal.replacements.SnippetTemplate.Arguments;

/**
 * Represents a field in a HotSpot type.
 */
public class HotSpotResolvedJavaField extends CompilerObject implements ResolvedJavaField {

    // Must not conflict with any fields flags used by the VM - the assertion in the constructor
    // checks this assumption
    private static final int FIELD_INTERNAL_FLAG = 0x80000000;

    private static final long serialVersionUID = 7692985878836955683L;
    private final HotSpotResolvedObjectType holder;
    private final String name;
    private final JavaType type;
    private final int offset;
    private final int modifiers;
    private Constant constant;

    public HotSpotResolvedJavaField(HotSpotResolvedObjectType holder, String name, JavaType type, long offset, int modifiers, boolean internal) {
        assert (modifiers & FIELD_INTERNAL_FLAG) == 0;
        this.holder = holder;
        this.name = name;
        this.type = type;
        assert offset != -1;
        assert offset == (int) offset : "offset larger than int";
        this.offset = (int) offset;
        if (internal) {
            this.modifiers = modifiers | FIELD_INTERNAL_FLAG;
        } else {
            this.modifiers = modifiers;
        }
    }

    @Override
    public int getModifiers() {
        return modifiers & Modifier.fieldModifiers();
    }

    @Override
    public boolean isInternal() {
        return (modifiers & FIELD_INTERNAL_FLAG) != 0;
    }

    /**
     * Compares two {@link StackTraceElement}s for equality, ignoring differences in
     * {@linkplain StackTraceElement#getLineNumber() line number}.
     */
    private static boolean equalsIgnoringLine(StackTraceElement left, StackTraceElement right) {
        return left.getClassName().equals(right.getClassName()) && left.getMethodName().equals(right.getMethodName()) && left.getFileName().equals(right.getFileName());
    }

    /**
     * If the compiler is configured for AOT mode, {@link #readConstantValue(Constant)} should be
     * only called for snippets or replacements.
     */
    private static boolean isCalledForSnippets() {
        MetaAccessProvider metaAccess = runtime().getHostProviders().getMetaAccess();
        ResolvedJavaMethod makeGraphMethod = null;
        ResolvedJavaMethod initMethod = null;
        try {
            Class<?> rjm = ResolvedJavaMethod.class;
            makeGraphMethod = metaAccess.lookupJavaMethod(ReplacementsImpl.class.getDeclaredMethod("makeGraph", rjm, rjm, rjm, SnippetInliningPolicy.class, boolean.class, boolean.class));
            initMethod = metaAccess.lookupJavaMethod(SnippetTemplate.AbstractTemplates.class.getDeclaredMethod("template", Arguments.class));
        } catch (NoSuchMethodException | SecurityException e) {
            throw new GraalInternalError(e);
        }
        StackTraceElement makeGraphSTE = makeGraphMethod.asStackTraceElement(0);
        StackTraceElement initSTE = initMethod.asStackTraceElement(0);

        StackTraceElement[] stackTrace = new Exception().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            // Ignoring line numbers should not weaken this check too much while at
            // the same time making it more robust against source code changes
            if (equalsIgnoringLine(makeGraphSTE, element) || equalsIgnoringLine(initSTE, element)) {
                return true;
            }
        }
        return false;
    }

    private static final List<ResolvedJavaField> notEmbeddable = new ArrayList<>();

    private static void addResolvedToSet(Field field) {
        MetaAccessProvider metaAccess = runtime().getHostProviders().getMetaAccess();
        notEmbeddable.add(metaAccess.lookupJavaField(field));
    }

    static {
        try {
            addResolvedToSet(Boolean.class.getDeclaredField("TRUE"));
            addResolvedToSet(Boolean.class.getDeclaredField("FALSE"));

            Class<?> characterCacheClass = Character.class.getDeclaredClasses()[0];
            assert "java.lang.Character$CharacterCache".equals(characterCacheClass.getName());
            addResolvedToSet(characterCacheClass.getDeclaredField("cache"));

            Class<?> byteCacheClass = Byte.class.getDeclaredClasses()[0];
            assert "java.lang.Byte$ByteCache".equals(byteCacheClass.getName());
            addResolvedToSet(byteCacheClass.getDeclaredField("cache"));

            Class<?> shortCacheClass = Short.class.getDeclaredClasses()[0];
            assert "java.lang.Short$ShortCache".equals(shortCacheClass.getName());
            addResolvedToSet(shortCacheClass.getDeclaredField("cache"));

            Class<?> integerCacheClass = Integer.class.getDeclaredClasses()[0];
            assert "java.lang.Integer$IntegerCache".equals(integerCacheClass.getName());
            addResolvedToSet(integerCacheClass.getDeclaredField("cache"));

            Class<?> longCacheClass = Long.class.getDeclaredClasses()[0];
            assert "java.lang.Long$LongCache".equals(longCacheClass.getName());
            addResolvedToSet(longCacheClass.getDeclaredField("cache"));

            addResolvedToSet(Throwable.class.getDeclaredField("UNASSIGNED_STACK"));
            addResolvedToSet(Throwable.class.getDeclaredField("SUPPRESSED_SENTINEL"));
        } catch (SecurityException | NoSuchFieldException e) {
            throw new GraalInternalError(e);
        }
    }

    /**
     * in AOT mode, some fields should never be embedded even for snippets/replacements.
     */
    private boolean isEmbeddable() {
        if (ImmutableCode.getValue() && notEmbeddable.contains(this)) {
            return false;
        }
        return true;
    }

    private static final String SystemClassName = "Ljava/lang/System;";

    /**
     * {@inheritDoc}
     * <p>
     * The {@code value} field in {@link OptionValue} is considered constant if the type of
     * {@code receiver} is (assignable to) {@link StableOptionValue}.
     */
    @Override
    public Constant readConstantValue(Constant receiver) {
        assert !ImmutableCode.getValue() || isCalledForSnippets() : receiver;

        if (receiver == null) {
            assert Modifier.isStatic(modifiers);
            if (constant == null) {
                if (holder.isInitialized() && !holder.getName().equals(SystemClassName) && isEmbeddable()) {
                    if (Modifier.isFinal(getModifiers())) {
                        constant = readValue(receiver);
                    }
                }
            }
            return constant;
        } else {
            /*
             * for non-static final fields, we must assume that they are only initialized if they
             * have a non-default value.
             */
            assert !Modifier.isStatic(modifiers);
            Object object = receiver.asObject();

            // Canonicalization may attempt to process an unsafe read before
            // processing a guard (e.g. a type check) for this read
            // so we need to type check the object being read
            if (isInObject(object)) {
                if (Modifier.isFinal(getModifiers())) {
                    Constant value = readValue(receiver);
                    if (assumeNonStaticFinalFieldsAsFinal(object.getClass()) || !value.isDefaultForKind()) {
                        return value;
                    }
                } else if (isStable()) {
                    Constant value = readValue(receiver);
                    if (assumeDefaultStableFieldsAsFinal(object.getClass()) || !value.isDefaultForKind()) {
                        return value;
                    }
                } else {
                    Class<?> clazz = object.getClass();
                    if (StableOptionValue.class.isAssignableFrom(clazz)) {
                        assert getName().equals("value") : "Unexpected field in " + StableOptionValue.class.getName() + " hierarchy:" + this;
                        StableOptionValue<?> option = (StableOptionValue<?>) object;
                        return Constant.forObject(option.getValue());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Determines if a given object contains this field.
     */
    public boolean isInObject(Object object) {
        return getDeclaringClass().isAssignableFrom(HotSpotResolvedObjectType.fromClass(object.getClass()));
    }

    @Override
    public Constant readValue(Constant receiver) {
        if (receiver == null) {
            assert Modifier.isStatic(modifiers);
            if (holder.isInitialized()) {
                return runtime().getHostProviders().getConstantReflection().readUnsafeConstant(getKind(), holder.mirror(), offset, getKind() == Kind.Object);
            }
            return null;
        } else {
            assert !Modifier.isStatic(modifiers);
            Object object = receiver.asObject();
            assert object != null && isInObject(object);
            return runtime().getHostProviders().getConstantReflection().readUnsafeConstant(getKind(), object, offset, getKind() == Kind.Object);
        }
    }

    private static boolean assumeNonStaticFinalFieldsAsFinal(Class<?> clazz) {
        return clazz == SnippetCounter.class;
    }

    /**
     * Usually {@link Stable} fields are not considered constant if the value is the
     * {@link Constant#isDefaultForKind default value}. For some special classes we want to override
     * this behavior.
     */
    private static boolean assumeDefaultStableFieldsAsFinal(Class<?> clazz) {
        // HotSpotVMConfig has a lot of zero-value fields which we know are stable and want to be
        // considered as constants.
        if (clazz == HotSpotVMConfig.class) {
            return true;
        }
        return false;
    }

    @Override
    public HotSpotResolvedObjectType getDeclaringClass() {
        return holder;
    }

    @Override
    public Kind getKind() {
        return getType().getKind();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public JavaType getType() {
        return type;
    }

    public int offset() {
        return offset;
    }

    @Override
    public String toString() {
        return format("HotSpotField<%H.%n %t:", this) + offset + ">";
    }

    @Override
    public boolean isSynthetic() {
        Field javaField = toJava();
        if (javaField != null) {
            return javaField.isSynthetic();
        }
        return false;
    }

    /**
     * Checks if this field has the {@link Stable} annotation.
     * 
     * @return true if field has {@link Stable} annotation, false otherwise
     */
    public boolean isStable() {
        Annotation annotation = getAnnotation(Stable.class);
        return annotation != null;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        Field javaField = toJava();
        if (javaField != null) {
            return javaField.getAnnotation(annotationClass);
        }
        return null;
    }

    private Field toJava() {
        try {
            return holder.mirror().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
