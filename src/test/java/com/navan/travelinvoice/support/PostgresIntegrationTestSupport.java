package com.navan.travelinvoice.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresIntegrationTestSupport.PostgresConfiguration.class)
public abstract class PostgresIntegrationTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfiguration {
        @Bean(initMethod = "start", destroyMethod = "stop")
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17");
        }

        @Bean
        DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
            return registry -> {
                registry.add("spring.datasource.url", postgres::getJdbcUrl);
                registry.add("spring.datasource.username", postgres::getUsername);
                registry.add("spring.datasource.password", postgres::getPassword);
            };
        }
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected JsonMapper mapper;
    @Autowired
    protected JdbcTemplate jdbc;

    protected final HttpClient client = HttpClient.newHttpClient();
    private JsonNode seeds;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("TRUNCATE TABLE invoices, transactions RESTART IDENTITY CASCADE");
        seeds = mapper.readTree(Files.readString(Path.of("fixtures/task-b/seed-payloads.json")));
    }

    protected void assertJsonEquivalent(JsonNode actual, JsonNode expected) {
        assertThat(actual).isNotNull();
        if (expected.isNumber()) {
            assertThat(actual.isNumber()).isTrue();
            assertThat(actual.decimalValue()).isEqualByComparingTo(expected.decimalValue());
        } else if (expected.isObject()) {
            assertThat(actual.isObject()).isTrue();
            assertThat(actual.size()).isEqualTo(expected.size());
            expected.properties().forEach(entry -> assertJsonEquivalent(actual.get(entry.getKey()), entry.getValue()));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).isTrue();
            assertThat(actual.size()).isEqualTo(expected.size());
            for (int index = 0; index < expected.size(); index++) {
                assertJsonEquivalent(actual.get(index), expected.get(index));
            }
        } else {
            assertThat(actual).isEqualTo(expected);
        }
    }

    protected void assertInvalidAndEmpty(ObjectNode input) throws Exception {
        HttpResponse<String> response = post(input);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
        assertThat(count("transactions")).isZero();
        assertThat(count("transaction_taxes")).isZero();
        assertThat(count("transaction_fees")).isZero();
    }

    protected int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    protected ObjectNode payload(String name) {
        return (ObjectNode) seeds.get(name).deepCopy();
    }

    protected URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    protected HttpResponse<String> post(JsonNode input) throws Exception {
        return post("/transactions", input);
    }

    protected HttpResponse<String> post(String path, JsonNode input) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(long id) throws Exception {
        return get("/transactions/" + id);
    }

    protected HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> patch(long id, JsonNode input) throws Exception {
        return patch("/transactions/" + id, input);
    }

    protected HttpResponse<String> patch(String path, JsonNode input) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> delete(long id) throws Exception {
        return delete("/transactions/" + id);
    }

    protected HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }
}
