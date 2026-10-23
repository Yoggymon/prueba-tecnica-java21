package com.example.financial.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        try {
            BigDecimal cents = value.setScale(2, RoundingMode.UNNECESSARY);
            if (cents.precision() > 19) {
                throw new IllegalArgumentException("Amount exceeds NUMERIC(19,2)");
            }
            return cents;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount must be an exact number of cents", exception);
        }
    }
}
