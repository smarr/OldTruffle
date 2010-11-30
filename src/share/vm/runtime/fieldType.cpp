/*
 * Copyright (c) 1997, 2000, Oracle and/or its affiliates. All rights reserved.
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

# include "incls/_precompiled.incl"
# include "incls/_fieldType.cpp.incl"

void FieldType::skip_optional_size(symbolOop signature, int* index) {
  jchar c = signature->byte_at(*index);
  while (c >= '0' && c <= '9') {
    *index = *index + 1;
    c = signature->byte_at(*index);
  }
}

BasicType FieldType::basic_type(symbolOop signature) {
  return char2type(signature->byte_at(0));
}

// Check if it is a valid array signature
bool FieldType::is_valid_array_signature(symbolOop sig) {
  assert(sig->utf8_length() > 1, "this should already have been checked");
  assert(sig->byte_at(0) == '[', "this should already have been checked");
  // The first character is already checked
  int i = 1;
  int len = sig->utf8_length();
  // First skip all '['s
  while(i < len - 1 && sig->byte_at(i) == '[') i++;

  // Check type
  switch(sig->byte_at(i)) {
    case 'B': // T_BYTE
    case 'C': // T_CHAR
    case 'D': // T_DOUBLE
    case 'F': // T_FLOAT
    case 'I': // T_INT
    case 'J': // T_LONG
    case 'S': // T_SHORT
    case 'Z': // T_BOOLEAN
      // If it is an array, the type is the last character
      return (i + 1 == len);
    case 'L':
      // If it is an object, the last character must be a ';'
      return sig->byte_at(len - 1) == ';';
  }

  return false;
}


BasicType FieldType::get_array_info(symbolOop signature, jint* dimension, symbolOop* object_key, TRAPS) {
  assert(basic_type(signature) == T_ARRAY, "must be array");
  int index = 1;
  int dim   = 1;
  skip_optional_size(signature, &index);
  while (signature->byte_at(index) == '[') {
    index++;
    dim++;
    skip_optional_size(signature, &index);
  }
  ResourceMark rm;
  symbolOop element = oopFactory::new_symbol(signature->as_C_string() + index, CHECK_(T_BYTE));
  BasicType element_type = FieldType::basic_type(element);
  if (element_type == T_OBJECT) {
    char* object_type = element->as_C_string();
    object_type[element->utf8_length() - 1] = '\0';
    *object_key = oopFactory::new_symbol(object_type + 1, CHECK_(T_BYTE));
  }
  // Pass dimension back to caller
  *dimension = dim;
  return element_type;
}
