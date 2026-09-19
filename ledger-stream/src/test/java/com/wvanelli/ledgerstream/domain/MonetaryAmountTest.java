package com.wvanelli.ledgerstream.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonetaryAmountTest {

    @Test
    @DisplayName("Should parse valid monetary strings conforming to NUMERIC(15,2)")
    void shouldParseValidMonetaryStrings() {
        MonetaryAmount a1 = new MonetaryAmount("1450.75");
        assertThat(a1.getValue()).isEqualByComparingTo(new BigDecimal("1450.75"));
        assertThat(a1.toCanonicalString()).isEqualTo("1450.75");

        MonetaryAmount a2 = new MonetaryAmount("100.5");
        assertThat(a2.toCanonicalString()).isEqualTo("100.50");

        MonetaryAmount a3 = new MonetaryAmount("50");
        assertThat(a3.toCanonicalString()).isEqualTo("50.00");

        MonetaryAmount a4 = new MonetaryAmount("9999999999999.99");
        assertThat(a4.toCanonicalString()).isEqualTo("9999999999999.99");
    }

    @Test
    @DisplayName("Should accept minimum valid amount 0.01")
    void shouldAcceptMinimumValidAmount() {
        MonetaryAmount min = new MonetaryAmount("0.01");
        assertThat(min.getValue()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(min.toCanonicalString()).isEqualTo("0.01");
    }

    @Test
    @DisplayName("Should normalize equivalent representations to identical canonical string for idempotency checksums")
    void shouldNormalizeEquivalentRepresentations() {
        MonetaryAmount a1 = new MonetaryAmount("150.5");
        MonetaryAmount a2 = new MonetaryAmount("150.50");

        assertThat(a1).isEqualTo(a2);
        assertThat(a1.toCanonicalString()).isEqualTo(a2.toCanonicalString()).isEqualTo("150.50");
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
    }

    @Test
    @DisplayName("Should treat '1', '1.0', and '1.00' as the same value after normalization")
    void shouldNormalizeIntegerAndDecimalRepresentations() {
        MonetaryAmount fromInteger = new MonetaryAmount("1");
        MonetaryAmount fromOneDecimal = new MonetaryAmount("1.0");
        MonetaryAmount fromTwoDecimals = new MonetaryAmount("1.00");

        assertThat(fromInteger).isEqualTo(fromOneDecimal).isEqualTo(fromTwoDecimals);
        assertThat(fromInteger.toCanonicalString())
                .isEqualTo(fromOneDecimal.toCanonicalString())
                .isEqualTo(fromTwoDecimals.toCanonicalString())
                .isEqualTo("1.00");
        assertThat(fromInteger.hashCode())
                .isEqualTo(fromOneDecimal.hashCode())
                .isEqualTo(fromTwoDecimals.hashCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "0.00"})
    @DisplayName("Should reject zero amount — settlements must have strictly positive amount")
    void shouldRejectZeroAmount(String zeroAmount) {
        assertThatThrownBy(() -> new MonetaryAmount(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1450.755", "0.001", "100.1234", "99.999"})
    @DisplayName("Should reject excess decimal precision instead of silently rounding")
    void shouldRejectExcessDecimalPrecision(String invalidScale) {
        assertThatThrownBy(() -> new MonetaryAmount(invalidScale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000000000000.00", "99999999999999.00", "12345678901234"})
    @DisplayName("Should reject integer part exceeding 13 digits (NUMERIC 15,2 overflow)")
    void shouldRejectIntegerPartOverflow(String overflowAmount) {
        assertThatThrownBy(() -> new MonetaryAmount(overflowAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-10.00", "-0.01", "-100", "-9999999999999.99"})
    @DisplayName("Should reject negative amounts according to domain invariant")
    void shouldRejectNegativeAmounts(String negativeAmount) {
        assertThatThrownBy(() -> new MonetaryAmount(negativeAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e5", "1E2", "abc", "10,50", "0123.45", "", "   "})
    @DisplayName("Should reject scientific notation, non-numeric characters, and invalid formatting")
    void shouldRejectInvalidFormatting(String invalidFormat) {
        assertThatThrownBy(() -> new MonetaryAmount(invalidFormat))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should construct from BigDecimal and validate invariants")
    void shouldConstructFromBigDecimal() {
        BigDecimal valid = new BigDecimal("250.00");
        MonetaryAmount amount = new MonetaryAmount(valid);
        assertThat(amount.toCanonicalString()).isEqualTo("250.00");

        BigDecimal minimum = new BigDecimal("0.01");
        MonetaryAmount minAmount = new MonetaryAmount(minimum);
        assertThat(minAmount.toCanonicalString()).isEqualTo("0.01");

        BigDecimal negative = new BigDecimal("-1.00");
        assertThatThrownBy(() -> new MonetaryAmount(negative))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");

        BigDecimal zero = BigDecimal.ZERO;
        assertThatThrownBy(() -> new MonetaryAmount(zero))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");

        BigDecimal excessScale = new BigDecimal("10.999");
        assertThatThrownBy(() -> new MonetaryAmount(excessScale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Excess decimal precision rejected");
    }

    @Test
    @DisplayName("Should reject null inputs with NullPointerException")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> new MonetaryAmount((String) null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new MonetaryAmount((BigDecimal) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should implement Comparable contract for ordering")
    void shouldImplementComparable() {
        MonetaryAmount small = new MonetaryAmount("10.00");
        MonetaryAmount large = new MonetaryAmount("1000.00");

        assertThat(small.compareTo(large)).isNegative();
        assertThat(large.compareTo(small)).isPositive();
        assertThat(small.compareTo(new MonetaryAmount("10.00"))).isZero();
    }
}
