package com.example.financial;

import com.example.financial.model.Money;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Validación de importes")
class MoneyTest {
    @ParameterizedTest(name = "Importe válido: {0}")
    @DisplayName("Acepta importes con céntimos exactos")
    @ValueSource(strings = {"1", "1.5", "1.50", "1.500"})
    void acceptsExactCentAmounts(String input) {
        assertThat(Money.amount(new BigDecimal(input)).scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "Importe no válido: {0}")
    @DisplayName("Rechaza importes ausentes, no positivos o fuera de los límites")
    @NullSource
    @ValueSource(strings = {"0", "-1", "-0.01", "1.005", "100000000000000000.00"})
    void rejectsMissingNonPositiveFractionalOrOverflowAmounts(String input) {
        assertThatThrownBy(() -> Money.amount(input == null ? null : new BigDecimal(input)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Acepta el mayor importe que permite la base de datos")
    void acceptsTheLargestPersistableAmount() {
        assertThat(Money.amount(new BigDecimal("99999999999999999.99")))
                .isEqualByComparingTo("99999999999999999.99");
    }

}
