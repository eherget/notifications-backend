package com.redhat.cloud.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.FilterProvider;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.redhat.cloud.notifications.models.filter.ApiResponseFilter;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.smallrye.reactive.messaging.connectors.InMemoryConnector;
import io.vertx.core.json.jackson.DatabindCodec;
import org.mockserver.client.MockServerClient;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class TestLifecycleManager implements QuarkusTestResourceLifecycleManager {

    PostgreSQLContainer<?> postgreSQLContainer;
    MockServerContainer mockEngineServer;
    MockServerClient mockServerClient;
    MockServerClientConfig configurator;

    @Override
    public Map<String, String> start() {
        System.out.println("++++  TestLifecycleManager start +++");
        configureObjectMapper();
        Map<String, String> properties = new HashMap<>();
        try {
            setupPostgres(properties);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        setupMockEngine(properties);

        /*
         * We'll use an in-memory Reactive Messaging connector to send payloads.
         * See https://smallrye.io/smallrye-reactive-messaging/smallrye-reactive-messaging/2/testing/testing.html
         */
        properties.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("ingress"));

        System.out.println(" -- Running with properties: " + properties);
        return properties;
    }

    private void configureObjectMapper() {
        FilterProvider filterProvider = new SimpleFilterProvider().addFilter(ApiResponseFilter.NAME, new ApiResponseFilter());
        ObjectMapper mapper = DatabindCodec.mapper();
        mapper.setFilterProvider(filterProvider);
        mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void stop() {
        postgreSQLContainer.stop();
        mockEngineServer.stop();
        InMemoryConnector.clear();
    }


    @Override
    public void inject(Object testInstance) {
        Class<?> c = testInstance.getClass();
        while (c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getAnnotation(MockServerConfig.class) != null) {
                    if (!MockServerClientConfig.class.isAssignableFrom(f.getType())) {
                        throw new RuntimeException("@MockRbacConfig can only be used on fields of type RbacConfigurator");
                    }

                    f.setAccessible(true);
                    try {
                        f.set(testInstance, configurator);
                        return;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            c = c.getSuperclass();
        }
    }

    void setupPostgres(Map<String, String> props) throws SQLException {
        postgreSQLContainer = new PostgreSQLContainer<>("postgres");
        postgreSQLContainer.start();
        // Now that postgres is started, we need to get its URL and tell Quarkus
        // quarkus.datasource.driver=io.opentracing.contrib.jdbc.TracingDriver
        // Driver needs a 'tracing' in the middle like jdbc:tracing:postgresql://localhost:5432/postgres
        String jdbcUrl = postgreSQLContainer.getJdbcUrl();
        String dbUrl = jdbcUrl.substring(jdbcUrl.indexOf(':') + 1).replace("jdbc:", "");
        props.put("quarkus.datasource.reactive.url", dbUrl);
        props.put("quarkus.datasource.username", "test");
        props.put("quarkus.datasource.password", "test");
        props.put("quarkus.datasource.db-kind", "postgresql");

        // Install the pgcrypto extension
        // Could perhas be done by a migration with a lower number than the 'live' ones.
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(jdbcUrl);
        Connection connection = ds.getConnection("test", "test");
        Statement statement = connection.createStatement();
        statement.execute("CREATE EXTENSION pgcrypto;");
        statement.close();
        connection.close();
    }

    void setupMockEngine(Map<String, String> props) {
        mockEngineServer = new MockServerContainer();

        // set up mock engine
        mockEngineServer.start();
        String mockServerUrl = "http://" + mockEngineServer.getContainerIpAddress() + ":" + mockEngineServer.getServerPort();
        mockServerClient = new MockServerClient(mockEngineServer.getContainerIpAddress(), mockEngineServer.getServerPort());

        configurator = new MockServerClientConfig(mockServerClient);

        props.put("rbac/mp-rest/url", mockServerUrl);
    }
}
