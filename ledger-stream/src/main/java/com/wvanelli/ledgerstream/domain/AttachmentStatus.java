package com.wvanelli.ledgerstream.domain;

/**
 * Storage lifecycle status of an attachment.
 */
public enum AttachmentStatus {
    /**
     * Resides in staging storage awaiting transaction completion.
     */
    STAGED,

    /**
     * Promoted to permanent storage following transaction commit.
     */
    PERMANENT,

    /**
     * Purged/compensated due to transaction failure or retention policy.
     */
    PURGED
}
