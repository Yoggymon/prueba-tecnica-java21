package com.example.financial;

import com.example.financial.repository.FinancialRepository;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Transferencias")
class TransferTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.7");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean FinancialRepository repository;

    @BeforeEach
    void resetAccounts() {
        reset(repository);
        jdbc.update("delete from operation_attempts");
        jdbc.update("delete from operations");
        jdbc.update("update accounts set balance = initial_balance");
    }

    @Test
    @DisplayName("Registra la transferencia una sola vez al repetir la solicitud")
    void transfersAndReplaysOneCompleteMovement() {
        var first = transfer("transfer", 1, 2, "10.00");
        assertResult(first, 200, "SUCCESS", null);
        var replay = transfer("transfer", 1, 2, "10.000");
        assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertBalances("9990.00", "10.00");
        assertCounts(1, 0, 1);
        var movement = jdbc.queryForMap("select * from operations");
        assertThat(movement.get("id").toString()).isEqualTo(first.getBody().path("id").asText());
        assertThat(movement.get("kind")).isEqualTo("TRANSFER");
        assertThat(movement.get("source_id")).isEqualTo(1L);
        assertThat(movement.get("destination_id")).isEqualTo(2L);
        assertThat((BigDecimal) movement.get("amount")).isEqualByComparingTo("10.00");
        assertThat(movement.get("created_at")).isNotNull();
    }

    @ParameterizedTest(name = "Caso de rechazo {index}")
    @DisplayName("Conserva el rechazo por saldo insuficiente, cuenta inexistente o límite de saldo")
    @ValueSource(strings = {"funds", "source", "destination", "overflow"})
    void businessRejectionsArePersistentAndReplayable(String scenario) {
        long source = scenario.equals("source") ? 999 : 1;
        long destination = scenario.equals("destination") ? 999 : 2;
        String amount = scenario.equals("funds") ? "10000.01" : "0.01";
        String secondBalance = scenario.equals("overflow") ? "99999999999999999.99" : "0.00";
        jdbc.update("update accounts set balance = ? where id = 2", new BigDecimal(secondBalance));
        String reason = switch (scenario) {
            case "funds" -> "INSUFFICIENT_FUNDS";
            case "overflow" -> "DESTINATION_BALANCE_LIMIT";
            default -> "ACCOUNT_NOT_FOUND";
        };
        var first = transfer(scenario, source, destination, amount);
        assertResult(first, 422, "REJECTED", reason);
        var replay = transfer(scenario, source, destination, amount);
        assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertBalances("10000.00", secondBalance);
        assertCounts(0, 1, 1);
        assertThat(jdbc.queryForObject("select reason from operation_attempts", String.class)).isEqualTo(reason);
    }

    @Test
    @DisplayName("Acepta el saldo de destino en el límite permitido")
    void acceptsDestinationBalanceAtTheExactLimit() {
        jdbc.update("update accounts set balance = 99999999999999999.98 where id = 2");
        assertResult(transfer("limit", 1, 2, "0.01"), 200, "SUCCESS", null);
        assertBalances("9999.99", "99999999999999999.99");
        assertCounts(1, 0, 1);
    }

    @ParameterizedTest(name = "Solicitud no válida: {0}")
    @DisplayName("Las solicitudes no válidas no alteran saldos ni registros")
    @ValueSource(strings = {"{}", "null", "{",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":1,\"amount\":1}",
            "{\"key\":\"x\",\"sourceId\":0,\"destinationId\":2,\"amount\":1}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":-1,\"amount\":1}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":2.5,\"amount\":1}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":9223372036854775808,\"amount\":1}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":2,\"amount\":1.005}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":2,\"amount\":0}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":2,\"amount\":-1}",
            "{\"key\":\"x\",\"sourceId\":1,\"destinationId\":2,\"amount\":100000000000000000.00}",
            "{\"key\":\" \",\"sourceId\":1,\"destinationId\":2,\"amount\":1}"})
    void invalidRequestsHaveNoPersistentEffects(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity("/transfers", new HttpEntity<>(body, headers), JsonNode.class);
        assertError(response, 400, "INVALID_REQUEST");
        assertBalances("10000.00", "0.00");
        assertCounts(0, 0, 0);
    }

    @Test
    @DisplayName("Rechaza una clave repetida con otros datos o tipo de operación")
    void mismatchedPayloadsAndKindsConflictAcrossBothEndpoints() {
        assertResult(transfer("global", 1, 2, "5"), 200, "SUCCESS", null);
        assertError(transfer("global", 1, 999, "5"), 409, "IDEMPOTENCY_CONFLICT");
        assertError(transfer("global", 2, 1, "5"), 409, "IDEMPOTENCY_CONFLICT");
        assertError(transfer("global", 1, 2, "6"), 409, "IDEMPOTENCY_CONFLICT");
        assertError(http.postForEntity("/withdrawals", Map.of("key", "global", "sourceId", 1,
                "amount", 5), JsonNode.class), 409, "IDEMPOTENCY_CONFLICT");
        var withdrawal = http.postForEntity("/withdrawals", Map.of("key", "withdraw", "sourceId", 1,
                "amount", 5), JsonNode.class);
        assertResult(withdrawal, 200, "SUCCESS", null);
        assertError(transfer("withdraw", 1, 2, "5"), 409, "IDEMPOTENCY_CONFLICT");
        assertBalances("9990.00", "5.00");
        assertCounts(2, 0, 2);
    }

    @Test
    @DisplayName("Un fallo entre débito y crédito revierte los cambios y registra el fallo")
    void failureAfterDebitRollsBackBalancesMovementAndAttempt() {
        // Aplicar el débito real y provocar un fallo antes del crédito, en la misma transacción.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(jdbc.queryForObject("select balance from accounts where id = 1", BigDecimal.class))
                    .isEqualByComparingTo("9990.00");
            throw new IllegalStateException("test-only failure after debit");
        }).when(repository).debit(anyLong(), any(BigDecimal.class));
        var response = transfer("rollback", 1, 2, "10");
        assertResult(response, 503, "FAILED", "PROCESSING_FAILURE");
        assertBalances("10000.00", "0.00");
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = 'FAILED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from operations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from operations", String.class)).isEqualTo("FAILED");
        assertThat(transfer("rollback", 1, 2, "10").getBody()).isEqualTo(response.getBody());
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Las transferencias simultáneas en ambos sentidos finalizan y conservan los saldos")
    void coordinatedBidirectionalTransfersConserveBalancesWithoutDeadlocks() throws Exception {
        jdbc.update("update accounts set balance = 10000.00 where id = 2");
        int requests = 24;
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, requests).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return transfer("concurrent-" + i, i % 2 == 0 ? 1 : 2, i % 2 == 0 ? 2 : 1, "10");
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) {
                assertResult(future.get(30, TimeUnit.SECONDS), 200, "SUCCESS", null);
            }
        }
        assertBalances("10000.00", "10000.00");
        assertCounts(requests, 0, requests);
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'PROCESSING'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", BigDecimal.class))
                .isEqualByComparingTo("20000.00");
    }

    private ResponseEntity<JsonNode> transfer(String key, long source, long destination, String amount) {
        return http.postForEntity("/transfers", Map.of("key", key, "sourceId", source,
                "destinationId", destination, "amount", new BigDecimal(amount)), JsonNode.class);
    }

    private void assertResult(ResponseEntity<JsonNode> response, int code, String status, String reason) {
        assertThat(response.getStatusCode().value()).isEqualTo(code);
        assertThat(response.getBody().path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(response.getBody().size()).isEqualTo(3);
        assertThat(response.getBody().path("status").asText()).isEqualTo(status);
        if (reason == null) assertThat(response.getBody().path("reason").isNull()).isTrue();
        else assertThat(response.getBody().path("reason").asText()).isEqualTo(reason);
    }

    private void assertError(ResponseEntity<JsonNode> response, int code, String error) {
        assertThat(response.getStatusCode().value()).isEqualTo(code);
        assertThat(response.getBody().toString()).isEqualTo("{\"error\":\"" + error + "\"}");
    }

    private void assertBalances(String source, String destination) {
        assertThat(jdbc.queryForObject("select balance from accounts where id = 1", BigDecimal.class))
                .isEqualByComparingTo(source);
        assertThat(jdbc.queryForObject("select balance from accounts where id = 2", BigDecimal.class))
                .isEqualByComparingTo(destination);
    }

    private void assertCounts(long successes, long rejections, long attempts) {
        assertThat(jdbc.queryForObject("select count(*) from operations", Long.class)).isEqualTo(successes + rejections);
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'SUCCESS'", Long.class)).isEqualTo(successes);
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'REJECTED'", Long.class)).isEqualTo(rejections);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts", Long.class)).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = 'SUCCESS'", Long.class)).isEqualTo(successes);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = 'REJECTED'", Long.class)).isEqualTo(rejections);
    }
}
