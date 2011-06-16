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

package com.oracle.max.graal.runtime;

import com.sun.cri.ci.*;
import com.sun.cri.ri.*;

/**
 * Entries into the HotSpot VM from Java code.
 */
public interface VMEntries {

    // Checkstyle: stop

    byte[] RiMethod_code(HotSpotMethodResolved method);

    String RiMethod_signature(HotSpotMethodResolved method);

    RiExceptionHandler[] RiMethod_exceptionHandlers(HotSpotMethodResolved method);

    boolean RiMethod_hasBalancedMonitors(HotSpotMethodResolved method);

    RiMethod RiMethod_uniqueConcreteMethod(HotSpotMethodResolved method);

    int RiMethod_invocationCount(HotSpotMethodResolved method);

    RiTypeProfile RiMethod_typeProfile(HotSpotMethodResolved method, int bci);

    int RiMethod_branchProbability(HotSpotMethodResolved method, int bci);

    RiType RiSignature_lookupType(String returnType, HotSpotTypeResolved accessingClass);

    Object RiConstantPool_lookupConstant(long vmId, int cpi);

    RiMethod RiConstantPool_lookupMethod(long vmId, int cpi, byte byteCode);

    RiSignature RiConstantPool_lookupSignature(long vmId, int cpi);

    RiType RiConstantPool_lookupType(long vmId, int cpi);

    RiField RiConstantPool_lookupField(long vmId, int cpi, byte byteCode);

    RiConstantPool RiType_constantPool(HotSpotTypeResolved klass);

    void installMethod(HotSpotTargetMethod targetMethod);

    long installStub(HotSpotTargetMethod targetMethod);

    HotSpotVMConfig getConfiguration();

    RiMethod RiType_resolveMethodImpl(HotSpotTypeResolved klass, String name, String signature);

    boolean RiType_isSubtypeOf(HotSpotTypeResolved klass, RiType other);

    RiType getPrimitiveArrayType(CiKind kind);

    RiType RiType_arrayOf(HotSpotTypeResolved klass);

    RiType RiType_componentType(HotSpotTypeResolved klass);

    boolean RiType_isInitialized(HotSpotTypeResolved klass);

    RiType getType(Class<?> javaClass);

    void recordBailout(String reason);

    RiType RiType_uniqueConcreteSubtype(HotSpotTypeResolved hotSpotTypeResolved);

    RiType RiType_superType(HotSpotTypeResolved hotSpotTypeResolved);

    int getArrayLength(CiConstant array);

    boolean compareConstantObjects(CiConstant x, CiConstant y);

    RiType getRiType(CiConstant constant);

    // Checkstyle: resume
}
