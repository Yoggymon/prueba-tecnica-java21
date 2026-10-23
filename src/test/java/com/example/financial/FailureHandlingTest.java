package com.example.financial;

import com.example.financial.model.TransferRequest;
import com.example.financial.repository.FinancialRepository;
import com.example.financial.service.OperationOrchestrator;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "financial.lock-timeout-ms=100")
@Testcontainers
class FailureHandlingTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.7");
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean FinancialRepository repository;
    @MockitoSpyBean PlatformTransactionManager transactions;
    @MockitoSpyBean OperationOrchestrator orchestrator;

    @BeforeEach
    void resetData() {
        reset(repository, transactions, orchestrator);
        jdbc.update("delete from operation_attempts");
        jdbc.update("delete from operations");
        jdbc.update("update accounts set balance = initial_balance");
    }

    @ParameterizedTest
    @ValueSource(strings = {"40P01", "40001", "55P03"})
    void recognizedRollbackAfterRealDebitRetriesThenSucceeds(String state) {
        var calls = failDebits(state, 1);
        var result = post("/transfers", "retry", "10");
        result(result, 200, "SUCCESS", null);
        assertThat(calls.get()).isEqualTo(2);
        balances("9990", "10");
        counts(1, 1, 2);
        assertThat(post("/transfers", "retry", "10").getBody()).isEqualTo(result.getBody());
        assertThat(calls.get()).isEqualTo(2);
        counts(1, 1, 2);
    }

    @Test
    void threeTotalAttemptsThenLaterSameKeyRecoveryAndConflictDoesNotChangeCounters() {
        var calls = failDebits("40001", 3);
        result(post("/transfers", "exhausted", "10"), 503, "FAILED", "TRANSIENT_CONFLICT");
        assertThat(calls.get()).isEqualTo(3);
        balances("10000", "0");
        counts(1, 3, 3);
        error(post("/transfers", "exhausted", "11"), "IDEMPOTENCY_CONFLICT", 409);
        counts(1, 3, 3);
        result(post("/transfers", "exhausted", "10"), 200, "SUCCESS", null);
        assertThat(calls.get()).isEqualTo(4);
        balances("9990", "10");
        counts(1, 3, 4);
    }

    @Test
    void nonTransientWithdrawalFailureIsNotRetriedAndReplaysStably() {
        var calls = new AtomicInteger();
        doAnswer(i -> {
            i.callRealMethod();
            calls.incrementAndGet();
            throw new IllegalStateException("private SQL must not escape");
        }).when(repository).debit(anyLong(), any());
        var first = post("/withdrawals", "permanent", "5");
        result(first, 503, "FAILED", "PROCESSING_FAILURE");
        assertThat(post("/withdrawals", "permanent", "5").getBody()).isEqualTo(first.getBody());
        assertThat(calls.get()).isEqualTo(1);
        balances("10000", "0");
        counts(1, 1, 1);
    }

    @Test
    void concurrentDuplicateSuccessCannotBeOverwrittenByDelayedFailureRecorder() throws Exception {
        var calls = failDebits("40001", 1);
        var recorderReady = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var locks = new AtomicInteger();
        doAnswer(i -> {
            if (locks.incrementAndGet() == 2) {
                recorderReady.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return i.callRealMethod();
        }).when(repository).find(eq("overlap"), eq(true));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> post("/transfers", "overlap", "10"));
            try {
                assertThat(recorderReady.await(10, TimeUnit.SECONDS)).isTrue();
                var second = post("/transfers", "overlap", "10");
                result(second, 200, "SUCCESS", null);
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).getBody()).isEqualTo(second.getBody());
            } finally {
                release.countDown();
            }
        }
        assertThat(calls.get()).isEqualTo(2);
        balances("9990", "10");
        counts(1, 1, 2);
        assertThat(jdbc.queryForObject("select status from operations", String.class)).isEqualTo("SUCCESS");
    }

    @Test
    void definitiveRejectionRemainsRejectedAfterFundsChange() {
        var first = post("/withdrawals", "rejected", "10001");
        result(first, 422, "REJECTED", "INSUFFICIENT_FUNDS");
        jdbc.update("update accounts set balance = 20000 where id = 1");
        assertThat(post("/withdrawals", "rejected", "10001").getBody()).isEqualTo(first.getBody());
        balances("20000", "0");
        counts(1, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simulatedLostCommitAcknowledgementNeverRepeatsTheDebit(boolean resolutionUnavailable) {
        var committed = simulateLostCommitAcknowledgement(resolutionUnavailable);
        var response = post("/transfers", "commit", "10");
        if (resolutionUnavailable) error(response, "OUTCOME_UNKNOWN", 503);
        else result(response, 200, "SUCCESS", null);
        balances("9990", "10");
        counts(1, 0, 1);
        committed.set(false);
        result(post("/transfers", "commit", "10"), 200, "SUCCESS", null);
        balances("9990", "10");
        counts(1, 0, 1);
        verify(repository, times(1)).debit(anyLong(), any());
    }

    @Test
    void connectionFailureAfterDebitIsUnknownNotConfirmedFailedAndLaterKeyRecovers() {
        var calls = failDebits("08006", 1);
        error(post("/transfers", "connection", "10"), "OUTCOME_UNKNOWN", 503);
        balances("10000", "0");
        counts(1, 0, 0);
        assertThat(jdbc.queryForObject("select status from operations", String.class)).isEqualTo("PROCESSING");
        result(post("/transfers", "connection", "10"), 200, "SUCCESS", null);
        assertThat(calls.get()).isEqualTo(2);
        balances("9990", "10");
        counts(1, 0, 1);
    }

    @Test
    void registrationUnavailableDoesNotInventAnOperationOrLeakSql() {
        doThrow(new DataAccessResourceFailureException("private SQL"))
                .when(repository).registerTransfer(any());
        error(post("/transfers", "registration", "10"), "SERVICE_UNAVAILABLE", 503);
        balances("10000", "0");
        counts(0, 0, 0);
    }

    @Test
    void actualPostgresRowLockTimeoutIsBoundedAndRetryable() throws Exception {
        var timeoutObserved = new CountDownLatch(1);
        var states = new java.util.concurrent.CopyOnWriteArrayList<String>();
        doAnswer(i -> {
            try {
                return i.callRealMethod();
            } catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql) states.add(sql.getSQLState());
                }
                timeoutObserved.countDown();
                throw failure;
            }
        }).when(repository).lockBalance(anyLong());
        try (var connection = DATABASE.createConnection("");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from accounts where id = 1 for update")) {
                statement.executeQuery().close();
            }
            var pending = executor.submit(() -> post("/transfers", "real-lock", "10"));
            try {
                assertThat(timeoutObserved.await(10, TimeUnit.SECONDS)).isTrue();
                connection.rollback();
                result(pending.get(10, TimeUnit.SECONDS), 200, "SUCCESS", null);
            } finally {
                connection.rollback();
            }
        }
        assertThat(states).contains("55P03");
        balances("9990", "10");
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = 'FAILED'", Long.class))
                .isBetween(1L, 2L);
        assertThat(jdbc.queryForObject("select count(*) from operations where status = 'SUCCESS'", Long.class)).isEqualTo(1);
    }

    @Test
    void interruptionStopsRetryAndRestoresWorkerFlag() {
        var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
        doAnswer(i -> {
            i.callRealMethod();
            worker.set(Thread.currentThread());
            Thread.currentThread().interrupt();
            throw new UncategorizedSQLException("interruption simulation", "sanitized", new SQLException("test", "40001"));
        }).when(repository).debit(anyLong(), any());
        var flagAfterOrchestration = new AtomicBoolean();
        OperationOrchestrator target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(orchestrator);
        // Capture the flag at the controller boundary before the servlet container clears it.
        doAnswer(i -> {
            var operation = i.callRealMethod();
            flagAfterOrchestration.set(Thread.currentThread().isInterrupted());
            return operation;
        }).when(target).execute(any(TransferRequest.class));
        result(post("/transfers", "interrupted", "10"), 503, "FAILED", "TRANSIENT_CONFLICT");
        assertThat(worker.get()).isNotNull();
        assertThat(flagAfterOrchestration.get()).isTrue();
        verify(repository, times(1)).debit(anyLong(), any());
        balances("10000", "0");
        counts(1, 1, 1);
    }

    @Test
    void unavailableFailureAuditReturnsUnknownWithoutInventedFailure() {
        failDebits("40001", 1);
        doThrow(new DataAccessResourceFailureException("private SQL"))
                .when(repository).appendAttempt(any(), eq("FAILED"), anyString());
        error(post("/transfers", "audit-outage", "10"), "OUTCOME_UNKNOWN", 503);
        verify(repository, times(1)).debit(anyLong(), any());
        balances("10000", "0");
        counts(1, 0, 0);
        assertThat(jdbc.queryForObject("select status from operations", String.class)).isEqualTo("PROCESSING");
        result(post("/transfers", "audit-outage", "10"), 200, "SUCCESS", null);
        balances("9990", "10");
        counts(1, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/withdrawals", "/transfers"})
    void coordinatedIdenticalKeysProduceOnlyOneMovement(String path) throws Exception {
        int duplicates = 12;
        var ready = new CountDownLatch(duplicates);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = java.util.stream.IntStream.range(0, duplicates).mapToObj(n -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return post(path, "duplicates", "5");
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JsonNode first = null;
            for (var future : pending) {
                var response = future.get(10, TimeUnit.SECONDS);
                result(response, 200, "SUCCESS", null);
                if (first == null) first = response.getBody();
                assertThat(response.getBody()).isEqualTo(first);
            }
        }
        balances("9995", path.equals("/transfers") ? "5" : "0");
        counts(1, 0, 1);
    }

    @Test
    void simulatedRegistrationCommitExceptionResolvesExistingTerminalResult() {
        var original = post("/transfers", "registration-commit", "10");
        var once = new AtomicBoolean(true);
        doAnswer(i -> {
            i.callRealMethod();
            if (once.getAndSet(false)) throw new TransactionSystemException("test-only registration commit acknowledgement");
            return null;
        }).when(transactions).commit(any(TransactionStatus.class));
        var replay = post("/transfers", "registration-commit", "10");
        result(replay, 200, "SUCCESS", null);
        assertThat(replay.getBody()).isEqualTo(original.getBody());
        balances("9990", "10");
        counts(1, 0, 1);
        verify(repository, times(1)).debit(anyLong(), any());
    }

    @Test
    void uncertainRegistrationResolutionStillChecksPayloadBeforeReturningTerminalResult() {
        var original = post("/transfers", "registration-payload", "10");
        doThrow(new UncategorizedSQLException("registration connection simulation", "sanitized",
                new SQLException("test-only", "08006"))).when(repository).registerTransfer(any());
        error(post("/transfers", "registration-payload", "11"), "IDEMPOTENCY_CONFLICT", 409);
        var replay = post("/transfers", "registration-payload", "10");
        result(replay, 200, "SUCCESS", null);
        assertThat(replay.getBody()).isEqualTo(original.getBody());
        balances("9990", "10");
        counts(1, 0, 1);
        verify(repository, times(1)).debit(anyLong(), any());
    }

    private AtomicInteger failDebits(String state, int failures) {
        var calls = new AtomicInteger();
        var transactionIds = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        doAnswer(i -> {
            i.callRealMethod();
            assertThat(transactionIds.add(jdbc.queryForObject("select txid_current()", Long.class)))
                    .as("each processing attempt uses a fresh PostgreSQL transaction").isTrue();
            if (calls.incrementAndGet() <= failures) {
                throw new UncategorizedSQLException("test-only after-debit simulation", "sanitized",
                        new SQLException("test-only", state));
            }
            return null;
        }).when(repository).debit(anyLong(), any());
        return calls;
    }

    // Simulation at the actual transaction-manager boundary, NOT a real network outage.
    private AtomicBoolean simulateLostCommitAcknowledgement(boolean unavailable) {
        var debited = new AtomicBoolean();
        var committed = new AtomicBoolean();
        doAnswer(i -> { debited.set(true); return i.callRealMethod(); })
                .when(repository).debit(anyLong(), any());
        doAnswer(i -> {
            i.callRealMethod();
            if (debited.getAndSet(false)) {
                committed.set(true);
                throw new TransactionSystemException("simulated lost acknowledgement");
            }
            return null;
        }).when(transactions).commit(any(TransactionStatus.class));
        if (unavailable) {
            doAnswer(i -> {
                if (committed.get()) throw new DataAccessResourceFailureException("private SQL");
                return i.callRealMethod();
            }).when(repository).find(anyString(), eq(true));
        }
        return committed;
    }

    private org.springframework.http.ResponseEntity<JsonNode> post(String path, String key, String amount) {
        var body = new java.util.HashMap<String, Object>(Map.of("key", key, "sourceId", 1,
                "amount", new BigDecimal(amount)));
        if (path.equals("/transfers")) body.put("destinationId", 2);
        return http.postForEntity(path, body, JsonNode.class);
    }

    private void result(org.springframework.http.ResponseEntity<JsonNode> response, int code, String status, String reason) {
        assertThat(response.getStatusCode().value()).isEqualTo(code);
        assertThat(response.getBody().size()).isEqualTo(3);
        assertThat(response.getBody().path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(response.getBody().path("status").asText()).isEqualTo(status);
        if (reason == null) assertThat(response.getBody().path("reason").isNull()).isTrue();
        else assertThat(response.getBody().path("reason").asText()).isEqualTo(reason);
    }

    private void error(org.springframework.http.ResponseEntity<JsonNode> response, String code, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().toString()).isEqualTo("{\"error\":\"" + code + "\"}");
    }

    private void balances(String source, String destination) {
        assertThat(jdbc.queryForObject("select balance from accounts where id = 1", BigDecimal.class)).isEqualByComparingTo(source);
        assertThat(jdbc.queryForObject("select balance from accounts where id = 2", BigDecimal.class)).isEqualByComparingTo(destination);
    }

    private void counts(long operations, long failed, long attempts) {
        assertThat(jdbc.queryForObject("select count(*) from operations", Long.class)).isEqualTo(operations);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = 'FAILED'", Long.class)).isEqualTo(failed);
        assertThat(jdbc.queryForObject("select count(*) from operation_attempts", Long.class)).isEqualTo(attempts);
    }
}
