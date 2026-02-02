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

import java.io.StringReader;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.AstToDoc;
import net.hydromatic.morel.util.Pretty;

/**
 * Morel code formatter.
 *
 * <p>Parses Morel source code, converts the AST to a {@link Pretty.Doc}, and
 * renders it at the given line width.
 */
public class MorelFmt {
  /** Default line width. */
  public static final int DEFAULT_WIDTH = 80;

  private MorelFmt() {}

  /**
   * Formats a Morel statement (expression or declaration) at the given line
   * width. The input should be a single statement, without a trailing
   * semicolon.
   */
  public static String format(String source, int lineWidth) {
    final String code =
        source.trim().endsWith(";") ? source.trim() : source.trim() + ";";
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(code));
    parser.zero("fmt");
    final AstNode statement = parser.statementSemicolonSafe();
    final Pretty.Doc doc = AstToDoc.toDoc(statement);
    return Pretty.render(lineWidth, doc);
  }

  /** Formats a Morel statement at the default line width (80). */
  public static String format(String source) {
    return format(source, DEFAULT_WIDTH);
  }
}

// End MorelFmt.java
