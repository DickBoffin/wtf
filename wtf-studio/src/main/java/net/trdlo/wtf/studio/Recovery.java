package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.project.ProjectIo;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The recovery copies of unsaved work: this window's own, and the ones a start finds whose window
 * has gone.
 * <p>
 * <b>A copy and its lock, made together and gone together.</b> A window's copy is {@code <id>.wtf}
 * in {@code ~/.wtf/recovery} – a project like any other, the bytes a save of it would write – and
 * beside it {@code <id>.lock}, which the window holds locked for as long as the copy is its own and
 * which names the file the project is saved in, or nothing for one never saved. Both are made with
 * the first copy after the project became unsaved, and both go when it is saved or thrown away. Not
 * beside the project: its folder may be read-only or synced, and a project never saved has none. And
 * not {@code .part}, which a save owns.
 * <p>
 * <b>Whose copy it is, the lock says.</b> The system lets go of a lock with the process that held
 * it, however the process ended – a crash, a kill, a power cut – so a copy whose lock a start can
 * take is one whose window has gone, and that is what it offers. A copy whose window is still
 * running – a second WTF started beside the first – is locked, and is neither offered nor touched;
 * the second window keeps a copy of its own beside it. Nothing here asks {@link SingleInstance},
 * which may not be listening at all.
 * <p>
 * Off the toolkit, and every method is synchronized: a copy is written and deleted on the recovery
 * thread ({@link RecoveryWatch}), found by the startup task, and taken or thrown away on the FX
 * thread.
 */
final class Recovery {

    private static final Logger log = LoggerFactory.getLogger(Recovery.class);

    /** The lock beside each copy, which names the file the copy is of. */
    static final String LOCK = ".lock";

    /**
     * How old a lock with no copy beside it has to be before a start clears it away. A crash between
     * making the two leaves one; a window that has just made its lock and not yet locked it has one
     * younger than this.
     */
    static final Duration LEFTOVER_AGE = Duration.ofMinutes(1);

    /** Longer than any path a system gives a file: a lock that holds more names nothing. */
    private static final int LONGEST_NAME = 32_768;

    private static final SecureRandom IDS = new SecureRandom();

    private final Path folder;
    /** This window's copy and its lock, while it has them. */
    private @Nullable Held own;
    /** Whether a failure has been logged, so that a folder that takes no copy says so once. */
    private boolean complained;

    Recovery(final @NotNull Path folder) {
        this.folder = folder;
    }

    /** {@code ~/.wtf/recovery}, beside the user's own providers. */
    static @NotNull Path defaultFolder() {
        return Path.of(System.getProperty("user.home"), ".wtf", "recovery");
    }

    // -------------------------------------------------------------------------------------------
    // This window's copy
    // -------------------------------------------------------------------------------------------

    /**
     * Writes this window's copy, making it and its lock first when it has none.
     *
     * @param of the file the project is saved in, or null for one never saved
     */
    synchronized void write(final @NotNull WtfFile copy, final @Nullable Path of) {
        try {
            Held held = own;
            if (held == null) {
                held = claim();
                own = held;
            }
            held.name(of);
            copy.write(copyOf(held.id));
        } catch (final IOException | RuntimeException ex) {
            complain(ex);
        }
    }

    /** The project is saved, or thrown away: its copy goes, and the lock with it. */
    synchronized void delete() {
        final Held held = own;
        if (held == null) {
            return;
        }
        try {
            Files.deleteIfExists(copyOf(held.id));
            Files.deleteIfExists(partOf(held.id));
        } catch (final IOException ex) {
            // Kept, lock and all: the next delete tries again, and a window that goes before then
            // leaves it for the next start to offer, which beats a copy nothing will ever offer
            complain(ex);
            return;
        }
        own = null;
        held.release();
        deleteQuietly(lockOf(held.id));
    }

    /**
     * The window is going with its project unsaved and nothing said about it – a session ending, say:
     * the copy stays for the next start, its lock let go, which is what a crash leaves too. A lock
     * whose copy was never written goes.
     */
    synchronized void close() {
        final Held held = own;
        if (held == null) {
            return;
        }
        own = null;
        held.release();
        if (!Files.exists(copyOf(held.id))) {
            deleteQuietly(lockOf(held.id));
        }
    }

