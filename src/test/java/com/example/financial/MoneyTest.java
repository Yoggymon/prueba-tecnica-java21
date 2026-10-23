package com.example.financial;

import com.example.financial.model.Money;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.5", "1.50", "1.500"})
    void acceptsExactCentAmounts(String input) {
        assertThat(Money.amount(new BigDecimal(input)).scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "-0.01", "1.005", "100000000000000000.00"})
    void rejectsMissingNonPositiveFractionalOrOverflowAmounts(String input) {
        assertThatThrownBy(() -> Money.amount(input == null ? null : new BigDecimal(input)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheLargestPersistableAmount() {
        assertThat(Money.amount(new BigDecimal("99999999999999999.99")))
                .isEqualByComparingTo("99999999999999999.99");
    }

}
