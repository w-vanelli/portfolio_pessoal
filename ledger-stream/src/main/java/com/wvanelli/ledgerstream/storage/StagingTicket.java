package com.wvanelli.ledgerstream.storage;

import java.util.Objects;

/** An attempt-owned file; the digest covers the bytes actually written. */
public record StagingTicket(String storagePath, long fileSizeBytes, String contentType,
                            String originalFileName, String contentChecksum) {
    public StagingTicket {
        Objects.requireNonNull(storagePath);
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(originalFileName);
        if (fileSizeBytes < 0 || contentChecksum == null || !contentChecksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid staging size or SHA-256 digest");
        }
    }
}
