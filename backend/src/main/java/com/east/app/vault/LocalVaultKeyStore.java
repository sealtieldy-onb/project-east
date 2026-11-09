package com.east.app.vault;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores the local data-encryption key in an owner-only file on this computer. */
@Component
public final class LocalVaultKeyStore {

    static final int KEY_BYTES = 32;

    private static final EnumSet<PosixFilePermission> OWNER_ONLY_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final EnumSet<PosixFilePermission> OWNER_ONLY_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path keyFile;

    public LocalVaultKeyStore(@Value("${east.vault.key-file:}") String configuredPath) {
        String path = configuredPath == null || configuredPath.isBlank()
                ? Path.of(System.getProperty("user.home"), ".east", "vault.key").toString()
                : configuredPath;
        this.keyFile = Path.of(path).toAbsolutePath().normalize();
    }

    public synchronized Optional<byte[]> read() {
        try {
            if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            if (Files.isSymbolicLink(keyFile) || !Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new LocalVaultKeyException();
            }
            restrictPermissions(keyFile, OWNER_ONLY_FILE);
            if (Files.size(keyFile) != KEY_BYTES) throw new LocalVaultKeyException();
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length != KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw new LocalVaultKeyException();
            }
            return Optional.of(key);
        } catch (IOException | UnsupportedOperationException exception) {
            throw new LocalVaultKeyException();
        }
    }

    public synchronized void write(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new LocalVaultKeyException();
        Path parent = keyFile.getParent();
        if (parent == null) throw new LocalVaultKeyException();

        Path temporary = null;
        try {
            if (Files.isSymbolicLink(parent)) throw new LocalVaultKeyException();
            createOwnerOnlyDirectory(parent);
            restrictPermissions(parent, OWNER_ONLY_DIRECTORY);
            temporary = createOwnerOnlyTempFile(parent);
            restrictPermissions(temporary, OWNER_ONLY_FILE);
            Files.write(temporary, key, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            restrictPermissions(temporary, OWNER_ONLY_FILE);
            try {
                Files.move(temporary, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(keyFile, OWNER_ONLY_FILE);
        } catch (IOException | UnsupportedOperationException exception) {
            throw new LocalVaultKeyException();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The key is still protected by owner-only permissions and was moved or retained.
                }
            }
        }
    }

    public synchronized void delete() {
        try {
            if (Files.isSymbolicLink(keyFile)) throw new LocalVaultKeyException();
            Files.deleteIfExists(keyFile);
        } catch (IOException exception) {
            throw new LocalVaultKeyException();
        }
    }

    private static void restrictPermissions(Path path, EnumSet<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX systems inherit their current user's home-directory ACLs.
        } catch (IOException exception) {
            throw new LocalVaultKeyException();
        }
    }

    private static void createOwnerOnlyDirectory(Path directory) throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> permissions =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY);
            Files.createDirectories(directory, permissions);
        } catch (UnsupportedOperationException ignored) {
            Files.createDirectories(directory);
        }
    }

    private static Path createOwnerOnlyTempFile(Path directory) throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> permissions =
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE);
            return Files.createTempFile(directory, ".east-vault-key-", ".tmp", permissions);
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile(directory, ".east-vault-key-", ".tmp");
        }
    }

    public static final class LocalVaultKeyException extends RuntimeException {
        public LocalVaultKeyException() {
            super("The local encryption key file is unavailable.");
        }
    }
}
