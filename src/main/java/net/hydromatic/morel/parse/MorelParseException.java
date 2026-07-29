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
package net.hydromatic.morel.parse;

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.MorelException;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Exception caused by a parse error. */
public class MorelParseException extends RuntimeException
    implements MorelException {
  private final Pos pos;

  MorelParseException(Throwable cause, Pos pos) {
    super(clean(cause.getMessage()), cause);
    this.pos = pos;
  }

  /**
   * Removes the non-reserved keywords from the list of expected tokens.
   *
   * <p>{@code all} is a token only so that it can be recognized after {@code
   * with} and {@code extend}; every rule that accepts an identifier accepts it
   * too. Naming it alongside {@code <IDENTIFIER>} would suggest that it is an
   * alternative to it, and would name a word that is irrelevant to whatever the
   * user actually got wrong.
   */
  private static @Nullable String clean(@Nullable String message) {
    if (message == null || !message.contains("Was expecting")) {
      return message;
    }
    final StringBuilder buf = new StringBuilder();
    boolean removed = false;
    for (String line : message.split("\n", -1)) {
      final String trimmed = line.trim();
      if (trimmed.length() > 5
          && trimmed.startsWith("\"")
          && trimmed.endsWith("\" ...")
          && Parsers.NON_RESERVED_KEYWORDS.contains(
              trimmed.substring(1, trimmed.length() - 5))) {
        removed = true;
        continue;
      }
      if (buf.length() > 0) {
        buf.append('\n');
      }
      buf.append(line);
    }
    return removed ? buf.toString() : message;
  }

  @Override
  public Pos pos() {
    return pos;
  }

  @Override
  public StringBuilder describeTo(StringBuilder buf) {
    return pos.describeTo(buf).append(' ').append(getMessage());
  }
}

// End MorelParseException.java
