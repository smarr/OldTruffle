/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.amd64;

import com.oracle.jvmci.code.Register;
import com.oracle.jvmci.code.RegisterConfig;
import com.oracle.jvmci.code.TargetDescription;

import static com.oracle.jvmci.amd64.AMD64.*;

import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.jvmci.hotspot.*;
import com.oracle.jvmci.hotspot.amd64.*;

final class AMD64UncommonTrapStub extends UncommonTrapStub {

    private RegisterConfig registerConfig;

    public AMD64UncommonTrapStub(HotSpotProviders providers, TargetDescription target, HotSpotVMConfig config, HotSpotForeignCallLinkage linkage) {
        super(providers, target, linkage);
        Register[] allocatable = new Register[]{rbx, rcx, rdx, rsi, rdi, r8, r9, r10, r11, r13, r14};
        registerConfig = new AMD64HotSpotRegisterConfig(target.arch, config, allocatable);
    }

    @Override
    public RegisterConfig getRegisterConfig() {
        return registerConfig;
    }
}
