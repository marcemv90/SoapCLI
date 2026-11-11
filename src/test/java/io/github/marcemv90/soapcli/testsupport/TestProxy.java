package io.github.marcemv90.soapcli.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal forward proxy that insists on Basic authentication: CONNECT tunnels for HTTPS and absolute-URI requests
 * for plain HTTP (which it relays to the real target).
 */
public final class TestProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String expectedAuth;
    /** Request lines seen, e.g. {@code CONNECT 127.0.0.1:8443 HTTP/1.1}, with their authorization outcome. */
    public final List<String> log = new CopyOnWriteArrayList<>();

    public TestProxy(String user, String password) throws IOException {
        this.expectedAuth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Thread t = new Thread(() -> serve(client), "test-proxy-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "test-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void serve(Socket client) {
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            while (true) {
                String head = readHead(in);
                if (head == null) {
                    return;
                }
                String[] lines = head.split("\r\n");
                String requestLine = lines[0];
                String auth = null;
                int contentLength = 0;
                for (String line : lines) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("proxy-authorization:")) {
                        auth = line.substring(line.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                byte[] body = in.readNBytes(contentLength);
                if (!expectedAuth.equals(auth)) {
                    log.add(requestLine + " -> 407");
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n"
                            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    continue; // the client may retry on the same connection
                }
                log.add(requestLine + " -> authorized");
                if (requestLine.startsWith("CONNECT ")) {
                    tunnel(c, requestLine.split(" ")[1]);
                } else {
                    relayPlainHttp(out, requestLine, head, body);
                }
                return;
            }
        } catch (IOException ignored) {
            // connection closed
        }
    }

    private void tunnel(Socket client, String hostPort) throws IOException {
        int colon = hostPort.lastIndexOf(':');
        try (Socket target = new Socket(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)))) {
            client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            Thread up = new Thread(() -> pipe(client, target));
            up.setDaemon(true);
            up.start();
            pipe(target, client);
            try {
                up.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void relayPlainHttp(OutputStream clientOut, String requestLine, String head, byte[] body) throws IOException {
        // POST http://127.0.0.1:1234/path HTTP/1.1 -> origin-form request to the real target
        String[] parts = requestLine.split(" ");
        java.net.URI uri = java.net.URI.create(parts[1]);
        try (Socket target = new Socket(uri.getHost(), uri.getPort())) {
            StringBuilder sb = new StringBuilder(parts[0]).append(' ').append(uri.getRawPath()).append(" HTTP/1.1\r\n");
            String[] lines = head.split("\r\n");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].toLowerCase().startsWith("proxy-") && !lines[i].toLowerCase().startsWith("connection:")) {
                    sb.append(lines[i]).append("\r\n");
                }
            }
            sb.append("Connection: close\r\n\r\n");
            target.getOutputStream().write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            target.getOutputStream().write(body);
            target.getOutputStream().flush();
            target.getInputStream().transferTo(clientOut);
            clientOut.flush();
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.getOutputStream().flush();
        } catch (IOException ignored) {
            // closed
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) {
                state++;
            } else if (b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = 0;
            }
            if (state == 4) {
                return buf.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
