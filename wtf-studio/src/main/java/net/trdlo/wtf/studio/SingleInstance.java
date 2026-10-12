package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One WTF for the projects opened from a file manager.
 * <p>
 * Each double-click on a {@code .wtf} file starts a process of its own, and two windows over one
 * set of preferences write over each other's remembered view when they close. So the WTF that
 * holds {@code instance.lock} listens on a loopback port, written with a random token into
 * {@code instance.port} beside it, and a WTF started with a project first offers the project
 * there: when the running one says it took it, the new one never opens a window.
 * <p>
 * Only a start with a project hands over. A plain start is a new window as before, which is what
 * a developer running the build beside an installed WTF expects. Anything going wrong – no
 * running WTF, a port file left by one that crashed, a port now used by something else – means
 * the new one starts after all: two windows are better than none.
 * <p>
 * Two different things are timed, and only one of them is bounded. {@link Answer#begin} is how the
 * FX thread claims the offer, and that claim is timed ({@link #beginTimeoutMs}): a WTF that is hung
 * or mid-close never claims it, and past the timeout the listener gives up and says {@code timeout}
 * on its own, so the second WTF is not left holding a socket for a WTF that may never answer. What
 * the person then does with the offer – answer whatever the window was asking when it came, then
 * the unsaved-changes question, however long that takes ({@link HandOver}) – is never timed: once
 * {@code begin} has been claimed, the listener writes {@code asking} and waits without a limit for
 * {@link Answer#report}, so a slow answer is still believed, rather than the file landing in a
 * second window while the first is still asking about it. A window that closes before it gets to a
 * claimed offer reports nothing: the connection closes with its process, which {@link #handOver}
 * reads as nobody there. Each connection is handled on a thread of its own, so one hand-over
 * waiting on either of those does not hold up the next one behind it.
 */
final class SingleInstance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SingleInstance.class);

    static final String LOCK_FILE = "instance.lock";
    static final String PORT_FILE = "instance.port";
    private static final int TIMEOUT_MS = 2_000;
    /** How long the listener waits for {@link Answer#begin} before giving up on its own. */
    private static final int BEGIN_TIMEOUT_MS = 3_000;
    private static final SecureRandom TOKENS = new SecureRandom();

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private final Path portFile;
    private final int beginTimeoutMs;

    private SingleInstance(final FileChannel channel, final FileLock lock, final ServerSocket server,
                           final Path portFile, final int beginTimeoutMs) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        this.portFile = portFile;
        this.beginTimeoutMs = beginTimeoutMs;
    }

    /** Tries to open the handed-over project on the FX thread, and answers through {@code answer}. */
    @FunctionalInterface
    interface Offer {
        void offer(@NotNull Path project, @NotNull Answer answer);
    }

    /**
     * What the FX thread does with an offer, in order.
     * <p>
     * {@link #begin} is called first, and only its return value says whether anything else may
     * happen: false means the listener has already given up on this offer, past its own timeout,
     * and the caller must do nothing at all from here – no bringing the window to front, no
     * question, no open, since nobody is still listening for the answer to any of those.
     */
    interface Answer {
        /** @return whether this offer is still wanted; called once, before anything else */
        boolean begin();

        /**
         * What came of it; called at most once, only after {@link #begin} has returned true – and
         * not at all by a window that closed before it got to the offer.
         */
        void report(@NotNull Result result);
    }

    /** What the running WTF did with a project handed over, as the one that asked is told it, lower-cased. */
    enum Result { OPENED, DECLINED, FAILED }

    /** What became of a project offered to whichever WTF is running, for {@link #handOver}'s caller. */
    enum Outcome {
        /** The running WTF opened it: this process has nothing further to do. */
        TAKEN,
        /**
         * Nobody is running, nothing answered like WTF, or the WTF that claimed it went without an
         * answer: this process opens the file, quietly.
         */
        NONE,
        /** A WTF is running and kept its unsaved changes instead of taking this one. */
        DECLINED,
        /** A WTF is running and the file would not open there either. */
        FAILED,
        /** A WTF is running but never claimed the offer. */
        TIMED_OUT
    }

    /**
     * Becomes the WTF that projects are handed to, unless another one already is.
     *
     * @param open called on the listening thread with each project handed over
     * @return the listener, or null when another WTF holds the lock or the folder is not writable
     */
    static @Nullable SingleInstance serve(final @NotNull Path dir, final @NotNull Offer open) {
        return serve(dir, open, BEGIN_TIMEOUT_MS);
    }

    /**
     * As {@link #serve(Path, Offer)}, with the begin timeout given rather than the real one – for a
     * test that wants what happens past it without waiting out the real few seconds.
     */
    static @Nullable SingleInstance serve(final @NotNull Path dir, final @NotNull Offer open,
                                          final int beginTimeoutMs) {
        FileChannel channel = null;
        ServerSocket server = null;
        try {
            Files.createDirectories(dir);
            channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (final OverlappingFileLockException heldHere) {
                channel.close();
                return null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            final byte[] secret = new byte[16];
            TOKENS.nextBytes(secret);
            final String token = HexFormat.of().formatHex(secret);
            final Path portFile = dir.resolve(PORT_FILE);
            writePortFile(portFile, server.getLocalPort() + " " + token + "\n");
            final SingleInstance instance = new SingleInstance(channel, lock, server, portFile, beginTimeoutMs);
            final Thread acceptor = new Thread(() -> instance.accept(token, open), "single-instance");
            acceptor.setDaemon(true);
            acceptor.start();
            return instance;
        } catch (final IOException | RuntimeException ex) {
            log.warn("Projects opened from the file manager will each start a WTF: {}", ex.toString());
            closeQuietly(server);
            closeQuietly(channel);
            return null;
        }
    }

    /**
     * Writes the port and token where only this account can read them, then moves the file into
     * place – a rename does not change the permissions a file was created with, so this never
     * widens them afterward the way a chmod once the bytes are already down would.
     * <p>
     * POSIX gets {@code rw-------}, set at creation. Windows has no such bits; the file inherits
     * the ACL of the folder it is written into ({@code %LOCALAPPDATA%\WTF\logs}, see
     * {@link LogDir}), which the user's profile already keeps other accounts out of, so nothing
     * further is set here.
     */
    private static void writePortFile(final @NotNull Path portFile, final @NotNull String content) throws IOException {
        final Path part = portFile.resolveSibling(portFile.getFileName() + ".part");
        Files.deleteIfExists(part);
        if (portFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            final Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            final FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions.asFileAttribute(ownerOnly);
            Files.createFile(part, attribute);
            Files.write(part, content.getBytes(StandardCharsets.UTF_8));
        } else {
            Files.writeString(part, content, StandardCharsets.UTF_8);
        }
        Files.move(part, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    /** Accepts connections until closed, each on a thread of its own so an idle one holds up nothing else. */
    private void accept(final @NotNull String token, final @NotNull Offer open) {
        while (!server.isClosed()) {
            try {
                final Socket s = server.accept();
                final Thread worker = new Thread(() -> handle(s, token, open), "single-instance-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (final IOException ex) {
                if (!server.isClosed()) {
                    log.debug("A hand-over that did not happen: {}", ex.toString());
                }
            }
        }
    }

    /**
     * One connection: a request line, then whether the FX thread claims the offer within
     * {@link #beginTimeoutMs} ({@code asking}) or not ({@code timeout}), and only once claimed,
     * without any further limit, what it did with it ({@code opened}, {@code declined} or
     * {@code failed}).
     */
    private void handle(final @NotNull Socket s, final @NotNull String token, final @NotNull Offer open) {
        try (s) {
            s.setSoTimeout(TIMEOUT_MS);
            final String line = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            final Path project = line == null ? null : projectIn(line, token);
            if (project == null) {
                return;         // not a request this WTF understands: silence, as a wrong token gets
            }

            final CompletableFuture<Boolean> began = new CompletableFuture<>();
            final CompletableFuture<Result> answered = new CompletableFuture<>();
            open.offer(project, new Answer() {
                @Override
                public boolean begin() {
                    return began.complete(true);           // false once the listener has moved on
                }

                @Override
                public void report(final @NotNull Result result) {
                    answered.complete(result);
                }
            });

            boolean claimed;
            try {
                claimed = began.get(beginTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException notInTime) {
                began.complete(false);          // gives up, unless begin() just now won the race
                claimed = began.join();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                began.complete(false);
                claimed = began.join();
            } catch (final ExecutionException impossible) {
                claimed = false;                 // began is never completed exceptionally
            }

            final OutputStream out = s.getOutputStream();
            if (!claimed) {
                out.write("timeout\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            out.write("asking\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // No limit from here: this is the person's own answer, however long that takes.
            final String reply = answered.join().name().toLowerCase(Locale.ROOT);
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException | RuntimeException ex) {
            log.debug("A hand-over that did not happen: {}", ex.toString());
        }
    }

    /** "token path": the path, when the token is this WTF's and the path names a project. */
    private static @Nullable Path projectIn(final @NotNull String line, final @NotNull String token) {
        final int space = line.indexOf(' ');
        if (space < 0 || !line.substring(0, space).equals(token)) {
            return null;
        }
        final String path = line.substring(space + 1);
        if (!path.toLowerCase(Locale.ROOT).endsWith(net.trdlo.wtf.studio.project.ProjectIo.EXTENSION)) {
            return null;
        }
        try {
            return Path.of(path);
        } catch (final InvalidPathException ex) {
            return null;
        }
    }

    /**
     * Offers a project to the WTF that is running, if one is.
     *
     * @return what came of it; only {@link Outcome#TAKEN} means this process has nothing more to do
     */
    static @NotNull Outcome handOver(final @NotNull Path dir, final @NotNull Path project) {
        try {
            final String[] portAndToken = Files.readString(dir.resolve(PORT_FILE), StandardCharsets.UTF_8)
                    .strip().split(" ", 2);
            if (portAndToken.length != 2) {
                return Outcome.NONE;
            }
            try (final Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        Integer.parseInt(portAndToken[0])), TIMEOUT_MS);
                // Long enough for a graceful "asking" or "timeout" to arrive on its own; the
                // person's own answer, once "asking" has arrived, is never bounded (see below).
                s.setSoTimeout(TIMEOUT_MS + BEGIN_TIMEOUT_MS);
                final OutputStream out = s.getOutputStream();
                out.write((portAndToken[1] + " " + project.toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                final String claim = in.readLine();
                if (claim == null) {
                    return Outcome.NONE;            // the running WTF crashed or closed meanwhile
                }
                if ("timeout".equals(claim)) {
                    return Outcome.TIMED_OUT;
                }
                if (!"asking".equals(claim)) {
                    return Outcome.NONE;            // not a reply this process understands
                }
                s.setSoTimeout(0);                   // now it is the person's own answer being waited for
                final String reply = in.readLine();
                return switch (reply == null ? "" : reply) {
                    case "opened" -> Outcome.TAKEN;
                    case "declined" -> Outcome.DECLINED;
                    case "failed" -> Outcome.FAILED;
                    default -> Outcome.NONE;
                };
            }
        } catch (final IOException | RuntimeException ex) {
            return Outcome.NONE;       // nobody there, or not WTF: this one starts
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            Files.deleteIfExists(portFile);
            lock.release();
            channel.close();
        } catch (final IOException ex) {
            log.debug("Closing the single-instance listener: {}", ex.toString());
        }
    }
}
