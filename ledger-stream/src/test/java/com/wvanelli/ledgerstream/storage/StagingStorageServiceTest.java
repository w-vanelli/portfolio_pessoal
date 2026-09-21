package com.wvanelli.ledgerstream.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StagingStorageServiceTest {
    @TempDir Path root;

    private StagingStorageServiceImpl storage() throws IOException {
        return new StagingStorageServiceImpl(root.resolve("staging").toString(), root.resolve("permanent").toString());
    }

    @Test void stagesBytesAndDigestWithoutClosingCallerStream() throws Exception {
        var storage = storage();
        var input = new ByteArrayInputStream("synthetic".getBytes()) {
            @Override public void close() { throw new AssertionError("Caller owns stream"); }
        };
        var ticket = storage.stage("proof.txt", "text/plain", input);
        assertThat(ticket.fileSizeBytes()).isEqualTo(9);
        assertThat(ticket.contentChecksum()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("synthetic".getBytes())));
        Path permanent = storage.promoteToPermanent(ticket.storagePath());
        assertThat(permanent).hasContent("synthetic");
        assertThat(Path.of(ticket.storagePath())).doesNotExist();
        storage.compensateStaging(ticket.storagePath());
        storage.compensateStaging(ticket.storagePath());
        assertThat(permanent).hasContent("synthetic");
    }

    @Test void unsupportedAtomicMoveKeepsSourceAndDoesNotFallBack() throws Exception {
        var storage = new StagingStorageServiceImpl(root.resolve("staging").toString(), root.resolve("permanent").toString()) {
            @Override void atomicMove(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
            }
        };
        var ticket = storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("proof".getBytes()));
        assertThatThrownBy(() -> storage.promoteToPermanent(ticket.storagePath()))
                .isInstanceOf(AtomicMoveNotSupportedException.class);
        assertThat(Path.of(ticket.storagePath())).hasContent("proof");
        try (var paths = Files.list(root.resolve("permanent"))) { assertThat(paths).isEmpty(); }
    }

    @Test void reservationPreventsOverwritingExistingDestination() throws Exception {
        var storage = storage();
        var ticket = storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("new".getBytes()));
        Path destination = root.resolve("permanent").resolve(Path.of(ticket.storagePath()).getParent().getFileName());
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("content"), "existing");
        assertThatThrownBy(() -> storage.promoteToPermanent(ticket.storagePath())).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(destination.resolve("content")).hasContent("existing");
        assertThat(Path.of(ticket.storagePath())).hasContent("new");
    }

    @Test void concurrentPromotionsHaveOneWinnerWithoutOverwriting() throws Exception {
        var storage = storage();
        var ticket = storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("proof".getBytes()));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> promote = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { storage.promoteToPermanent(ticket.storagePath()); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            var a = pool.submit(promote);
            var b = pool.submit(promote);
            assertThat(a.get(5, TimeUnit.SECONDS) ^ b.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void losingAttemptCleanupCannotDeleteWinnerWithSameFilename() throws Exception {
        var storage = storage();
        var winner = storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("winner".getBytes()));
        var loser = storage.stage("proof.txt", "text/plain", new ByteArrayInputStream("loser".getBytes()));
        Path permanent = storage.promoteToPermanent(winner.storagePath());
        storage.compensateStaging(loser.storagePath());
        assertThat(permanent).hasContent("winner");
    }

    @Test void rejectsTraversalAndSymlinks() throws Exception {
        var storage = storage();
        assertThatThrownBy(() -> storage.stage("../proof", "text/plain", InputStream.nullInputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        Path outside = root.resolve("outside");
        Files.writeString(outside, "keep");
        assertThatThrownBy(() -> storage.compensateStaging(outside.toString())).isInstanceOf(IOException.class);
        var ticket = storage.stage("proof", "text/plain", InputStream.nullInputStream());
        Files.delete(Path.of(ticket.storagePath()));
        Files.createSymbolicLink(Path.of(ticket.storagePath()), outside);
        assertThatThrownBy(() -> storage.compensateStaging(ticket.storagePath())).isInstanceOf(IOException.class);
        assertThat(outside).hasContent("keep");
    }

    @Test void cleansPartialWriteOnReadFailure() throws Exception {
        var storage = storage();
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("injected read failure"); }
        };
        assertThatThrownBy(() -> storage.stage("proof", "text/plain", failing)).isInstanceOf(IOException.class);
        try (var paths = Files.list(root.resolve("staging"))) { assertThat(paths).isEmpty(); }
    }

    @Test void cleanupFailureIsObservable() throws Exception {
        var storage = storage();
        var ticket = storage.stage("proof", "text/plain", InputStream.nullInputStream());
        Files.writeString(Path.of(ticket.storagePath()).getParent().resolve("unexpected"), "keep");
        assertThatThrownBy(() -> storage.compensateStaging(ticket.storagePath())).isInstanceOf(DirectoryNotEmptyException.class);
    }
}
