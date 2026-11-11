package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** A {@code multipart/related} MTOM/XOP message: the SOAP envelope as root part followed by binary attachments. */
public final class MultipartBody implements RequestBody {

    private static final String CRLF = "\r\n";

    private final byte[] head;
    private final List<byte[]> partHeaders = new ArrayList<>();
    private final List<Attachment> attachments;
    private final List<Long> sizes = new ArrayList<>();
    private final byte[] tail;
    private final long length;

    public MultipartBody(String boundary, String rootContentId, String rootContentType, String rootXml, Charset charset,
                         List<Attachment> attachments) {
        this.attachments = attachments;

        StringBuilder rootHead = new StringBuilder();
        rootHead.append("--").append(boundary).append(CRLF)
                .append("Content-Type: ").append(rootContentType).append(CRLF)
                .append("Content-Transfer-Encoding: 8bit").append(CRLF)
                .append("Content-ID: ").append(rootContentId).append(CRLF)
                .append(CRLF);
        byte[] rootBytes = rootXml.getBytes(charset);
        byte[] headBytes = rootHead.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlf = CRLF.getBytes(StandardCharsets.ISO_8859_1);
        this.head = concat(headBytes, rootBytes, crlf);

        long total = head.length;
        for (Attachment a : attachments) {
            String header = "--" + boundary + CRLF
                    + "Content-Type: application/octet-stream" + CRLF
                    + "Content-ID: <" + a.cid + ">" + CRLF
                    + "Content-Transfer-Encoding: binary" + CRLF
                    + CRLF;
            byte[] h = header.getBytes(StandardCharsets.ISO_8859_1);
            long size = a.size();
            partHeaders.add(h);
            sizes.add(size);
            total += h.length + size + crlf.length;
        }
        this.tail = ("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.ISO_8859_1);
        this.length = total + tail.length;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        out.write(head);
        byte[] buffer = new byte[8192];
        for (int i = 0; i < attachments.size(); i++) {
            out.write(partHeaders.get(i));
            long copied = 0;
            try (InputStream in = Files.newInputStream(attachments.get(i).file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;
                }
            }
            if (copied != sizes.get(i)) {
                throw new IOException("Attachment '" + attachments.get(i).file + "' changed size while it was being sent");
            }
            out.write(CRLF.getBytes(StandardCharsets.ISO_8859_1));
        }
        out.write(tail);
    }

    private static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) {
            n += a.length;
        }
        byte[] result = new byte[n];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
