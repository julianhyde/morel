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

import static net.hydromatic.morel.parse.Parsers.appendId;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.JavaVersion;

/**
 * Converts a value of any type to its standard string form.
 *
 * <p>Unlike {@link net.hydromatic.morel.compile.Pretty}, this is a plain, flat
 * renderer: there is no line wrapping and no truncation, so none of the
 * pretty-printing properties (print depth, line width, string depth, and so
 * forth) apply. It renders composite values (records, tuples, lists, bags)
 * recursively; {@code Pretty} does not use that code path, because it lays wide
 * values out across lines, but both share the primitive ({@link
 * #renderPrimitive}) rendering.
 *
 * <p>Every renderer comes in two forms: one that returns a {@link String}, and
 * one that appends to a {@link StringBuilder} (the more efficient form, which
 * the String form calls). There is a {@code renderX} method for each primitive
 * and each composite (list, bag, record, datatype), plus general {@link
 * #renderValue} and {@link #renderPrimitive} dispatchers.
 */
public class Render {
  private Render() {}

  /** Converts a {@code float} to a String per the JDK. */
  static final Function<Float, String> FLOAT_TO_STRING =
      JavaVersion.CURRENT.compareTo(JavaVersion.of(19)) >= 0
          ? f -> Float.toString(f)
          : Render::floatToString0;

  /** Renders a value of the given type to a flat string. */
  public static String renderValue(
      TypeSystem typeSystem, Type type, Object value) {
    return renderValue(new StringBuilder(), typeSystem, type, value).toString();
  }

  /** Appends the rendering of a value of the given type to {@code buf}. */
  public static StringBuilder renderValue(
      StringBuilder buf, TypeSystem typeSystem, Type type, Object value) {
    while (type instanceof AliasType) {
      type = ((AliasType) type).type;
    }
    if (value instanceof Variant) {
      final Variant v = (Variant) value;
      return renderValue(buf, typeSystem, v.type, v.value);
    }
    switch (type.op()) {
        // lint: sort where '#case' until '#default:'
      case DATA_TYPE:
        return renderDatatype(buf, typeSystem, (DataType) type, value);
      case FORALL_TYPE:
        return renderValue(buf, typeSystem, ((ForallType) type).type, value);
      case FUNCTION_TYPE:
        return buf.append("fn");
      case ID:
        return renderPrimitive(buf, (PrimitiveType) type, value);
      case LIST:
        return renderList(buf, typeSystem, type.elementType(), toList(value));
      case RECORD_TYPE:
        return renderRecord(buf, typeSystem, (RecordType) type, toList(value));
      case TUPLE_TYPE:
        return renderTuple(
            buf, typeSystem, ((TupleType) type).argTypes, toList(value));
      default:
        return buf.append(value);
    }
  }

  /** Renders a list value, {@code [e0,e1,...]}. */
  public static String renderList(
      TypeSystem typeSystem, Type elementType, List<Object> list) {
    return renderList(new StringBuilder(), typeSystem, elementType, list)
        .toString();
  }

  public static StringBuilder renderList(
      StringBuilder buf,
      TypeSystem typeSystem,
      Type elementType,
      List<Object> list) {
    return appendSeq(buf, typeSystem, "[", "]", elementType, list);
  }

  /**
   * Renders a bag value. A bag prints like a list, {@code [e0,e1,...]},
   * distinguishable only by its type.
   */
  public static String renderBag(
      TypeSystem typeSystem, Type elementType, List<Object> list) {
    return renderBag(new StringBuilder(), typeSystem, elementType, list)
        .toString();
  }

  public static StringBuilder renderBag(
      StringBuilder buf,
      TypeSystem typeSystem,
      Type elementType,
      List<Object> list) {
    return appendSeq(buf, typeSystem, "[", "]", elementType, list);
  }

  /** Renders a record value, {@code {name0=v0,name1=v1,...}}. */
  public static String renderRecord(
      TypeSystem typeSystem, RecordType recordType, List<Object> value) {
    return renderRecord(new StringBuilder(), typeSystem, recordType, value)
        .toString();
  }

