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

import java.io.IOException;
import java.util.List;

/**
 * A persistent, stateful Morel execution environment.
 *
 * <p>Each call to {@link #execute} runs only the new cell's code; bindings
 * accumulated from prior calls are automatically available.
 */
public interface Kernel extends AutoCloseable {
  /**
   * Executes the given Morel code and returns the lines of captured output.
   * Bindings defined by the code are retained for subsequent calls.
   */
  List<String> execute(String code) throws IOException;

  @Override
  void close();
}

// End Kernel.java
