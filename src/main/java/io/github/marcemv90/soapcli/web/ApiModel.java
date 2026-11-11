package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON bodies of the web API. Plain data with public fields, so Jackson maps them without ceremony. */
public final class ApiModel {

    private ApiModel() {
    }

    /** What the browser sends to {@code /api/send} and {@code /api/preview}. Everything is optional but the body. */
    public static class SendRequest {
        /** A profile from the server's profiles file; the other fields override it. */
        public String profile;
        public String endpoint;
        public String body;
        /** {@code auto}, {@code 1.1} or {@code 1.2}. */
        public String soapVersion;
        public String soapAction;
        public Map<String, String> headers = new LinkedHashMap<>();
        /** Values for {@code ___NAME:default___} placeholders. */
        public Map<String, String> args = new LinkedHashMap<>();
        /** {@code user:password}. */
        public String authBasic;
        public String authBearer;
        public Integer timeoutSeconds;
        public Integer connectTimeoutSeconds;
        public Boolean followRedirects;
        public Boolean insecure;
        public Boolean mtom;
        public List<AttachmentUpload> attachments = new ArrayList<>();
    }

    public static class AttachmentUpload {
        public String cid;
        /** Informational only: it is never used to build a path. */
        public String filename;
        public String contentBase64;
    }

    public static class NameValue {
        public String name;
        public String value;

        public NameValue() {
        }

        public NameValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** The request as it will be (or was) sent, with credentials masked. */
    public static class RequestInfo {
        public String endpoint;
        public String soapVersion;
        public List<NameValue> headers = new ArrayList<>();
        public long contentLength;
        public boolean mtom;
        public String previewXml;
        public List<String> attachments = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    public static class SendResponse {
        public int status;
        public String statusMessage;
        public long elapsedMillis;
        /** The URL that finally answered (differs from the endpoint only when redirects were followed). */
        public String uri;
        public String contentType;
        public int bodySize;
        public boolean binary;
        /** The body, pretty-printed when it is XML. {@code null} for binary responses. */
        public String body;
        /** The body exactly as decoded, only present when it differs from {@link #body}. */
        public String rawBody;
        /** Binary responses (MTOM...) so the browser can offer them as a download. */
        public String bodyBase64;
        public List<NameValue> headers = new ArrayList<>();
        public String location;
        public RequestInfo request;
    }

    public static class ErrorResponse {
        public ErrorBody error;

        public ErrorResponse(String category, String message) {
            this.error = new ErrorBody(category, message);
        }
    }

    public static class ErrorBody {
        /** {@code config}, {@code forbidden}, {@code network}, {@code unsupported}, {@code too-large}, {@code internal}. */
        public String category;
        public String message;

        public ErrorBody(String category, String message) {
            this.category = category;
            this.message = message;
        }
    }

    public static class FormatRequest {
        public String xml;
    }

    public static class FormatResponse {
        public String xml;
    }

    public static class InfoResponse {
        public String version;
        public boolean insecureTlsAllowed;
        public List<String> allowedHosts = new ArrayList<>();
        public long maxBodyBytes;
        public boolean profilesFileFound;
        public String profilesFile;
    }

    public static class ProfileInfo {
        public String name;
        /** The effective settings after {@code extends}, with secrets masked. */
        public JsonNode settings;
        public String error;
        public boolean hasRequestFile;
    }

    public static class ProfilesResponse {
        public String file;
        public boolean found;
        public String error;
        public List<ProfileInfo> profiles = new ArrayList<>();
    }

    public static class SendError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int httpStatus;
        public final String category;

        public SendError(int httpStatus, String category, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.category = category;
        }
    }
}
