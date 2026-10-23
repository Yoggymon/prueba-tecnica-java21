package com.example.financial;

import java.math.BigDecimal;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WithdrawalTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.7");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetAccounts() {
        jdbc.update("delete from operation_attempts");
        jdbc.update("delete from operations");
        jdbc.update("update accounts set balance = initial_balance");
    }

    @Test
    void withdrawsAndPersistsTheCompleteMovement() {
        var response = withdraw("withdraw-1", "5.00");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("status").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("reason").isNull()).isTrue();
        assertBalance("9995.00");
        var movement = jdbc.queryForMap("select * from operations");
        assertThat(movement.get("id").toString()).isEqualTo(response.getBody().path("id").asText());
        assertThat(movement.get("kind")).isEqualTo("WITHDRAWAL");
        assertThat(movement.get("source_id")).isEqualTo(1L);
        assertThat(movement.get("destination_id")).isNull();
        assertThat((BigDecimal) movement.get("amount")).isEqualByComparingTo("5.00");
        assertThat(movement.get("created_at")).isNotNull();
        assertThat(movement.get("status")).isEqualTo("SUCCESS");
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("select result from operation_attempts", String.class)).isEqualTo("SUCCESS");
        var replay = withdraw("withdraw-1", "5.00");
        assertThat(replay.getStatusCode()).isEqualTo(response.getStatusCode());
        assertThat(replay.getBody()).isEqualTo(response.getBody());
        assertBalance("9995.00");
        assertCounts(1, 1);
    }

    @Test
    void rejectsInsufficientFundsWithoutFinancialEffects() {
        var response = withdraw("insufficient", "10001.00");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().path("status").asText()).isEqualTo("REJECTED");
        assertThat(response.getBody().path("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertBalance("10000.00");
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'SUCCESS'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'REJECTED'", Long.class)).isEqualTo(1);
        var replay = withdraw("insufficient", "10001.00");
        assertThat(replay.getStatusCode().value()).isEqualTo(422);
        assertThat(replay.getBody()).isEqualTo(response.getBody());
        assertCounts(1, 1);
    }

    @Test
    void missingAccountIsAuditedAndReplayed() {
        var request = Map.of("key", "missing", "sourceId", 999, "amount", 5);
        var first = http.postForEntity("/withdrawals", request, JsonNode.class);
        assertThat(first.getStatusCode().value()).isEqualTo(422);
        assertThat(first.getBody().path("status").asText()).isEqualTo("REJECTED");
        assertThat(first.getBody().path("reason").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(first.getBody().path("id").asText()).isNotBlank();
        var replay = http.postForEntity("/withdrawals", request, JsonNode.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(422);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertBalance("10000.00");
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("select result from operation_attempts", String.class)).isEqualTo("REJECTED");
    }

    @ParameterizedTest
    @CsvSource({"2, 5.00", "1, 6.00"})
    void payloadMismatchConflictsButEquivalentCentsReplay(long source, BigDecimal amount) {
        var first = withdraw("payload", "5.00");
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var replay = withdraw("payload", "5.000");
        assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        var conflict = http.postForEntity("/withdrawals",
                Map.of("key", "payload", "sourceId", source, "amount", amount), JsonNode.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().toString()).isEqualTo("{\"error\":\"IDEMPOTENCY_CONFLICT\"}");
        assertBalance("9995.00");
        assertCounts(1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"key\":\" \",\"sourceId\":1,\"amount\":5}",
            "{\"key\":\"x\",\"sourceId\":0,\"amount\":5}",
            "{\"key\":\"x\",\"sourceId\":1.5,\"amount\":5}",
            "{\"key\":\"x\",\"sourceId\":1,\"amount\":1.005}",
            "{\"key\":\"x\",\"sourceId\":1,\"amount\":0}",
            "{\"key\":\"x\",\"sourceId\":1,\"amount\":-1}",
            "{\"key\":\"x\",\"sourceId\":1,\"amount\":100000000000000000.00}"})
    void malformedRequestsNeverRegister(String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertInvalid(http.postForEntity("/withdrawals", new HttpEntity<>(json, headers), JsonNode.class));
    }

    @Test
    void enforcesKeyLengthAndAcceptsItsBoundary() {
        assertInvalid(withdraw("k".repeat(129), "5"));
        assertThat(withdraw("k".repeat(128), "5").getStatusCode().value()).isEqualTo(200);
        assertBalance("9995.00");
        assertCounts(1, 1);
    }

    @Test
    void extraDestinationFieldStillDoesNotTurnAWithdrawalIntoATransfer() {
        var response = http.postForEntity("/withdrawals", Map.of("key", "extra-destination",
                "sourceId", 1, "destinationId", 2, "amount", 5), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("status").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("reason").isNull()).isTrue();
        assertThat(response.getBody().size()).isEqualTo(3);
        assertBalance("9995.00");
        assertCounts(1, 1);
        assertThat(jdbc.queryForObject("select kind from operations", String.class)).isEqualTo("WITHDRAWAL");
        assertThat(jdbc.queryForMap("select destination_id from operations").get("destination_id")).isNull();
    }

    private void assertInvalid(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).isEqualTo("{\"error\":\"INVALID_REQUEST\"}");
        assertBalance("10000.00");
        assertCounts(0, 0);
    }

    private void assertCounts(long operations, long attempts) {
        assertThat(jdbc.queryForObject("select count(*) from operations", Long.class)).isEqualTo(operations);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts", Long.class)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("select balance from accounts where id = 2", BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    private ResponseEntity<JsonNode> withdraw(String key, String amount) {
        return http.postForEntity("/withdrawals", Map.of("key", key, "sourceId", 1,
                "amount", new BigDecimal(amount)), JsonNode.class);
    }

    private void assertBalance(String expected) {
        assertThat(jdbc.queryForObject("select balance from accounts where id = 1", BigDecimal.class))
                .isEqualByComparingTo(expected);
    }
}
