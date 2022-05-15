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

import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.CalciteForeignValue;
import net.hydromatic.morel.foreign.ForeignValue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.apache.calcite.util.Util.toLinux;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class NestedCalciteSchemaTest {

  @Test
  void test() {
    Schema userSchema = new ReflectiveSchema(new UserSchema());
    Schema todoSchema = new ReflectiveSchema(new TodoSchema());

    Map<String, ForeignValue> foreignValueMap = Calcite
        .withDataSets(
            ImmutableMap.of("user", (Calcite calcite) -> {
              SchemaPlus newSchema = calcite.rootSchema.add("users", userSchema);
              newSchema.add("todo", todoSchema);
              newSchema.add("todo2", todoSchema);
              return new CalciteForeignValue(calcite, newSchema, true);
            })
        )
        .foreignValues();

    String sql = "user;\n"
        + "user.todo;\n"
        + "user.todo2;\n"
        + "from t in user.users yield t;\n"
        + "from t in user.todo2.todos yield t;\n";
    InputStream in = new ByteArrayInputStream(sql.getBytes());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Main main =
        new Main(ImmutableList.of(), in, new PrintStream(out), foreignValueMap,
            new File(""));

    main.run();
    System.out.println(out);

    String expected = "val it = {todo={todos=<relation>},"
        + "todo2={todos=<relation>},"
        + "users=<relation>}\n"
        + "  : {todo:{todos:{completed:bool, name:string} list}, "
        + "todo2:{todos:{completed:bool, name:string} list}, "
        + "users:{age:int, name:string} list}\n"
        + "val it = {todos=<relation>} : {todos:{completed:bool, name:string} list}\n"
        + "val it = {todos=<relation>} : {todos:{completed:bool, name:string} list}\n"
        + "val it = [{age=20,name=\"John\"},{age=21,name=\"Jane\"},{age=22,name=\"Jack\"}]\n"
        + "  : {age:int, name:string} list\n"
        + "val it =\n"
        + "  [{completed=false,name=\"Buy milk\"},{completed=false,name=\"Buy eggs\"},\n"
        + "   {completed=false,name=\"Buy bread\"}] : {completed:bool, name:string} list\n";
    assertThat(toLinux(out.toString()), is(expected));
  }


  public class UserSchema {
    @Override
    public String toString() {
      return "UserSchema";
    }

    public final User[] users = new User[]{
        new User("John", 20),
        new User("Jane", 21),
        new User("Jack", 22)
    };
  }

  public class User {
    public String name;
    public int age;

    User(String name, int age) {
      this.name = name;
      this.age = age;
    }
  }

  public class TodoSchema {
    @Override
    public String toString() {
      return "TodoSchema";
    }

    public final Todo[] todos = new Todo[]{
        new Todo("Buy milk", false),
        new Todo("Buy eggs", false),
        new Todo("Buy bread", false)
    };
  }


  public class Todo {
    public String name;
    public boolean completed;

    Todo(String name, boolean completed) {
      this.name = name;
      this.completed = completed;
    }
  }

}

// End NestedCalciteSchemaTest.java
