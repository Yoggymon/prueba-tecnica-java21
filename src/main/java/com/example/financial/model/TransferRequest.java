package com.example.financial.model;

import java.math.BigDecimal;

public record TransferRequest(String key, long sourceId, long destinationId, BigDecimal amount) {
    public TransferRequest {
        if (key == null || key.isBlank() || key.length() > 128 || sourceId <= 0
                || destinationId <= 0 || sourceId == destinationId) {
            throw new IllegalArgumentException("Invalid transfer request");
        }
        amount = Money.amount(amount);
    }
}
