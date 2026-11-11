package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.io.OutputStream;

/** The bytes of the HTTP request, with a known length so large attachments need not be buffered twice. */
public interface RequestBody {

    long contentLength();

    void writeTo(OutputStream out) throws IOException;

    /** A body that is already in memory. */
    final class Bytes implements RequestBody {
        private final byte[] bytes;

        public Bytes(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            out.write(bytes);
        }
    }
}
