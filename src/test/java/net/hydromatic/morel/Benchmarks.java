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

import static java.util.concurrent.TimeUnit.SECONDS;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** A collection of benchmarks. */
public class Benchmarks {
  private Benchmarks() {}

  /** Benchmark for Wadler's "A Prettier Printer" algorithm. */
  @State(Scope.Benchmark)
  public static class WadlerBenchmark {
    @Param({"5", "50"})
    public int complexity;

    @Param({"false", "true"})
    public boolean render;

    @Param({"12345"})
    public int seed = 12345;

    @Benchmark
    @Fork(value = 1, warmups = 2)
    @Measurement(time = 5, timeUnit = SECONDS)
    public void benchmark() {
      WadlerTest.benchmark(seed, complexity, render);
    }
  }
}

// End Benchmarks.java
