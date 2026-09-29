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
package net.hydromatic.morel.eval.codes;

import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;

import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.jspecify.annotations.NonNull;

/**
 * Implementations of built-in functions and values in the Option and Either
 * structures.
 */
public final class OptionCodes {
  private OptionCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.EITHER_APP, EITHER_APP);
    b.add(BuiltIn.EITHER_APP_LEFT, EITHER_APP_LEFT);
    b.add(BuiltIn.EITHER_APP_RIGHT, EITHER_APP_RIGHT);
    b.add(BuiltIn.EITHER_AS_LEFT, EITHER_AS_LEFT);
    b.add(BuiltIn.EITHER_AS_RIGHT, EITHER_AS_RIGHT);
    b.add(BuiltIn.EITHER_FOLD, EITHER_FOLD);
    b.add(BuiltIn.EITHER_IS_LEFT, EITHER_IS_LEFT);
    b.add(BuiltIn.EITHER_IS_RIGHT, EITHER_IS_RIGHT);
    b.add(BuiltIn.EITHER_MAP, EITHER_MAP);
    b.add(BuiltIn.EITHER_MAP_LEFT, EITHER_MAP_LEFT);
    b.add(BuiltIn.EITHER_MAP_RIGHT, EITHER_MAP_RIGHT);
    b.add(BuiltIn.EITHER_PARTITION, EITHER_PARTITION);
    b.add(BuiltIn.EITHER_PROJ, EITHER_PROJ);
    b.add(BuiltIn.OPTION_APP, OPTION_APP);
    b.add(BuiltIn.OPTION_COMPOSE, OPTION_COMPOSE);
    b.add(BuiltIn.OPTION_COMPOSE_PARTIAL, OPTION_COMPOSE_PARTIAL);
    b.add(BuiltIn.OPTION_FILTER, OPTION_FILTER);
    b.add(BuiltIn.OPTION_GET_OPT, OPTION_GET_OPT);
    b.add(BuiltIn.OPTION_IS_SOME, OPTION_IS_SOME);
    b.add(BuiltIn.OPTION_JOIN, OPTION_JOIN);
    b.add(BuiltIn.OPTION_MAP, OPTION_MAP);
    b.add(BuiltIn.OPTION_MAP_PARTIAL, OPTION_MAP_PARTIAL);
    b.add(BuiltIn.OPTION_VAL_OF, OPTION_VAL_OF);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** Returns whether an {@code either} value is a left value. */
  private static boolean isEitherLeft(List either) {
    return either.get(0) == BuiltIn.Constructor.EITHER_INL.constructor;
  }

  /** Returns the (left or right) value from an {@code either}. */
  private static Object eitherProj(List either) {
    return either.get(1);
  }

  /** Creates a left {@code either}. */
  private static List<Object> eitherInl(Object o) {
    return FlatLists.of(BuiltIn.Constructor.EITHER_INL.constructor, o);
  }

  /** Creates a right {@code either}. */
  private static List<Object> eitherInr(Object o) {
    return FlatLists.of(BuiltIn.Constructor.EITHER_INR.constructor, o);
  }

  /** @see BuiltIn#EITHER_APP */
  private static final Applicable2 EITHER_APP =
      new BaseApplicable2<Applicable1, Applicable1, Applicable1>(
          BuiltIn.EITHER_APP) {
        @Override
        public Applicable1<Unit, List> apply(Applicable1 f, Applicable1 g) {
          return either -> {
            Object o = eitherProj(either);
            if (isEitherLeft(either)) {
              f.apply(o);
            } else {
              g.apply(o);
            }
            return Unit.INSTANCE;
          };
        }
      };

  /** @see BuiltIn#EITHER_APP_LEFT */
  private static final Applicable2 EITHER_APP_LEFT =
      new BaseApplicable2<Unit, Applicable1, List>(BuiltIn.EITHER_APP_LEFT) {
        @Override
        public Unit apply(Applicable1 f, List either) {
          if (isEitherLeft(either)) {
            f.apply(eitherProj(either));
          }
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#EITHER_APP_RIGHT */
  private static final Applicable2 EITHER_APP_RIGHT =
      new BaseApplicable2<Unit, Applicable1, List>(BuiltIn.EITHER_APP_RIGHT) {
        @Override
        public Unit apply(Applicable1 f, List either) {
          if (!isEitherLeft(either)) {
            f.apply(eitherProj(either));
          }
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#EITHER_AS_LEFT */
  private static final Applicable1<List, List> EITHER_AS_LEFT =
      new BaseApplicable1<List, List>(BuiltIn.EITHER_AS_LEFT) {
        @Override
        public List apply(List either) {
          return isEitherLeft(either)
              ? optionSome(eitherProj(either))
              : OPTION_NONE;
        }
      };

  /** @see BuiltIn#EITHER_AS_RIGHT */
  private static final Applicable1<List, List> EITHER_AS_RIGHT =
      new BaseApplicable1<List, List>(BuiltIn.EITHER_AS_RIGHT) {
        @Override
        public List apply(List either) {
          return isEitherLeft(either)
              ? OPTION_NONE
              : optionSome(eitherProj(either));
        }
      };

  /** @see BuiltIn#EITHER_FOLD */
  private static final Applicable2 EITHER_FOLD =
      new BaseApplicable2<
          Applicable1<Applicable1<Object, List>, Object>,
          Applicable,
          Applicable>(BuiltIn.EITHER_FOLD) {
        @Override
        public Applicable1<Applicable1<Object, List>, Object> apply(
            Applicable f, Applicable g) {
          return init ->
              either -> {
                Applicable a = isEitherLeft(either) ? f : g;
                return ((Applicable1) a)
                    .apply(FlatLists.of(eitherProj(either), init));
              };
        }
      };

  /** @see BuiltIn#EITHER_IS_LEFT */
  private static final Applicable1<Boolean, List> EITHER_IS_LEFT =
      new BaseApplicable1<Boolean, List>(BuiltIn.EITHER_IS_LEFT) {
        @Override
        public Boolean apply(List either) {
          return isEitherLeft(either);
        }
      };

  /** @see BuiltIn#EITHER_IS_RIGHT */
  private static final Applicable1<Boolean, List> EITHER_IS_RIGHT =
      new BaseApplicable1<Boolean, List>(BuiltIn.EITHER_IS_RIGHT) {
        @Override
        public Boolean apply(List either) {
          return !isEitherLeft(either);
        }
      };

  /** @see BuiltIn#EITHER_MAP */
  private static final Applicable2 EITHER_MAP =
      new BaseApplicable2<Applicable1, Applicable1, Applicable1>(
          BuiltIn.EITHER_MAP) {
        @Override
        public Applicable1<List, List> apply(Applicable1 f, Applicable1 g) {
          return either -> {
            Object o = eitherProj(either);
            if (isEitherLeft(either)) {
              return eitherInl(f.apply(o));
            } else {
              return eitherInr(g.apply(o));
            }
          };
        }
      };

  /** @see BuiltIn#EITHER_MAP_LEFT */
  private static final Applicable2 EITHER_MAP_LEFT =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.EITHER_MAP_LEFT) {
        @Override
        public List apply(Applicable1 f, List either) {
          return isEitherLeft(either)
              ? eitherInl(f.apply(eitherProj(either)))
              : either;
        }
      };

  /** @see BuiltIn#EITHER_MAP_RIGHT */
  private static final Applicable2 EITHER_MAP_RIGHT =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.EITHER_MAP_RIGHT) {
        @Override
        public List apply(Applicable1 f, List either) {
          return isEitherLeft(either)
              ? either
              : eitherInr(f.apply(eitherProj(either)));
        }
      };

  /** @see BuiltIn#EITHER_PARTITION */
  private static final Applicable1 EITHER_PARTITION =
      new BaseApplicable1<List<List>, List<List>>(BuiltIn.EITHER_PARTITION) {
        @Override
        public List<List> apply(List<List> eithers) {
          final List lefts = new ArrayList();
          final List rights = new ArrayList();
          eithers.forEach(
              either -> {
                List target = isEitherLeft(either) ? lefts : rights;
                target.add(eitherProj(either));
              });
          return FlatLists.of(lefts, rights);
        }
      };

  /** @see BuiltIn#EITHER_PROJ */
  private static final Applicable1<Object, List> EITHER_PROJ =
      new BaseApplicable1<Object, List>(BuiltIn.EITHER_PROJ) {
        @Override
        public Object apply(List either) {
          return eitherProj(either);
        }
      };

  /** @see BuiltIn#OPTION_APP */
  private static final Applicable2 OPTION_APP =
      new BaseApplicable2<Unit, Applicable1, List>(BuiltIn.OPTION_APP) {
        @Override
        public Unit apply(Applicable1 f, List a) {
          if (a.size() == 2) {
            f.apply(a.get(1));
          }
          return Unit.INSTANCE;
        }

        @Override
        public String toString() {
          return super.toString();
        }

        @Override
        public Describer describe(Describer describer) {
          return super.describe(describer);
        }
      };

  /** @see BuiltIn#OPTION_COMPOSE */
  private static final Applicable2 OPTION_COMPOSE =
      new BaseApplicable2<Applicable1, Applicable1, Applicable1<List, Object>>(
          BuiltIn.OPTION_COMPOSE) {
        @Override
        public Applicable1 apply(Applicable1 f, Applicable1<List, Object> g) {
          return (Applicable1<@NonNull List, @NonNull Object>)
              arg -> {
                final List ga = g.apply(arg); // g (a)
                if (ga.size() == 2) { // SOME v
                  return optionSome(f.apply(ga.get(1))); // SOME (f (v))
                }
                return ga; // NONE
              };
        }
      };

  /** @see BuiltIn#OPTION_COMPOSE_PARTIAL */
  private static final Applicable2 OPTION_COMPOSE_PARTIAL =
      new BaseApplicable2<Applicable1, Applicable1<List, Object>, Applicable1>(
          BuiltIn.OPTION_COMPOSE_PARTIAL) {
        @Override
        public Applicable1 apply(Applicable1<List, Object> f, Applicable1 g) {
          return (Applicable1<@NonNull List, @NonNull Object>)
              arg -> {
                final List ga = (List) g.apply(arg); // g (a)
                if (ga.size() == 2) { // SOME v
                  return f.apply(ga.get(1)); // f (v)
                }
                return ga; // NONE
              };
        }
      };

  /** @see BuiltIn#OPTION_FILTER */
  private static final Applicable2 OPTION_FILTER =
      new BaseApplicable2<List, Applicable1, Object>(BuiltIn.OPTION_FILTER) {
        @Override
        public List apply(Applicable1 f, Object arg) {
          if ((Boolean) f.apply(arg)) {
            return optionSome(arg);
          } else {
            return OPTION_NONE;
          }
        }
      };

  /** @see BuiltIn#OPTION_GET_OPT */
  private static final Applicable2 OPTION_GET_OPT =
      new BaseApplicable2<Object, List, Object>(BuiltIn.OPTION_GET_OPT) {
        @Override
        public Object apply(List opt, Object o) {
          if (opt.size() == 2) {
            assert opt.get(0) == BuiltIn.Constructor.OPTION_SOME.constructor;
            return opt.get(1); // SOME has 2 elements, NONE has 1
          }
          return o;
        }
      };

  /** @see BuiltIn#OPTION_IS_SOME */
  private static final Applicable OPTION_IS_SOME =
      new BaseApplicable1<Boolean, List>(BuiltIn.OPTION_IS_SOME) {
        @Override
        public Boolean apply(List opt) {
          return opt.size() == 2; // SOME has 2 elements, NONE has 1
        }
      };

  /** @see BuiltIn#OPTION_JOIN */
  private static final Applicable OPTION_JOIN =
      new BaseApplicable1<List, List<List>>(BuiltIn.OPTION_JOIN) {
        @Override
        public List apply(List<List> opt) {
          return opt.size() == 2
              ? opt.get(1) // SOME(SOME(v)) -> SOME(v), SOME(NONE) -> NONE
              : opt; // NONE -> NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP */
  private static final Applicable2 OPTION_MAP =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.OPTION_MAP) {
        @Override
        public List apply(Applicable1 f, List a) {
          if (a.size() == 2) { // SOME v
            return optionSome(f.apply(a.get(1))); // SOME (f v)
          }
          return a; // NONE
        }
      };

  /** @see BuiltIn#OPTION_MAP_PARTIAL */
  private static final Applicable2 OPTION_MAP_PARTIAL =
      new BaseApplicable2<List, Applicable1<List, Object>, List>(
          BuiltIn.OPTION_MAP_PARTIAL) {
        @Override
        public List apply(Applicable1<List, Object> f, List a) {
          if (a.size() == 2) { // SOME v
            return f.apply(a.get(1)); // f v
          }
          return a; // NONE
        }
      };

  /** @see BuiltIn#OPTION_VAL_OF */
  private static final Applicable OPTION_VAL_OF = new OptionValOf(Pos.ZERO);

  /** Implements {@link #OPTION_VAL_OF}. */
  private static class OptionValOf
      extends BasePositionedApplicable1<Object, List> {
    OptionValOf(Pos pos) {
      super(BuiltIn.OPTION_VAL_OF, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new OptionValOf(pos);
    }

    @Override
    public Object apply(List opt) {
      if (opt.size() == 2) { // SOME has 2 elements, NONE has 1
        return opt.get(1);
      } else {
        throw new MorelRuntimeException(BuiltInExn.OPTION, pos);
      }
    }
  }
}

// End OptionCodes.java
