/*
 * Copyright (c) 1999, 2012, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef SHARE_VM_COMPILER_ABSTRACTCOMPILER_HPP
#define SHARE_VM_COMPILER_ABSTRACTCOMPILER_HPP

#include "ci/compilerInterface.hpp"

typedef void (*initializer)(void);

#ifdef GRAAL
class CompilerStatistics {
 public:
  elapsedTimer _t_osr_compilation;
  elapsedTimer _t_standard_compilation;
  int _sum_osr_bytes_compiled;
  int _sum_standard_bytes_compiled;
  CompilerStatistics() : _sum_osr_bytes_compiled(0), _sum_standard_bytes_compiled(0) {}
};
#endif

class AbstractCompiler : public CHeapObj<mtCompiler> {
 private:
  bool _is_initialized; // Mark whether compiler object is initialized

 protected:
  // Used for tracking global state of compiler runtime initialization
  enum { uninitialized, initializing, initialized };

  // The (closed set) of concrete compiler classes. Using an tag like this
  // avoids a confusing use of macros around the definition of the
  // 'is_<compiler type>' methods.
  enum Type { c1, c2, shark, graal };

  // This method will call the initialization method "f" once (per compiler class/subclass)
  // and do so without holding any locks
  void initialize_runtimes(initializer f, volatile int* state);

 private:
  Type _type;

#ifdef GRAAL
  CompilerStatistics _stats;
#endif

 public:
  AbstractCompiler(Type type) : _is_initialized(false), _type(type)    {}

  // Name of this compiler
  virtual const char* name() = 0;

  // Should a native wrapper be generated by the runtime. This method
  // does *not* answer the question "can this compiler generate code for
  // a native method".
  virtual bool supports_native()                 { return true; }
  virtual bool supports_osr   ()                 { return true; }
  virtual bool can_compile_method(methodHandle method)  { return true; }
  bool is_c1   ()                                { return _type == c1; }
  bool is_c2   ()                                { return _type == c2; }
  bool is_shark()                                { return _type == shark; }
  bool is_graal()                                { return _type == graal; }

  // Customization
  virtual bool needs_stubs            ()         = 0;

  void mark_initialized()                        { _is_initialized = true; }
  bool is_initialized()                          { return _is_initialized; }

  virtual void initialize()                      = 0;

  // Compilation entry point for methods
  virtual void compile_method(ciEnv* env,
                              ciMethod* target,
                              int entry_bci) {
    ShouldNotReachHere();
  }


  // Print compilation timers and statistics
  virtual void print_timers() {
    ShouldNotReachHere();
  }

#ifdef GRAAL
  CompilerStatistics* stats() { return &_stats; }
#endif
};

#endif // SHARE_VM_COMPILER_ABSTRACTCOMPILER_HPP
