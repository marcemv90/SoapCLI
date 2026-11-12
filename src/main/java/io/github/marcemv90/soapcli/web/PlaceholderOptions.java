package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.JsonSplice;
import io.github.marcemv90.soapcli.web.ApiModel.PlaceholderInfo;
import io.github.marcemv90.soapcli.web.ApiModel.PlaceholderOption;
import io.github.marcemv90.soapcli.web.ApiModel.SavePlaceholderRequest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The options the UI offers in a dropdown for the placeholders ({@code ___NAME:default___}) of a request. They live in
 * a file of their own, next to the profiles file, because they belong to the placeholders, not to an environment:
 *
 * <pre>
 * { "placeholders": {
 *     "ENV":     { "options": ["acceptance", "staging", "production"] },
 *     "COUNTRY": { "description": "Country code",
 *                  "options": [ { "value": "ES", "label": "Spain" }, "NL" ] }
 * } }
 * </pre>
 *
 * Comments and trailing commas are accepted, as in the profiles file.
 */
final class PlaceholderOptions {

    static final String CONTAINER = "placeholders";
    /** The same shape of name the request's placeholders have ({@code ___NAME:default___}). */
    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]{0,63}");
    static final int MAX_OPTIONS = 500;
    static final int MAX_VALUE = 1000;
    static final int MAX_LABEL = 200;
    static final int MAX_DESCRIPTION = 300;

    private PlaceholderOptions() {
    }

    /**
     * Reads the file. A problem with one entry is reported on that entry, so that the rest keeps working.
     *
     * @throws ConfigException if the file as a whole cannot be read
     */
    static List<PlaceholderInfo> parse(String text) {
        List<PlaceholderInfo> list = new java.util.ArrayList<>();
        if (text.trim().isEmpty()) {
            return list;
        }
        JsonNode root = JsonSplice.parse(text);
        JsonNode all = root.get(CONTAINER);
        if (all == null || !all.isObject()) {
            throw new ConfigException("Invalid placeholders file: missing top-level '" + CONTAINER + "' object");
        }
        Iterator<Map.Entry<String, JsonNode>> it = all.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            PlaceholderInfo info = new PlaceholderInfo();
            info.name = e.getKey();
            try {
                read(e.getValue(), info);
            } catch (ConfigException ex) {
                info.options.clear();
                info.error = ex.getMessage();
            }
            list.add(info);
        }
        return list;
    }

    private static void read(JsonNode node, PlaceholderInfo info) {
        if (!NAME.matcher(info.name).matches()) {
            throw new ConfigException("'" + info.name + "' is not a valid placeholder name (letters, digits, _ . -)");
        }
        if (node == null || !node.isObject()) {
            throw new ConfigException("'" + info.name + "' must be an object with an \"options\" list");
        }
        JsonNode description = node.get("description");
        if (description != null && !description.isNull()) {
            if (description.isContainerNode()) {
                throw new ConfigException("'" + info.name + "': \"description\" must be text");
            }
            info.description = description.asText();
        }
        JsonNode options = node.get("options");
        if (options == null || !options.isArray()) {
            throw new ConfigException("'" + info.name + "' needs an \"options\" list");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode option : options) {
            PlaceholderOption o = new PlaceholderOption();
            if (option.isObject()) {
                JsonNode value = option.get("value");
                if (value == null || value.isNull() || value.isContainerNode()) {
                    throw new ConfigException("'" + info.name + "': every option with a label needs a \"value\"");
                }
                o.value = value.asText();
                JsonNode label = option.get("label");
                o.label = label == null || label.isNull() || label.isContainerNode() ? null : label.asText();
            } else if (option.isValueNode() && !option.isNull()) {
                o.value = option.asText();
            } else {
                throw new ConfigException("'" + info.name + "': an option is a text, a number, or { \"value\", \"label\" }");
            }
            if (!seen.add(o.value)) {
                throw new ConfigException("'" + info.name + "': the option '" + o.value + "' is listed twice");
            }
            info.options.add(o);
        }
    }

    /**
     * The entry to write for a save request, after checking it. Whatever else the entry already had in the file (a note
     * of the author's, say) is kept.
     *
     * @param existing the entry as it is in the file, or {@code null}
     */
    static ObjectNode definition(SavePlaceholderRequest request, JsonNode existing) {
        List<PlaceholderOption> options = request.options;
        if (options == null || options.isEmpty()) {
            throw new ConfigException("Give at least one option (or remove the placeholder)");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new ConfigException("Too many options: at most " + MAX_OPTIONS);
        }
        if (request.description != null && request.description.length() > MAX_DESCRIPTION) {
            throw new ConfigException("The description is longer than " + MAX_DESCRIPTION + " characters");
        }
        Set<String> seen = new HashSet<>();
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (PlaceholderOption o : options) {
            if (o == null || o.value == null || o.value.isEmpty()) {
                throw new ConfigException("An option cannot be empty");
            }
            if (o.value.length() > MAX_VALUE) {
                throw new ConfigException("An option is longer than " + MAX_VALUE + " characters");
            }
            if (!seen.add(o.value)) {
                throw new ConfigException("The option '" + o.value + "' is listed twice");
            }
            String label = o.label == null ? "" : o.label.trim();
            if (label.length() > MAX_LABEL) {
                throw new ConfigException("The label of '" + o.value + "' is longer than " + MAX_LABEL + " characters");
            }
            if (label.isEmpty() || label.equals(o.value)) {
                array.add(o.value);
            } else {
                array.addObject().put("value", o.value).put("label", label);
            }
        }
        ObjectNode entry = existing != null && existing.isObject() ? ((ObjectNode) existing).deepCopy() : JsonNodeFactory.instance.objectNode();
        String description = request.description == null ? "" : request.description.trim();
        if (description.isEmpty()) {
            entry.remove("description");
        } else {
            entry.put("description", description);
        }
        entry.set("options", array);
        return entry;
    }
}
