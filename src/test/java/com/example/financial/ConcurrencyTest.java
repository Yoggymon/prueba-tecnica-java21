package com.example.financial;

import com.example.financial.model.Operation;
import com.example.financial.model.TransferRequest;
import com.example.financial.model.WithdrawalRequest;
import com.example.financial.service.OperationOrchestrator;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.connection-timeout=60000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=60",
        "spring.jdbc.template.query-timeout=60s",
        "financial.lock-timeout-ms=60000"
})
@Testcontainers
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@DisplayName("Operaciones simultáneas y comprobación de saldos")
class ConcurrencyTest {
    private static final int ACTIVE_OPERATIONS = 64;
    private static final Duration WORKLOAD_TIMEOUT = Duration.ofSeconds(240);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17.7");

    @Autowired OperationOrchestrator orchestrator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabaseOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        jdbc.update("delete from operation_attempts");
        jdbc.update("delete from operations");
        jdbc.update("delete from accounts");
        jdbc.update("insert into accounts(id, initial_balance, balance) values (1, 10000, 10000), (2, 0, 0)");
    }

    @ParameterizedTest(name = "Caso {index} ({0}): {1} solicitudes de {2}, saldo inicial {3}")
    @DisplayName("Comprueba los saldos de cada cuenta con los movimientos registrados")
    @CsvSource({
            "WITHDRAWAL, 1000, 5.00, 10000.00, 1000, 0, 5000.00, 0.00",
            "TRANSFER,    500, 10.00, 10000.00, 500, 0, 5000.00, 5000.00",
            "WITHDRAWAL, 10000, 1.00, 10000.00, 10000, 0, 0.00, 0.00",
            "TRANSFER,   5000, 1.00, 10000.00, 5000, 0, 5000.00, 5000.00",
            "WITHDRAWAL,  128, 5.00, 100.00, 20, 108, 0.00, 0.00",
            "TRANSFER,    128, 5.00, 100.00, 20, 108, 0.00, 100.00"
    })
    void concurrentWorkloadsReconcileEveryAccount(String kind, int tasks, String amount, String initial,
                                                int successes, int rejections, String source, String destination)
            throws Exception {
        setInitialBalance(1, initial);
        var results = runTogether(tasks, i -> kind.equals("WITHDRAWAL")
                ? orchestrator.execute(new WithdrawalRequest("withdraw-" + i, 1, new BigDecimal(amount)))
                : orchestrator.execute(new TransferRequest("transfer-" + i, 1, 2, new BigDecimal(amount))));
        assertThat(results.stream().filter(r -> r.status().equals("SUCCESS"))).hasSize(successes);
        assertThat(results.stream().filter(r -> r.status().equals("REJECTED"))).hasSize(rejections);
        assertLedger(results, successes, rejections, kind, amount, source, destination);
    }

    @Test
    @DisplayName("La misma clave genera un solo resultado y repetirla no cambia los datos")
    void concurrentSameKeyHasOneStableResultAndReplayDoesNotChangeDatabase() throws Exception {
        var request = new WithdrawalRequest("shared-key", 1, new BigDecimal("5.00"));
        var results = runTogether(128, ignored -> orchestrator.execute(request));
        assertThat(results).containsOnly(results.getFirst());
        assertThat(results.getFirst().status()).isEqualTo("SUCCESS");
        assertThat(results.getFirst().reason()).isNull();
        assertLedger(results, 1, 0, "WITHDRAWAL", "5.00", "9995.00", "0.00");
        var beforeReplay = databaseDigest();
        assertThat(orchestrator.execute(new WithdrawalRequest("shared-key", 1, new BigDecimal("5.000"))))
                .isEqualTo(results.getFirst());
        assertThat(databaseDigest()).isEqualTo(beforeReplay);
    }

    @Test
    @DisplayName("Las transferencias en ambos sentidos conservan ambos saldos")
    void bidirectionalTransfersWithSufficientFundsConserveBothBalances() throws Exception {
        setInitialBalance(2, "10000.00");
        var results = runTogether(512, i -> orchestrator.execute(new TransferRequest("both-" + i,
                i % 2 == 0 ? 1 : 2, i % 2 == 0 ? 2 : 1, new BigDecimal("10.00"))));
        assertLedger(results, 512, 0, "TRANSFER", "10.00", "10000.00", "10000.00");
        for (long source : List.of(1L, 2L)) {
            assertThat(count("select count(*) from operations where source_id = " + source)).isEqualTo(256);
            assertThat(jdbc.queryForObject("select sum(amount) from operations where source_id = ?",
                    BigDecimal.class, source)).isEqualByComparingTo("2560.00");
        }
    }

    private void setInitialBalance(long account, String amount) {
        assertThat(jdbc.update("update accounts set initial_balance = ?, balance = ? where id = ?",
                new BigDecimal(amount), new BigDecimal(amount), account)).isEqualTo(1);
    }

    private List<Operation.Result> runTogether(int tasks, IntFunction<Operation.Result> operation) throws Exception {
        long deadline = System.nanoTime() + WORKLOAD_TIMEOUT.toNanos();
        var ready = new CountDownLatch(tasks);
        var start = new CountDownLatch(1);
        var active = new Semaphore(ACTIVE_OPERATIONS);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var completed = new ExecutorCompletionService<Operation.Result>(executor);
        var futures = new ArrayList<Future<Operation.Result>>(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(completed.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(remaining(deadline), TimeUnit.NANOSECONDS)).isTrue();
                    assertThat(active.tryAcquire(remaining(deadline), TimeUnit.NANOSECONDS)).isTrue();
                    try {
                        return operation.apply(index);
                    } finally {
                        active.release();
                    }
                }));
            }
            // Todas las tareas deben estar listas antes de iniciar las operaciones.
            assertThat(ready.await(remaining(deadline), TimeUnit.NANOSECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<Operation.Result>(tasks);
            for (int i = 0; i < tasks; i++) {
                var future = completed.poll(remaining(deadline), TimeUnit.NANOSECONDS);
                assertThat(future).as("tarea completada dentro del plazo total de la prueba").isNotNull();
                results.add(future.get()); // La tarea ya terminó; cualquier excepción se propaga.
            }
            assertThat(results).hasSize(tasks);
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            // Evitar ExecutorService.close(): su espera sin límite podría impedir que termine una prueba fallida.
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("finalización de las tareas").isTrue();
        }
    }

    private long remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        assertThat(nanos).as("plazo total de la prueba aún disponible").isPositive();
        return nanos;
    }

    private void assertLedger(List<Operation.Result> results, long successes, long rejections,
                              String kind, String amount, String firstBalance, String secondBalance) {
        for (var result : results) {
            assertThat(result.id()).isNotNull();
            assertThat(result.status()).isIn("SUCCESS", "REJECTED");
            assertThat(result.reason()).isEqualTo(result.status().equals("SUCCESS") ? null : "INSUFFICIENT_FUNDS");
        }
        assertThat(results.stream().map(Operation.Result::id).distinct()).hasSize((int) (successes + rejections));
        Map<UUID, Operation.Result> persisted = jdbc.query("select id, status, reason from operations", rs -> {
            var rows = new java.util.HashMap<UUID, Operation.Result>();
            while (rs.next()) {
                var id = rs.getObject("id", UUID.class);
                rows.put(id, new Operation.Result(id, rs.getString("status"), rs.getString("reason")));
            }
            return rows;
        });
        results.forEach(result -> assertThat(persisted.get(result.id())).isEqualTo(result));
        assertThat(count("select count(*) from operations")).isEqualTo(successes + rejections);
        assertThat(count("select count(*) from operations where status = 'SUCCESS'")).isEqualTo(successes);
        assertThat(count("select count(*) from operations where status = 'REJECTED'")).isEqualTo(rejections);
        assertThat(count("select count(*) from operations where status in ('PROCESSING', 'FAILED')")).isZero();
        assertThat(count("select count(*) from operations where retryable_failure")).isZero();
        assertThat(count("select count(*) from operations where created_at is null")).isZero();
        assertThat(jdbc.queryForList("select distinct kind from operations", String.class)).containsExactly(kind);
        assertThat(jdbc.queryForList("select distinct amount from operations", BigDecimal.class))
                .singleElement().satisfies(value -> assertThat(value).isEqualByComparingTo(amount));
        assertThat(count("select count(*) from operations where source_id not in (1, 2)"
                + " or (kind = 'WITHDRAWAL' and destination_id is not null)"
                + " or (kind = 'TRANSFER' and (destination_id not in (1, 2) or source_id = destination_id))")).isZero();
        for (var entry : Map.of("SUCCESS", successes, "REJECTED", rejections).entrySet()) {
            BigDecimal expected = new BigDecimal(amount).multiply(BigDecimal.valueOf(entry.getValue()));
            assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from operations where status = ?",
                    BigDecimal.class, entry.getKey())).isEqualByComparingTo(expected);
            assertThat(jdbc.queryForObject("select count(*) from operation_attempts where result = ?",
                    Long.class, entry.getKey())).isEqualTo(entry.getValue());
        }
        assertThat(count("select count(*) from operation_attempts")).isEqualTo(successes + rejections);
        assertThat(count("""
                select count(*) from operations o where
                  (select count(*) from operation_attempts a where a.operation_id = o.id) <> 1
                """)).isZero();
        assertThat(count("""
                select count(*) from operation_attempts a join operations o on o.id = a.operation_id
                where a.result <> o.status or a.reason is distinct from o.reason
                """)).isZero();
        assertThat(count("select count(*) from accounts")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select balance from accounts where id = 1", BigDecimal.class))
                .isEqualByComparingTo(firstBalance);
        assertThat(jdbc.queryForObject("select balance from accounts where id = 2", BigDecimal.class))
                .isEqualByComparingTo(secondBalance);
        assertThat(count("select count(*) from accounts where balance < 0")).isZero();
        assertThat(count("""
                select count(*) from accounts a where balance <> initial_balance
                  - coalesce((select sum(o.amount) from operations o
                              where o.status = 'SUCCESS' and o.source_id = a.id), 0)
                  + coalesce((select sum(o.amount) from operations o
                              where o.status = 'SUCCESS' and o.kind = 'TRANSFER'
                                and o.destination_id = a.id), 0)
                """)).as("el saldo de cada cuenta coincide con los movimientos aplicados y registrados").isZero();
        assertThat(jdbc.queryForObject("select sum(balance) from accounts", BigDecimal.class))
                .isEqualByComparingTo(jdbc.queryForObject("""
                        select (select sum(initial_balance) from accounts)
                          - coalesce(sum(amount) filter (where status = 'SUCCESS' and kind = 'WITHDRAWAL'), 0)
                        from operations
                        """, BigDecimal.class));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private List<List<Map<String, Object>>> databaseDigest() {
        // Comparar todas las filas ordenadas, incluidas fechas, contadores, motivos y datos de reintentos.
        return List.of(jdbc.queryForList("select * from accounts order by id"),
                jdbc.queryForList("select * from operations order by id"),
                jdbc.queryForList("select * from operation_attempts order by id"));
    }
}
