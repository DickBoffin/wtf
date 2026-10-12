package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The API keys in the Windows Credential Manager, where Windows keeps what a user signs in with:
 * a generic credential for each provider, which only that user can read.
 * <p>
 * A key is named {@code <node>/key.<provider>} – {@code trdlo.net/wtf/key.mapy-com} – the
 * preference store's node and the name the key had in it, so a test with a node of its own has
 * credentials of its own as well. It stays on this computer ({@code CRED_PERSIST_LOCAL_MACHINE})
 * and is kept as UTF-16, as {@code cmdkey} keeps a password.
 * <p>
 * Reached through the JDK's foreign-function API rather than a library: four functions of
 * {@code Advapi32}, linked once and on first use. Where they cannot be linked – native access
 * refused, as a later JDK will without {@code --enable-native-access} – every call says so with an
 * {@link IOException}, and {@link ApiKeys} keeps its keys for the session. No message here ever
 * holds a key: {@link ApiKeys} logs them.
 */
final class WindowsCredentials implements ApiKeys.Store {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    /** {@code CREDENTIALW} on 64-bit Windows, the only Windows JDK 25 runs on. */
    private static final StructLayout CREDENTIAL = MemoryLayout.structLayout(
            JAVA_INT.withName("Flags"),
            JAVA_INT.withName("Type"),
            ADDRESS.withName("TargetName"),
            ADDRESS.withName("Comment"),
            MemoryLayout.structLayout(JAVA_INT, JAVA_INT).withName("LastWritten"),     // a FILETIME
            JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("CredentialBlob"),
            JAVA_INT.withName("Persist"),
            JAVA_INT.withName("AttributeCount"),
            ADDRESS.withName("Attributes"),
            ADDRESS.withName("TargetAlias"),
            ADDRESS.withName("UserName"));

    private static final long TYPE = offsetOf("Type");
    private static final long TARGET_NAME = offsetOf("TargetName");
    private static final long BLOB_SIZE = offsetOf("CredentialBlobSize");
    private static final long BLOB = offsetOf("CredentialBlob");
    private static final long PERSIST = offsetOf("Persist");

    private static long offsetOf(final @NotNull String field) {
        return CREDENTIAL.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    /** What a credential's name starts with: the node's path and the name a key had in it. */
    private final String prefix;

    /** @param nodePath the preference node the keys belong to, {@code trdlo.net/wtf} for WTF's own */
    WindowsCredentials(final @NotNull String nodePath) {
        this.prefix = nodePath + "/" + ApiKeys.KEY_PREFIX;
    }

    /** The credential a provider's key is kept as. */
    @NotNull String nameOf(final @NotNull String providerId) {
        return prefix + providerId;
    }

    @Override
    public @Nullable String read(final @NotNull String providerId) throws IOException {
        final Advapi f = advapi();
        final String name = nameOf(providerId);
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(f.callState());
            final MemorySegment found = arena.allocate(ADDRESS);
            final int ok = (int) f.read().invokeExact(state, arena.allocateFrom(name, StandardCharsets.UTF_16LE),
                    CRED_TYPE_GENERIC, 0, found);
            if (ok == 0) {
                final int error = f.lastError(state);
                if (error == ERROR_NOT_FOUND) {
                    return null;
                }
                throw new IOException("Windows did not read " + name + ": error " + error);
            }
            // Windows's own copy, which goes back to CredFree once the key is out of it
            final MemorySegment given = found.get(ADDRESS, 0);
            try {
                final MemorySegment credential = given.reinterpret(CREDENTIAL.byteSize());
                final int size = credential.get(JAVA_INT, BLOB_SIZE);
                return size == 0 ? null : new String(credential.get(ADDRESS, BLOB).reinterpret(size)
                        .toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
            } finally {
                f.free().invokeExact(given);
            }
        } catch (final IOException | RuntimeException | Error ex) {
            throw ex;
        } catch (final Throwable ex) {
            // invokeExact is declared to throw anything; these four functions throw nothing
            throw new IOException("Windows did not read " + name + ": " + ex.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public void write(final @NotNull String providerId, final @Nullable String key) throws IOException {
        final Advapi f = advapi();
        final String name = nameOf(providerId);
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment state = arena.allocate(f.callState());
            final MemorySegment target = arena.allocateFrom(name, StandardCharsets.UTF_16LE);
            if (key == null) {
                final int ok = (int) f.delete().invokeExact(state, target, CRED_TYPE_GENERIC, 0);
                final int error = ok == 0 ? f.lastError(state) : 0;
                if (ok == 0 && error != ERROR_NOT_FOUND) {
                    throw new IOException("Windows did not delete " + name + ": error " + error);
                }
                return;
            }
            final byte[] secret = key.getBytes(StandardCharsets.UTF_16LE);
            final MemorySegment credential = arena.allocate(CREDENTIAL);
            credential.set(JAVA_INT, TYPE, CRED_TYPE_GENERIC);
            credential.set(ADDRESS, TARGET_NAME, target);
            credential.set(JAVA_INT, BLOB_SIZE, secret.length);
            credential.set(ADDRESS, BLOB, arena.allocateFrom(JAVA_BYTE, secret));
            credential.set(JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
            final int ok = (int) f.write().invokeExact(state, credential, 0);
            if (ok == 0) {
                // A key longer than the 1 280 characters a credential holds ends here, with 1783
                throw new IOException("Windows did not keep " + name + ": error " + f.lastError(state));
            }
        } catch (final IOException | RuntimeException | Error ex) {
            throw ex;
        } catch (final Throwable ex) {
            throw new IOException("Windows did not keep " + name + ": " + ex.getClass().getSimpleName(), ex);
        }
    }

    // -------------------------------------------------------------------------------------------
    // The functions, linked once for the process
    // -------------------------------------------------------------------------------------------

    /** {@code CredReadW}, {@code CredWriteW}, {@code CredDeleteW} and {@code CredFree}. */
    private record Advapi(@NotNull MethodHandle read, @NotNull MethodHandle write,
                          @NotNull MethodHandle delete, @NotNull MethodHandle free,
                          @NotNull StructLayout callState, @NotNull VarHandle getLastError) {

        int lastError(final @NotNull MemorySegment state) {
            return (int) getLastError.get(state, 0L);
        }
    }

    private static @Nullable Advapi linked;
    /** Why the functions could not be linked, once that has been tried and failed. */
    private static @Nullable String unlinkable;

    private static synchronized @NotNull Advapi advapi() throws IOException {
        if (linked == null && unlinkable == null) {
            try {
                linked = link();
            } catch (final RuntimeException ex) {
                // No such library or function, or native access refused to this module
                unlinkable = ex.toString();
            }
        }
        if (linked == null) {
            throw new IOException("The Credential Manager cannot be reached: " + unlinkable);
        }
        return linked;
    }

    private static @NotNull Advapi link() {
        final Linker linker = Linker.nativeLinker();
        final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32", Arena.global());
        final Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
        final StructLayout state = Linker.Option.captureStateLayout();
        return new Advapi(
                linker.downcallHandle(advapi32.findOrThrow("CredReadW"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), lastError),
                linker.downcallHandle(advapi32.findOrThrow("CredWriteW"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), lastError),
                linker.downcallHandle(advapi32.findOrThrow("CredDeleteW"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), lastError),
                linker.downcallHandle(advapi32.findOrThrow("CredFree"), FunctionDescriptor.ofVoid(ADDRESS)),
                state, state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError")));
    }
}
