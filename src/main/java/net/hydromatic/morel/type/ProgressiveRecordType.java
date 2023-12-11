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
package net.hydromatic.morel.type;

import java.util.SortedMap;

import static java.util.Objects.requireNonNull;

/** Progressive record type. */
public class ProgressiveRecordType extends RecordType {
  /** A field of this name indicates that a record type is progressive. */
  public static final String DUMMY = "$dummy";

  private final Handler handler;

  /** Creates a ProgressiveRecordType. */
  ProgressiveRecordType(Handler handler,
      SortedMap<String, Type> argNameTypes) {
    super(argNameTypes);
    assert argNameTypes.containsKey(DUMMY);
    this.handler = requireNonNull(handler, "handler");
  }

  @Override public boolean isProgressive() {
    return true;
  }

  @Override public Key key() {
    return Keys.progressiveRecord(handler, Keys.toKeys(argNameTypes));
  }

  /** Handles mutations. */
  public interface Handler {
  }

  public enum DefaultHandler implements Handler {
    INSTANCE
  }
}

// End ProgressiveRecordType.java
