/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.virtual.phases.ea;

import java.util.*;

import com.oracle.graal.nodes.*;

public abstract class EffectsBlockState<T extends EffectsBlockState<T>> {

    protected final IdentityHashMap<ValueNode, ValueNode> scalarAliases;

    protected EffectsBlockState() {
        scalarAliases = new IdentityHashMap<>();
    }

    protected EffectsBlockState(EffectsBlockState<T> other) {
        scalarAliases = new IdentityHashMap<>(other.scalarAliases);
    }

    public void addScalarAlias(ValueNode alias, ValueNode value) {
        scalarAliases.put(alias, value);
    }

    public ValueNode getScalarAlias(ValueNode alias) {
        ValueNode result = scalarAliases.get(alias);
        return result == null ? alias : result;
    }

    @Override
    public String toString() {
        return "Scalar Aliases: " + scalarAliases.toString();
    }

    public void meetAliases(List<T> states) {
        scalarAliases.putAll(states.get(0).scalarAliases);
        for (int i = 1; i < states.size(); i++) {
            EffectsBlockState<T> state = states.get(i);
            meetMaps(scalarAliases, state.scalarAliases);
        }
    }

    public boolean equivalentTo(T other) {
        if (this == other) {
            return true;
        }
        return scalarAliases.equals(other.scalarAliases);
    }

    protected static <K, V> boolean compareMaps(Map<K, V> left, Map<K, V> right) {
        if (left.size() != right.size()) {
            return false;
        }
        return compareMapsNoSize(left, right);
    }

    protected static <K, V> boolean compareMapsNoSize(Map<K, V> left, Map<K, V> right) {
        if (left == right) {
            return true;
        }
        for (Map.Entry<K, V> entry : right.entrySet()) {
            K key = entry.getKey();
            V value = entry.getValue();
            assert value != null;
            V otherValue = left.get(key);
            if (otherValue != value && !value.equals(otherValue)) {
                return false;
            }
        }
        return true;
    }

    protected static <U, V> void meetMaps(Map<U, V> target, Map<U, V> source) {
        Iterator<Map.Entry<U, V>> iter = target.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<U, V> entry = iter.next();
            if (source.containsKey(entry.getKey())) {
                assert source.get(entry.getKey()) == entry.getValue();
            } else {
                iter.remove();
            }
        }
    }

}
