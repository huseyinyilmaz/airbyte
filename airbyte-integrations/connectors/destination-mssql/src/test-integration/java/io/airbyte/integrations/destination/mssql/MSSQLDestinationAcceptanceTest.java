/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.mssql;

import static io.airbyte.integrations.standardtest.destination.DateTimeUtils.DATE;
import static io.airbyte.integrations.standardtest.destination.DateTimeUtils.DATE_TIME;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.string.Strings;
import io.airbyte.db.Database;
import io.airbyte.db.Databases;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.ExtendedNameTransformer;
import io.airbyte.integrations.standardtest.destination.DateTimeUtils;
import io.airbyte.integrations.standardtest.destination.DestinationAcceptanceTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MSSQLServerContainer;

public class MSSQLDestinationAcceptanceTest extends DestinationAcceptanceTest {

  private static MSSQLServerContainer<?> db;
  private final ExtendedNameTransformer namingResolver = new ExtendedNameTransformer();
  private JsonNode configWithoutDbName;
  private JsonNode config;

  @Override
  protected String getImageName() {
    return "airbyte/destination-mssql:dev";
  }

  @Override
  protected boolean supportsDBT() {
    return true;
  }

  @Override
  protected boolean supportsNormalization() {
    return true;
  }

  private JsonNode getConfig(final MSSQLServerContainer<?> db) {
    return Jsons.jsonNode(ImmutableMap.builder()
        .put("host", db.getHost())
        .put("port", db.getFirstMappedPort())
        .put("username", db.getUsername())
        .put("password", db.getPassword())
        .put("schema", "test_schema")
        .build());
  }

  @Override
  protected JsonNode getConfig() {
    return config;
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    return Jsons.jsonNode(ImmutableMap.builder()
        .put("host", db.getHost())
        .put("username", db.getUsername())
        .put("password", "wrong password")
        .put("schema", "public")
        .put("port", db.getFirstMappedPort())
        .put("ssl", false)
        .build());
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv env,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
      throws Exception {
    return retrieveRecordsFromTable(namingResolver.getRawTableName(streamName), namespace)
        .stream()
        .map(r -> Jsons.deserialize(r.get(JavaBaseConstants.COLUMN_NAME_DATA).asText()))
        .collect(Collectors.toList());
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv env, final String streamName, final String namespace)
      throws Exception {
    final String tableName = namingResolver.getIdentifier(streamName);
    return retrieveRecordsFromTable(tableName, namespace);
  }

  @Override
  protected List<String> resolveIdentifier(final String identifier) {
    final List<String> result = new ArrayList<>();
    final String resolved = namingResolver.getIdentifier(identifier);
    result.add(identifier);
    result.add(resolved);
    if (!resolved.startsWith("\"")) {
      result.add(resolved.toLowerCase());
      result.add(resolved.toUpperCase());
    }
    return result;
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, final String schemaName) throws SQLException {
    return Databases.createSqlServerDatabase(db.getUsername(), db.getPassword(),
        db.getJdbcUrl()).query(
            ctx -> {
              ctx.fetch(String.format("USE %s;", config.get("database")));
              return ctx
                  .fetch(String.format("SELECT * FROM %s.%s ORDER BY %s ASC;", schemaName, tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT))
                  .stream()
                  .map(r -> r.formatJSON(JdbcUtils.getDefaultJSONFormat()))
                  .map(Jsons::deserialize)
                  .collect(Collectors.toList());
            });
  }

  @BeforeAll
  protected static void init() {
    db = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-GA-ubuntu-16.04").acceptLicense();
    db.start();
  }

  private static Database getDatabase(final JsonNode config) {
    // todo (cgardens) - rework this abstraction so that we do not have to pass a null into the
    // constructor. at least explicitly handle it, even if the impl doesn't change.
    return Databases.createDatabase(
        config.get("username").asText(),
        config.get("password").asText(),
        String.format("jdbc:sqlserver://%s:%s",
            config.get("host").asText(),
            config.get("port").asInt()),
        "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        null);
  }

  // how to interact with the mssql test container manaully.
  // 1. exec into mssql container (not the test container container)
  // 2. /opt/mssql-tools/bin/sqlcmd -S localhost -U SA -P "A_Str0ng_Required_Password"
  @Override
  protected void setup(final TestDestinationEnv testEnv) throws SQLException {
    configWithoutDbName = getConfig(db);
    final String dbName = Strings.addRandomSuffix("db", "_", 10);

    final Database database = getDatabase(configWithoutDbName);
    database.query(ctx -> {
      ctx.fetch(String.format("CREATE DATABASE %s;", dbName));
      ctx.fetch(String.format("USE %s;", dbName));
      ctx.fetch("CREATE TABLE id_and_name(id INTEGER NOT NULL, name VARCHAR(200), born DATETIMEOFFSET(7));");
      ctx.fetch(
          "INSERT INTO id_and_name (id, name, born) VALUES (1,'picard', '2124-03-04T01:01:01Z'),  (2, 'crusher', '2124-03-04T01:01:01Z'), (3, 'vash', '2124-03-04T01:01:01Z');");
      return null;
    });

    config = Jsons.clone(configWithoutDbName);
    ((ObjectNode) config).put("database", dbName);
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) {}

  @AfterAll
  static void cleanUp() {
    db.stop();
    db.close();
  }

  @Override
  public boolean requiresDateTimeConversionForNormalizedSync() {
    return true;
  }

  @Override
  public void convertDateTime(ObjectNode data, Map<String, String> dateTimeFieldNames) {
    if (dateTimeFieldNames.keySet().isEmpty()) {
      return;
    }
    for (String path : dateTimeFieldNames.keySet()) {
      if (isOneLevelPath(path) && !data.at(path).isMissingNode() && DateTimeUtils.isDateTimeValue(data.at(path).asText())) {
        var key = path.replace("/", StringUtils.EMPTY);
        switch (dateTimeFieldNames.get(path)) {
          case DATE_TIME -> data.put(key.toLowerCase(),
              DateTimeUtils.convertToMSSQLFormat(data.at(path).asText()));
          case DATE -> data.put(key.toLowerCase(),
              DateTimeUtils.convertToDateFormat(data.at(path).asText()));
        }
      }
    }
  }

  @Override
  protected void assertSameValue(String key,
                                 JsonNode expectedValue,
                                 JsonNode actualValue) {
    if (DateTimeUtils.isDateTimeValue(expectedValue.asText()) && DateTimeUtils.isDateTimeValue(actualValue.asText())) {
      /*
       * Omitted millis for assertion because MSSQL datetime values are rounded to increments of .000,
       * .003, or .007 seconds
       * https://docs.microsoft.com/en-us/sql/t-sql/data-types/datetime-transact-sql?view=sql-server-ver15
       * #rounding-of-datetime-fractional-second-precision
       */
      Assertions.assertEquals(DateTimeUtils.MILLISECONDS_PATTERN.matcher(expectedValue.asText()).replaceAll(StringUtils.EMPTY),
          DateTimeUtils.MILLISECONDS_PATTERN.matcher(actualValue.asText()).replaceAll(StringUtils.EMPTY));
    } else {
      super.assertSameValue(key, expectedValue, actualValue);
    }
  }

}
