package com.example.financial.model;

import java.math.BigDecimal;
import java.util.UUID;

public record Operation(UUID id, String kind, long sourceId, Long destinationId, BigDecimal amount,
                        String status, String reason, boolean retryableFailure) {
    public record Result(UUID id, String status, String reason) {}

    public boolean terminal() {
        return status.equals("SUCCESS") || status.equals("REJECTED")
                || (status.equals("FAILED") && !retryableFailure);
    }

    public Result result() {
        return new Result(id, status, reason);
    }

    public boolean matches(WithdrawalRequest request) {
        return kind.equals("WITHDRAWAL") && sourceId == request.sourceId() && destinationId == null
                && amount.compareTo(request.amount()) == 0;
    }

    public boolean matches(TransferRequest request) {
        return kind.equals("TRANSFER") && sourceId == request.sourceId()
                && destinationId != null && destinationId == request.destinationId()
                && amount.compareTo(request.amount()) == 0;
    }
}
