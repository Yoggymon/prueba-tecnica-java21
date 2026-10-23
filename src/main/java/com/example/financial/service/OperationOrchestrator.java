package com.example.financial.service;

import com.example.financial.model.Operation;
import com.example.financial.model.TransferRequest;
import com.example.financial.model.WithdrawalRequest;

import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(OperationOrchestrator.class);
    private final OperationRegistration registration;
    private final WithdrawalProcessor withdrawals;
    private final TransferProcessor transfers;
    private final FailureRecorder failures;

    public OperationOrchestrator(OperationRegistration registration, WithdrawalProcessor withdrawals,
                                 TransferProcessor transfers, FailureRecorder failures) {
        this.registration = registration;
        this.withdrawals = withdrawals;
        this.transfers = transfers;
        this.failures = failures;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Operation.Result execute(WithdrawalRequest request) {
        return execute(request.key(), () -> registration.register(request), () -> withdrawals.process(request),
                operation -> operation.matches(request));
    }

    @Transactional(propagation = Propagation.NEVER)
    public Operation.Result execute(TransferRequest request) {
        return execute(request.key(), () -> registration.register(request), () -> transfers.process(request),
                operation -> operation.matches(request));
    }

    private Operation.Result execute(String key, Runnable register, Supplier<Operation.Result> process,
                                     Predicate<Operation> matches) {
        try {
            register.run();
        } catch (OperationRegistration.IdempotencyConflict conflict) {
            throw conflict;
        } catch (RuntimeException failure) {
            if (unknownOutcome(failure)) return resolve(key, matches);
            LOG.warn("Financial registration unavailable");
            // No financial transaction has been started. Never invent a movement/failure row.
            throw new Unavailable("SERVICE_UNAVAILABLE");
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return process.get(); // external proxy call: fresh transaction on each iteration
            } catch (OperationRegistration.IdempotencyConflict conflict) {
                throw conflict;
            } catch (RuntimeException failure) {
                if (unknownOutcome(failure)) return resolve(key, matches);
                boolean retryable = transientRollback(failure);
                Operation.Result recorded;
                try {
                    recorded = failures.record(key, retryable);
                } catch (RuntimeException auditFailure) {
                    LOG.warn("Financial failure audit unavailable; rollback observed but audit not confirmed");
                    return resolve(key, matches);
                }
                if (!recorded.status().equals("FAILED") || !retryable || attempt == 3) return recorded;
                try {
                    Thread.sleep(20L * attempt); // bounded backoff, no active transaction/locks
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return recorded;
                }
            }
        }
        throw new IllegalStateException("Unreachable attempt bound");
    }

    private Operation.Result resolve(String key, Predicate<Operation> matches) {
        try {
            Operation.Result terminal = failures.resolve(key, matches);
            if (terminal != null) return terminal;
        } catch (OperationRegistration.IdempotencyConflict conflict) {
            throw conflict;
        } catch (RuntimeException unavailable) {
            LOG.warn("Financial outcome resolution unavailable");
        }
        LOG.warn("Financial outcome unconfirmed; retry only with the same key");
        throw new Unavailable("OUTCOME_UNKNOWN");
    }

    private boolean unknownOutcome(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionException) return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) return true;
        }
        return false;
    }

    private boolean transientRollback(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && ("40P01".equals(sql.getSQLState())
                    || "40001".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String code) { super(code); }
    }
}
