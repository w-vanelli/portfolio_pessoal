package com.wvanelli.ledgerstream.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface StagingStorageService {
    /** Reads but never closes the caller-owned stream. */
    StagingTicket stage(String originalFileName, String contentType, InputStream stream) throws IOException;

    /** Requires ATOMIC_MOVE; failure preserves the source. This is not a crash-durability guarantee. */
    Path promoteToPermanent(String storagePath) throws IOException;

    /** Deletes only this attempt's staging file. Cleanup errors remain observable. */
    void compensateStaging(String storagePath) throws IOException;
}