  public static StringBuilder renderRecord(
      StringBuilder buf,
      TypeSystem typeSystem,
      RecordType recordType,
      List<Object> value) {
    final Iterator<Object> iterator = value.iterator();
    buf.append('{');
    final boolean[] first = {true};
    recordType.argNameTypes.forEach(
        (name, fieldType) -> {
          if (!first[0]) {
            buf.append(',');
          }
          first[0] = false;
          appendId(buf, name).append('=');
          renderValue(buf, typeSystem, fieldType, iterator.next());
        });
    return buf.append('}');
  }

  /**
   * Renders a datatype value: a collection like a list, an opaque value
   * directly, or a constructor application.
   */
  public static String renderDatatype(
      TypeSystem typeSystem, DataType dataType, Object value) {
    return renderDatatype(new StringBuilder(), typeSystem, dataType, value)
        .toString();
  }

  public static StringBuilder renderDatatype(
      StringBuilder buf,
      TypeSystem typeSystem,
      DataType dataType,
      Object value) {
    if (!(value instanceof List)) {
      // An abstract "doc" prints as "-"; other opaque values (e.g. "cx",
      // "measure") print via toString.
      return buf.append(
          dataType.name.equals("doc") ? "-" : String.valueOf(value));
    }
    final List<Object> list = toList(value);
    if (dataType.name.equals("vector")) {
      buf.append('#');
      return appendSeq(buf, typeSystem, "[", "]", dataType.arg(0), list);
    }
    if (dataType.isCollection()) {
      return appendSeq(buf, typeSystem, "[", "]", dataType.elementType(), list);
    }
    final String tyConName = (String) list.get(0);
    if (list.size() < 2) {
      return buf.append(tyConName); // nullary constructor, e.g. "LESS"
    }
    Object arg = list.get(1);
    if (arg instanceof Variant) {
      arg = ((Variant) arg).value;
    }
    if (dataType.name.equals("continuous_set")
        || dataType.name.equals("discrete_set")) {
      arg = Codes.setToRangeList(arg);
    }
    final Type argType = dataType.typeConstructors(typeSystem).get(tyConName);
    final boolean needParentheses =
        argType.op() == Op.DATA_TYPE
            && arg instanceof List
            && ((List<?>) arg).size() > 1;
    buf.append(tyConName).append(' ');
    if (needParentheses) {
      buf.append('(');
      renderValue(buf, typeSystem, argType, arg);
      return buf.append(')');
    }
    return renderValue(buf, typeSystem, argType, arg);
  }

