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

    /** A file for a {@code cid:}: its bytes ({@code contentBase64}) or, instead, a {@code path} on this server. */
    public static class AttachmentUpload {
        public String cid;
        /** Informational only: it is never used to build a path. */
        public String filename;
        public String contentBase64;
        /**
         * A file on the server, absolute or relative to the attachments folder ({@code --attachments-dir}). Only files
         * inside that folder are accepted.
         */
        public String path;
    }

    public static class AttachmentCheckRequest {
        public String path;
    }

    /** What the server found at an attachment path. */
    public static class AttachmentCheckResponse {
        /** The path as the browser wrote it. */
        public String path;
        public String name;
        public long size;
        /** Where it really is, after resolving a relative path and symbolic links. */
        public String resolved;
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
        /** The folder attachments may be read from by path. */
        public String attachmentsDir;
        public String placeholdersFile;
        /** Whether the server lets the UI change the profiles and placeholders files (they may still be read-only). */
        public boolean configEditAllowed;
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
        /** Identifies the content of the file; an edit has to quote it. */
        public String revision;
        public boolean editable;
        /** Why {@link #editable} is false. */
        public String readOnlyReason;
        public List<ProfileInfo> profiles = new ArrayList<>();
    }

    /** A profile as it is in the file, for editing: not merged with what it extends, secrets masked. */
    public static class ProfileDefinition {
        public String name;
        public String revision;
        public JsonNode definition;
        /** The profiles that inherit from this one. */
        public List<String> dependents = new ArrayList<>();
    }

    /** Creates a profile, or replaces its definition. A secret left as {@code ****} keeps the saved value. */
    public static class SaveProfileRequest {
        public String name;
        /** The revision of the file the form was made from. */
        public String revision;
        /** {@code true} for a new profile: it is refused if the name exists. */
        public boolean create;
        public JsonNode definition;
    }

    public static class DeleteRequest {
        public String name;
        public String revision;
    }

    public static class SaveResponse {
        /** The revision of the file after the change. */
        public String revision;
        /** Things worth telling the author: ignored settings, checks that could not be made. */
        public List<String> warnings = new ArrayList<>();
    }

    public static class PlaceholderOption {
        public String value;
        /** What the dropdown shows; the value itself when absent. */
        public String label;

        public PlaceholderOption() {
        }

        public PlaceholderOption(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    public static class PlaceholderInfo {
        public String name;
        public String description;
        public List<PlaceholderOption> options = new ArrayList<>();
        /** Why this entry cannot be used, if it cannot. */
        public String error;
    }

    public static class PlaceholdersResponse {
        public String file;
        public boolean found;
        public String error;
        public String revision;
        public boolean editable;
        public String readOnlyReason;
        public List<PlaceholderInfo> placeholders = new ArrayList<>();
    }

    public static class SavePlaceholderRequest {
        public String name;
        public String revision;
        public boolean create;
        public String description;
        public List<PlaceholderOption> options = new ArrayList<>();
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
