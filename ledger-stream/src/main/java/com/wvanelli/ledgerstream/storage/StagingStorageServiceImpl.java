package com.wvanelli.ledgerstream.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Local, application-owned roots on a trusted filesystem; no external writers are supported. */
@Service
public class StagingStorageServiceImpl implements StagingStorageService {
    // A stable OS lock serializes reservation recovery across cooperating processes.
    private static final Object PROMOTION_MONITOR = new Object();
    private static final Set<Path> WRITING = ConcurrentHashMap.newKeySet();
    private final Path stagingRoot;
    private final Path permanentRoot;

    public StagingStorageServiceImpl(
            @Value("${ledgerstream.storage.staging-dir}") String stagingDir,
            @Value("${ledgerstream.storage.permanent-dir}") String permanentDir) throws IOException {
        stagingRoot = initializeRoot(stagingDir);
        permanentRoot = initializeRoot(permanentDir);
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
        validateRoots();
        Path attempt = Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        Path file = attempt.resolve("content");
        WRITING.add(attempt);
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
        } finally {
            WRITING.remove(attempt);
        }
    }

    @Override
    public Path promoteToPermanent(String storagePath) throws IOException {
        synchronized (PROMOTION_MONITOR) {
            validateRoots();
            Path lockPath = permanentRoot.resolve(".promotion.lock");
            validateFile(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, NOFOLLOW_LINKS); var lock = channel.lock()) {
                Path source = stagingPath(storagePath);
                Path target = getPermanentPath(storagePath);
                Path directory = target.getParent();
                if (attributes(target) != null) throw new FileAlreadyExistsException(target.toString());
                if (attributes(source) == null) throw new NoSuchFileException(source.toString());
                if (attributes(directory) == null) {
                    Files.createDirectory(directory);
                } else {
                    // Crash after reservation: reuse ONLY an empty owned directory while locked.
                    requireEmpty(directory);
                }
                try {
                    atomicMove(source, target);
                } catch (IOException failure) {
                    try { Files.delete(directory); }
                    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
                return target;
            }
        }
    }

    void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void compensateStaging(String storagePath) throws IOException {
        cleanupOrphanStagingDirectory(stagingPath(storagePath).getParent());
    }

    @Override
    public Path getPermanentPath(String storagePath) throws IOException {
        Path source = stagingPath(storagePath);
        Path target = permanentRoot.resolve(source.getParent().getFileName()).resolve("content");
        validateDirectory(target.getParent(), true);
        validateFile(target);
        return target;
    }

    @Override
    public boolean isPermanentlyStored(String storagePath) throws IOException {
        return attributes(getPermanentPath(storagePath)) != null;
    }

    @Override
    public boolean isStaged(String storagePath) throws IOException {
        return attributes(stagingPath(storagePath)) != null;
    }

    @Override
    public List<Path> listStagingAttemptDirectories() throws IOException {
        validateRoots();
        List<Path> attempts = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(stagingRoot)) {
            for (Path entry : entries) {
                // Foreign entries are never candidates. A malformed owned attempt is
                // validated separately when processing it so it cannot block others.
                if (isUuid(entry.getFileName().toString()) && !WRITING.contains(entry)
                        && Files.isDirectory(entry, NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) {
                    attempts.add(entry);
                }
            }
        }
        return attempts;
    }

    @Override
    public void cleanupOrphanStagingDirectory(Path attemptDirectory) throws IOException {
        Path file = stagingPath(attemptDirectory.resolve("content").toString());
        Path attempt = file.getParent();
        if (WRITING.contains(attempt)) throw new IOException("Attempt is actively being written");
        if (attributes(attempt) == null) return;
        // Validate ALL children before deleting anything. Never recurse.
        try (var children = Files.newDirectoryStream(attempt)) {
            for (Path child : children) {
                if (!child.equals(file)) throw new DirectoryNotEmptyException(attempt.toString());
                validateFile(child);
            }
        }
        Files.deleteIfExists(file);
        Files.deleteIfExists(attempt);
    }

    private Path stagingPath(String storagePath) throws IOException {
        validateRoots();
        Path path;
        try { path = strictPath(storagePath); }
        catch (IllegalArgumentException ex) { throw new IOException("Invalid staging path", ex); }
        if (!path.startsWith(stagingRoot) || path.getNameCount() != stagingRoot.getNameCount() + 2
                || !path.getFileName().toString().equals("content")
                || !isUuid(path.getParent().getFileName().toString())) {
            throw new IOException("Path does not identify an owned staging file");
        }
        validateDirectory(path.getParent(), true);
        validateFile(path);
        return path;
    }

    private static boolean isUuid(String name) {
        return name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static Path strictPath(String value) throws IOException {
        Path path = Path.of(value).toAbsolutePath();
        for (Path part : path) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IOException("Traversal is not allowed");
            }
        }
        return path;
    }

    private static Path initializeRoot(String value) throws IOException {
        Path configured = Path.of(value).toAbsolutePath();
        for (Path part : configured) {
            if (part.toString().equals("..")) throw new IOException("Traversal is not allowed in storage roots");
        }
        // Existing configuration uses ./storage/...; canonical paths match persisted tickets.
        Path root = configured.normalize();
        validateAncestors(root);
        Files.createDirectories(root);
        validateDirectory(root, false);
        return root.toRealPath();
    }

    private void validateRoots() throws IOException {
        validateAncestors(stagingRoot);
        validateAncestors(permanentRoot);
        validateDirectory(stagingRoot, false);
        validateDirectory(permanentRoot, false);
    }

    private static void validateAncestors(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("Symbolic links are not allowed: " + cursor);
            BasicFileAttributes attrs = attributes(cursor);
            if (attrs != null && !attrs.isDirectory()) throw new IOException("Not a directory: " + cursor);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return null; }
    }

    private static void validateDirectory(Path path, boolean allowAbsent) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (attrs == null && allowAbsent) return;
        if (attrs == null || !attrs.isDirectory() || attrs.isSymbolicLink()) {
            throw new IOException("Expected an owned directory: " + path);
        }
    }

    private static void validateFile(Path path) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (attrs != null && (!attrs.isRegularFile() || attrs.isSymbolicLink())) {
            throw new IOException("Expected a regular owned content file: " + path);
        }
    }

    private static void requireEmpty(Path directory) throws IOException {
        try (var children = Files.newDirectoryStream(directory)) {
            if (children.iterator().hasNext()) throw new DirectoryNotEmptyException(directory.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
