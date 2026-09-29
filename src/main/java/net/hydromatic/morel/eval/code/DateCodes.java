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

import static java.lang.String.format;
import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_EQUAL;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_GREATER;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_LESS;
import static net.hydromatic.morel.eval.code.StringCodes.scanString;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.code.StringCodes.CharSource;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the {@code Date} and
 * {@code Time} structures.
 */
@SuppressWarnings({"rawtypes"})
public final class DateCodes {
  private DateCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.DATE_COMPARE, DATE_COMPARE);
    b.add(BuiltIn.DATE_DATE, DATE_DATE);
    b.add(BuiltIn.DATE_DAY, DATE_DAY);
    b.add(BuiltIn.DATE_FMT, DATE_FMT);
    b.add(BuiltIn.DATE_FROM_STRING, DATE_FROM_STRING);
    b.add(BuiltIn.DATE_FROM_TIME_LOCAL, DATE_FROM_TIME_LOCAL);
    b.add(BuiltIn.DATE_FROM_TIME_UNIV, DATE_FROM_TIME_UNIV);
    b.add(BuiltIn.DATE_HOUR, DATE_HOUR);
    b.add(BuiltIn.DATE_IS_DST, DATE_IS_DST);
    b.add(BuiltIn.DATE_LOCAL_OFFSET, DATE_LOCAL_OFFSET);
    b.add(BuiltIn.DATE_MINUTE, DATE_MINUTE);
    b.add(BuiltIn.DATE_MONTH_FN, DATE_MONTH_FN);
    b.add(BuiltIn.DATE_SCAN, DATE_SCAN);
    b.add(BuiltIn.DATE_SECOND, DATE_SECOND);
    b.add(BuiltIn.DATE_TO_STRING, DATE_TO_STRING);
    b.add(BuiltIn.DATE_TO_TIME, DATE_TO_TIME);
    b.add(BuiltIn.DATE_WEEK_DAY, DATE_WEEK_DAY);
    b.add(BuiltIn.DATE_YEAR, DATE_YEAR);
    b.add(BuiltIn.DATE_YEAR_DAY, DATE_YEAR_DAY);
    b.add(BuiltIn.TIME_ADD, TIME_ADD);
    b.add(BuiltIn.TIME_COMPARE, TIME_COMPARE);
    b.add(BuiltIn.TIME_FMT, TIME_FMT);
    b.add(BuiltIn.TIME_FROM_MICROSECONDS, TIME_FROM_MICROSECONDS);
    b.add(BuiltIn.TIME_FROM_MILLISECONDS, TIME_FROM_MILLISECONDS);
    b.add(BuiltIn.TIME_FROM_NANOSECONDS, TIME_FROM_NANOSECONDS);
    b.add(BuiltIn.TIME_FROM_REAL, TIME_FROM_REAL);
    b.add(BuiltIn.TIME_FROM_SECONDS, TIME_FROM_SECONDS);
    b.add(BuiltIn.TIME_FROM_STRING, TIME_FROM_STRING);
    b.add(BuiltIn.TIME_GE, TIME_GE);
    b.add(BuiltIn.TIME_GT, TIME_GT);
    b.add(BuiltIn.TIME_LE, TIME_LE);
    b.add(BuiltIn.TIME_LT, TIME_LT);
    b.add(BuiltIn.TIME_NOW, TIME_NOW);
    b.add(BuiltIn.TIME_SCAN, TIME_SCAN);
    b.add(BuiltIn.TIME_SUBTRACT, TIME_SUBTRACT);
    b.add(BuiltIn.TIME_TO_MICROSECONDS, TIME_TO_MICROSECONDS);
    b.add(BuiltIn.TIME_TO_MILLISECONDS, TIME_TO_MILLISECONDS);
    b.add(BuiltIn.TIME_TO_NANOSECONDS, TIME_TO_NANOSECONDS);
    b.add(BuiltIn.TIME_TO_REAL, TIME_TO_REAL);
    b.add(BuiltIn.TIME_TO_SECONDS, TIME_TO_SECONDS);
    b.add(BuiltIn.TIME_TO_STRING, TIME_TO_STRING);
    b.add(BuiltIn.TIME_ZERO_TIME, TIME_ZERO_TIME);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#DATE_COMPARE */
  private static final Applicable DATE_COMPARE =
      new BaseApplicable2<List, OffsetDateTime, OffsetDateTime>(
          BuiltIn.DATE_COMPARE) {
        @Override
        public List apply(OffsetDateTime d1, OffsetDateTime d2) {
          return order(d1.toInstant().compareTo(d2.toInstant()));
        }
      };

  /** @see BuiltIn#DATE_DATE */
  private static final Applicable DATE_DATE = new DateDate(Pos.ZERO);

  /** Implements {@link #DATE_DATE}. */
  private static class DateDate
      extends BasePositionedApplicable1<OffsetDateTime, List> {
    DateDate(Pos pos) {
      super(BuiltIn.DATE_DATE, pos);
    }

    @Override
    public DateDate withPos(Pos pos) {
      return new DateDate(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      return apply((List) arg, sessionZone(stack.session));
    }

    @Override
    public OffsetDateTime apply(List r) {
      return apply(r, ZoneId.systemDefault());
    }

    private OffsetDateTime apply(List r, ZoneId defaultZone) {
      // Record fields in alphabetical order:
      // day, hour, minute, month, offset, second, year
      final int day = (Integer) r.get(0);
      final int hour = (Integer) r.get(1);
      final int minute = (Integer) r.get(2);
      final List monthVal = (List) r.get(3);
      final List offsetOpt = (List) r.get(4);
      final int second = (Integer) r.get(5);
      final int year = (Integer) r.get(6);
      final Month month = dateMonthFromName((String) monthVal.get(0));
      try {
        final LocalDateTime ldt =
            dateOf(year, month, day, hour, minute, second);
        final ZoneOffset zone;
        if (offsetOpt.size() == 2) {
          final long offsetNanos = (Long) offsetOpt.get(1);
          zone =
              ZoneOffset.ofTotalSeconds((int) (offsetNanos / 1_000_000_000L));
        } else {
          zone = defaultZone.getRules().getOffset(ldt);
        }
        return OffsetDateTime.of(ldt, zone);
      } catch (DateTimeException e) {
        throw new MorelRuntimeException(BuiltInExn.DATE, pos);
      }
    }
  }

  /**
   * Builds a date, normalizing fields that are out of range.
   *
   * <p>A day, hour, minute or second outside its usual range carries into the
   * field above it, as it does in C's {@code mktime}: day 32 of March is April
   * 1, day 0 is the last day of February, and hour 25 is hour 1 of the next
   * day. Only the year can be so far out of range that there is no such date,
   * and then {@link DateTimeException} is thrown.
   */
  private static LocalDateTime dateOf(
      int year, Month month, int day, int hour, int minute, int second) {
    return LocalDateTime.of(year, month.getValue(), 1, 0, 0, 0)
        .plusDays(day - 1L)
        .plusHours(hour)
        .plusMinutes(minute)
        .plusSeconds(second);
  }

  /** @see BuiltIn#DATE_DAY */
  private static final Applicable DATE_DAY =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_DAY) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getDayOfMonth();
        }
      };

  /** @see BuiltIn#DATE_FMT */
  private static final Applicable DATE_FMT =
      new BaseApplicable1<Applicable, String>(BuiltIn.DATE_FMT) {
        @Override
        public Applicable apply(String fmt) {
          return new BaseApplicable1<String, OffsetDateTime>(BuiltIn.DATE_FMT) {
            @Override
            public String apply(OffsetDateTime d) {
              return dateFmt(fmt, d);
            }
          };
        }
      };

  /** @see BuiltIn#DATE_FROM_STRING */
  private static final Applicable DATE_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.DATE_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(DATE_SCAN, s);
        }
      };

  /** @see BuiltIn#DATE_FROM_TIME_LOCAL */
  private static final Applicable DATE_FROM_TIME_LOCAL =
      new ApplicableImpl(BuiltIn.DATE_FROM_TIME_LOCAL) {
        @Override
        public Object apply(Stack stack, Object arg) {
          return timeToDate((Long) arg, sessionZone(stack.session));
        }
      };

  /** @see BuiltIn#DATE_FROM_TIME_UNIV */
  private static final Applicable DATE_FROM_TIME_UNIV =
      new BaseApplicable1<OffsetDateTime, Long>(BuiltIn.DATE_FROM_TIME_UNIV) {
        @Override
        public OffsetDateTime apply(Long t) {
          return timeToDate(t, ZoneOffset.UTC);
        }
      };

  /** @see BuiltIn#DATE_HOUR */
  private static final Applicable DATE_HOUR =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_HOUR) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getHour();
        }
      };

  /** @see BuiltIn#DATE_IS_DST */
  private static final Applicable DATE_IS_DST =
      new BaseApplicable1<List, OffsetDateTime>(BuiltIn.DATE_IS_DST) {
        @Override
        public List apply(OffsetDateTime d) {
          return OPTION_NONE; // OffsetDateTime does not carry DST information
        }
      };

  /** @see BuiltIn#DATE_LOCAL_OFFSET */
  private static final ApplicableImpl DATE_LOCAL_OFFSET =
      new ApplicableImpl(BuiltIn.DATE_LOCAL_OFFSET) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final Session session = stack.session;
          final Instant now = sessionNow(session);
          final int totalSeconds =
              sessionZone(session).getRules().getOffset(now).getTotalSeconds();
          return (long) totalSeconds * 1_000_000_000L;
        }
      };

  /** @see BuiltIn#DATE_MINUTE */
  private static final Applicable DATE_MINUTE =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_MINUTE) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getMinute();
        }
      };

  /** @see BuiltIn#DATE_MONTH_FN */
  private static final Applicable DATE_MONTH_FN =
      new BaseApplicable1<List, OffsetDateTime>(BuiltIn.DATE_MONTH_FN) {
        @Override
        public List apply(OffsetDateTime d) {
          return DATE_MONTHS.get(d.getMonthValue() - 1);
        }
      };

  /**
   * Month names, as they appear in the format that {@link #DATE_SCAN} reads.
   */
  private static final Set<String> DATE_MONTH_NAMES =
      BuiltIn.Datatype.DATE_MONTH.constructors().stream()
          .map(c -> c.constructor)
          .collect(ImmutableSet.toImmutableSet());

  /**
   * Month values for {@link BuiltIn#DATE_MONTH_FN}, indexed Jan(0)..Dec(11).
   */
  private static final List<List<String>> DATE_MONTHS =
      transformEager(
          BuiltIn.Datatype.DATE_MONTH.constructors(),
          c -> ImmutableList.of(c.constructor));

  /** @see BuiltIn#DATE_SCAN */
  private static final DateScan DATE_SCAN = new DateScan(Pos.ZERO);

  /** Implements {@link #DATE_SCAN}. */
  private static class DateScan
      extends BasePositionedApplicable2<
          List, Applicable1<List, Object>, Object> {
    DateScan(Pos pos) {
      super(BuiltIn.DATE_SCAN, pos);
    }

    @Override
    public DateScan withPos(Pos pos) {
      return new DateScan(pos);
    }

    @Override
    public List apply(Applicable1<List, Object> reader, Object stream) {
      final CharSource[] source = {new CharSource(reader, stream)};

      // The weekday has to be a valid name, but says nothing that the rest
      // of the date does not; the weekday of the result comes from the date.
      final String weekday = fixedWidth(source, 3);
      if (weekday == null || !DATE_WEEKDAY_NAMES.contains(weekday)) {
        return OPTION_NONE;
      }
      final String monthName = space(source) ? fixedWidth(source, 3) : null;
      if (monthName == null || !DATE_MONTH_NAMES.contains(monthName)) {
        return OPTION_NONE;
      }

      // The day occupies two characters, and may be written with a leading
      // zero or a leading space: "Mar 08" and "Mar  8" are both allowed.
      if (!space(source)) {
        return OPTION_NONE;
      }
      int day = 0;
      final int pad = source[0].peek();
      if (pad != ' ') {
        if (!isDigit(pad)) {
          return OPTION_NONE;
        }
        day = pad - '0';
      }
      source[0].advance();
      if (!isDigit(source[0].peek())) {
        return OPTION_NONE;
      }
      day = day * 10 + (source[0].peek() - '0');
      source[0].advance();

      final int hour = space(source) ? twoDigits(source) : -1;
      final int minute = colon(source) ? twoDigits(source) : -1;
      final int second = colon(source) ? twoDigits(source) : -1;
      if (hour < 0 || minute < 0 || second < 0 || !space(source)) {
        return OPTION_NONE;
      }

      final StringBuilder yearDigits = new StringBuilder();
      if (digits(source, yearDigits, 10) == 0) {
        return OPTION_NONE;
      }

      final OffsetDateTime date;
      try {
        final int year = Integer.parseInt(yearDigits.toString());
        date =
            OffsetDateTime.of(
                dateOf(
                    year,
                    dateMonthFromName(monthName),
                    day,
                    hour,
                    minute,
                    second),
                ZoneOffset.UTC);
      } catch (NumberFormatException | DateTimeException e) {
        throw new MorelRuntimeException(BuiltInExn.DATE, pos);
      }
      return optionSome(ImmutableList.of(date, source[0].stream()));
    }

    /** Returns whether {@code c} is a decimal digit. */
    private boolean isDigit(int c) {
      return c >= '0' && c <= '9';
    }

    /** Consumes a space, and returns whether there was one. */
    private boolean space(CharSource[] source) {
      return literal(source, ' ');
    }

    /** Consumes a colon, and returns whether there was one. */
    private boolean colon(CharSource[] source) {
      return literal(source, ':');
    }

    /** Consumes {@code c}, and returns whether it was next. */
    private boolean literal(CharSource[] source, char c) {
      if (source[0].peek() != c) {
        return false;
      }
      source[0].advance();
      return true;
    }

    /**
     * Consumes two digits and returns their value, or -1 if there are not two
     * digits.
     */
    private int twoDigits(CharSource[] source) {
      final int c0 = source[0].peek();
      if (!isDigit(c0)) {
        return -1;
      }
      source[0].advance();
      final int c1 = source[0].peek();
      if (!isDigit(c1)) {
        return -1;
      }
      source[0].advance();
      return (c0 - '0') * 10 + (c1 - '0');
    }

    /**
     * Consumes {@code n} characters, or returns null if the stream ends first.
     */
    private @Nullable String fixedWidth(CharSource[] source, int n) {
      final StringBuilder b = new StringBuilder();
      for (int i = 0; i < n; i++) {
        if (source[0].peek() < 0) {
          return null;
        }
        b.append((char) source[0].peek());
        source[0].advance();
      }
      return b.toString();
    }
  }

  /** @see BuiltIn#DATE_SECOND */
  private static final Applicable DATE_SECOND =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_SECOND) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getSecond();
        }
      };

  /** @see BuiltIn#DATE_TO_STRING */
  private static final Applicable DATE_TO_STRING =
      new BaseApplicable1<String, OffsetDateTime>(BuiltIn.DATE_TO_STRING) {
        @Override
        public String apply(OffsetDateTime d) {
          return dateToString(d);
        }
      };

  /** @see BuiltIn#DATE_TO_TIME */
  private static final Applicable DATE_TO_TIME =
      new BaseApplicable1<Long, OffsetDateTime>(BuiltIn.DATE_TO_TIME) {
        @Override
        public Long apply(OffsetDateTime d) {
          final Instant inst = d.toInstant();
          return inst.getEpochSecond() * 1_000_000_000L + inst.getNano();
        }
      };

  /** @see BuiltIn#DATE_WEEK_DAY */
  private static final Applicable DATE_WEEK_DAY =
      new BaseApplicable1<List, OffsetDateTime>(BuiltIn.DATE_WEEK_DAY) {
        @Override
        public List apply(OffsetDateTime d) {
          return DATE_WEEKDAYS.get(d.getDayOfWeek().getValue() - 1);
        }
      };

  /**
   * Weekday names, as they appear in the format that {@link #DATE_SCAN} reads.
   */
  private static final Set<String> DATE_WEEKDAY_NAMES =
      BuiltIn.Datatype.DATE_WEEKDAY.constructors().stream()
          .map(c -> c.constructor)
          .collect(ImmutableSet.toImmutableSet());

  /**
   * Weekday values for {@link BuiltIn#DATE_WEEK_DAY}, indexed Mon(0)..Sun(6).
   */
  private static final List<List<String>> DATE_WEEKDAYS =
      transformEager(
          BuiltIn.Datatype.DATE_WEEKDAY.constructors(),
          c -> ImmutableList.of(c.constructor));

  /** @see BuiltIn#DATE_YEAR */
  private static final Applicable DATE_YEAR =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_YEAR) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getYear();
        }
      };

  /** @see BuiltIn#DATE_YEAR_DAY */
  private static final Applicable DATE_YEAR_DAY =
      new BaseApplicable1<Integer, OffsetDateTime>(BuiltIn.DATE_YEAR_DAY) {
        @Override
        public Integer apply(OffsetDateTime d) {
          return d.getDayOfYear() - 1; // 0-based in SML
        }
      };

  /** Converts a nanosecond time value to an {@link OffsetDateTime}. */
  private static OffsetDateTime timeToDate(long t, ZoneId zone) {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochSecond(t / 1_000_000_000L, t % 1_000_000_000L), zone);
  }

  /**
   * Returns the local timezone from the session's {@code timeZone} property, or
   * the JVM default if the property is not set.
   */
  private static ZoneId sessionZone(Session session) {
    final String zoneId = (String) Prop.TIME_ZONE.get(session.map);
    return zoneId != null ? ZoneId.of(zoneId) : ZoneId.systemDefault();
  }

  /**
   * Returns the current instant from the session's {@code now} property, or
   * {@link Instant#now()} if the property is not set.
   */
  private static Instant sessionNow(Session session) {
    final String now = (String) Prop.NOW.get(session.map);
    return now != null ? Instant.parse(now) : Instant.now();
  }

  /** Converts a month constructor name (e.g., "Jan") to a {@link Month}. */
  private static Month dateMonthFromName(String name) {
    switch (name) {
      case "Jan":
        return Month.JANUARY;
      case "Feb":
        return Month.FEBRUARY;
      case "Mar":
        return Month.MARCH;
      case "Apr":
        return Month.APRIL;
      case "May":
        return Month.MAY;
      case "Jun":
        return Month.JUNE;
      case "Jul":
        return Month.JULY;
      case "Aug":
        return Month.AUGUST;
      case "Sep":
        return Month.SEPTEMBER;
      case "Oct":
        return Month.OCTOBER;
      case "Nov":
        return Month.NOVEMBER;
      case "Dec":
        return Month.DECEMBER;
      default:
        throw new AssertionError(name);
    }
  }

  /**
   * Formats an {@link OffsetDateTime} using an SML {@code strftime}-style
   * format string.
   */
  private static String dateFmt(String fmt, OffsetDateTime d) {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fmt.length(); i++) {
      final char c = fmt.charAt(i);
      if (c == '%' && i + 1 < fmt.length()) {
        final char code = fmt.charAt(++i);
        switch (code) {
          case 'a':
            sb.append(d.format(Formatters.EEE));
            break;
          case 'A':
            sb.append(d.format(Formatters.EEEE));
            break;
          case 'b':
          case 'h':
            sb.append(d.format(Formatters.MMM));
            break;
          case 'B':
            sb.append(d.format(Formatters.MMMM));
            break;
          case 'c':
            sb.append(dateToString(d, false));
            break;
          case 'd':
            sb.append(format("%02d", d.getDayOfMonth()));
            break;
          case 'e':
            sb.append(format("%2d", d.getDayOfMonth()));
            break;
          case 'H':
            sb.append(format("%02d", d.getHour()));
            break;
          case 'I':
            sb.append(format("%02d", ((d.getHour() - 1 + 12) % 12) + 1));
            break;
          case 'j':
            sb.append(format("%03d", d.getDayOfYear()));
            break;
          case 'm':
            sb.append(format("%02d", d.getMonthValue()));
            break;
          case 'M':
            sb.append(format("%02d", d.getMinute()));
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'p':
            sb.append(d.getHour() < 12 ? "AM" : "PM");
            break;
          case 'S':
            sb.append(format("%02d", d.getSecond()));
            break;
          case 't':
            sb.append('\t');
            break;
          case 'w':
            sb.append(d.getDayOfWeek().getValue() % 7); // 0=Sun, 6=Sat
            break;
          case 'y':
            sb.append(format("%02d", d.getYear() % 100));
            break;
          case 'Y':
            sb.append(format("%04d", d.getYear()));
            break;
          case 'Z':
            sb.append(d.getOffset().getId());
            break;
          case '%':
            sb.append('%');
            break;
          default:
            sb.append('%').append(code);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Formats an {@link OffsetDateTime} as "Www Mmm DD HH:MM:SS YYYY", the format
   * used by SML's {@code Date.toString}.
   */
  private static String dateToString(OffsetDateTime d) {
    return dateToString(d, true);
  }

  /**
   * Formats an {@link OffsetDateTime} as "Www Mmm DD HH:MM:SS YYYY".
   *
   * <p>{@code Date.toString} pads the day with a zero, and the {@code %c}
   * format code pads it with a space; the two are otherwise the same. The year
   * is not padded, as {@code %Y} does not pad it.
   */
  private static String dateToString(OffsetDateTime d, boolean zeroPadDay) {
    return format(
        Locale.ROOT,
        zeroPadDay
            ? "%s %s %02d %02d:%02d:%02d %d"
            : "%s %s %2d %02d:%02d:%02d %d",
        d.format(Formatters.EEE),
        d.format(Formatters.MMM),
        d.getDayOfMonth(),
        d.getHour(),
        d.getMinute(),
        d.getSecond(),
        d.getYear());
  }

  /**
   * Converts the result of {@link Comparable#compareTo(Object)} to an {@code
   * Order} value.
   */
  static List order(int c) {
    if (c < 0) {
      return ORDER_LESS;
    }
    if (c > 0) {
      return ORDER_GREATER;
    }
    return ORDER_EQUAL;
  }

  /**
   * Appends to {@code b} the digits that {@code source} starts with, and
   * returns how many there were.
   */
  static int digits(CharSource[] source, StringBuilder b, int base) {
    int n = 0;
    while (source[0].peek() >= 0
        && Character.digit(source[0].peek(), base) >= 0) {
      b.append((char) source[0].peek());
      source[0].advance();
      ++n;
    }
    return n;
  }

  /** @see BuiltIn#TIME_ADD */
  private static final Applicable2 TIME_ADD =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.TIME_ADD) {
        @Override
        public Long apply(Long t1, Long t2) {
          return t1 + t2;
        }
      };

  /** @see BuiltIn#TIME_COMPARE */
  private static final Applicable2 TIME_COMPARE =
      new BaseApplicable2<List, Long, Long>(BuiltIn.TIME_COMPARE) {
        @Override
        public List apply(Long t1, Long t2) {
          return order(Long.compare(t1, t2));
        }
      };

  /** @see BuiltIn#TIME_FMT */
  private static final Applicable1 TIME_FMT =
      new BaseApplicable1<Applicable, Integer>(BuiltIn.TIME_FMT) {
        @Override
        public Applicable apply(Integer n) {
          return new BaseApplicable1<String, Long>(BuiltIn.TIME_FMT) {
            @Override
            public String apply(Long t) {
              return timeFmt(n, t);
            }
          };
        }
      };

  /** @see BuiltIn#TIME_FROM_MICROSECONDS */
  private static final Applicable1 TIME_FROM_MICROSECONDS =
      new BaseApplicable1<Long, Integer>(BuiltIn.TIME_FROM_MICROSECONDS) {
        @Override
        public Long apply(Integer n) {
          return (long) n * 1_000L;
        }
      };

  /** @see BuiltIn#TIME_FROM_MILLISECONDS */
  private static final Applicable1 TIME_FROM_MILLISECONDS =
      new BaseApplicable1<Long, Integer>(BuiltIn.TIME_FROM_MILLISECONDS) {
        @Override
        public Long apply(Integer n) {
          return (long) n * 1_000_000L;
        }
      };

  /** @see BuiltIn#TIME_FROM_NANOSECONDS */
  private static final Applicable1 TIME_FROM_NANOSECONDS =
      new BaseApplicable1<Long, Integer>(BuiltIn.TIME_FROM_NANOSECONDS) {
        @Override
        public Long apply(Integer n) {
          return (long) n;
        }
      };

  /** @see BuiltIn#TIME_FROM_REAL */
  private static final Applicable1 TIME_FROM_REAL = new TimeFromReal(Pos.ZERO);

  /** Implements {@link #TIME_FROM_REAL}. */
  private static class TimeFromReal
      extends BasePositionedApplicable1<Long, Float> {
    TimeFromReal(Pos pos) {
      super(BuiltIn.TIME_FROM_REAL, pos);
    }

    @Override
    public TimeFromReal withPos(Pos pos) {
      return new TimeFromReal(pos);
    }

    @Override
    public Long apply(Float r) {
      if (Float.isNaN(r) || Float.isInfinite(r)) {
        throw new MorelRuntimeException(BuiltInExn.TIME, pos);
      }
      return (long) ((double) r * 1_000_000_000d);
    }
  }

  /** @see BuiltIn#TIME_FROM_SECONDS */
  private static final Applicable1 TIME_FROM_SECONDS =
      new BaseApplicable1<Long, Integer>(BuiltIn.TIME_FROM_SECONDS) {
        @Override
        public Long apply(Integer n) {
          return (long) n * 1_000_000_000L;
        }
      };

  /** @see BuiltIn#TIME_FROM_STRING */
  private static final Applicable1 TIME_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.TIME_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(TIME_SCAN, s);
        }
      };

  /** @see BuiltIn#TIME_GE */
  private static final Applicable2 TIME_GE =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.TIME_GE) {
        @Override
        public Boolean apply(Long t1, Long t2) {
          return t1 >= t2;
        }
      };

  /** @see BuiltIn#TIME_GT */
  private static final Applicable2 TIME_GT =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.TIME_GT) {
        @Override
        public Boolean apply(Long t1, Long t2) {
          return t1 > t2;
        }
      };

  /** @see BuiltIn#TIME_LE */
  private static final Applicable2 TIME_LE =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.TIME_LE) {
        @Override
        public Boolean apply(Long t1, Long t2) {
          return t1 <= t2;
        }
      };

  /** @see BuiltIn#TIME_LT */
  private static final Applicable2 TIME_LT =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.TIME_LT) {
        @Override
        public Boolean apply(Long t1, Long t2) {
          return t1 < t2;
        }
      };

  /** @see BuiltIn#TIME_NOW */
  private static final ApplicableImpl TIME_NOW =
      new ApplicableImpl(BuiltIn.TIME_NOW) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final Instant now = sessionNow(stack.session);
          return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        }
      };

  /** @see BuiltIn#TIME_SCAN */
  private static final TimeScan TIME_SCAN = new TimeScan(Pos.ZERO);

  /** Implements {@link #TIME_SCAN}. */
  private static class TimeScan
      extends BasePositionedApplicable2<
          List, Applicable1<List, Object>, Object> {
    TimeScan(Pos pos) {
      super(BuiltIn.TIME_SCAN, pos);
    }

    @Override
    public TimeScan withPos(Pos pos) {
      return new TimeScan(pos);
    }

    @Override
    public List apply(Applicable1<List, Object> reader, Object stream) {
      final CharSource[] source = {new CharSource(reader, stream)};
      source[0].skipWhitespace();

      final boolean negative;
      if (source[0].peek() == '~' || source[0].peek() == '-') {
        negative = true;
        source[0].advance();
      } else {
        negative = false;
        if (source[0].peek() == '+') {
          source[0].advance();
        }
      }

      final StringBuilder integer = new StringBuilder();
      digits(source, integer, 10);
      final StringBuilder fraction = new StringBuilder();
      if (source[0].peek() == '.') {
        // A decimal point must be followed by at least one digit; if it is
        // not, the whole time is ill-formed, not merely finished.
        source[0].advance();
        if (digits(source, fraction, 10) == 0) {
          return OPTION_NONE;
        }
      } else if (integer.length() == 0) {
        return OPTION_NONE;
      }

      // Nanoseconds are the finest we can represent; discard the rest, and
      // pad if there were fewer than nine fractional digits.
      while (fraction.length() < 9) {
        fraction.append('0');
      }
      fraction.setLength(9);

      final long nanos;
      try {
        final long seconds =
            integer.length() == 0 ? 0 : Long.parseLong(integer.toString());
        nanos =
            Math.addExact(
                Math.multiplyExact(seconds, 1_000_000_000L),
                Long.parseLong(fraction.toString()));
      } catch (NumberFormatException | ArithmeticException e) {
        throw new MorelRuntimeException(BuiltInExn.TIME, pos);
      }
      return optionSome(
          ImmutableList.of(negative ? -nanos : nanos, source[0].stream()));
    }
  }

  /** @see BuiltIn#TIME_SUBTRACT */
  private static final Applicable2 TIME_SUBTRACT =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.TIME_SUBTRACT) {
        @Override
        public Long apply(Long t1, Long t2) {
          return t1 - t2;
        }
      };

  /** @see BuiltIn#TIME_TO_MICROSECONDS */
  private static final Applicable1 TIME_TO_MICROSECONDS =
      new BaseApplicable1<Integer, Long>(BuiltIn.TIME_TO_MICROSECONDS) {
        @Override
        public Integer apply(Long t) {
          return (int) (t / 1_000L);
        }
      };

  /** @see BuiltIn#TIME_TO_MILLISECONDS */
  private static final Applicable1 TIME_TO_MILLISECONDS =
      new BaseApplicable1<Integer, Long>(BuiltIn.TIME_TO_MILLISECONDS) {
        @Override
        public Integer apply(Long t) {
          return (int) (t / 1_000_000L);
        }
      };

  /** @see BuiltIn#TIME_TO_NANOSECONDS */
  private static final Applicable1 TIME_TO_NANOSECONDS =
      new BaseApplicable1<Integer, Long>(BuiltIn.TIME_TO_NANOSECONDS) {
        @Override
        public Integer apply(Long t) {
          return (int) (long) t;
        }
      };

  /** @see BuiltIn#TIME_TO_REAL */
  private static final Applicable1 TIME_TO_REAL =
      new BaseApplicable1<Float, Long>(BuiltIn.TIME_TO_REAL) {
        @Override
        public Float apply(Long t) {
          return (float) (t / 1e9);
        }
      };

  /** @see BuiltIn#TIME_TO_SECONDS */
  private static final Applicable1 TIME_TO_SECONDS =
      new BaseApplicable1<Integer, Long>(BuiltIn.TIME_TO_SECONDS) {
        @Override
        public Integer apply(Long t) {
          return (int) (t / 1_000_000_000L);
        }
      };

  /** @see BuiltIn#TIME_TO_STRING */
  private static final Applicable1 TIME_TO_STRING =
      new BaseApplicable1<String, Long>(BuiltIn.TIME_TO_STRING) {
        @Override
        public String apply(Long t) {
          return timeFmt(3, t);
        }
      };

  /** @see BuiltIn#TIME_ZERO_TIME */
  private static final Long TIME_ZERO_TIME = 0L;

  /**
   * Formats a time value as decimal seconds with {@code n} fractional digits,
   * using {@code ~} prefix for negative values (SML convention).
   */
  private static String timeFmt(int n, long t) {
    final boolean negative = t < 0;
    final long abs = negative ? -t : t;
    final long seconds = abs / 1_000_000_000L;
    final StringBuilder b = new StringBuilder();
    if (negative) {
      b.append('~');
    }
    b.append(seconds);
    if (n > 0) {
      b.append('.');
      final long nanos = abs % 1_000_000_000L;
      String frac = format("%09d", nanos).substring(0, Math.min(n, 9));
      b.append(frac);
      while (frac.length() < n) {
        b.append('0');
        --n;
      }
    }
    return b.toString();
  }

  static class Formatters {
    static final DateTimeFormatter EEE =
        DateTimeFormatter.ofPattern("EEE", Locale.ROOT);
    static final DateTimeFormatter EEEE =
        DateTimeFormatter.ofPattern("EEEE", Locale.ROOT);
    static final DateTimeFormatter MMM =
        DateTimeFormatter.ofPattern("MMM", Locale.ROOT);
    static final DateTimeFormatter MMMM =
        DateTimeFormatter.ofPattern("MMMM", Locale.ROOT);
  }
}

// End DateCodes.java
