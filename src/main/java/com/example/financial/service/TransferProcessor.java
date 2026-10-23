package com.example.financial.service;

import com.example.financial.model.Operation;
import com.example.financial.model.TransferRequest;
import com.example.financial.repository.FinancialRepository;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferProcessor {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private final FinancialRepository repository;

    public TransferProcessor(FinancialRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Operation.Result process(TransferRequest request) {
        repository.configureLockTimeout();
        Operation operation = repository.find(request.key(), true);
        if (!operation.matches(request)) {
            throw new OperationRegistration.IdempotencyConflict();
        }
        if (operation.terminal()) {
            return operation.result();
        }
        // Every direction takes the lower account ID first, after the operation lock.
        long lowerId = Math.min(operation.sourceId(), operation.destinationId());
        long upperId = Math.max(operation.sourceId(), operation.destinationId());
        BigDecimal lower = repository.lockBalance(lowerId);
        BigDecimal upper = repository.lockBalance(upperId);
        if (lower == null || upper == null) {
            return repository.finish(operation.id(), "REJECTED", "ACCOUNT_NOT_FOUND");
        }
        BigDecimal source = operation.sourceId() == lowerId ? lower : upper;
        BigDecimal destination = operation.sourceId() == lowerId ? upper : lower;
        if (source.compareTo(operation.amount()) < 0) {
            return repository.finish(operation.id(), "REJECTED", "INSUFFICIENT_FUNDS");
        }
        if (destination.add(operation.amount()).compareTo(MAX_BALANCE) > 0) {
            return repository.finish(operation.id(), "REJECTED", "DESTINATION_BALANCE_LIMIT");
        }
        repository.debit(operation.sourceId(), operation.amount());
        repository.credit(operation.destinationId(), operation.amount());
        return repository.finish(operation.id(), "SUCCESS", null);
    }
}