  private static StringBuilder renderTuple(
      StringBuilder buf,
      TypeSystem typeSystem,
      List<? extends Type> types,
      List<Object> list) {
    buf.append('(');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        buf.append(',');
      }
      renderValue(buf, typeSystem, types.get(i), list.get(i));
    }
    return buf.append(')');
  }

  private static StringBuilder appendSeq(
      StringBuilder buf,
      TypeSystem typeSystem,
      String open,
      String close,
      Type elementType,
      List<Object> list) {
    if (list instanceof RelList) {
      return buf.append(RelList.RELATION);
    }
    buf.append(open);
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        buf.append(',');
      }
      renderValue(buf, typeSystem, elementType, list.get(i));
    }
    return buf.append(close);
  }

  /**
   * Renders a primitive value to a string; strings and characters are quoted
   * and escaped, with no truncation. Parallel to {@link #renderPrimitive(
   * StringBuilder, PrimitiveType, Object)} but avoids allocating a {@link
   * StringBuilder} for primitives whose String form is direct.
   */
  public static String renderPrimitive(
      PrimitiveType primitiveType, Object value) {
    switch (primitiveType) {
        // lint: sort where '#case' until '#default:'
      case BOOL:
        return renderBool((Boolean) value);
      case CHAR:
        return renderChar((Character) value);
      case INT:
        return renderInt((Integer) value);
      case REAL:
        return renderReal((Float) value);
      case STRING:
        return renderString((String) value);
      case UNIT:
        return renderUnit();
      case WORD:
        return renderWord((Long) value);
      default:
        return String.valueOf(value);
    }
  }

  /** Appends the rendering of a primitive value to {@code buf}. */
  public static StringBuilder renderPrimitive(
      StringBuilder buf, PrimitiveType primitiveType, Object value) {
    switch (primitiveType) {
        // lint: sort where '#case' until '#default:'
      case BOOL:
        return renderBool(buf, (Boolean) value);
      case CHAR:
        return renderChar(buf, (Character) value);
      case INT:
        return renderInt(buf, (Integer) value);
      case REAL:
        return renderReal(buf, (Float) value);
      case STRING:
        return renderString(buf, (String) value);
      case UNIT:
        return renderUnit(buf);
      case WORD:
        return renderWord(buf, (Long) value);
      default:
        return buf.append(value);
    }
  }

  // Per-primitive renderers, each in String and StringBuilder forms.

  public static String renderBool(boolean b) {
    return Boolean.toString(b);
  }

  public static StringBuilder renderBool(StringBuilder buf, boolean b) {
    return buf.append(b);
  }

  public static String renderChar(char c) {
    return "#\"" + Parsers.charToString(c) + '"';
  }

  public static StringBuilder renderChar(StringBuilder buf, char c) {
    return buf.append('#')
        .append('"')
        .append(Parsers.charToString(c))
        .append('"');
  }

  /**
   * Renders an {@code int} in Standard ML form, with '~' for the minus sign.
   */
  public static String renderInt(int i) {
    return Integer.toString(i).replace('-', '~');
  }

  public static StringBuilder renderInt(StringBuilder buf, int i) {
    return buf.append(renderInt(i));
  }

  /**
   * Renders a {@code float} in the format expected of Standard ML {@code real}
   * values.
   *
   * <p>Matches Standard ML's {@code Real.toString}, which drops the trailing
   * ".0" from whole-number reals (so {@code 1.0} prints as "1" and {@code
   * 1.0e10} prints as "1E10").
   */
  public static String renderReal(float f) {
    if (Float.isFinite(f)) {
      return stripTrailingZero(FLOAT_TO_STRING.apply(f)).replace('-', '~');
    } else if (f == Float.POSITIVE_INFINITY) {
      return "inf";
    } else if (f == Float.NEGATIVE_INFINITY) {
      return "~inf";
    } else if (Float.isNaN(f)) {
      return "nan";
    } else {
      throw new AssertionError("unknown float " + f);
    }
  }

  public static StringBuilder renderReal(StringBuilder buf, float f) {
    return buf.append(renderReal(f));
  }

  public static String renderString(String s) {
    return renderString(new StringBuilder(), s).toString();
  }

  public static StringBuilder renderString(StringBuilder buf, String s) {
    buf.append('"');
    Parsers.stringToString(s, buf);
    return buf.append('"');
  }

  public static String renderUnit() {
    return "()";
  }

  public static StringBuilder renderUnit(StringBuilder buf) {
    return buf.append("()");
  }

  /** Renders a {@code word} in hexadecimal, like Standard ML. */
  public static String renderWord(long w) {
    return "0wx" + Long.toUnsignedString(w, 16).toUpperCase(Locale.ROOT);
  }

  public static StringBuilder renderWord(StringBuilder buf, long w) {
    return buf.append("0wx")
        .append(Long.toUnsignedString(w, 16).toUpperCase(Locale.ROOT));
  }

  /**
   * If the mantissa part of the given Java float string ends with ".0", removes
   * those two characters. For example, "1.0" becomes "1" and "1.0E10" becomes
   * "1E10". Leaves strings like "1.5" and "1.5E10" unchanged.
   */
  private static String stripTrailingZero(String s) {
    int e = s.indexOf('E');
    int mantissaEnd = e < 0 ? s.length() : e;
    if (mantissaEnd >= 2
        && s.charAt(mantissaEnd - 1) == '0'
        && s.charAt(mantissaEnd - 2) == '.') {
      return s.substring(0, mantissaEnd - 2) + s.substring(mantissaEnd);
    }
    return s;
  }

  private static String floatToString0(float f) {
    String s = Float.toString(f);
    switch (s) {
      case "1.17549435E-38":
        return "1.1754944E-38";
      case "1.23456795E12":
        return "1.234568E12";
      case "1.23456791E11":
        return "1.2345679E11";
      case "1.23456788E10":
        return "1.2345679E10";
      case "1.23456792E8":
        return "1.2345679E8";
      default:
        return s;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> toList(Object value) {
    if (value instanceof TypedValue) {
      return (List<Object>) ((TypedValue) value).valueAs(List.class);
    }
    return (List<Object>) value;
  }
}

// End Render.java
