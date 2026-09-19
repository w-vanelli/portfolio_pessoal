package com.wvanelli.ledgerstream.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Entity representing an attachment associated with a financial settlement event.
 * Mapped to table {@code settlement_attachments}.
 */
@Entity
@Table(name = "settlement_attachments")
public class SettlementAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private SettlementEvent settlementEvent;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AttachmentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SettlementAttachment() {
        // Required by JPA specification
    }

    public SettlementAttachment(String originalFileName,
                                Long fileSizeBytes,
                                String contentType,
                                String storagePath) {
        this.originalFileName = Objects.requireNonNull(originalFileName, "originalFileName must not be null");
        this.fileSizeBytes = Objects.requireNonNull(fileSizeBytes, "fileSizeBytes must not be null");
        this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath must not be null");
        this.status = AttachmentStatus.STAGED;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (this.status == null) {
            this.status = AttachmentStatus.STAGED;
        }
    }

    public void setSettlementEvent(SettlementEvent settlementEvent) {
        this.settlementEvent = settlementEvent;
    }

    public void updateStatus(AttachmentStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
    }

    public void updateStoragePath(String newPath) {
        this.storagePath = Objects.requireNonNull(newPath, "newPath must not be null");
    }

    // Getters
    public Long getId() {
        return id;
    }

    public SettlementEvent getSettlementEvent() {
        return settlementEvent;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getContentType() {
        return contentType;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public AttachmentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