    /**
     * A copy's name, and its lock, made: an id nothing else in the folder has, and a lock made new
     * and taken at once.
     */
    private @NotNull Held claim() throws IOException {
        Files.createDirectories(folder);
        for (int attempt = 1; ; attempt++) {
            final byte[] id = new byte[8];
            IDS.nextBytes(id);
            final String name = HexFormat.of().formatHex(id);
            final Path lockFile = lockOf(name);
            final FileChannel channel;
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (final FileAlreadyExistsException taken) {
                if (attempt >= 5) {
                    throw taken;
                }
                continue;
            }
            final FileLock lock;
            try {
                lock = tryLock(channel);
            } catch (final IOException | RuntimeException ex) {
                closeQuietly(channel);
                deleteQuietly(lockFile);
                throw ex;
            }
            if (lock != null) {
                return new Held(name, channel, lock);
            }
            // A start looking at it at this very moment, before it could be locked: another name
            closeQuietly(channel);
            deleteQuietly(lockFile);
            if (attempt >= 5) {
                throw new IOException("No lock could be taken in " + folder);
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // The copies a start finds
    // -------------------------------------------------------------------------------------------

    /**
     * The copies whose windows have gone, newest first, each locked by this start until it is taken,
     * thrown away or kept – so that no other start offers it at the same time.
     * <p>
     * A copy's {@code .part} goes on the way, being what its window was writing when it went; and so
     * does a lock with no copy beside it, once it is {@link #LEFTOVER_AGE} old – what a crash between
     * making the two left.
     */
    synchronized @NotNull List<Orphan> orphans() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        final List<Orphan> found = new ArrayList<>();
        try (DirectoryStream<Path> locks = Files.newDirectoryStream(folder, "*" + LOCK)) {
            for (final Path lockFile : locks) {
                final String name = lockFile.getFileName().toString();
                final String id = name.substring(0, name.length() - LOCK.length());
                if (own != null && own.id.equals(id)) {
                    continue;
                }
                final Orphan orphan = look(id);
                if (orphan != null) {
                    found.add(orphan);
                }
            }
        } catch (final IOException | RuntimeException ex) {
            complain(ex);
        }
        found.sort(Comparator.comparing(Orphan::changed).reversed());
        return found;
    }

    /** One lock: an orphan when its window has gone and its copy is there, else null. */
    private @Nullable Orphan look(final @NotNull String id) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockOf(id), StandardOpenOption.READ, StandardOpenOption.WRITE);
            final FileLock lock = tryLock(channel);
            if (lock == null) {
                channel.close();
                return null;                    // its window is running
            }
            Files.deleteIfExists(partOf(id));
            final Path copy = copyOf(id);
            if (Files.isRegularFile(copy)) {
                return new Orphan(copy, nameIn(channel), Files.getLastModifiedTime(copy).toInstant(),
                        new Held(id, channel, lock));
            }
            final boolean leftover = Files.getLastModifiedTime(lockOf(id)).toInstant()
                    .isBefore(Instant.now().minus(LEFTOVER_AGE));
            lock.release();
            channel.close();
            if (leftover) {
                Files.deleteIfExists(lockOf(id));
            }
            return null;
        } catch (final IOException | RuntimeException ex) {
            closeQuietly(channel);
            complain(ex);
            return null;
        }
    }

    /**
     * A copy whose window has gone, locked by the start that found it until it is taken, thrown away
     * or kept.
     */
    final class Orphan {

        private final Path copy;
        private final @Nullable Path of;
        private final Instant changed;
        /** Its lock, until one of the three below has been decided. */
        private @Nullable Held held;

        private Orphan(final @NotNull Path copy, final @Nullable Path of, final @NotNull Instant changed,
                       final @NotNull Held held) {
            this.copy = copy;
            this.of = of;
            this.changed = changed;
            this.held = held;
        }

        /** The copy itself, a project file. */
        @NotNull Path copy() {
            return copy;
        }

        /** The file it is a copy of, or null for a project that was never saved. */
        @Nullable Path of() {
            return of;
        }

        /** When its window last wrote it. */
        @NotNull Instant changed() {
            return changed;
        }

        /** Opened in this window: the copy is the window's own from now on, until it is saved or thrown away. */
        void take() {
            adopt(this);
        }

        /** Thrown away: the copy goes, and its lock. */
        void discard() {
            throwAway(this);
        }

        /** Put off: its lock is let go, and the next start finds it again. Nothing, once taken or thrown away. */
        void keep() {
            putOff(this);
        }
    }

    private synchronized void adopt(final @NotNull Orphan orphan) {
        final Held held = orphan.held;
        if (held == null) {
            return;
        }
        orphan.held = null;
        if (own != null) {
            delete();           // whatever this window had was of the project the copy replaced
        }
        held.named(orphan.of);
        own = held;
    }

    private synchronized void throwAway(final @NotNull Orphan orphan) {
        final Held held = orphan.held;
        if (held == null) {
            return;
        }
        orphan.held = null;
        try {
            Files.deleteIfExists(orphan.copy);
        } catch (final IOException ex) {
            complain(ex);       // kept after all, and offered again
            held.release();
            return;
        }
        held.release();
        deleteQuietly(lockOf(held.id));
    }

    private synchronized void putOff(final @NotNull Orphan orphan) {
        final Held held = orphan.held;
        if (held != null) {
            orphan.held = null;
            held.release();
        }
    }

    // -------------------------------------------------------------------------------------------

    /** A copy's lock, taken by this process: its id, and the file it names. */
    private static final class Held {
        final String id;
        final FileChannel channel;
        final FileLock lock;
        /** The file the lock names, once it has been written; see {@link #name}. */
        private @Nullable Path named;
        private boolean written;

        Held(final @NotNull String id, final @NotNull FileChannel channel, final @NotNull FileLock lock) {
            this.id = id;
            this.channel = channel;
            this.lock = lock;
        }

        /** Writes into the lock the file the copy is of, when it is not what the lock already names. */
        void name(final @Nullable Path of) throws IOException {
            if (written && Objects.equals(named, of)) {
                return;
            }
            final ByteBuffer bytes = ByteBuffer.wrap(of == null ? new byte[0]
                    : of.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            channel.truncate(0);
            long at = 0;
            while (bytes.hasRemaining()) {
                at += channel.write(bytes, at);
            }
            channel.force(true);
            named(of);
        }

        /** What the lock names already, as found in it. */
        void named(final @Nullable Path of) {
            named = of;
            written = true;
        }

        void release() {
            try {
                lock.release();
            } catch (final IOException ex) {
                log.debug("Letting go of a recovery copy: {}", ex.toString());
            }
            closeQuietly(channel);
        }
    }

    /** A lock, or null when it is held – by another process, or by this one, a window of its own. */
    private static @Nullable FileLock tryLock(final @NotNull FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (final OverlappingFileLockException heldHere) {
            return null;
        }
    }

    /**
     * The file a lock names: a project's, or null for one never saved – and for a name cut short by a
     * power cut halfway through writing it, which no longer ends as a project's does.
     */
    private static @Nullable Path nameIn(final @NotNull FileChannel channel) throws IOException {
        final long size = channel.size();
        if (size == 0 || size > LONGEST_NAME) {
            return null;
        }
        final ByteBuffer bytes = ByteBuffer.allocate((int) size);
        while (bytes.hasRemaining()) {
            if (channel.read(bytes, bytes.position()) < 0) {
                break;
            }
        }
        final String name = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        if (!name.toLowerCase(Locale.ROOT).endsWith(ProjectIo.EXTENSION)) {
            return null;
        }
        try {
            return Path.of(name);
        } catch (final InvalidPathException ex) {
            return null;
        }
    }

    private @NotNull Path copyOf(final @NotNull String id) {
        return folder.resolve(id + ProjectIo.EXTENSION);
    }

    /** What {@link WtfFile#write} writes a copy into before it replaces the one before. */
    private @NotNull Path partOf(final @NotNull String id) {
        return folder.resolve(id + ProjectIo.EXTENSION + ".part");
    }

    private @NotNull Path lockOf(final @NotNull String id) {
        return folder.resolve(id + LOCK);
    }

    private void deleteQuietly(final @NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (final IOException ex) {
            complain(ex);
        }
    }

    private static void closeQuietly(final @Nullable Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (final IOException ignored) {
                // going away regardless
            }
        }
    }

    /** Once in the log at a warning, and after that only for somebody asking for the details. */
    private synchronized void complain(final @NotNull Exception ex) {
        if (complained) {
            log.debug("Recovery copy in {}: {}", folder, ex.toString());
            return;
        }
        complained = true;
        log.warn("Unsaved work may be left without a recovery copy in {}: {}", folder, ex.toString());
    }
}
