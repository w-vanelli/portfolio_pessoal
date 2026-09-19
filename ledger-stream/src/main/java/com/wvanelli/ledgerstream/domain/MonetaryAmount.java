package com.wvanelli.ledgerstream.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object representing a monetary amount strictly constrained to the PostgreSQL NUMERIC(15,2) specification.
 *
 * <p>Key Invariants:
 * <ul>
 *   <li>Direct String to BigDecimal conversion, avoiding any lossy intermediate double or float representations.</li>
 *   <li>Amount must be strictly positive (greater than zero).</li>
 *   <li>Permitted range: 0.01 to 9,999,999,999,999.99 (max 13 integer digits and at most 2 fractional digits).</li>
 *   <li>Negative values and zero are rejected by domain validation.</li>
 *   <li>Excess precision (&gt; 2 decimal digits) is rejected rather than silently rounded.</li>
 *   <li>Deterministic normalization to scale 2 enables stable SHA-256 payload checksums for idempotency verification.</li>
 * </ul>
 *
 * <p><strong>Design Decision (approved):</strong> This value object is currently used exclusively for settlement
 * amounts. The strictly-positive constraint reflects the approved domain rule that every settlement must have
 * {@code amount > 0}. Representations "1", "1.0", and "1.00" are treated as the same value after normalization.
 * The format regex serves as a syntactic guard; it does not replace the numeric validation in this class.
 */
public final class MonetaryAmount implements Comparable<MonetaryAmount>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Regex enforcing NUMERIC(15,2) format bounds:
     * - Disallows leading zeros unless integer part is exactly 0.
     * - Up to 13 integer digits (1 to 13 digits).
     * - Optional fractional part of 1 or 2 digits.
     *
     * <p>Note: This regex is a format guard only. Zero rejection and positivity
     * are enforced by the numeric validation below, not by this pattern.
     */
    private static final Pattern MONETARY_REGEX = Pattern.compile("^(0|[1-9]\\d{0,12})(\\.\\d{1,2})?$");

    private final BigDecimal value;

    /**
     * Constructs a MonetaryAmount from a string representation.
     *
     * @param rawAmount raw string from API/DTO (e.g. "1450.75", "100.5", "50")
     * @throws IllegalArgumentException if format is invalid, amount is zero or negative,
     *                                  scale &gt; 2, or integer digits &gt; 13
     */
    public MonetaryAmount(String rawAmount) {
        Objects.requireNonNull(rawAmount, "Monetary amount string must not be null");
        String trimmed = rawAmount.trim();

        if (!MONETARY_REGEX.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid monetary amount format. Must conform to NUMERIC(15,2) with max 13 integer digits and at most 2 decimal places: '" + rawAmount + "'"
            );
        }

        BigDecimal parsed = new BigDecimal(trimmed);

        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Monetary amount must be strictly positive (> 0). Received: " + rawAmount
            );
        }

        if (parsed.scale() > 2) {
            throw new IllegalArgumentException("Excess decimal precision rejected; maximum scale is 2: " + rawAmount);
        }

        // Normalized to exact scale 2 (e.g. "10.5" -> 10.50, "100" -> 100.00)
        this.value = parsed.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Constructs a MonetaryAmount from an existing BigDecimal.
     *
     * @param amount the BigDecimal value (must be strictly positive)
     * @throws IllegalArgumentException if amount is zero or negative, scale &gt; 2, or integer digits &gt; 13
     */
    public MonetaryAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "Monetary amount BigDecimal must not be null");

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Monetary amount must be strictly positive (> 0). Received: " + amount
            );
        }

        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Excess decimal precision rejected; maximum scale is 2: " + amount);
        }

        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        int integerDigits = normalized.precision() - normalized.scale();
        if (integerDigits > 13) {
            throw new IllegalArgumentException("Integer part exceeds NUMERIC(15,2) limit of 13 digits: " + amount);
        }

        this.value = normalized;
    }

    public BigDecimal getValue() {
        return value;
    }

    /**
     * Returns canonical string representation with exactly 2 decimal places (e.g. "1450.50").
     * Required for deterministic SHA-256 idempotency payload checksum computation.
     * Representations "1", "1.0", and "1.00" all normalize to "1.00".
     */
    public String toCanonicalString() {
        return value.toPlainString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MonetaryAmount that)) return false;
        return value.compareTo(that.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public int compareTo(MonetaryAmount o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return toCanonicalString();
    }
}
