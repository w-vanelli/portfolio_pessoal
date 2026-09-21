package com.wvanelli.ledgerstream.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingStorageServiceImplTest {

    @TempDir
    Path tempDir;

    private StagingStorageServiceImpl service;
    private Path stagingDir;
    private Path permanentDir;

    @BeforeEach
    void setUp() throws IOException {
        stagingDir = tempDir.resolve("staging");
        permanentDir = tempDir.resolve("permanent");
        service = new StagingStorageServiceImpl(stagingDir.toString(), permanentDir.toString());
    }

    @Test
    void shouldStageFileAndReturnCorrectTicket() throws IOException {
        String content = "Settlement receipt data 2026-09-18";
        InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        
        StagingTicket ticket = service.stage("receipt.txt", "text/plain", in);
        
        assertThat(ticket).isNotNull();
        assertThat(ticket.originalFileName()).isEqualTo("receipt.txt");
        assertThat(ticket.contentType()).isEqualTo("text/plain");
        assertThat(ticket.fileSizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(ticket.contentChecksum()).isNotNull();
        
        Path stagedPath = Path.of(ticket.storagePath());
        assertThat(stagedPath).exists();
        assertThat(stagedPath.getParent().getParent()).isEqualTo(stagingDir.toAbsolutePath().normalize());
        assertThat(stagedPath.getFileName().toString()).isEqualTo("content");
        assertThat(Files.readString(stagedPath)).isEqualTo(content);
    }

    @Test
    void shouldGenerateUniqueFileNamesOnMultipleStages() throws IOException {
        InputStream in1 = new ByteArrayInputStream("content1".getBytes());
        InputStream in2 = new ByteArrayInputStream("content2".getBytes());
        
        StagingTicket ticket1 = service.stage("data.dat", "application/octet-stream", in1);
        StagingTicket ticket2 = service.stage("data.dat", "application/octet-stream", in2);
        
        assertThat(ticket1.storagePath()).isNotEqualTo(ticket2.storagePath());
    }

    @Test
    void shouldPreserveFileExtension() throws IOException {
        InputStream in = new ByteArrayInputStream("test".getBytes());
        StagingTicket ticket = service.stage("report.pdf", "application/pdf", in);
        
        assertThat(ticket.originalFileName()).endsWith(".pdf");
    }

    @Test
    void shouldPromoteToPermamentAndVerifyContent() throws IOException {
        String content = "Permanent data";
        InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        StagingTicket ticket = service.stage("doc.txt", "text/plain", in);
        
        Path permanentPath = service.promoteToPermanent(ticket.storagePath());
        
        assertThat(permanentPath).exists();
        assertThat(permanentPath.getParent().getParent()).isEqualTo(permanentDir.toAbsolutePath().normalize());
        assertThat(Files.readString(permanentPath)).isEqualTo(content);
        
        assertThat(Path.of(ticket.storagePath())).doesNotExist();
    }

    @Test
    void shouldRejectPromotionWhenDestinationExists() throws IOException {
        InputStream in1 = new ByteArrayInputStream("data1".getBytes());
        StagingTicket ticket1 = service.stage("file.txt", "text/plain", in1);
        
        // Manually create the destination directory to simulate conflict
        Path sourcePath = Path.of(ticket1.storagePath());
        Path destPath = permanentDir.resolve(sourcePath.getParent().getFileName());
        Files.createDirectory(destPath);
        
        assertThatThrownBy(() -> service.promoteToPermanent(ticket1.storagePath()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldCompensateStagedFile() throws IOException {
        InputStream in = new ByteArrayInputStream("test".getBytes());
        StagingTicket ticket = service.stage("test.txt", "text/plain", in);
        
        Path stagedPath = Path.of(ticket.storagePath());
        assertThat(stagedPath).exists();
        
        service.compensateStaging(ticket.storagePath());
        
        assertThat(stagedPath).doesNotExist();
    }

    @Test
    void shouldCompensateIdempotentlyWhenFileAlreadyAbsent() {
        Path fakePath = stagingDir.resolve(java.util.UUID.randomUUID().toString()).resolve("content");
        
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            service.compensateStaging(fakePath.toString());
        });
    }

    private static InputStream failingInputStream() {
        return new InputStream() {
            private int bytesRead = 0;
            @Override
            public int read() throws IOException {
                if (bytesRead++ > 10) throw new IOException("Simulated I/O failure");
                return 'X';
            }
        };
    }

    @Test
    void shouldCleanupPartialFileOnStageFailure() {
        assertThatThrownBy(() -> service.stage("fail.txt", "text/plain", failingInputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Simulated I/O failure");
        
        try {
            assertThat(Files.list(stagingDir)).isEmpty();
        } catch (IOException e) {
            org.junit.jupiter.api.Assertions.fail(e.getMessage());
        }
    }

    @Test
    void shouldRejectPathTraversalInStaging() {
        InputStream in = new ByteArrayInputStream("test".getBytes());
        assertThatThrownBy(() -> service.stage("../../../etc/passwd", "text/plain", in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected a plain attachment filename");
    }

    @Test
    void shouldRejectPathTraversalInPromotion() {
        String outsidePath = tempDir.resolve("outside.txt").toString();
        assertThatThrownBy(() -> service.promoteToPermanent(outsidePath))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Path does not identify an owned staging file");
    }

    @Test
    void shouldRejectPathTraversalInCompensation() {
        String outsidePath = tempDir.resolve("outside.txt").toString();
        assertThatThrownBy(() -> service.compensateStaging(outsidePath))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Path does not identify an owned staging file");
    }
}
