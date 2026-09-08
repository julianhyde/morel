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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.AnalyzePlanRequest;
import org.apache.spark.connect.proto.AnalyzePlanResponse;
import org.apache.spark.connect.proto.DataType;
import org.apache.spark.connect.proto.ExecutePlanRequest;
import org.apache.spark.connect.proto.ExecutePlanResponse;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Read;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.SQL;
import org.apache.spark.connect.proto.SparkConnectServiceGrpc;
import org.apache.spark.connect.proto.UserContext;

/** A connection to a Spark Connect server, over gRPC. */
class SparkConnection implements SparkBackend.Connection {
  /** Default port of a Spark Connect server. */
  static final int DEFAULT_PORT = 15002;

  private final TypeSystem typeSystem;
  private final String uri;
  private final String sessionId = UUID.randomUUID().toString();
  private final UserContext userContext;
  private final ManagedChannel channel;
  private final SparkConnectServiceGrpc.SparkConnectServiceBlockingStub stub;
  private boolean closed;

  SparkConnection(TypeSystem typeSystem, String uri) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.uri = requireNonNull(uri, "uri");
    final URI u = parse(uri);
    final int port = u.getPort() < 0 ? DEFAULT_PORT : u.getPort();
    String userId = System.getProperty("user.name", "morel");
    // Parameters follow the path, separated by ';', e.g.
    // "sc://host:port/;user_id=fred;token=...".
    final String path = u.getPath() == null ? "" : u.getPath();
    for (String param : path.split(";")) {
      final int eq = param.indexOf('=');
      if (eq > 0 && param.substring(0, eq).equals("user_id")) {
        userId = param.substring(eq + 1);
      }
    }
    this.userContext = UserContext.newBuilder().setUserId(userId).build();
    this.channel =
        ManagedChannelBuilder.forAddress(u.getHost(), port)
            .usePlaintext()
            .build();
    this.stub = SparkConnectServiceGrpc.newBlockingStub(channel);
  }

  private static URI parse(String uri) {
    final URI u;
    try {
      u = new URI(uri);
    } catch (URISyntaxException e) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Invalid Spark Connect URI: " + uri, e);
    }
    if (!"sc".equals(u.getScheme()) || u.getHost() == null) {
      throw new SparkBackend.SparkException(
          "CONNECTION",
          "Invalid Spark Connect URI: "
              + uri
              + " (expected sc://host[:port][/;param=value...])");
    }
    return u;
  }

  @Override
  public String uri() {
    return uri;
  }

  @Override
  public SparkBackend.Result sql(String sql) {
    final Relation relation =
        Relation.newBuilder().setSql(SQL.newBuilder().setQuery(sql)).build();
    return execute(relation);
  }

  /** Executes a plan whose root is the given relation. */
  SparkBackend.Result execute(Relation relation) {
    checkOpen();
    final ExecutePlanRequest request =
        ExecutePlanRequest.newBuilder()
            .setSessionId(sessionId)
            .setUserContext(userContext)
            .setClientType("morel")
            .setPlan(Plan.newBuilder().setRoot(relation))
            .build();
    DataType schema = null;
    final List<ByteString> batches = new ArrayList<>();
    try {
      final Iterator<ExecutePlanResponse> responses = stub.executePlan(request);
      while (responses.hasNext()) {
        final ExecutePlanResponse response = responses.next();
        if (response.hasSchema()) {
          schema = response.getSchema();
        }
        if (response.hasArrowBatch()) {
          batches.add(response.getArrowBatch().getData());
        }
      }
    } catch (StatusRuntimeException e) {
      throw toException(e);
    }
    if (schema == null) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Spark returned no schema for the query");
    }
    final Type rowType = SparkTypes.rowType(typeSystem, schema);
    final List<Object> rows = ArrowDecoder.decode(batches, rowType);
    return new ResultImpl(rowType, rows);
  }

  /**
   * Converts a gRPC failure into a {@link SparkBackend.SparkException},
   * carrying Spark's error class if the server sent one.
   *
   * <p>Spark attaches a {@code google.rpc.ErrorInfo} to the status, whose
   * reason is the Java exception class and whose metadata includes
   * "errorClass", Spark's error condition (e.g. "DIVIDE_BY_ZERO"), when the
   * error has one.
   */
  static SparkBackend.SparkException toException(StatusRuntimeException e) {
    final Status status = StatusProto.fromThrowable(e);
    String errorClass = "CONNECTION";
    final String description = e.getStatus().getDescription();
    final String message =
        description != null ? description : e.getStatus().toString();
    if (status != null) {
      for (Any any : status.getDetailsList()) {
        if (any.is(ErrorInfo.class)) {
          try {
            final ErrorInfo info = any.unpack(ErrorInfo.class);
            final String condition = info.getMetadataMap().get("errorClass");
            errorClass = condition != null ? condition : info.getReason();
          } catch (InvalidProtocolBufferException ignore) {
            // fall through
          }
        }
      }
    }
    return new SparkBackend.SparkException(errorClass, message, e);
  }

  @Override
  public List<String> databases() {
    return column(sql("SHOW DATABASES"), "namespace");
  }

  @Override
  public List<String> tables(String database) {
    return column(sql("SHOW TABLES IN " + quote(database)), "tableName");
  }

  /** Returns a column of a result as strings. */
  private static List<String> column(SparkBackend.Result result, String name) {
    final RecordLikeType rowType = (RecordLikeType) result.rowType();
    final int i =
        ImmutableList.copyOf(rowType.argNameTypes().keySet()).indexOf(name);
    if (i < 0) {
      throw new SparkBackend.SparkException(
          "CONNECTION", "Result has no column " + name);
    }
    final List<String> values = new ArrayList<>();
    for (Object row : result.rows()) {
      values.add((String) ((List<?>) row).get(i));
    }
    return values;
  }

  @Override
  public Type tableType(String database, String table) {
    checkOpen();
    final Relation relation =
        Relation.newBuilder()
            .setRead(
                Read.newBuilder()
                    .setNamedTable(
                        Read.NamedTable.newBuilder()
                            .setUnparsedIdentifier(
                                quote(database) + "." + quote(table))))
            .build();
    final AnalyzePlanRequest request =
        AnalyzePlanRequest.newBuilder()
            .setSessionId(sessionId)
            .setUserContext(userContext)
            .setClientType("morel")
            .setSchema(
                AnalyzePlanRequest.Schema.newBuilder()
                    .setPlan(Plan.newBuilder().setRoot(relation)))
            .build();
    final AnalyzePlanResponse response;
    try {
      response = stub.analyzePlan(request);
    } catch (StatusRuntimeException e) {
      throw toException(e);
    }
    final Type rowType =
        SparkTypes.rowType(typeSystem, response.getSchema().getSchema());
    return typeSystem.bagType(rowType);
  }

  @Override
  public List<Object> rows(String database, String table) {
    return sql("SELECT * FROM " + quote(database) + "." + quote(table)).rows();
  }

  /** Quotes an identifier with back-ticks, as Spark SQL does. */
  private static String quote(String identifier) {
    return "`" + identifier.replace("`", "``") + "`";
  }

  private void checkOpen() {
    if (closed) {
      throw new SparkBackend.SparkException(
          "CLOSED", "Connection to " + uri + " is closed");
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      channel.shutdown();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Implementation of {@link SparkBackend.Result}. */
  private static class ResultImpl implements SparkBackend.Result {
    private final Type rowType;
    private final List<Object> rows;

    ResultImpl(Type rowType, List<Object> rows) {
      this.rowType = rowType;
      this.rows = ImmutableList.copyOf(rows);
    }

    @Override
    public Type rowType() {
      return rowType;
    }

    @Override
    public List<Object> rows() {
      return rows;
    }
  }
}

// End SparkConnection.java
