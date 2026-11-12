package io.github.marcemv90.soapcli.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Named presets stored in a JSON file ({@code {"profiles": {"name": {...}}}}).
 *
 * <p>Supports {@code "extends": "other"} (deep merge, {@code null} removes an inherited key) and {@code ${VAR}} /
 * {@code ${VAR:-default}} references to environment variables so secrets need not live in the file.
 * Comments and trailing commas are accepted in the JSON.
 */
public final class Profiles {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Pattern PURE_ENV_REF = Pattern.compile("^\\$\\{[^}]*}$");
    private static final int MAX_DEPTH = 10;

    private final Path file;
    private final ObjectNode profiles;

    private Profiles(Path file, ObjectNode profiles) {
        this.file = file;
        this.profiles = profiles;
    }

    /** {@code SOAPCLI_PROFILE_FILE}, else {@code ~/.config/soap-cli-profiles.json}. */
    public static Path defaultFile(Map<String, String> env, String userHome) {
        String fromEnv = env.get("SOAPCLI_PROFILE_FILE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv);
        }
        if (userHome != null && !userHome.isEmpty()) {
            return Paths.get(userHome, ".config", "soap-cli-profiles.json");
        }
        return Paths.get("soap-cli-profiles.json");
    }

    public static Profiles load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("Profiles file '" + file + "' not found. Create it, pass another one with "
                    + "--profile-file, or set SOAPCLI_PROFILE_FILE");
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Failed to read profiles file '" + file + "': " + e.getMessage(), e);
        }
        return parse(text, file);
    }

    /** Reads profiles from text that came from {@code file} (which is only used in messages). */
    public static Profiles parse(String text, Path file) {
        JsonNode root;
        try (JsonParser parser = MAPPER.createParser(new java.io.StringReader(text))) {
            root = MAPPER.readTree(parser);
        } catch (IOException e) {
            throw new ConfigException("Failed to read profiles file '" + file + "': " + e.getMessage(), e);
        }
        JsonNode profiles = root == null ? null : root.get("profiles");
        if (profiles == null || !profiles.isObject()) {
            throw new ConfigException("Invalid profiles file '" + file + "': missing top-level 'profiles' object");
        }
        return new Profiles(file, (ObjectNode) profiles);
    }

    public Path file() {
        return file;
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        profiles.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The profile ready to apply: merged, interpolated, validated. */
    public Profile resolve(String name, Map<String, String> env) {
        ObjectNode merged = merged(name, new ArrayList<>());
        merged.remove("extends");
        JsonNode expanded = interpolate(merged, "", name, env);
        return Profile.from(name, expanded);
    }

    /** The effective profile for display: merged, {@code ${...}} left as written, secrets masked. */
    public ObjectNode describe(String name) {
        ObjectNode own = ownNode(name);
        ObjectNode merged = merged(name, new ArrayList<>());
        JsonNode extendsNode = own.get("extends");
        ObjectNode result = MAPPER.createObjectNode();
        if (extendsNode != null && extendsNode.isTextual()) {
            result.put("extends", extendsNode.asText());
        }
        merged.remove("extends");
        merged.fields().forEachRemaining(e -> result.set(e.getKey(), e.getValue()));
        return mask(result);
    }

    /**
     * What the profile itself says, for editing: not merged with what it extends, {@code ${...}} left as written, and
     * secrets masked. {@code null} values (which remove an inherited setting) are kept.
     */
    public ObjectNode own(String name) {
        return mask(ownNode(name).deepCopy());
    }

    /** The profile as it is in the file, secrets included, to put back what a masked value stands for. */
    public ObjectNode ownUnmasked(String name) {
        return ownNode(name).deepCopy();
    }

    public boolean has(String name) {
        JsonNode node = profiles.get(name);
        return node != null && !node.isNull();
    }

    /** The profiles that inherit from {@code name}, directly or through others. */
    public List<String> dependents(String name) {
        List<String> result = new ArrayList<>();
        boolean grew = true;
        List<String> known = new ArrayList<>();
        known.add(name);
        while (grew) {
            grew = false;
            for (String candidate : names()) {
                if (known.contains(candidate)) {
                    continue;
                }
                JsonNode node = profiles.get(candidate);
                JsonNode parent = node != null && node.isObject() ? node.get("extends") : null;
                if (parent != null && parent.isTextual() && known.contains(parent.asText())) {
                    known.add(candidate);
                    result.add(candidate);
                    grew = true;
                }
            }
        }
        return result;
    }

    /**
     * Checks a profile the way using it would: it must merge with what it extends and every value must have the right
     * type. A {@code ${VAR}} that is not set on this machine cannot be expanded, so the types are not checked then.
     *
     * @return what is worth telling the author (ignored settings, a check that could not be made)
     * @throws ConfigException if the profile cannot be used
     */
    public List<String> validate(String name, Map<String, String> env) {
        List<String> notes = new ArrayList<>();
        ObjectNode merged = merged(name, new ArrayList<>());
        merged.remove("extends");
        JsonNode expanded;
        try {
            expanded = interpolate(merged, "", name, env);
        } catch (MissingEnvironmentException e) {
            notes.add(e.getMessage() + ". The types of this profile's values could not be checked here");
            return notes;
        }
        notes.addAll(Profile.from(name, expanded).warnings);
        return notes;
    }

    /**
     * Puts back the secrets a masked copy stood for: a secret whose value is {@link SecretMasker#MASK} in
     * {@code submitted} keeps what {@code existing} has. Any other value is taken as written, so typing a new secret
     * replaces the old one.
     *
     * @param existing the profile as it is in the file, or {@code null} for a new one
     * @throws ConfigException if a value is masked but there is nothing saved to keep
     */
    public static ObjectNode restoreSecrets(ObjectNode submitted, ObjectNode existing) {
        return restoreSecrets(submitted, existing, "");
    }

    private static ObjectNode restoreSecrets(ObjectNode submitted, ObjectNode existing, String path) {
        ObjectNode result = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> it = submitted.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode value = e.getValue();
            JsonNode old = existing == null ? null : existing.get(key);
            String where = path.isEmpty() ? key : path + "." + key;
            if (value.isObject()) {
                result.set(key, restoreSecrets((ObjectNode) value, old != null && old.isObject() ? (ObjectNode) old : null, where));
            } else if (value.isTextual() && SecretMasker.MASK.equals(value.asText()) && SecretMasker.isSecretName(key)) {
                if (old == null || !old.isValueNode() || old.isNull()) {
                    throw new ConfigException("'" + where + "' is hidden (" + SecretMasker.MASK + ") but there is no saved value to keep: "
                            + "type the value");
                }
                result.set(key, old);
            } else {
                result.set(key, value);
            }
        }
        return result;
    }

    /** Human readable rendering of {@link #describe(String)} output, indented by {@code indent} spaces. */
    public static List<String> render(JsonNode node, int indent) {
        List<String> lines = new ArrayList<>();
        render(node, indent, lines);
        return lines;
    }

    private static void render(JsonNode node, int indent, List<String> lines) {
        String pad = " ".repeat(indent);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue().isObject()) {
                lines.add(pad + e.getKey() + ":");
                render(e.getValue(), indent + 4, lines);
            } else {
                String value = e.getValue().asText();
                lines.add(pad + e.getKey() + ": " + (SecretMasker.MASK.equals(value) ? SecretMasker.MASK + " (set)" : value));
            }
        }
    }

    // ---- merging --------------------------------------------------------------------------------------------------

    private ObjectNode ownNode(String name) {
        JsonNode node = profiles.get(name);
        if (node == null || node.isNull()) {
            throw new ConfigException("Profile '" + name + "' not found in '" + file + "'. Available: "
                    + String.join(", ", names()));
        }
        if (!node.isObject()) {
            throw new ConfigException("Profile '" + name + "' must be a JSON object");
        }
        return (ObjectNode) node;
    }

    private ObjectNode merged(String name, List<String> chain) {
        if (chain.contains(name)) {
            chain.add(name);
            throw new ConfigException("Profiles extend each other in a loop: " + String.join(" -> ", chain));
        }
        if (chain.size() >= MAX_DEPTH) {
            throw new ConfigException("Profile 'extends' chain is too deep: " + String.join(" -> ", chain));
        }
        ObjectNode own = ownNode(name).deepCopy();
        JsonNode parent = own.get("extends");
        ObjectNode base;
        if (parent == null || parent.isNull()) {
            base = MAPPER.createObjectNode();
        } else {
            if (!parent.isTextual()) {
                throw new ConfigException("Profile '" + name + "': 'extends' must be the name of another profile");
            }
            if (!profiles.has(parent.asText())) {
                throw new ConfigException("Profile '" + name + "' extends the unknown profile '" + parent.asText() + "'");
            }
            chain.add(name);
            base = merged(parent.asText(), chain);
            chain.remove(chain.size() - 1);
        }
        own.remove("extends");
        return merge(base, own);
    }

    private static ObjectNode merge(ObjectNode base, ObjectNode child) {
        Iterator<Map.Entry<String, JsonNode>> it = child.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode value = e.getValue();
            JsonNode existing = base.get(e.getKey());
            if (value.isNull()) {
                base.remove(e.getKey());                      // null un-sets an inherited value
            } else if (value.isObject() && existing != null && existing.isObject()) {
                merge((ObjectNode) existing, (ObjectNode) value);
            } else {
                base.set(e.getKey(), value);
            }
        }
        return base;
    }

    // ---- ${ENV} ---------------------------------------------------------------------------------------------------

    private static JsonNode interpolate(JsonNode node, String path, String profile, Map<String, String> env) {
        if (node.isTextual()) {
            return MAPPER.getNodeFactory().textNode(expandText(node.asText(), path, profile, env));
        }
        if (node.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(),
                    interpolate(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), profile, env)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                copy.add(interpolate(child, path, profile, env));
            }
            return copy;
        }
        return node;
    }

    private static String expandText(String text, String path, String profile, Map<String, String> env) {
        Matcher m = ENV_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String value = env.get(m.group(1));
            if (value == null) {
                value = m.group(2);
            }
            if (value == null) {
                throw new MissingEnvironmentException("Profile '" + profile + "': the environment variable '" + m.group(1)
                        + "' is not set (used in '" + path + "'). Set it, or write ${" + m.group(1)
                        + ":-default} to provide a fallback");
            }
            sb.append(text, last, m.start()).append(value);
            last = m.end();
        }
        return sb.append(text, last, text.length()).toString();
    }

    // ---- masking --------------------------------------------------------------------------------------------------

    private static ObjectNode mask(ObjectNode node) {
        ObjectNode copy = MAPPER.createObjectNode();
        node.fields().forEachRemaining(e -> {
            JsonNode value = e.getValue();
            if (value.isObject()) {
                copy.set(e.getKey(), mask((ObjectNode) value));
            } else if (value.isValueNode() && !value.isNull() && SecretMasker.isSecretName(e.getKey())
                    && !PURE_ENV_REF.matcher(value.asText()).matches()) {
                copy.put(e.getKey(), SecretMasker.MASK);   // a ${VAR} reference is not a secret: keep it visible
            } else {
                copy.set(e.getKey(), value);
            }
        });
        return copy;
    }

    /** A {@code ${VAR}} in a profile names a variable that is not set (and has no {@code :-default}). */
    public static final class MissingEnvironmentException extends ConfigException {
        private static final long serialVersionUID = 1L;

        MissingEnvironmentException(String message) {
            super(message);
        }
    }

    /** Pretty JSON of {@link #describe(String)}. */
    public static String toJson(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
