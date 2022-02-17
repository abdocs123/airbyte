/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Maps;
import io.airbyte.db.Databases;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.BaseConnector;
import io.airbyte.integrations.base.AirbyteMessageConsumer;
import io.airbyte.integrations.base.Destination;
import io.airbyte.integrations.base.sentry.AirbyteSentry;
import io.airbyte.integrations.destination.NamingConventionTransformer;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteConnectionStatus.Status;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractJdbcDestination extends BaseConnector implements Destination {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJdbcDestination.class);

  private final String driverClass;
  private final NamingConventionTransformer namingResolver;
  private final SqlOperations sqlOperations;

  protected String getDriverClass() {
    return driverClass;
  }

  protected NamingConventionTransformer getNamingResolver() {
    return namingResolver;
  }

  protected SqlOperations getSqlOperations() {
    return sqlOperations;
  }

  public AbstractJdbcDestination(final String driverClass,
      final NamingConventionTransformer namingResolver,
      final SqlOperations sqlOperations) {
    this.driverClass = driverClass;
    this.namingResolver = namingResolver;
    this.sqlOperations = sqlOperations;
  }

  @Override
  public AirbyteConnectionStatus check(final JsonNode config) {

    try (final JdbcDatabase database = getDatabase(config)) {
      final String outputSchema = namingResolver.getIdentifier(config.get("schema").asText());
      AirbyteSentry.executeWithTracing("CreateAndDropTable",
          () -> attemptSQLCreateAndDropTableOperations(outputSchema, database, namingResolver, sqlOperations));
      return new AirbyteConnectionStatus().withStatus(Status.SUCCEEDED);
    } catch (final Exception e) {
      LOGGER.error("Exception while checking connection: ", e);
      return new AirbyteConnectionStatus()
          .withStatus(Status.FAILED)
          .withMessage("Could not connect with provided configuration. \n" + e.getMessage());
    }
  }

  public static void attemptSQLCreateAndDropTableOperations(final String outputSchema,
      final JdbcDatabase database,
      final NamingConventionTransformer namingResolver,
      final SqlOperations sqlOps)
      throws Exception {
    // attempt to get metadata from the database as a cheap way of seeing if we can connect.
    database.bufferedResultSetQuery(conn -> conn.getMetaData().getCatalogs(), JdbcUtils.getDefaultSourceOperations()::rowToJson);

    // verify we have write permissions on the target schema by creating a table with a random name,
    // then dropping that table
    final String outputTableName = namingResolver.getIdentifier("_airbyte_connection_test_" + UUID.randomUUID().toString().replaceAll("-", ""));
    sqlOps.createSchemaIfNotExists(database, outputSchema);
    sqlOps.createTableIfNotExists(database, outputSchema, outputTableName);
    sqlOps.dropTableIfExists(database, outputSchema, outputTableName);
  }

  protected JdbcDatabase getDatabase(final JsonNode config) {
    final JsonNode jdbcConfig = toJdbcConfig(config);

    return Databases.createJdbcDatabase(
        jdbcConfig.get("username").asText(),
        jdbcConfig.has("password") ? jdbcConfig.get("password").asText() : null,
        jdbcConfig.get("jdbc_url").asText(),
        driverClass,
        getConnectionProperties(config));
  }

  protected abstract Map<String, String> getConnectionProperties(final JsonNode config);

  public abstract JsonNode toJdbcConfig(JsonNode config);

  @Override
  public AirbyteMessageConsumer getConsumer(final JsonNode config,
      final ConfiguredAirbyteCatalog catalog,
      final Consumer<AirbyteMessage> outputRecordCollector) {
    return JdbcBufferedConsumerFactory.create(outputRecordCollector, getDatabase(config), sqlOperations, namingResolver, config, catalog);
  }


  public static Map<String, String> parseJdbcParameters(final JsonNode config, final String jdbcParametersKey) {
    if (config.has(jdbcParametersKey)) {
      return parseJdbcParameters(config.get(jdbcParametersKey).asText());
    } else {
      return Maps.newHashMap();
    }
  }

  public static Map<String, String> parseJdbcParameters(final String jdbcPropertiesString) {
    final Map<String, String> parameters = new HashMap<>();
    if (!jdbcPropertiesString.isBlank()) {
      final String[] keyValuePairs = jdbcPropertiesString.split("&");
      for (final String kv : keyValuePairs) {
        final String[] split = kv.split("=");
        if (split.length == 2) {
          parameters.put(split[0], split[1]);
        } else {
          throw new IllegalArgumentException(
              "jdbc_url_params must be formatted as 'key=value' pairs separated by the symbol '&'. (example: key1=value1&key2=value2&key3=value3). Got "
                  + jdbcPropertiesString);
        }
      }
    }
    return parameters;
  }
}
