package com.example.financial.service;

import com.example.financial.model.TransferRequest;
import com.example.financial.model.WithdrawalRequest;
import com.example.financial.repository.FinancialRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationRegistration {
    private final FinancialRepository repository;

    public OperationRegistration(FinancialRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(WithdrawalRequest request) {
        repository.configureLockTimeout();
        repository.registerWithdrawal(request);
        if (!repository.find(request.key(), false).matches(request)) {
            throw new IdempotencyConflict();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void register(TransferRequest request) {
        repository.configureLockTimeout();
        repository.registerTransfer(request);
        if (!repository.find(request.key(), false).matches(request)) {
            throw new IdempotencyConflict();
        }
    }

    public static class IdempotencyConflict extends RuntimeException {}
}
