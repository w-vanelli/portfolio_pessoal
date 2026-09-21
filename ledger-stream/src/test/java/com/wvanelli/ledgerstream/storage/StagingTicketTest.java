package com.wvanelli.ledgerstream.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingTicketTest {

    @Test
    @DisplayName("Should create StagingTicket with valid fields")
    void shouldCreateStagingTicket() {
        StagingTicket ticket = new StagingTicket(
                "/storage/staging/file-123.tmp",
                2048L,
                "application/pdf",
                "invoice.pdf",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );

        assertThat(ticket.storagePath()).isEqualTo("/storage/staging/file-123.tmp");
        assertThat(ticket.fileSizeBytes()).isEqualTo(2048L);
        assertThat(ticket.contentType()).isEqualTo("application/pdf");
        assertThat(ticket.originalFileName()).isEqualTo("invoice.pdf");
        assertThat(ticket.contentChecksum()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("Should reject negative file size or null attributes")
    void shouldRejectInvalidTicketAttributes() {
        assertThatThrownBy(() -> new StagingTicket(
                "/storage/staging/file-123.tmp",
                -1L,
                "application/pdf",
                "invoice.pdf",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid staging size or SHA-256 digest");

        assertThatThrownBy(() -> new StagingTicket(
                null,
                100L,
                "application/pdf",
                "invoice.pdf",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )).isInstanceOf(NullPointerException.class);
    }
}
