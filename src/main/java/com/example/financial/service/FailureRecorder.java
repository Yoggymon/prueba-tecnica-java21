package com.example.financial.service;

import com.example.financial.model.Operation;
import com.example.financial.repository.FinancialRepository;

import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FailureRecorder {
    private final FinancialRepository repository;

    public FailureRecorder(FinancialRepository repository) {
        this.repository = repository;
    }

    // Called only after the processing proxy has completed rollback and released its locks.
    @Transactional
    public Operation.Result record(String key, boolean retryable) {
        repository.configureLockTimeout();
        Operation operation = repository.find(key, true);
        String reason = retryable ? "TRANSIENT_CONFLICT" : "PROCESSING_FAILURE";
        repository.appendAttempt(operation.id(), "FAILED", reason);
        if (operation.terminal()) return operation.result();
        repository.markFailure(operation.id(), reason, retryable);
        return new Operation.Result(operation.id(), "FAILED", reason);
    }

    @Transactional
    public Operation.Result resolve(String key, Predicate<Operation> matches) {
        repository.configureLockTimeout();
        Operation operation = repository.find(key, true);
        if (!matches.test(operation)) throw new OperationRegistration.IdempotencyConflict();
        return operation.terminal() ? operation.result() : null;
    }
}
