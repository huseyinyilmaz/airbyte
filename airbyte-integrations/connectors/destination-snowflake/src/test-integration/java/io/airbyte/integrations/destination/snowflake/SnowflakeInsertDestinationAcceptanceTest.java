/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.snowflake;

import static io.airbyte.integrations.standardtest.destination.DateTimeUtils.DATE;
import static io.airbyte.integrations.standardtest.destination.DateTimeUtils.DATE_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.commons.string.Strings;
import io.airbyte.config.StandardCheckConnectionOutput.Status;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.NamingConventionTransformer;
import io.airbyte.integrations.standardtest.destination.DataArgumentsProvider;
import io.airbyte.integrations.standardtest.destination.DateTimeUtils;
import io.airbyte.integrations.standardtest.destination.DestinationAcceptanceTest;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class SnowflakeInsertDestinationAcceptanceTest extends DestinationAcceptanceTest {

  private static final NamingConventionTransformer NAME_TRANSFORMER = new SnowflakeSQLNameTransformer();

  // this config is based on the static config, and it contains a random
  // schema name that is different for each test run
  private JsonNode config;
  private JdbcDatabase database;

  @Override
  protected String getImageName() {
    return "airbyte/destination-snowflake:dev";
  }

  @Override
  protected JsonNode getConfig() {
    return config;
  }

  public JsonNode getStaticConfig() {
    final JsonNode insertConfig = Jsons.deserialize(IOs.readFile(Path.of("secrets/insert_config.json")));
    Preconditions.checkArgument(!SnowflakeDestinationResolver.isS3Copy(insertConfig));
    Preconditions.checkArgument(!SnowflakeDestinationResolver.isGcsCopy(insertConfig));
    return insertConfig;
  }

  @Override
  protected JsonNode getFailCheckConfig() {
    final JsonNode invalidConfig = Jsons.clone(config);
    ((ObjectNode) invalidConfig.get("credentials")).put("password", "wrong password");
    return invalidConfig;
  }

  @Override
  protected List<JsonNode> retrieveRecords(final TestDestinationEnv env,
                                           final String streamName,
                                           final String namespace,
                                           final JsonNode streamSchema)
      throws Exception {
    return retrieveRecordsFromTable(NAME_TRANSFORMER.getRawTableName(streamName), NAME_TRANSFORMER.getNamespace(namespace))
        .stream()
        .map(j -> Jsons.deserialize(j.get(JavaBaseConstants.COLUMN_NAME_DATA.toUpperCase()).asText()))
        .collect(Collectors.toList());
  }

  @Override
  protected boolean supportsNormalization() {
    return true;
  }

  @Override
  protected boolean supportsDBT() {
    return true;
  }

  @Override
  protected boolean implementsNamespaces() {
    return true;
  }

  @Override
  protected boolean supportNamespaceTest() {
    return true;
  }

  @Override
  protected Optional<NamingConventionTransformer> getNameTransformer() {
    return Optional.of(NAME_TRANSFORMER);
  }

  @Override
  protected List<JsonNode> retrieveNormalizedRecords(final TestDestinationEnv testEnv, final String streamName, final String namespace)
      throws Exception {
    final String tableName = NAME_TRANSFORMER.getIdentifier(streamName);
    final String schema = NAME_TRANSFORMER.getNamespace(namespace);
    // Temporarily disabling the behavior of the ExtendedNameTransformer, see (issue #1785) so we don't
    // use quoted names
    // if (!tableName.startsWith("\"")) {
    // // Currently, Normalization always quote tables identifiers
    // tableName = "\"" + tableName + "\"";
    // }
    return retrieveRecordsFromTable(tableName, schema);
  }

  @Override
  protected List<String> resolveIdentifier(final String identifier) {
    final List<String> result = new ArrayList<>();
    final String resolved = NAME_TRANSFORMER.getIdentifier(identifier);
    result.add(identifier);
    result.add(resolved);
    if (!resolved.startsWith("\"")) {
      result.add(resolved.toLowerCase());
      result.add(resolved.toUpperCase());
    }
    return result;
  }

  private List<JsonNode> retrieveRecordsFromTable(final String tableName, final String schema) throws SQLException {
    return database.bufferedResultSetQuery(
        connection -> {
          try (final ResultSet tableInfo = connection.createStatement()
              .executeQuery(String.format("SHOW TABLES LIKE '%s' IN SCHEMA %s;", tableName, schema));) {
            assertTrue(tableInfo.next());
            // check that we're creating permanent tables. DBT defaults to transient tables, which have
            // `TRANSIENT` as the value for the `kind` column.
            assertEquals("TABLE", tableInfo.getString("kind"));
            return connection.createStatement()
                .executeQuery(String.format("SELECT * FROM %s.%s ORDER BY %s ASC;", schema, tableName, JavaBaseConstants.COLUMN_NAME_EMITTED_AT));
          }
        },
        JdbcUtils.getDefaultSourceOperations()::rowToJson);
  }

  // for each test we create a new schema in the database. run the test in there and then remove it.
  @Override
  protected void setup(final TestDestinationEnv testEnv) throws Exception {
    final String schemaName = Strings.addRandomSuffix("integration_test", "_", 5);
    final String createSchemaQuery = String.format("CREATE SCHEMA %s", schemaName);

    this.config = Jsons.clone(getStaticConfig());
    ((ObjectNode) config).put("schema", schemaName);

    database = SnowflakeDatabase.getDatabase(config);
    database.execute(createSchemaQuery);
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  }

  @Override
  protected void tearDown(final TestDestinationEnv testEnv) throws Exception {
    final String createSchemaQuery = String.format("DROP SCHEMA IF EXISTS %s", config.get("schema").asText());
    database.execute(createSchemaQuery);
    database.close();
  }

  @Test
  public void testBackwardCompatibilityAfterAddingOauth() {
    final JsonNode deprecatedStyleConfig = Jsons.clone(config);
    final JsonNode password = deprecatedStyleConfig.get("credentials").get("password");

    ((ObjectNode) deprecatedStyleConfig).remove("credentials");
    ((ObjectNode) deprecatedStyleConfig).set("password", password);

    assertEquals(Status.SUCCEEDED, runCheckWithCatchedException(deprecatedStyleConfig));
  }

  /**
   * This test is disabled because it is very slow, and should only be run manually for now.
   */
  @Disabled
  @ParameterizedTest
  @ArgumentsSource(DataArgumentsProvider.class)
  public void testSyncWithBillionRecords(final String messagesFilename, final String catalogFilename) throws Exception {
    final AirbyteCatalog catalog = Jsons.deserialize(MoreResources.readResource(catalogFilename), AirbyteCatalog.class);
    final ConfiguredAirbyteCatalog configuredCatalog = CatalogHelpers.toDefaultConfiguredCatalog(catalog);
    final List<AirbyteMessage> messages = MoreResources.readResource(messagesFilename).lines()
        .map(record -> Jsons.deserialize(record, AirbyteMessage.class)).collect(Collectors.toList());

    final List<AirbyteMessage> largeNumberRecords =
        Collections.nCopies(15000000, messages).stream().flatMap(List::stream).collect(Collectors.toList());

    final JsonNode config = getConfig();
    runSyncAndVerifyStateOutput(config, largeNumberRecords, configuredCatalog, false);
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
              DateTimeUtils.convertToSnowflakeFormat(data.at(path).asText()));
          case DATE -> data.put(key.toLowerCase(),
              DateTimeUtils.convertToDateFormatWithZeroTime(data.at(path).asText()));
        }
      }
    }
  }

  protected void deserializeNestedObjects(List<AirbyteMessage> messages, List<AirbyteRecordMessage> actualMessages) {
    HashSet<String> nestedFieldNames = new HashSet<>();
    for (AirbyteMessage message : messages) {
      if (message.getType() == Type.RECORD) {
        var iterator = message.getRecord().getData().fieldNames();
        if (iterator.hasNext()) {
          var fieldName = iterator.next();
          if (message.getRecord().getData().get(fieldName).isContainerNode()) {
            nestedFieldNames.add(fieldName.toUpperCase());
          }
        }
      }
    }
    if (actualMessages != null) {
      for (AirbyteRecordMessage message : actualMessages) {
        nestedFieldNames.stream().filter(name -> message.getData().has(name)).forEach(name -> {
          var data = message.getData().get(name).asText();
          try {
            ((ObjectNode) message.getData()).put(name,
                new ObjectMapper().readTree(data));
          } catch (JsonProcessingException e) {
            e.printStackTrace();
          }
        });
      }
    }
  }

}
