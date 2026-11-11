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
package net.hydromatic.morel.tpcds;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests {@link TpcdsData}'s reading of {@code tpcds/schema.sql}. */
class TpcdsDataTest {
  @Test
  void testSchema() throws IOException {
    final Map<String, TpcdsData.TableDef> tables = TpcdsData.schema();
    assertThat(tables.size(), is(24));
    int columnCount = 0;
    int notNullCount = 0;
    for (TpcdsData.TableDef table : tables.values()) {
      for (TpcdsData.ColumnDef column : table.columns) {
        ++columnCount;
        if (!column.nullable) {
          ++notNullCount;
        }
      }
    }
    assertThat(columnCount, is(425));
    assertThat(notNullCount, is(46));

    final TpcdsData.TableDef storeSales =
        requireNonNull(tables.get("store_sales"));
    assertThat(storeSales.columns.size(), is(23));
    assertThat(
        storeSales.columns.get(0).header(), is("ss_sold_date_sk:int option"));
    assertThat(storeSales.columns.get(2).header(), is("ss_item_sk:int"));
    assertThat(
        storeSales.columns.get(13).header(),
        is("ss_sales_price:decimal option"));
    final TpcdsData.TableDef dateDim = requireNonNull(tables.get("date_dim"));
    assertThat(dateDim.columns.get(1).header(), is("d_date_id:string"));
    assertThat(dateDim.columns.get(2).header(), is("d_date:date option"));
  }
}

// End TpcdsDataTest.java
