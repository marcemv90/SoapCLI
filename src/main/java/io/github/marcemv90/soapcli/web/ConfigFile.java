package io.github.marcemv90.soapcli.web;

import io.github.marcemv90.soapcli.web.ApiModel.SendError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A configuration file the UI may be allowed to change (the profiles file, the placeholder options file).
 *
 * <p>Editing is guarded three ways: the server must allow it ({@code --allow-config-edit}, or listening on this machine
 * only), the file must be writable (a Docker volume mounted {@code :ro} is not), and every change carries the
 * <em>revision</em> of the file it was made against, so that an edit made in another window or by hand is never
 * overwritten by a stale form.
 */
final class ConfigFile {

    /** Config files are small; refusing a huge one keeps a mistake from filling the server's memory. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    /** The revision of a file that does not exist yet. */
    static final String MISSING = "missing";

    /** A file that may hold secrets (the profiles) is created readable by its owner only. */
    static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rw-------");
    /** A file meant to be shared (a saved request) is created readable by others, so that it can be backed up or edited by hand. */
    static final Set<PosixFilePermission> SHARED = PosixFilePermissions.fromString("rw-r--r--");

    private final Path file;
    private final String label;
    private final Supplier<String> notAllowedReason;
    private final Set<PosixFilePermission> newFilePermissions;

    /**
     * @param label            what the file is called in messages, e.g. "profiles file"
     * @param notAllowedReason why the server does not let the UI change it, or {@code null} if it does
     */
    ConfigFile(Path file, String label, String notAllowedReason) {
        this(file, label, () -> notAllowedReason, PRIVATE);
    }

    /**
     * @param notAllowedReason asked every time, because the answer can change while the server runs (a workspace that
     *                         an administrator marks as read-only); {@code null} means that the UI may change the file
     * @param newFilePermissions the permissions a file gets when it is created
     */
    ConfigFile(Path file, String label, Supplier<String> notAllowedReason, Set<PosixFilePermission> newFilePermissions) {
        this.file = file;
        this.label = label;
        this.notAllowedReason = notAllowedReason;
        this.newFilePermissions = newFilePermissions;
    }

    Path path() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** The text of the file, or an empty string if it does not exist. */
    String read() {
        return snapshot().text;
    }

    /** The text of the file and its revision, read together so that they belong to each other. */
    Snapshot snapshot() {
        if (!exists()) {
            return new Snapshot("", MISSING);
        }
        byte[] bytes = readBytes();
        return new Snapshot(new String(bytes, StandardCharsets.UTF_8), revisionOf(bytes));
    }

    static final class Snapshot {
        final String text;
        final String revision;

        Snapshot(String text, String revision) {
            this.text = text;
            this.revision = revision;
        }
    }

    /** Identifies the content of the file: it changes whenever the file does. */
    String revision() {
        return exists() ? revisionOf(readBytes()) : MISSING;
    }

    /** Why the file cannot be edited from the UI, or {@code null} if it can. */
    String readOnlyReason() {
        String notAllowed = notAllowedReason.get();
        if (notAllowed != null) {
            return notAllowed;
        }
        if (exists()) {
            return Files.isWritable(file) ? null
                    : "The " + label + " (" + file + ") is read-only. With Docker, mount the folder that holds it without :ro.";
        }
        Path folder = file.toAbsolutePath().getParent();
        while (folder != null && !Files.exists(folder)) {
            folder = folder.getParent();
        }
        return folder != null && Files.isWritable(folder) ? null
                : "The " + label + " does not exist and " + file + " cannot be created there (the folder is not writable).";
    }

    /**
     * Changes the file: {@code change} gets its current text and returns the new text, which is written. Fails with a
     * conflict if the file is no longer at {@code expectedRevision}. Nothing is written if {@code change} throws.
     *
     * @return the revision of the file after the change
     */
    synchronized String edit(String expectedRevision, UnaryOperator<String> change) {
        String reason = readOnlyReason();
        if (reason != null) {
            throw new SendError(403, "forbidden", reason);
        }
        Snapshot current = snapshot();
        if (!current.revision.equals(expectedRevision)) {
            throw new SendError(409, "conflict", "The " + label + " changed since you opened it (in another window, or by hand). "
                    + "Reload it and make your change again.");
        }
        byte[] bytes = change.apply(current.text).getBytes(StandardCharsets.UTF_8);
        write(bytes);
        return revisionOf(bytes);
    }

    // ---- writing ------------------------------------------------------------------------------------------------------

    /**
     * Replaces the file through a temporary file in the same folder, so that a crash never leaves half a file. That is
     * not possible where the file is the mount point of a single-file volume (the folder cannot be written to, or the
     * file cannot be replaced), and then the file is rewritten in place.
     */
    private void write(byte[] bytes) {
        try {
            Path target = exists() ? file.toRealPath() : file.toAbsolutePath(); // a symbolic link stays a link
            Path folder = target.getParent();
            if (folder != null && !Files.exists(folder)) {
                Files.createDirectories(folder);
            }
            Path temp = null;
            try {
                temp = Files.createTempFile(folder, ".soapcli-", ".tmp");
                Files.write(temp, bytes);
                keepPermissions(target, temp);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                temp = null;
                return;
            } catch (IOException | RuntimeException e) {
                // fall through to the in-place write below
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                        // nothing more to do
                    }
                }
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not write the " + label + ": " + e.getMessage());
        }
    }

    /** The file keeps the permissions it had (a profiles file is often 600); a new one gets {@link #newFilePermissions}. */
    private void keepPermissions(Path original, Path temp) {
        try {
            Set<PosixFilePermission> permissions = Files.exists(original)
                    ? Files.getPosixFilePermissions(original)
                    : newFilePermissions;
            Files.setPosixFilePermissions(temp, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system (Windows): nothing to keep
        }
    }

    private byte[] readBytes() {
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw new SendError(400, "config", "The " + label + " is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not read the " + label + ": " + e.getMessage());
        }
    }

    private static String revisionOf(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
