package io.github.marcemv90.soapcli.web;

import io.github.marcemv90.soapcli.core.Attachment;
import io.github.marcemv90.soapcli.core.CallConfig;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.HostPolicy;
import io.github.marcemv90.soapcli.core.PreparedRequest;
import io.github.marcemv90.soapcli.core.Profile;
import io.github.marcemv90.soapcli.core.Profiles;
import io.github.marcemv90.soapcli.core.RequestBuilder;
import io.github.marcemv90.soapcli.core.SecretMasker;
import io.github.marcemv90.soapcli.core.SoapClient;
import io.github.marcemv90.soapcli.core.SoapResponse;
import io.github.marcemv90.soapcli.core.SoapVersion;
import io.github.marcemv90.soapcli.core.Version;
import io.github.marcemv90.soapcli.core.XmlCharsets;
import io.github.marcemv90.soapcli.core.XmlFormat;
import io.github.marcemv90.soapcli.web.ApiModel.AttachmentUpload;
import io.github.marcemv90.soapcli.web.ApiModel.InfoResponse;
import io.github.marcemv90.soapcli.web.ApiModel.NameValue;
import io.github.marcemv90.soapcli.web.ApiModel.ProfileInfo;
import io.github.marcemv90.soapcli.web.ApiModel.ProfilesResponse;
import io.github.marcemv90.soapcli.web.ApiModel.RequestInfo;
import io.github.marcemv90.soapcli.web.ApiModel.SendError;
import io.github.marcemv90.soapcli.web.ApiModel.SendRequest;
import io.github.marcemv90.soapcli.web.ApiModel.SendResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the web API does, free of HTTP: turns a browser request into a {@link CallConfig}, runs it through the same
 * engine as the command line, and shapes the answer for JSON.
 *
 * <p>The server's own settings always win over the browser's: the host allow-list, the response size limit and the
 * timeout ceilings are applied after the request's values, so a user cannot talk their way around them.
 */
final class ApiService {

    static final int MAX_CONNECT_SECONDS = 120;
    static final int MAX_READ_SECONDS = 600;
    private static final long MAX_PROFILE_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_BINARY_PREVIEW_BYTES = 5 * 1024 * 1024;
    private static final Pattern CID = Pattern.compile("[A-Za-z0-9._~%@+\\-]+");

    private final ServeOptions options;
    private final HostPolicy hostPolicy;
    private final SoapClient client = new SoapClient();

    ApiService(ServeOptions options) {
        this.options = options;
        HostPolicy policy = new HostPolicy(options.allowedHosts);
        this.hostPolicy = policy.isOpen() ? null : policy;
    }

    // ---- info and profiles ----------------------------------------------------------------------------------------

    InfoResponse info() {
        InfoResponse info = new InfoResponse();
        info.version = Version.get();
        info.insecureTlsAllowed = !options.noInsecureTls;
        if (hostPolicy != null) {
            info.allowedHosts = hostPolicy.patterns();
        }
        info.maxBodyBytes = options.maxBodyBytes;
        info.profilesFile = options.profileFile.toString();
        info.profilesFileFound = Files.isRegularFile(options.profileFile);
        return info;
    }

    ProfilesResponse profiles() {
        ProfilesResponse response = new ProfilesResponse();
        response.file = options.profileFile.toString();
        response.found = Files.isRegularFile(options.profileFile);
        if (!response.found) {
            return response;
        }
        Profiles profiles;
        try {
            profiles = Profiles.load(options.profileFile);
        } catch (ConfigException e) {
            response.error = e.getMessage();
            return response;
        }
        for (String name : profiles.names()) {
            ProfileInfo info = new ProfileInfo();
            info.name = name;
            try {
                info.settings = profiles.describe(name); // masked: secrets never reach the browser
                info.hasRequestFile = info.settings.has("requestFile");
            } catch (ConfigException e) {
                info.error = e.getMessage();
            }
            response.profiles.add(info);
        }
        return response;
    }

    /** The default request XML of a profile ({@code requestFile}), to prefill the editor. */
    String profileRequest(String name) {
        Profile profile = resolveProfile(name);
        CallConfig scratch = new CallConfig();
        profile.applyTo(scratch, options.userHome);
        if (scratch.requestFile == null) {
            throw new SendError(404, "config", "The profile '" + name + "' has no requestFile");
        }
        return readRequestFile(scratch.requestFile);
    }

