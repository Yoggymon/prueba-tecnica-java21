package com.example.financial.model;

import java.math.BigDecimal;

public record WithdrawalRequest(String key, long sourceId, BigDecimal amount) {
    public WithdrawalRequest {
        if (key == null || key.isBlank() || key.length() > 128 || sourceId <= 0) {
            throw new IllegalArgumentException("Invalid withdrawal request");
        }
        amount = Money.amount(amount);
    }
}
