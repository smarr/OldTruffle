/*
 * Copyright (c) 1999, 2010, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "opto/locknode.hpp"
#include "opto/parse.hpp"
#include "opto/rootnode.hpp"
#include "opto/runtime.hpp"

//=============================================================================
const RegMask &BoxLockNode::in_RegMask(uint i) const {
  return _inmask;
}

const RegMask &BoxLockNode::out_RegMask() const {
  return *Matcher::idealreg2regmask[Op_RegP];
}

uint BoxLockNode::size_of() const { return sizeof(*this); }

BoxLockNode::BoxLockNode( int slot ) : Node( Compile::current()->root() ),
                                       _slot(slot), _is_eliminated(false) {
  init_class_id(Class_BoxLock);
  init_flags(Flag_rematerialize);
  OptoReg::Name reg = OptoReg::stack2reg(_slot);
  _inmask.Insert(reg);
}

//-----------------------------hash--------------------------------------------
uint BoxLockNode::hash() const {
  if (EliminateNestedLocks)
    return NO_HASH; // Each locked region has own BoxLock node
  return Node::hash() + _slot + (_is_eliminated ? Compile::current()->fixed_slots() : 0);
}

//------------------------------cmp--------------------------------------------
uint BoxLockNode::cmp( const Node &n ) const {
  if (EliminateNestedLocks)
    return (&n == this); // Always fail except on self
  const BoxLockNode &bn = (const BoxLockNode &)n;
  return bn._slot == _slot && bn._is_eliminated == _is_eliminated;
}

BoxLockNode* BoxLockNode::box_node(Node* box) {
  // Chase down the BoxNode
  while (!box->is_BoxLock()) {
    //    if (box_node->is_SpillCopy()) {
    //      Node *m = box_node->in(1);
    //      if (m->is_Mach() && m->as_Mach()->ideal_Opcode() == Op_StoreP) {
    //        box_node = m->in(m->as_Mach()->operand_index(2));
    //        continue;
    //      }
    //    }
    assert(box->is_SpillCopy() || box->is_Phi(), "Bad spill of Lock.");
    // Only BoxLock nodes with the same stack slot are merged.
    // So it is enough to trace one path to find the slot value.
    box = box->in(1);
  }
  return box->as_BoxLock();
}

OptoReg::Name BoxLockNode::reg(Node* box) {
  return box_node(box)->in_RegMask(0).find_first_elem();
}

bool BoxLockNode::same_slot(Node* box1, Node* box2) {
  return box_node(box1)->_slot == box_node(box2)->_slot;
}

// Is BoxLock node used for one simple lock region (same box and obj)?
bool BoxLockNode::is_simple_lock_region(LockNode** unique_lock, Node* obj) {
  LockNode* lock = NULL;
  bool has_one_lock = false;
  for (uint i = 0; i < this->outcnt(); i++) {
    Node* n = this->raw_out(i);
    if (n->is_Phi())
      return false; // Merged regions
    if (n->is_AbstractLock()) {
      AbstractLockNode* alock = n->as_AbstractLock();
      // Check lock's box since box could be referenced by Lock's debug info.
      if (alock->box_node() == this) {
        if (alock->obj_node()->eqv_uncast(obj)) {
          if ((unique_lock != NULL) && alock->is_Lock()) {
            if (lock == NULL) {
              lock = alock->as_Lock();
              has_one_lock = true;
            } else if (lock != alock->as_Lock()) {
              has_one_lock = false;
            }
          }
        } else {
          return false; // Different objects
        }
      }
    }
  }
#ifdef ASSERT
  // Verify that FastLock and Safepoint reference only this lock region.
  for (uint i = 0; i < this->outcnt(); i++) {
    Node* n = this->raw_out(i);
    if (n->is_FastLock()) {
      FastLockNode* flock = n->as_FastLock();
      assert((flock->box_node() == this) && flock->obj_node()->eqv_uncast(obj),"");
    }
    if (n->is_SafePoint() && n->as_SafePoint()->jvms()) {
      SafePointNode* sfn = n->as_SafePoint();
      JVMState* youngest_jvms = sfn->jvms();
      int max_depth = youngest_jvms->depth();
      for (int depth = 1; depth <= max_depth; depth++) {
        JVMState* jvms = youngest_jvms->of_depth(depth);
        int num_mon  = jvms->nof_monitors();
        // Loop over monitors
        for (int idx = 0; idx < num_mon; idx++) {
          Node* obj_node = sfn->monitor_obj(jvms, idx);
          Node* box_node = sfn->monitor_box(jvms, idx);
          if (box_node == this) {
            assert(obj_node->eqv_uncast(obj),"");
          }
        }
      }
    }
  }
#endif
  if (unique_lock != NULL && has_one_lock) {
    *unique_lock = lock;
  }
  return true;
}

//=============================================================================
//-----------------------------hash--------------------------------------------
uint FastLockNode::hash() const { return NO_HASH; }

//------------------------------cmp--------------------------------------------
uint FastLockNode::cmp( const Node &n ) const {
  return (&n == this);                // Always fail except on self
}

//=============================================================================
//-----------------------------hash--------------------------------------------
uint FastUnlockNode::hash() const { return NO_HASH; }

//------------------------------cmp--------------------------------------------
uint FastUnlockNode::cmp( const Node &n ) const {
  return (&n == this);                // Always fail except on self
}

//
// Create a counter which counts the number of times this lock is acquired
//
void FastLockNode::create_lock_counter(JVMState* state) {
  BiasedLockingNamedCounter* blnc = (BiasedLockingNamedCounter*)
           OptoRuntime::new_named_counter(state, NamedCounter::BiasedLockingCounter);
  _counters = blnc->counters();
}

//=============================================================================
//------------------------------do_monitor_enter-------------------------------
void Parse::do_monitor_enter() {
  kill_dead_locals();

  // Null check; get casted pointer.
  Node *obj = do_null_check(peek(), T_OBJECT);
  // Check for locking null object
  if (stopped()) return;

  // the monitor object is not part of debug info expression stack
  pop();

  // Insert a FastLockNode which takes as arguments the current thread pointer,
  // the obj pointer & the address of the stack slot pair used for the lock.
  shared_lock(obj);
}

//------------------------------do_monitor_exit--------------------------------
void Parse::do_monitor_exit() {
  kill_dead_locals();

  pop();                        // Pop oop to unlock
  // Because monitors are guaranteed paired (else we bail out), we know
  // the matching Lock for this Unlock.  Hence we know there is no need
  // for a null check on Unlock.
  shared_unlock(map()->peek_monitor_box(), map()->peek_monitor_obj());
}