    private Profile resolveProfile(String name) {
        if (!Files.isRegularFile(options.profileFile)) {
            throw new SendError(404, "config", "There is no profiles file on this server (" + options.profileFile + ")");
        }
        try {
            return Profiles.load(options.profileFile).resolve(name, options.env);
        } catch (ConfigException e) {
            throw new SendError(400, "config", e.getMessage());
        }
    }

    private String readRequestFile(String file) {
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_PROFILE_REQUEST_BYTES) {
                throw new SendError(404, "config", "The request file '" + file + "' was not found or is too large");
            }
            return XmlFormat.clean(XmlCharsets.decode(Files.readAllBytes(path), null).text);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not read the request file: " + e.getMessage());
        }
    }

    // ---- format ---------------------------------------------------------------------------------------------------

    String format(String xml) {
        return XmlFormat.prettyPrint(XmlFormat.clean(xml == null ? "" : xml));
    }

    // ---- preview and send -----------------------------------------------------------------------------------------

    RequestInfo preview(SendRequest request) {
        try (Job job = prepare(request)) {
            return describe(job);
        }
    }

    SendResponse send(SendRequest request) {
        try (Job job = prepare(request)) {
            SoapResponse response = client.send(job.prepared, job.cfg.transport);
            return toResponse(response, job);
        }
    }

    private SendResponse toResponse(SoapResponse r, Job job) {
        SendResponse out = new SendResponse();
        out.status = r.status;
        out.statusMessage = r.statusMessage;
        out.elapsedMillis = r.elapsedMillis;
        out.uri = r.uri.toString();
        out.contentType = r.contentType();
        out.bodySize = r.body.length;
        out.request = describe(job);
        for (Map.Entry<String, String> h : r.headerEntries()) {
            out.headers.add(new NameValue(h.getKey(), SecretMasker.maskHeader(h.getKey(), h.getValue())));
        }
        if (r.isRedirect()) {
            out.location = r.header("Location");
        }
        if (r.body.length == 0) {
            out.body = "";
        } else if (r.isBinary()) {
            out.binary = true;
            if (r.body.length <= MAX_BINARY_PREVIEW_BYTES) {
                out.bodyBase64 = Base64.getEncoder().encodeToString(r.body);
            }
        } else {
            String text = XmlFormat.clean(XmlCharsets.decode(r.body, r.contentType()).text);
            String pretty = XmlFormat.prettyPrint(text);
            out.body = pretty;
            if (!pretty.equals(text)) {
                out.rawBody = text;
            }
        }
        return out;
    }

    private RequestInfo describe(Job job) {
        PreparedRequest p = job.prepared;
        RequestInfo info = new RequestInfo();
        info.endpoint = p.endpoint.toString();
        info.soapVersion = p.soapVersion.label;
        for (Map.Entry<String, String> h : p.headers.entries()) {
            info.headers.add(new NameValue(h.getKey(), SecretMasker.maskHeader(h.getKey(), h.getValue())));
        }
        info.contentLength = p.body.contentLength();
        info.mtom = p.mtom;
        info.previewXml = XmlFormat.prettyPrint(p.previewXml);
        for (Attachment a : p.attachments) {
            info.attachments.add(a.cid + " (" + a.size() + " bytes)");
        }
        info.warnings.addAll(job.warnings);
        info.warnings.addAll(p.warnings);
        return info;
    }

    // ---- turning the browser's request into a call ----------------------------------------------------------------

    /** A prepared call plus the temporary files that belong to it. */
    private static final class Job implements AutoCloseable {
        CallConfig cfg;
        PreparedRequest prepared;
        Path tempDir;
        final List<String> warnings = new java.util.ArrayList<>();

        @Override
        public void close() {
            if (tempDir == null) {
                return;
            }
            try (Stream<Path> files = Files.walk(tempDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (IOException ignored) {
                // best effort: the OS temp directory is cleaned up eventually
            }
        }
    }

    private Job prepare(SendRequest req) {
        if (req == null) {
            throw new SendError(400, "config", "The request is empty");
        }
        Job job = new Job();
        try {
            CallConfig cfg = new CallConfig();
            job.cfg = cfg;

            if (notBlank(req.profile)) {
                Profile profile = resolveProfile(req.profile);
                job.warnings.addAll(profile.warnings);
                profile.applyTo(cfg, options.userHome);
            }
            String profileRequestFile = cfg.requestFile;
            cfg.requestFile = null; // the browser supplies the body; the file is only a fallback

            applyOverrides(req, cfg);

            // The server's rules come last so that they cannot be overridden from the browser.
            cfg.transport.hostPolicy = hostPolicy;
            cfg.transport.maxResponseBytes = options.maxResponseBytes;
            cfg.transport.connectTimeoutSeconds = Math.min(cfg.transport.connectTimeoutSeconds, MAX_CONNECT_SECONDS);
            if (cfg.transport.readTimeoutSeconds <= 0 || cfg.transport.readTimeoutSeconds > MAX_READ_SECONDS) {
                cfg.transport.readTimeoutSeconds = MAX_READ_SECONDS;
            }
            if (cfg.transport.connectTimeoutSeconds <= 0) {
                cfg.transport.connectTimeoutSeconds = MAX_CONNECT_SECONDS;
            }

            String body = req.body;
            if (!notBlank(body) && profileRequestFile != null) {
                body = readRequestFile(profileRequestFile);
            }
            if (!notBlank(body)) {
                throw new SendError(400, "config", "The request body is empty");
            }

            saveAttachments(req.attachments, cfg, job);

            Charset charset = XmlCharsets.declared(body);
            job.prepared = RequestBuilder.build(cfg, body, charset, Version.userAgent());
            return job;
        } catch (RuntimeException e) {
            job.close();
            throw e;
        }
    }

    private void applyOverrides(SendRequest req, CallConfig cfg) {
        if (notBlank(req.endpoint)) {
            cfg.endpoint = req.endpoint.trim();
        }
        if (notBlank(req.soapVersion)) {
            cfg.soapVersion = SoapVersion.parse(req.soapVersion); // "auto" -> null: detect from the envelope
        }
        if (notBlank(req.soapAction)) {
            cfg.soapAction = req.soapAction.trim();
        }
        if (req.headers != null) {
            for (Map.Entry<String, String> h : req.headers.entrySet()) {
                if (notBlank(h.getKey())) {
                    cfg.headers.put(h.getKey().trim(), h.getValue() == null ? "" : h.getValue());
                }
            }
        }
        if (req.args != null) {
            for (Map.Entry<String, String> a : req.args.entrySet()) {
                if (notBlank(a.getKey())) {
                    cfg.args.put(a.getKey().trim(), a.getValue() == null ? "" : a.getValue());
                }
            }
        }
        if (notBlank(req.authBasic)) {
            cfg.authBasic = req.authBasic;
            cfg.authBearer = null;
        } else if (notBlank(req.authBearer)) {
            cfg.authBearer = req.authBearer;
            cfg.authBasic = null;
        }
        if (req.timeoutSeconds != null) {
            cfg.transport.readTimeoutSeconds = req.timeoutSeconds;
        }
        if (req.connectTimeoutSeconds != null) {
            cfg.transport.connectTimeoutSeconds = req.connectTimeoutSeconds;
        }
        if (req.followRedirects != null) {
            cfg.transport.followRedirects = req.followRedirects;
        }
        if (req.mtom != null) {
            cfg.mtom = req.mtom;
        }
        if (Boolean.TRUE.equals(req.insecure)) {
            if (options.noInsecureTls) {
                throw new SendError(403, "forbidden",
                        "Skipping TLS certificate checks is disabled on this server (--no-insecure-tls)");
            }
            cfg.transport.tls.insecure = true;
        }
    }

    /** Writes uploaded attachments to a private temporary directory; file names never come from the browser. */
    private void saveAttachments(List<AttachmentUpload> uploads, CallConfig cfg, Job job) {
        if (uploads == null || uploads.isEmpty()) {
            return;
        }
        try {
            Path dir = Files.createTempDirectory("soapcli-web-");
            job.tempDir = dir; // from now on the job cleans it up, even if a later attachment is rejected
            int index = 0;
            for (AttachmentUpload upload : uploads) {
                if (upload == null || !notBlank(upload.cid) || !CID.matcher(upload.cid.trim()).matches()) {
                    throw new SendError(400, "config", "Each attachment needs a CID made of letters, digits and . _ - ~ % @ +");
                }
                if (!notBlank(upload.contentBase64)) {
                    throw new SendError(400, "config", "The attachment '" + upload.cid + "' has no content");
                }
                byte[] data;
                try {
                    data = Base64.getDecoder().decode(upload.contentBase64.trim());
                } catch (IllegalArgumentException e) {
                    throw new SendError(400, "config", "The attachment '" + upload.cid + "' is not valid Base64");
                }
                Path file = dir.resolve("attachment-" + (index++) + ".bin");
                Files.write(file, data);
                cfg.attachments.put(upload.cid.trim(), file.toString());
            }
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not store the attachments: " + e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
