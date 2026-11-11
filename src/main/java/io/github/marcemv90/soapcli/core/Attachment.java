package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A file referenced from the request as {@code cid:<cid>}. */
public final class Attachment {

    public final String cid;
    public final Path file;

    public Attachment(String cid, Path file) {
        this.cid = cid;
        this.file = file;
    }

    public long size() {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new ConfigException("Cannot read attachment '" + file + "': " + e.getMessage(), e);
        }
    }
}
