/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.object;

import java.util.EnumSet;
import java.util.ServiceLoader;

import com.oracle.truffle.api.object.Shape.Allocator;

/**
 * Describes layout and behavior of a {@link DynamicObject} subclass and is used to create shapes.
 *
 * An object may change its shape but only to shapes of the same layout.
 */
public abstract class Layout {
    public static final String OPTION_PREFIX = "truffle.object.";

    private static final LayoutFactory LAYOUT_FACTORY = loadLayoutFactory();

    /**
     * Specifies the allowed implicit casts between primitive types without losing type information.
     */
    public enum ImplicitCast {
        IntToDouble,
        IntToLong,
    }

    /**
     * Creates a new {@link Builder}.
     */
    public static Builder newLayout() {
        return new Builder();
    }

    /**
     * Equivalent to {@code Layout.newLayout().build()}.
     */
    public static Layout createLayout() {
        return newLayout().build();
    }

    public abstract DynamicObject newInstance(Shape shape);

    public abstract Class<? extends DynamicObject> getType();

    public abstract Shape createShape(ObjectType operations);

    public abstract Shape createShape(ObjectType operations, Object sharedData);

    public abstract Shape createShape(ObjectType operations, Object sharedData, int id);

    /**
     * Create an allocator for static property creation. Reserves all array extension slots.
     */
    public abstract Allocator createAllocator();

    protected static LayoutFactory getFactory() {
        return LAYOUT_FACTORY;
    }

    private static LayoutFactory loadLayoutFactory() {
        LayoutFactory bestLayoutFactory = null;

        String layoutFactoryImplClassName = System.getProperty(OPTION_PREFIX + "LayoutFactory");
        if (layoutFactoryImplClassName != null) {
            Class<?> clazz;
            try {
                clazz = Class.forName(layoutFactoryImplClassName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            try {
                bestLayoutFactory = (LayoutFactory) clazz.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                throw new AssertionError(e);
            }
        } else {
            ServiceLoader<LayoutFactory> serviceLoader = ServiceLoader.load(LayoutFactory.class, Layout.class.getClassLoader());
            for (LayoutFactory currentLayoutFactory : serviceLoader) {
                if (bestLayoutFactory == null) {
                    bestLayoutFactory = currentLayoutFactory;
                } else if (currentLayoutFactory.getPriority() >= bestLayoutFactory.getPriority()) {
                    assert currentLayoutFactory.getPriority() != bestLayoutFactory.getPriority();
                    bestLayoutFactory = currentLayoutFactory;
                }
            }
        }

        if (bestLayoutFactory == null) {
            throw new AssertionError("LayoutFactory not found");
        }
        return bestLayoutFactory;
    }

    /**
     * Layout builder.
     *
     * @see Layout
     */
    public static final class Builder {
        private EnumSet<ImplicitCast> allowedImplicitCasts;

        /**
         * Create a new layout builder.
         */
        private Builder() {
            this.allowedImplicitCasts = EnumSet.noneOf(ImplicitCast.class);
        }

        /**
         * Build {@link Layout} from the configuration in this builder.
         */
        public Layout build() {
            return Layout.getFactory().createLayout(this);
        }

        /**
         * Set the allowed implicit casts in this layout.
         *
         * @see Layout.ImplicitCast
         */
        public Builder setAllowedImplicitCasts(EnumSet<ImplicitCast> allowedImplicitCasts) {
            this.allowedImplicitCasts = allowedImplicitCasts;
            return this;
        }

        /**
         * Add an allowed implicit cast in this layout.
         *
         * @see Layout.ImplicitCast
         */
        public Builder addAllowedImplicitCast(ImplicitCast allowedImplicitCast) {
            this.allowedImplicitCasts.add(allowedImplicitCast);
            return this;
        }
    }

    protected static EnumSet<ImplicitCast> getAllowedImplicitCasts(Builder builder) {
        return builder.allowedImplicitCasts;
    }
}
