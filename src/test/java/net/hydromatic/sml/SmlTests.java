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
package net.hydromatic.sml;

import org.junit.Assume;

import static org.junit.Assume.assumeTrue;

/** Utilities for writing tests. */
public class SmlTests {
  private SmlTests() {}

  /** Aborts the test with a given message.
   * Use this method if you want to abandon a test case half way through.
   * The test will "succeed", because this is an {@link Assume} failure.
   *
   * @param message Message
   */
  public static void abandon(String message) {
    assumeTrue(message, false);
  }
}

// End SmlTests.java
