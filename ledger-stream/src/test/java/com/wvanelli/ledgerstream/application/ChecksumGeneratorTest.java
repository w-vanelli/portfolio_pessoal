package com.wvanelli.ledgerstream.application;

import com.wvanelli.ledgerstream.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChecksumGeneratorTest {
    private String checksum(String amount, String description, String filename, String type, String digest, Long original) {
        return ChecksumGenerator.generatePayloadChecksum("SYNTH", "USD", new MonetaryAmount(amount),
                SettlementType.CARD_PAYOUT, description, original, filename, type, digest);
    }
    @Test void monetaryRepresentationsAreEquivalent() {
        assertThat(checksum("1", "d", null, null, null, null))
                .isEqualTo(checksum("1.0", "d", null, null, null, null))
                .isEqualTo(checksum("1.00", "d", null, null, null, null));
    }
    @Test void delimitersCannotShiftFieldBoundaries() {
        assertThat(checksum("1", "d", "a|b", "c", "hash", null))
                .isNotEqualTo(checksum("1", "d", "a", "b|c", "hash", null));
    }
    @Test void bytesPresenceAndOriginalReferenceArePartOfIdentity() {
        String base = checksum("1", "d", "a", "text/plain", "hash1", 1L);
        assertThat(base).isNotEqualTo(checksum("1", "d", "a", "text/plain", "hash2", 1L))
                .isNotEqualTo(checksum("1", "d", "a", "text/plain", "hash1", 2L))
                .isNotEqualTo(checksum("1", "d", null, null, null, 1L));
    }
    @Test void nullAndEmptyDescriptionAreExplicitlyDistinct() {
        assertThat(checksum("1", null, null, null, null, null))
                .isNotEqualTo(checksum("1", "", null, null, null, null));
    }
}
