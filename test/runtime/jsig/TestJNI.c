/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

#include <stdio.h>
#include <jni.h>
#define __USE_GNU
#include <signal.h>
#include <sys/ucontext.h>

#ifdef __cplusplus
extern "C" {
#endif

void sig_handler(int sig, siginfo_t *info, ucontext_t *context) {
    int thrNum;

    printf( " HANDLER (1) " );
    // Move forward RIP to skip failing instruction
    context->uc_mcontext.gregs[REG_RIP] += 6;
}

JNIEXPORT void JNICALL Java_TestJNI_doSomething(JNIEnv *env, jclass klass, jint val) {
    struct sigaction act;
    struct sigaction oact;
    pthread_attr_t attr;
    stack_t stack;

    act.sa_flags = SA_ONSTACK|SA_RESTART|SA_SIGINFO;
    sigfillset(&act.sa_mask);
    act.sa_handler = SIG_DFL;
    act.sa_sigaction = (void (*)())sig_handler;
    sigaction(0x20+val, &act, &oact);

    printf( " doSomething(%d) " , val);
    printf( " old handler = %p " , oact.sa_handler);
}

#ifdef __cplusplus
}
#endif

