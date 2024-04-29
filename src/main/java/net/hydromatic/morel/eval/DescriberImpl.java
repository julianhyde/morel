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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Implementation of {@link net.hydromatic.morel.eval.Code .Describer}. */
class DescriberImpl implements Describer {
  final StringBuilder buf = new StringBuilder();
  final Map<String, List<Integer>> nameIds = new HashMap<>();

  @Override public String toString() {
    return buf.toString();
  }

  @Override public Describer start(String name,
      Consumer<Detail> consumer) {
    buf.append(name);
    final DetailImpl detail = new DetailImpl();
    consumer.accept(detail);
    detail.end();
    return this;
  }

  @Override public int register(String name, int i) {
    final List<Integer> list =
        nameIds.computeIfAbsent(name, id_ -> new ArrayList<>());
    int j = list.indexOf(i);
    if (j < 0) {
      j = list.size();
      list.add(i);
    }
    return j;
  }

  /** Implementation of
   * {@link Detail}. */
  private class DetailImpl implements Detail {
    final int start = buf.length();

    @Override public Detail arg(String name, Object value) {
      buf.append(buf.length() == start ? "(" : ", ")
          .append(name)
          .append(name.isEmpty() ? "" : " ")
          .append(value);
      return this;
    }

    @Override public Detail arg(String name, Describable describable) {
      buf.append(buf.length() == start ? "(" : ", ")
          .append(name)
          .append(name.isEmpty() ? "" : " ");
      describable.describe(DescriberImpl.this);
      return this;
    }

    void end() {
      if (buf.length() > start) {
        buf.append(')');
      }
    }
  }
}

// End DescriberImpl.java
