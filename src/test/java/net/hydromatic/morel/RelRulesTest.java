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
package net.hydromatic.morel;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.RelRule;
import net.hydromatic.morel.compile.RelRules;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests {@link RelRules}, the driver that applies rules to a tree. */
public class RelRulesTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Registers 'bag'.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    /** {@code [3, 1, 2]}. */
    final Core.Exp list =
        core.list(
            typeSystem,
            PrimitiveType.INT,
            ImmutableList.of(intLiteral(3), intLiteral(1), intLiteral(2)));

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    /** {@code unorder (sort [$0] [3, 1, 2])}. */
    Core.Exp unorderSort() {
      final RelBuilder b = RelBuilder.create(typeSystem);
      b.push(list);
      b.sort(b.input(0));
      b.unorder();
      return b.build();
    }
  }

  /** A rule that fires wrongly: it returns a node of another kind. */
  private static final RelRule BAD =
      new RelRule() {
        @Override
        public String name() {
          return "Bad";
        }

        @Override
        public Core.@Nullable Exp apply(TypeSystem typeSystem, Core.Rel rel) {
          return rel instanceof Core.Unorder
              ? ((Core.Unorder) rel).input
              : null;
        }
      };

  @Test
  void testUnorderPushdown() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    assertThat(
        tree,
        hasToString(
            "unorder\n" //
                + "  sort [$0]\n"
                + "    [3, 1, 2]\n"));
    final Core.Exp tree2 =
        RelRules.rewrite(f.typeSystem, RelRules.STANDARD, tree);
    assertThat(
        tree2,
        hasToString(
            "unorder\n" //
                + "  [3, 1, 2]\n"));
    assertThat(tree2.type, hasToString("int bag"));
  }

  /** With no rules, a tree is returned as it is. */
  @Test
  void testNoRules() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    final Core.Exp tree2 =
        RelRules.rewrite(f.typeSystem, ImmutableList.of(), tree);
    assertThat(tree2, sameInstance(tree));
  }

  /** A rule that changes the root's type is caught at the firing. */
  @Test
  void testRuleMustPreserveType() {
    final Fixture f = new Fixture();
    final Core.Exp tree = f.unorderSort();
    final AssertionError e =
        assertThrows(
            AssertionError.class,
            () -> RelRules.rewrite(f.typeSystem, ImmutableList.of(BAD), tree));
    assertThat(e.getMessage(), containsString("rule Bad changed the type"));
  }
}

// End RelRulesTest.java
