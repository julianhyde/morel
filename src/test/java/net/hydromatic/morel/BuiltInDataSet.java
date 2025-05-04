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

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import net.hydromatic.foodmart.data.hsqldb.FoodmartHsqldb;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteForeignValue;
import net.hydromatic.morel.foreign.CalciteForeignValue.NameConverter;
import net.hydromatic.morel.foreign.DataSet;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.scott.data.hsqldb.ScottHsqldb;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.schema.SchemaPlus;
import org.checkerframework.checker.nullness.qual.NonNull;

/** Data sets for testing. */
enum BuiltInDataSet implements DataSet {
  /**
   * Returns a value based on the Foodmart JDBC database.
   *
   * <p>It is a record with fields for the following tables:
   *
   * <ul>
   *   <li>{@code bonus} - Bonus table
   *   <li>{@code customer} - Customers table
   *   <li>{@code customer_sales_fact} - Customer sales fact table
   *   <li>{@code dept} - Departments table
   *   <li>{@code emp} - Employees table
   *   <li>{@code salgrade} - Salary grade table
   * </ul>
   */
  FOODMART("foodmart", NameConverter.TO_LOWER) {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          JdbcSchema.dataSource(
              FoodmartHsqldb.URI,
              null,
              FoodmartHsqldb.USER,
              FoodmartHsqldb.PASSWORD);
      String hsqldbSchema = "foodmart";
      final JdbcSchema schema =
          JdbcSchema.create(
              rootSchema, schemaName, dataSource, null, hsqldbSchema);
      return rootSchema.add(schemaName, schema);
    }
  },

  /**
   * Returns a value based on the Scott JDBC database.
   *
   * <p>It is a record with fields for the following tables:
   *
   * <ul>
   *   <li>{@code bonuses} - Bonus table
   *   <li>{@code depts} - Departments table
   *   <li>{@code emps} - Employees table
   *   <li>{@code salgrades} - Salary grade table
   * </ul>
   */
  SCOTT("scott", BuiltInDataSet::scottNameConverter) {
    SchemaPlus schema(SchemaPlus rootSchema) {
      final DataSource dataSource =
          JdbcSchema.dataSource(
              ScottHsqldb.URI, null, ScottHsqldb.USER, ScottHsqldb.PASSWORD);
      final String hsqldbSchema = "SCOTT";
      final JdbcSchema schema =
          JdbcSchema.create(
              rootSchema, schemaName, dataSource, null, hsqldbSchema);
      return rootSchema.add(schemaName, schema);
    }
  };

  /**
   * Map of all known data sets.
   *
   * <p>Contains "foodmart" and "scott".
   */
  static final Map<String, DataSet> DICTIONARY =
      Stream.of(BuiltInDataSet.values())
          .collect(
              Collectors.toMap(d -> d.name().toLowerCase(Locale.ROOT), d -> d));

  final String schemaName;
  final NameConverter nameConverter;

  BuiltInDataSet(String schemaName, NameConverter nameConverter) {
    this.schemaName = requireNonNull(schemaName);
    this.nameConverter = requireNonNull(nameConverter);
  }

  /** Returns the Calcite schema of this data set. */
  abstract SchemaPlus schema(SchemaPlus rootSchema);

  @Override
  public ForeignValue foreignValue(Calcite calcite) {
    SchemaPlus schema = schema(calcite.rootSchema);
    return new CalciteForeignValue(calcite, schema, nameConverter);
  }

  /** Creates a {@link NameConverter} for the "scott" database. */
  private static String scottNameConverter(List<String> path, String name) {
    switch (name) {
      case "EMP":
        return "emps";
      case "DEPT":
        return "depts";
      case "BONUS":
        return "bonuses";
      case "SALGRADE":
        return "salgrades";
      default:
        return name.toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Map of built-in data sets.
   *
   * <p>Typically passed to {@link Shell} via the {@code --foreign} argument.
   */
  @SuppressWarnings("unused")
  public static class Dictionary extends AbstractMap<String, DataSet> {
    @Override
    @NonNull
    public Set<Entry<String, DataSet>> entrySet() {
      return DICTIONARY.entrySet();
    }
  }
}

// End BuiltInDataSet.java
