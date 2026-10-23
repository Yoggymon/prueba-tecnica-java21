package com.example.financial.service;

import com.example.financial.model.Operation;
import com.example.financial.model.WithdrawalRequest;
import com.example.financial.repository.FinancialRepository;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalProcessor {
    private final FinancialRepository repository;

    public WithdrawalProcessor(FinancialRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Operation.Result process(WithdrawalRequest request) {
        repository.configureLockTimeout();
        Operation operation = repository.find(request.key(), true);
        if (!operation.matches(request)) {
            throw new OperationRegistration.IdempotencyConflict();
        }
        if (operation.terminal()) {
            return operation.result();
        }
        BigDecimal balance = repository.lockBalance(operation.sourceId());
        if (balance == null) {
            return repository.finish(operation.id(), "REJECTED", "ACCOUNT_NOT_FOUND");
        }
        if (balance.compareTo(operation.amount()) < 0) {
            return repository.finish(operation.id(), "REJECTED", "INSUFFICIENT_FUNDS");
        }
        repository.debit(operation.sourceId(), operation.amount());
        return repository.finish(operation.id(), "SUCCESS", null);
    }
}
