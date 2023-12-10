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

import net.hydromatic.morel.type.ProgressiveRecordType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.TypeSystem;

import java.io.File;

import static java.util.Objects.requireNonNull;

/** Directory in the file system.
 *
 * <p>Its type is progressive, so that it can discover new files and
 * subdirectories.
 */
public class Directory implements ProgressiveRecordType.Handler {
  private final File file;

  /** Creates a Directory in the default directory. */
  public Directory() {
    this(new File("/home/jhyde/dev/morel.1/src/test/resources"));
  }

  /** Creates a Directory. */
  public Directory(File file) {
    this.file = requireNonNull(file, "file");
  }

  @Override public RecordLikeType discoverField(TypeSystem typeSystem,
      ProgressiveRecordType type, String fieldName) {
    return null;
  }
}

// End Directory.java
