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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests that the shell's output does not depend on how threads are scheduled.
 *
 * <p>A terminal built over streams rather than a tty echoes its input from a
 * pump thread of its own. That thread and the one rendering the line the reader
 * has read both wrote to the output, in whatever order they were scheduled, so
 * {@link ShellTest} was asserting one interleaving of two threads and failed
 * about once in a dozen full builds. {@code Shell.create} now clears the
 * terminal's ECHO attribute, leaving one writer.
 *
 * <p>This test forces the schedule that used to fail, rather than waiting for
 * it: every write from a thread other than this one is delayed, which on the
 * old code produced the interleaved output every time, at 20 milliseconds:
 *
 * <blockquote>
 *
 * <pre>{@code let\r\n  v- let\r\r\n=   val x = 1\r\r\n...}</pre>
 *
 * </blockquote>
 *
 * <p>Temporary, for [MOREL-433]: remove before merging to main. It is the
 * evidence that the race is gone, which sampling cannot give -- at the observed
 * rate, 36 clean builds would be needed for 95% confidence, and 360 to show the
 * rate had fallen tenfold.
 */
public class ShellRaceTest {

  /**
   * Runs the shell, delaying every write that does not come from this thread,
   * and returns the output and the threads that wrote it.
   */
  private static Output run(String in, long delayMillis) throws IOException {
    final Thread testThread = Thread.currentThread();
    final Set<String> writers = new LinkedHashSet<>();
    final ByteArrayOutputStream baos =
        new ByteArrayOutputStream() {
          @Override
          public synchronized void write(byte[] b, int off, int len) {
            final Thread thread = Thread.currentThread();
            writers.add(thread == testThread ? "test" : thread.getName());
            if (thread != testThread) {
              try {
                Thread.sleep(delayMillis);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
            super.write(b, off, len);
          }

          @Override
          public synchronized void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
          }
        };
    final ByteArrayInputStream bais =
        new ByteArrayInputStream(in.getBytes(UTF_8));
    final Shell.Config config =
        Shell.parse(Shell.Config.DEFAULT, ShellTest.Fixture.DEFAULT_ARG_LIST);
    Shell.create(config, bais, baos).run();
    return new Output(
        baos.toString(UTF_8.name()), ImmutableSet.copyOf(writers));
  }

  /** Output of the shell, and the threads that wrote it. */
  private static class Output {
    final String text;
    final Set<String> writers;

    Output(String text, Set<String> writers) {
      this.text = text;
      this.writers = writers;
    }
  }

  /**
   * Tests that the shell writes the same output however the threads are
   * scheduled, and that only one thread writes it.
   */
  @Test
  void testOutputDoesNotDependOnScheduling() throws IOException {
    final String in =
        "let\n" //
            + "  val x = 1\n"
            + "in\n"
            + "  x + 2\n"
            + "end;\n";
    final String expected =
        "- let\r\n" //
            + "=   val x = 1\r\n"
            + "= in\r\n"
            + "=   x + 2\r\n"
            + "= end;\r\n"
            + "val it = 3 : int\n"
            + "- \r\n";
    for (long delay : new long[] {0, 1, 5, 20, 50}) {
      final Output output = run(in, delay);
      assertThat("delay " + delay + "ms", output.text, is(expected));
      // The delay above fires only for a thread other than this one, so if any
      // other thread writes, the output depends on how the two are scheduled.
      assertThat(
          "delay " + delay + "ms", output.writers, is(ImmutableSet.of("test")));
    }
  }
}

// End ShellRaceTest.java
