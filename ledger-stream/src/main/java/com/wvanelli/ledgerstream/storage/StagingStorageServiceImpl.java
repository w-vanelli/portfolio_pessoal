package com.wvanelli.ledgerstream.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Local, application-owned roots on a trusted filesystem; no external writers are supported. */
@Service
public class StagingStorageServiceImpl implements StagingStorageService {
    private final Path stagingRoot;
    private final Path permanentRoot;

    public StagingStorageServiceImpl(
            @Value("${ledgerstream.storage.staging-dir}") String stagingDir,
            @Value("${ledgerstream.storage.permanent-dir}") String permanentDir) throws IOException {
        stagingRoot = Files.createDirectories(Path.of(stagingDir).toAbsolutePath().normalize()).toRealPath();
        permanentRoot = Files.createDirectories(Path.of(permanentDir).toAbsolutePath().normalize()).toRealPath();
        if (stagingRoot.startsWith(permanentRoot) || permanentRoot.startsWith(stagingRoot)) {
            throw new IllegalArgumentException("Storage roots must be separate directories");
        }
    }

    @Override
    public StagingTicket stage(String originalFileName, String contentType, InputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        if (originalFileName == null || originalFileName.isBlank() || originalFileName.length() > 255
                || originalFileName.contains("/") || originalFileName.contains("\\")
                || originalFileName.equals(".") || originalFileName.equals("..")) {
            throw new IllegalArgumentException("Expected a plain attachment filename");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 100) {
            throw new IllegalArgumentException("Invalid attachment content type");
        }
        Path attempt = Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        Path file = attempt.resolve("content");
        MessageDigest digest = sha256();
        try {
            long size = Files.copy(new DigestInputStream(stream, digest), file);
            return new StagingTicket(file.toString(), size, contentType, originalFileName,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(file);
                Files.delete(attempt);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public Path promoteToPermanent(String storagePath) throws IOException {
        Path source = stagingPath(storagePath);
        Path destinationDirectory = permanentRoot.resolve(source.getParent().getFileName());
        Path target = destinationDirectory.resolve("content");
        // Exclusive directory reservation, rather than an exists/move race or reliance
        // on ATOMIC_MOVE's platform-dependent behavior when the target already exists.
        Files.createDirectory(destinationDirectory);
        try {
            atomicMove(source, target);
        } catch (IOException failure) {
            try {
                Files.delete(destinationDirectory); // only if still empty
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        // Keep the empty staging directory as an attempt marker. Reconciliation is a future phase.
        return target;
    }

    void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void compensateStaging(String storagePath) throws IOException {
        Path source = stagingPath(storagePath);
        Files.deleteIfExists(source);
        Files.deleteIfExists(source.getParent());
    }

    private Path stagingPath(String storagePath) throws IOException {
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        if (!path.startsWith(stagingRoot) || path.getNameCount() != stagingRoot.getNameCount() + 2
                || !path.getFileName().toString().equals("content")
                || !path.getParent().getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)) {
            throw new IOException("Path does not identify an owned staging file");
        }
        return path;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
