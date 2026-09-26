package com.github.guoyyit.sparseeigensolverjava.service;

import com.github.guoyyit.sparseeigensolverjava.algo.LanczosState;
import com.github.guoyyit.sparseeigensolverjava.core.ErrorCode;
import com.github.guoyyit.sparseeigensolverjava.core.SolverException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Comparator;

/**
 * Filesystem-backed {@link SnapshotStore}.
 *
 * <p>Each request owns a directory {@code <base>/<requestId>/} containing
 * {@code state.bin} written via Java binary serialization (all doubles are
 * preserved exactly) and committed with an atomic rename. Corrupted snapshots
 * raise {@link ErrorCode#SNAPSHOT_CORRUPT}.
 */
public class FileSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(FileSnapshotStore.class);

    private final Path baseDir;

    public FileSnapshotStore(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (Exception e) {
            throw new SolverException(ErrorCode.INTERNAL_ERROR,
                    "cannot create snapshot directory " + baseDir, e);
        }
    }

    private Path stateFile(String requestId) {
        return dir(requestId).resolve("state.bin");
    }

    private Path dir(String requestId) {
        return baseDir.resolve(safe(requestId));
    }

    private static String safe(String requestId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new SolverException(ErrorCode.INVALID_CONFIG,
                    "requestId must match [A-Za-z0-9._-]{1,128}");
        }
        return requestId;
    }

    @Override
    public synchronized void saveState(String requestId, LanczosState state) {
        Path target = stateFile(requestId);
        File tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = File.createTempFile("state-", ".tmp", target.getParent().toFile());
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)))) {
                oos.writeObject(state);
            }
            try {
                Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            if (tmp != null) {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            throw new SolverException(ErrorCode.INTERNAL_ERROR,
                    "failed to persist snapshot for " + requestId, e);
        }
    }

    @Override
    public synchronized LanczosState loadState(String requestId) {
        Path target = stateFile(requestId);
        if (!Files.exists(target)) {
            return null;
        }
        try (ObjectInputStream ois =
                     new ObjectInputStream(new FileInputStream(target.toFile()))) {
            return (LanczosState) ois.readObject();
        } catch (Exception e) {
            log.warn("Corrupt snapshot for {}: {}", requestId, e.toString());
            throw new SolverException(ErrorCode.SNAPSHOT_CORRUPT,
                    "snapshot for request " + requestId + " is unreadable: "
                            + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void clearState(String requestId) {
        Path d = dir(requestId);
        if (!Files.exists(d)) {
            return;
        }
        try (var paths = Files.walk(d)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    log.warn("failed to delete {}: {}", p, e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("failed to clear snapshot {}: {}", requestId, e.toString());
        }
    }

    @Override
    public synchronized boolean exists(String requestId) {
        return Files.exists(stateFile(requestId));
    }

    /** Deletes all snapshots (test helper). */
    public synchronized void reset() {
        if (!Files.exists(baseDir)) {
            return;
        }
        try (var paths = Files.list(baseDir)) {
            paths.filter(Files::isDirectory).forEach(d -> clearState(d.getFileName().toString()));
        } catch (Exception e) {
            log.warn("reset failed: {}", e.toString());
        }
    }
}
