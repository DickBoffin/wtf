package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A project opened from the file manager goes to the WTF that is running, not to a second one. */
class SingleInstanceTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a project handed over arrives at the running WTF, which says it took it")
    void handedOver() throws Exception {
        final LinkedBlockingQueue<Path> arrived = new LinkedBlockingQueue<>();
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            arrived.add(project);
            assertTrue(answer.begin());
            answer.report(SingleInstance.Result.OPENED);
        })) {
            assertNotNull(running);
            final Path project = dir.resolve("Alps with spaces.wtf");
            assertEquals(SingleInstance.Outcome.TAKEN, SingleInstance.handOver(dir, project));
            assertEquals(project.toAbsolutePath(), arrived.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("a Cancel to the unsaved-changes question is not taken, and the caller is told so")
    void declined() throws Exception {
        final LinkedBlockingQueue<Path> arrived = new LinkedBlockingQueue<>();
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            arrived.add(project);
            assertTrue(answer.begin());
            answer.report(SingleInstance.Result.DECLINED);       // the person answered Cancel
        })) {
            assertNotNull(running);
            final Path project = dir.resolve("Alps.wtf");
            assertEquals(SingleInstance.Outcome.DECLINED, SingleInstance.handOver(dir, project));
            // The file did reach the running WTF – it was offered and refused, not lost on the way
            assertEquals(project.toAbsolutePath(), arrived.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("only one WTF listens; a second start serves nothing")
    void oneListener() {
        try (final SingleInstance first = SingleInstance.serve(dir, (p, a) -> { })) {
            assertNotNull(first);
            assertNull(SingleInstance.serve(dir, (p, a) -> { }));
        }
    }

    @Test
    @DisplayName("with no WTF running, or one that went, the new one starts after all")
    void nobodyThere() throws IOException {
        assertEquals(SingleInstance.Outcome.NONE, SingleInstance.handOver(dir, dir.resolve("a.wtf")), "no port file");

        try (final SingleInstance gone = SingleInstance.serve(dir, (p, a) -> { })) {
            assertNotNull(gone);
        }
        assertEquals(SingleInstance.Outcome.NONE, SingleInstance.handOver(dir, dir.resolve("a.wtf")), "closed");

        // A port file left by a WTF that crashed, naming a port nothing listens on any more
        final int deadPort;
        try (final ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = s.getLocalPort();
        }
        Files.writeString(dir.resolve(SingleInstance.PORT_FILE), deadPort + " cafe\n");
        assertEquals(SingleInstance.Outcome.NONE, SingleInstance.handOver(dir, dir.resolve("a.wtf")), "stale");
    }

    @Test
    @DisplayName("the running WTF takes nothing from a caller that does not know its token")
    void wrongToken() throws Exception {
        final LinkedBlockingQueue<Path> arrived = new LinkedBlockingQueue<>();
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            arrived.add(project);
            assertTrue(answer.begin());
            answer.report(SingleInstance.Result.OPENED);
        })) {
            assertNotNull(running);
            final Path portFile = dir.resolve(SingleInstance.PORT_FILE);
            final String port = Files.readString(portFile).split(" ")[0];
            Files.writeString(portFile, port + " not-the-token\n");
            assertEquals(SingleInstance.Outcome.NONE, SingleInstance.handOver(dir, dir.resolve("a.wtf")));
            assertNull(arrived.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    @DisplayName("an idle connection ahead of it does not hold up a hand-over")
    void idleConnectionDoesNotBlockAnother() throws Exception {
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            assertTrue(answer.begin());
            answer.report(SingleInstance.Result.OPENED);
        })) {
            assertNotNull(running);
            final String port = Files.readString(dir.resolve(SingleInstance.PORT_FILE)).strip().split(" ")[0];
            // Connects, like a real hand-over, but never sends a line: what "idle" means here.
            try (final Socket idle = new Socket()) {
                idle.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port)), 2_000);
                final long start = System.nanoTime();
                assertEquals(SingleInstance.Outcome.TAKEN,
                        SingleInstance.handOver(dir, dir.resolve("Alps.wtf")));
                final long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertTrue(elapsedMs < 1_000, "an idle connection ahead of it cost " + elapsedMs + " ms");
            }
        }
    }

    @Test
    @DisplayName("the port file is owner-only where the platform has such permissions")
    void portFileIsOwnerOnly() throws Exception {
        try (final SingleInstance running = SingleInstance.serve(dir, (p, a) -> { })) {
            assertNotNull(running);
            final Path portFile = dir.resolve(SingleInstance.PORT_FILE);
            final PosixFileAttributeView posix =
                    Files.getFileAttributeView(portFile, PosixFileAttributeView.class);
            org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "not a POSIX file system");
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    EnumSet.copyOf(Files.getPosixFilePermissions(portFile)));
        }
    }

    @Test
    @DisplayName("a slow answer is still believed, however long past the begin timeout it takes")
    void slowAnswerIsStillTaken() throws Exception {
        final int beginTimeoutMs = 200;
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            assertTrue(answer.begin());
            // The person taking their time over the unsaved-changes question: well past the
            // listener's own (short, here) begin timeout, but that timeout is not theirs.
            final Thread slow = new Thread(() -> {
                try {
                    Thread.sleep(beginTimeoutMs * 3L);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                answer.report(SingleInstance.Result.OPENED);
            });
            slow.setDaemon(true);
            slow.start();
        }, beginTimeoutMs)) {
            assertNotNull(running);
            assertEquals(SingleInstance.Outcome.TAKEN,
                    SingleInstance.handOver(dir, dir.resolve("Alps.wtf")));
        }
    }

    @Test
    @DisplayName("a begin() past the timeout is refused, and the caller is told it never answered")
    void lateBeginIsRefused() throws Exception {
        final int beginTimeoutMs = 200;
        final CompletableFuture<Boolean> lateBegin = new CompletableFuture<>();
        try (final SingleInstance running = SingleInstance.serve(dir, (project, answer) -> {
            // An FX thread too busy to even pick the offer up until well past the listener's
            // own timeout for it – the "hung or closing" WTF the offer must not wait forever on.
            final Thread late = new Thread(() -> {
                try {
                    Thread.sleep(beginTimeoutMs * 3L);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                lateBegin.complete(answer.begin());
            });
            late.setDaemon(true);
            late.start();
        }, beginTimeoutMs)) {
            assertNotNull(running);
            assertEquals(SingleInstance.Outcome.TIMED_OUT,
                    SingleInstance.handOver(dir, dir.resolve("Alps.wtf")));
            assertFalse(lateBegin.get(5, TimeUnit.SECONDS),
                    "a begin() after the listener gave up must be refused");
        }
    }
}
