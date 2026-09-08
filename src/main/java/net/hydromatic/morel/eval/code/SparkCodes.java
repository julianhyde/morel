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
package net.hydromatic.morel.eval.code;

import com.google.common.collect.ImmutableList;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.foreign.SparkConnections;

/**
 * Implementations of built-in functions and values in the {@code Spark}
 * structure.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class SparkCodes {
  private SparkCodes() {}

  /** Registers the implementations in this class. */
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.SPARK_CATALOG, SPARK_CATALOG);
    c.accept(BuiltIn.SPARK_CLOSE, SPARK_CLOSE);
    c.accept(BuiltIn.SPARK_CONNECT, SPARK_CONNECT);
    c.accept(BuiltIn.SPARK_CONNECT_DEFAULT, SPARK_CONNECT_DEFAULT);
    c.accept(BuiltIn.SPARK_USING, SPARK_USING);
  }

  /** @see BuiltIn#SPARK_CATALOG */
  private static final Applicable1 SPARK_CATALOG =
      new BaseApplicable1<Object, SparkBackend.Connection>(
          BuiltIn.SPARK_CATALOG) {
        @Override
        public Object apply(SparkBackend.Connection connection) {
          return SparkConnections.catalog(connection);
        }
      };

  /** @see BuiltIn#SPARK_CLOSE */
  private static final Applicable1 SPARK_CLOSE =
      new BaseApplicable1<Unit, SparkBackend.Connection>(BuiltIn.SPARK_CLOSE) {
        @Override
        public Unit apply(SparkBackend.Connection connection) {
          SparkConnections.close(connection);
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#SPARK_CONNECT */
  private static final Applicable SPARK_CONNECT = new SparkConnect(Pos.ZERO);

  /** Implements {@link #SPARK_CONNECT}. */
  private static class SparkConnect extends BasePositionedApplicable {
    SparkConnect(Pos pos) {
      super(BuiltIn.SPARK_CONNECT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkConnect(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      try {
        return SparkConnections.open(stack.session, (String) arg);
      } catch (SparkBackend.SparkException e) {
        throw Codes.sparkException(e, pos);
      }
    }
  }

  /** @see BuiltIn#SPARK_CONNECT_DEFAULT */
  private static final Applicable SPARK_CONNECT_DEFAULT =
      new SparkConnectDefault(Pos.ZERO);

  /** Implements {@link #SPARK_CONNECT_DEFAULT}. */
  private static class SparkConnectDefault extends BasePositionedApplicable {
    SparkConnectDefault(Pos pos) {
      super(BuiltIn.SPARK_CONNECT_DEFAULT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkConnectDefault(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      try {
        return SparkConnections.openDefault(stack.session);
      } catch (SparkBackend.SparkException e) {
        throw Codes.sparkException(e, pos);
      }
    }
  }

  /** @see BuiltIn#SPARK_USING */
  private static final Applicable SPARK_USING = new SparkUsing(Pos.ZERO);

  /**
   * Implements {@link #SPARK_USING}. Applied to a function, returns a function
   * that opens the default connection for each call.
   */
  private static class SparkUsing extends BasePositionedApplicable {
    SparkUsing(Pos pos) {
      super(BuiltIn.SPARK_USING, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SparkUsing(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final Session session = stack.session;
      @SuppressWarnings("unchecked")
      final Applicable1<Object, Object> f = (Applicable1<Object, Object>) arg;
      return new BaseApplicable1<Object, Object>(BuiltIn.SPARK_USING) {
        @Override
        public Object apply(Object x) {
          final SparkConnections.ConnectionRecord record;
          try {
            record = SparkConnections.openDefault(session);
          } catch (SparkBackend.SparkException e) {
            throw Codes.sparkException(e, pos);
          }
          try {
            return f.apply(ImmutableList.of(record.connection, x));
          } finally {
            SparkConnections.close(record.connection);
          }
        }
      };
    }
  }
}

// End SparkCodes.java
