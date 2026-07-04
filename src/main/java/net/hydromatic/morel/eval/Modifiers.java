/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.morel.eval;

import java.util.List;
import net.hydromatic.morel.type.Type;

/** Factory methods for {@link Modifier}. */
public class Modifiers {
  private Modifiers() {}

  /**
   * Creates an equality modifier, rendering {@code label = value}. Its one key
   * is the constrained value; {@code fieldIndex} locates the field within a
   * base element.
   */
  public static Modifier equality(
      String label, Type valueType, int fieldIndex) {
    return new Modifier.Equality(label, valueType, fieldIndex);
  }

  /**
   * Creates an anonymous filter modifier (a {@code where}), rendering the
   * predicate {@code format} with its holes substituted (for example {@code
   * "e.units > {0}"}).
   */
  public static Modifier filter(String format, List<Type> keyTypes) {
    return new Modifier.Filter(null, format, keyTypes);
  }

  /**
   * Creates a labeled filter modifier (a {@code restrict}), rendering {@code
   * label: <predicate>}.
   */
  public static Modifier labeledFilter(
      String label, String format, List<Type> keyTypes) {
    return new Modifier.Filter(label, label + ": " + format, keyTypes);
  }

  /**
   * Creates a relax directive (a {@code relax}) that removes any modifier on
   * {@code label} from the context.
   */
  public static Modifier relax(String label) {
    return new Modifier.Relax(label);
  }
}

// End Modifiers.java
