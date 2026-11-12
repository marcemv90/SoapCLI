"""A tiny SOAP service for trying SoapCLI without a real one.

It answers every POST with a SOAP envelope that describes what it received: the headers that matter, how big the request
was, whether it was a multipart (MTOM) message, and a preview of the body in which long Base64 runs (an inlined
attachment) are shortened. It checks nothing and stores nothing. The `mock` service of docker-compose.yml runs it;
delete that service when you point SoapCLI at your own endpoints.
"""
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

PORT = int(os.environ.get("PORT", "8081"))
MAX_BYTES = 25 * 1024 * 1024
PREVIEW_CHARS = 2000
SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/"
SOAP12 = "http://www.w3.org/2003/05/soap-envelope"
# What XML 1.0 cannot carry, and long runs that are almost certainly Base64 (an attachment inlined in the request).
CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")
BASE64_RUN = re.compile(r"[A-Za-z0-9+/=\r\n]{80,}")


def read_body(handler):
    """The request body, whether it came with a Content-Length or chunked. None if it is too large."""
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        body = bytearray()
        while True:
            size = int(handler.rfile.readline().split(b";")[0].strip() or b"0", 16)
            if size == 0:
                handler.rfile.readline()
                return bytes(body)
            body += handler.rfile.read(size)
            handler.rfile.readline()
            if len(body) > MAX_BYTES:
                return None
    length = int(handler.headers.get("Content-Length") or 0)
    return None if length > MAX_BYTES else handler.rfile.read(length)


def preview(body):
    text = CONTROL.sub("", body.decode("utf-8", errors="replace"))
    text = BASE64_RUN.sub(lambda m: f"[base64: {len(m.group(0).strip())} characters]", text)
    return text[:PREVIEW_CHARS] + (" [...]" if len(text) > PREVIEW_CHARS else "")


def describe(handler, body):
    content_type = handler.headers.get("Content-Type", "")
    namespace = SOAP12 if "application/soap+xml" in content_type else SOAP11
    # Credentials are never echoed: only the scheme of the Authorization header.
    scheme = handler.headers.get("Authorization", "").split(" ")[0]
    lines = [
        f"<mock:Path>{escape(handler.path)}</mock:Path>",
        f"<mock:ContentType>{escape(content_type)}</mock:ContentType>",
        f"<mock:SOAPAction>{escape(handler.headers.get('SOAPAction', ''))}</mock:SOAPAction>",
        f"<mock:Bytes>{len(body)}</mock:Bytes>",
        f"<mock:Authorization>{escape(scheme + ' ****' if scheme else 'none')}</mock:Authorization>",
    ]
    for name, value in handler.headers.items():
        if name.lower().startswith("x-"):
            lines.append(f'<mock:Header name="{escape(name)}">{escape(value)}</mock:Header>')
    if content_type.lower().startswith("multipart/related"):
        ids = re.findall(rb"(?im)^content-id:\s*(<[^>\r\n]*>)", body)
        lines.append(f'<mock:Multipart parts="{len(ids)}">' + "".join(
            f"<mock:Part>{escape(i.decode('latin-1'))}</mock:Part>" for i in ids) + "</mock:Multipart>")
    lines.append(f"<mock:Body>{escape(preview(body))}</mock:Body>")
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<soapenv:Envelope xmlns:soapenv="{namespace}">'
            f'<soapenv:Body><mock:EchoResponse xmlns:mock="urn:soapcli:example:mock">' + "".join(lines) +
            "</mock:EchoResponse></soapenv:Body></soapenv:Envelope>\n")


class Handler(BaseHTTPRequestHandler):
    server_version = "SoapCLIExampleMock"

    def _send(self, status, content_type, payload):
        data = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._send(200, "text/plain; charset=utf-8", "SoapCLI example mock: POST a SOAP request to /soap\n")

    def do_POST(self):
        body = read_body(self)
        if body is None:
            self._send(413, "text/plain; charset=utf-8", "The request is larger than 25 MB\n")
            return
        self._send(200, "text/xml; charset=utf-8", describe(self, body))

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


if __name__ == "__main__":
    print(f"SoapCLI example mock listening on :{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
