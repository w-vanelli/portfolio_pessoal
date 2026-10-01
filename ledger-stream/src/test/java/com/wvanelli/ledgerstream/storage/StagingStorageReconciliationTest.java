package com.wvanelli.ledgerstream.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StagingStorageReconciliationTest {
    @TempDir Path root;
    StagingStorageServiceImpl storage() throws IOException {
        return new StagingStorageServiceImpl(root.resolve("staging").toString(), root.resolve("permanent").toString());
    }
    StagingTicket ticket(StagingStorageService storage) throws IOException {
        return storage.stage("proof", "text/plain", new ByteArrayInputStream("proof".getBytes()));
    }

    @Test void acceptsDotInConfiguredRootsAndKeepsCanonicalTicketPaths() throws Exception {
        var storage = new StagingStorageServiceImpl(root + "/./staging", root + "/./permanent");
        var ticket = ticket(storage);
        assertThat(Path.of(ticket.storagePath()).getParent().getParent()).isEqualTo(root.resolve("staging").toRealPath());
    }

    @Test void validatesOwnedUuidContentAndTraversalBeforeEveryOperation() throws Exception {
        var storage = storage(); var ticket = ticket(storage); Path file = Path.of(ticket.storagePath());
        for (String invalid : new String[]{root.resolve("outside").toString(),
                root.resolve("staging/not-a-uuid/content").toString(), file.getParent().resolve("other").toString(),
                file.getParent().resolve("../" + file.getParent().getFileName() + "/content").toString()}) {
            assertThatThrownBy(() -> storage.getPermanentPath(invalid)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> storage.isStaged(invalid)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> storage.isPermanentlyStored(invalid)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> storage.compensateStaging(invalid)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> storage.cleanupOrphanStagingDirectory(root)).isInstanceOf(IOException.class);
        assertThat(file).hasContent("proof");
    }

    @Test void cleanupRejectsUnexpectedChildrenBeforeDeletingContent() throws Exception {
        var storage = storage(); var ticket = ticket(storage); Path file = Path.of(ticket.storagePath());
        Path unexpected = Files.createDirectories(file.getParent().resolve("foreign/nested"));
        assertThatThrownBy(() -> storage.cleanupOrphanStagingDirectory(file.getParent())).isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(file).hasContent("proof"); assertThat(unexpected).exists();
    }

    @Test void rejectsSymlinksInSourceDestinationRootsAndAncestors() throws Exception {
        var storage = storage(); var ticket = ticket(storage); Path source = Path.of(ticket.storagePath());
        Path external = Files.createDirectory(root.resolve("external"));
        Files.writeString(external.resolve("content"), "keep");
        Files.delete(source); Files.createSymbolicLink(source, external.resolve("content"));
        assertThatThrownBy(() -> storage.isStaged(ticket.storagePath())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.cleanupOrphanStagingDirectory(source.getParent())).isInstanceOf(IOException.class);
        Files.delete(source); Files.delete(source.getParent());
        Files.createSymbolicLink(source.getParent(), external);
        assertThatThrownBy(() -> storage.getPermanentPath(ticket.storagePath())).isInstanceOf(IOException.class);
        assertThat(storage.listStagingAttemptDirectories()).isEmpty();
        Files.delete(source.getParent()); Files.createDirectory(source.getParent()); Files.writeString(source, "proof");
        Path target = storage.getPermanentPath(ticket.storagePath());
        Files.createSymbolicLink(target.getParent(), external);
        assertThatThrownBy(() -> storage.promoteToPermanent(ticket.storagePath())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.isPermanentlyStored(ticket.storagePath())).isInstanceOf(IOException.class);
        Files.delete(target.getParent()); Files.createDirectory(target.getParent());
        Files.createSymbolicLink(target, external.resolve("content"));
        assertThatThrownBy(() -> storage.getPermanentPath(ticket.storagePath())).isInstanceOf(IOException.class);
        Path linkedRoot = root.resolve("linked-root"); Files.createSymbolicLink(linkedRoot, external);
        assertThatThrownBy(() -> new StagingStorageServiceImpl(linkedRoot.toString(), root.resolve("other").toString()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new StagingStorageServiceImpl(linkedRoot.resolve("child").toString(), root.resolve("other").toString()))
                .isInstanceOf(IOException.class);
        assertThat(external.resolve("content")).hasContent("keep");
    }

    @Test void detectsRootReplacementAfterInitialization() throws Exception {
        var storage = storage(); var ticket = ticket(storage);
        Files.move(root.resolve("staging"), root.resolve("original-staging"));
        Files.createSymbolicLink(root.resolve("staging"), root.resolve("original-staging"));
        assertThatThrownBy(storage::listStagingAttemptDirectories).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.isStaged(ticket.storagePath())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ticket(storage)).isInstanceOf(IOException.class);
    }

    @Test void rejectsContentDirectoryAndForeignReservations() throws Exception {
        var storage = storage(); var ticket = ticket(storage); Path source = Path.of(ticket.storagePath());
        Files.delete(source); Files.createDirectory(source);
        assertThatThrownBy(() -> storage.isStaged(ticket.storagePath())).isInstanceOf(IOException.class);
        Files.delete(source); Files.writeString(source, "proof");
        Path target = storage.getPermanentPath(ticket.storagePath()); Files.createDirectory(target.getParent());
        Files.writeString(target.getParent().resolve("unexpected"), "keep");
        assertThatThrownBy(() -> storage.promoteToPermanent(ticket.storagePath())).isInstanceOf(DirectoryNotEmptyException.class);
        assertThat(source).hasContent("proof");
    }

    @Test void excludesActiveLocalCopyFromOrphanScanAndCleanup() throws Exception {
        var storage = storage(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> storage.stage("proof", "text/plain", new InputStream() {
                @Override public int read() throws IOException {
                    started.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    return -1;
                }
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(storage.listStagingAttemptDirectories()).isEmpty();
                Path attempt;
                try (var paths = Files.list(root.resolve("staging"))) { attempt = paths.findFirst().orElseThrow(); }
                assertThatThrownBy(() -> storage.cleanupOrphanStagingDirectory(attempt)).isInstanceOf(IOException.class);
            } finally { release.countDown(); }
            assertThat(Path.of(future.get(5, TimeUnit.SECONDS).storagePath())).exists();
        }
    }

    @Test void recoversEmptyReservationWithConcurrentInstancesWithoutOverwriting() throws Exception {
        var first = storage(); var second = storage(); var ticket = ticket(first);
        Path target = first.getPermanentPath(ticket.storagePath()); Files.createDirectory(target.getParent());
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { first.promoteToPermanent(ticket.storagePath()); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            Callable<Boolean> b = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { second.promoteToPermanent(ticket.storagePath()); return true; }
                catch (FileAlreadyExistsException expected) { return false; }
            };
            var one = pool.submit(a); var two = pool.submit(b);
            assertThat(one.get(5, TimeUnit.SECONDS) ^ two.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(target).hasContent("proof");
    }

    @Test void enumerationIgnoresForeignNamesAndCleanupDoesNotTouchPermanent() throws Exception {
        var storage = storage(); Files.createDirectory(root.resolve("staging/foreign"));
        var ticket = ticket(storage); Path permanent = storage.promoteToPermanent(ticket.storagePath());
        assertThat(storage.listStagingAttemptDirectories()).containsExactly(Path.of(ticket.storagePath()).getParent());
        storage.cleanupOrphanStagingDirectory(Path.of(ticket.storagePath()).getParent());
        storage.cleanupOrphanStagingDirectory(Path.of(ticket.storagePath()).getParent());
        assertThat(permanent).hasContent("proof");
        assertThat(storage.isPermanentlyStored(ticket.storagePath())).isTrue();
        assertThat(storage.isStaged(ticket.storagePath())).isFalse();
    }
}
