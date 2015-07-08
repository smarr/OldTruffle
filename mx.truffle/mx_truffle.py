#
# commands.py - the GraalVM specific commands
#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2015, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------

import mx

_suite = mx.suite('truffle')

def build(args, vm=None):
    """build the Java sources"""
    opts2 = mx.build(['--source', '1.7'] + args)
    assert len(opts2.remainder) == 0

def maven_install_truffle(args):
    """install Truffle into your local Maven repository"""
    for name in mx._dists:
        dist = mx._dists[name]
        if dist.isProcessorDistribution:
            continue
        mx.archive(["@" + name])
        path = dist.path
        slash = path.rfind('/')
        dot = path.rfind('.')
        if dot <= slash:
            mx.abort('Dot should be after / in ' + path)
        artifactId = path[slash + 1: dot]
        mx.run(['mvn', 'install:install-file', '-DgroupId=com.oracle.' + dist.suite.name, '-DartifactId=' + artifactId, '-Dversion=' + mx.suite('truffle').release_version('SNAPSHOT'), '-Dpackaging=jar', '-Dfile=' + path])

def sl(args):
    """run an SL program"""
    vmArgs, slArgs = mx.extract_VM_args(args)
    mx.run_java(vmArgs + ['-cp', mx.classpath(["TRUFFLE", "com.oracle.truffle.sl"]), "com.oracle.truffle.sl.SLLanguage"] + slArgs)

def sldebug(args):
    """run a simple command line debugger for the Simple Language"""
    vmArgs, slArgs = mx.extract_VM_args(args, useDoubleDash=True)
    mx.run_java(vmArgs + ['-cp', mx.classpath("com.oracle.truffle.sl.tools"), "com.oracle.truffle.sl.tools.debug.SLREPLServer"] + slArgs)

mx.update_commands(_suite, {
    'maven-install-truffle' : [maven_install_truffle, ''],
    'sl' : [sl, '[SL args|@VM options]'],
    'sldebug' : [sldebug, '[SL args|@VM options]'],
})
