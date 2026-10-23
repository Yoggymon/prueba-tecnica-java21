package com.example.financial.repository;

import com.example.financial.model.Operation;
import com.example.financial.model.TransferRequest;
import com.example.financial.model.WithdrawalRequest;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialRepository {
    private final JdbcTemplate jdbc;
    private final int lockTimeoutMs;

    public FinancialRepository(JdbcTemplate jdbc,
                               @Value("${financial.lock-timeout-ms:1000}") int lockTimeoutMs) {
        if (lockTimeoutMs < 1 || lockTimeoutMs > 60000) {
            throw new IllegalArgumentException("financial.lock-timeout-ms must be 1..60000");
        }
        this.jdbc = jdbc;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public void configureLockTimeout() {
        jdbc.queryForObject("SELECT set_config('lock_timeout', ?, true)", String.class, lockTimeoutMs + "ms");
    }

    public void registerWithdrawal(WithdrawalRequest request) {
        register(request.key(), "WITHDRAWAL", request.sourceId(), null, request.amount());
    }

    public void registerTransfer(TransferRequest request) {
        register(request.key(), "TRANSFER", request.sourceId(), request.destinationId(), request.amount());
    }

    private void register(String key, String kind, long sourceId, Long destinationId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO operations(id, idempotency_key, kind, source_id, destination_id, amount, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, UUID.randomUUID(), key, kind, sourceId, destinationId, amount);
    }

    public Operation find(String key, boolean lock) {
        return jdbc.queryForObject("""
                SELECT id, kind, source_id, destination_id, amount, status, reason, retryable_failure
                FROM operations WHERE idempotency_key = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Operation(
                rs.getObject("id", UUID.class), rs.getString("kind"), rs.getLong("source_id"),
                rs.getObject("destination_id", Long.class), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getString("reason"), rs.getBoolean("retryable_failure")), key);
    }

    public BigDecimal lockBalance(long accountId) {
        var balances = jdbc.query("SELECT balance FROM accounts WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getBigDecimal("balance"), accountId);
        return balances.isEmpty() ? null : balances.getFirst();
    }

    public void debit(long accountId, BigDecimal amount) {
        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, accountId);
    }

    public void credit(long accountId, BigDecimal amount) {
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, accountId);
    }

    public Operation.Result finish(UUID id, String status, String reason) {
        jdbc.update("UPDATE operations SET status = ?, reason = ?, retryable_failure = false WHERE id = ?", status, reason, id);
        appendAttempt(id, status, reason);
        return new Operation.Result(id, status, reason);
    }

    public void appendAttempt(UUID id, String status, String reason) {
        jdbc.update("INSERT INTO operation_attempts(operation_id, result, reason) VALUES (?, ?, ?)",
                id, status, reason);
    }

    public void markFailure(UUID id, String reason, boolean retryable) {
        jdbc.update("UPDATE operations SET status = 'FAILED', reason = ?, retryable_failure = ? WHERE id = ?",
                reason, retryable, id);
    }
}
