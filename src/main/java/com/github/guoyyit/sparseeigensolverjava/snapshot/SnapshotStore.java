package com.github.guoyyit.sparseeigensolverjava.snapshot;

import com.github.guoyyit.sparseeigensolverjava.core.CsrMatrix;
import com.github.guoyyit.sparseeigensolverjava.core.SolverOptions;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed store for periodic subspace snapshots, one directory per task:
 * <pre>
 *   root/&lt;taskId&gt;/request.ser      original matrix + options (for restart recovery)
 *   root/&lt;taskId&gt;/snapshot-NNN.ser monotonically versioned snapshots, latest wins
 *   root/&lt;taskId&gt;/done             marker written on any terminal state
 * </pre>
 * Writes are atomic (temp file + move). A task is resumable iff it has a
 * request file and no done marker.
 */
public class SnapshotStore {

    /** Persisted request needed to resume a task after a process restart. */
    public record StoredRequest(CsrMatrix matrix, SolverOptions options)
            implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }

    private final Path rootDir;

    public SnapshotStore(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create snapshot dir " + rootDir, e);
        }
    }

    public Path rootDir() {
        return rootDir;
    }

    private Path taskDir(String taskId) {
        return rootDir.resolve(taskId);
    }

    public synchronized void saveRequest(String taskId, CsrMatrix matrix, SolverOptions options) {
        writeAtomic(taskDir(taskId).resolve("request.ser"), new StoredRequest(matrix, options));
    }

    public synchronized Optional<StoredRequest> loadRequest(String taskId) {
        Path p = taskDir(taskId).resolve("request.ser");
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        return Optional.of((StoredRequest) read(p));
    }

    /** Persist a new snapshot version; older versions are removed. */
    public synchronized void save(String taskId, SolverSnapshot snapshot) {
        Path dir = taskDir(taskId);
        int next = latestVersion(dir).orElse(0) + 1;
        writeAtomic(dir.resolve("snapshot-" + next + ".ser"), snapshot);
        // Keep only the latest version.
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().startsWith("snapshot-"))
                    .filter(f -> !f.getFileName().toString().equals("snapshot-" + next + ".ser"))
                    .forEach(f -> deleteQuietly(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<SolverSnapshot> loadLatest(String taskId) {
        Path dir = taskDir(taskId);
        Optional<Integer> latest = latestVersion(dir);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of((SolverSnapshot) read(dir.resolve("snapshot-" + latest.get() + ".ser")));
    }

    /** Mark the task terminal; recovery will skip it. */
    public synchronized void markDone(String taskId) {
        try {
            Files.createDirectories(taskDir(taskId));
            Files.writeString(taskDir(taskId).resolve("done"), "done");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Terminal-state cleanup: write the done marker and delete the (large)
     * snapshot files, keeping a small tombstone so recovery never resumes or
     * re-emits a finished task.
     */
    public synchronized void finalizeTask(String taskId) {
        markDone(taskId);
        Path dir = taskDir(taskId);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().startsWith("snapshot-"))
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized boolean isDone(String taskId) {
        return Files.exists(taskDir(taskId).resolve("done"));
    }

    /** Ids of tasks that have a stored request but no terminal marker. */
    public synchronized List<String> resumableTaskIds() {
        List<String> ids = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(rootDir)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                String id = d.getFileName().toString();
                if (Files.exists(d.resolve("request.ser")) && !Files.exists(d.resolve("done"))) {
                    ids.add(id);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** Remove all persisted state of a task. */
    public synchronized void delete(String taskId) {
        Path dir = taskDir(taskId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<Integer> latestVersion(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        int latest = -1;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (name.startsWith("snapshot-") && name.endsWith(".ser")) {
                    int v = Integer.parseInt(name.substring("snapshot-".length(), name.length() - 4));
                    latest = Math.max(latest, v);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return latest < 0 ? Optional.empty() : Optional.of(latest);
    }

    private void writeAtomic(Path target, Object payload) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(payload);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing " + target, e);
        }
    }

    private Object read(Path p) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(p))) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new UncheckedIOException("failed reading " + p, new IOException(e));
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
