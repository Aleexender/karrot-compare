package com.example.karrot.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    private static final String TEST_DATABASE = "karrot_with_design_test";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        // Refuse to delete anything unless the test datasource override is in effect.
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(TEST_DATABASE);
        jdbcTemplate.update("DELETE FROM items");
        jdbcTemplate.update("DELETE FROM users");
    }

    protected long createUser() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO users (nickname, created_at) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "tester");
            statement.setObject(2, LocalDateTime.now());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    protected long countItems() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items", Long.class);
    }

    protected static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "맥북 에어 M3");
        body.put("content", "거의 새것");
        body.put("price", 1_200_000L);
        body.put("negotiable", true);
        body.put("categoryId", 1L);
        return body;
    }

    protected HttpResponse<String> postItem(long userId, String idempotencyKey, Map<String, Object> body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/posts"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", Long.toString(userId))
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
