package io.github.marcemv90.soapcli.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Changes one entry of a JSON file in place, leaving every other byte of the file as it was.
 *
 * <p>The files SoapCLI reads (profiles, placeholder options) are written by people, who put comments in them and format
 * them as they like; both are accepted ({@code //}, {@code /* *}{@code /} and trailing commas). Parsing the file and
 * writing it back would silently throw all of that away, so an edit is a text splice instead: the parser tells where the
 * entry starts and ends, and only that stretch (or the place where a new entry goes) is rewritten. Comments inside the
 * entry that is replaced are lost; everything else, comments included, stays.
 *
 * <p>The shape is {@code { "<container>": { "<key>": <value>, ... }, ... }}, e.g. {@code container = "profiles"}.
 * Every edit is checked before it is returned: the result must parse to exactly the original document with that one
 * entry added, replaced or removed, so a bug here cannot damage a file.
 */
public final class JsonSplice {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    private JsonSplice() {
    }

    /** The document, with the leniency of the files SoapCLI reads. */
    public static JsonNode parse(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node == null || node.isMissingNode()) {
                throw new ConfigException("The file is empty");
            }
            return node;
        } catch (IOException e) {
            throw new ConfigException("Invalid JSON: " + firstLine(e.getMessage()));
        }
    }

    /** Whether {@code container} has an entry called {@code key}. */
    public static boolean has(String text, String container, String key) {
        if (text.trim().isEmpty()) {
            return false;
        }
        Scan scan = scan(text, container);
        return scan.container != null && last(scan.members, key) != null;
    }

    /** Adds the entry, or replaces its value if {@code key} already exists. */
    public static String put(String text, String container, String key, JsonNode value) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String result;
        if (text.trim().isEmpty()) {
            result = "{" + eol + "  " + quote(container) + ": {" + eol + "    " + quote(key) + ": "
                    + render(value, "    ", eol) + eol + "  }" + eol + "}" + eol;
        } else {
            Scan scan = scan(text, container);
            requireObject(scan, container);
            if (scan.container == null) {
                String indent = memberIndent(text, scan.rootStart, scan.rootEnd, scan.rootMembers);
                String entry = quote(container) + ": {" + eol + indent + "  " + quote(key) + ": "
                        + render(value, indent + "  ", eol) + eol + indent + "}";
                result = insert(text, scan.rootStart, scan.rootEnd, scan.rootMembers, entry, indent, eol);
            } else {
                Member existing = last(scan.members, key);
                if (existing != null) {
                    String indent = lineIndent(text, existing.nameStart);
                    result = text.substring(0, existing.valueStart)
                            + render(value, indent == null ? leading(text, existing.nameStart) : indent, eol)
                            + text.substring(existing.valueEnd);
                } else {
                    String indent = memberIndent(text, scan.container.valueStart, scan.containerEnd, scan.members);
                    String entry = quote(key) + ": " + render(value, indent, eol);
                    result = insert(text, scan.container.valueStart, scan.containerEnd, scan.members, entry, indent, eol);
                }
            }
        }
        ObjectNode expected = text.trim().isEmpty() ? MAPPER.createObjectNode() : (ObjectNode) parse(text).deepCopy();
        JsonNode box = expected.get(container);
        if (box == null || !box.isObject()) {
            box = expected.putObject(container);
        }
        ((ObjectNode) box).set(key, value);
        verify(result, expected);
        return result;
    }

    /** Removes the entry. The text is returned unchanged if there is no such entry. */
    public static String remove(String text, String container, String key) {
        if (text.trim().isEmpty()) {
            return text;
        }
        Scan scan = scan(text, container);
        requireObject(scan, container);
        Member m = scan.container == null ? null : last(scan.members, key);
        if (m == null) {
            return text;
        }
        int from = m.nameStart;
        int to = m.valueEnd;
        // Whole line(s) when the entry has them to itself, so that no blank line is left behind.
        boolean ownLine = lineIndent(text, from) != null;
        int afterValue = skipSpaces(text, to);
        if (afterValue < text.length() && text.charAt(afterValue) == ',') {
            to = afterValue + 1;
        } else {
            // The last entry: the comma before it goes too, if it can be told apart from a comment.
            int before = from;
            while (before > 0 && isSpace(text.charAt(before - 1))) {
                before--;
            }
            if (before > 0 && text.charAt(before - 1) == ',') {
                from = before - 1;
                ownLine = false;
                to = m.valueEnd;
            }
        }
        if (ownLine) {
            int lineStart = text.lastIndexOf('\n', from - 1) + 1;
            int rest = skipBlanks(text, to);
            if (rest >= text.length() || text.charAt(rest) == '\n' || text.charAt(rest) == '\r') {
                from = lineStart;
                to = rest < text.length() ? (text.startsWith("\r\n", rest) ? rest + 2 : rest + 1) : rest;
            }
        }
        String result = text.substring(0, from) + text.substring(to);
        ObjectNode expected = (ObjectNode) parse(text).deepCopy();
        ((ObjectNode) expected.get(container)).remove(key);
        verify(result, expected);
        return result;
    }

    // ---- scanning ---------------------------------------------------------------------------------------------------

    private static final class Member {
        String name;
        int nameStart;
        int valueStart;
        /** Just after the value. */
        int valueEnd;
    }

    private static final class Scan {
        int rootStart;
        /** Offset of the root's closing brace. */
        int rootEnd;
        final List<Member> rootMembers = new ArrayList<>();
        Member container;
        /** The file has an entry with the container's name, but it is not an object. */
        boolean containerNotObject;
        /** Offset of the container's closing brace. */
        int containerEnd;
        final List<Member> members = new ArrayList<>();
    }

    private static Scan scan(String text, String container) {
        Scan scan = new Scan();
        try (JsonParser p = FACTORY.createParser(new StringReader(text))) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new ConfigException("The file must hold a JSON object");
            }
            scan.rootStart = offset(p.currentTokenLocation().getCharOffset());
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                Member m = new Member();
                m.name = p.currentName();
                m.nameStart = offset(p.currentTokenLocation().getCharOffset());
                JsonToken value = p.nextToken();
                m.valueStart = offset(p.currentTokenLocation().getCharOffset());
                if (m.name.equals(container) && value == JsonToken.START_OBJECT) {
                    scan.container = m;
                    scan.members.clear();
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        Member inner = new Member();
                        inner.name = p.currentName();
                        inner.nameStart = offset(p.currentTokenLocation().getCharOffset());
                        JsonToken innerValue = p.nextToken();
                        inner.valueStart = offset(p.currentTokenLocation().getCharOffset());
                        finish(p, innerValue);
                        inner.valueEnd = offset(p.currentLocation().getCharOffset());
                        scan.members.add(inner);
                    }
                    scan.containerEnd = offset(p.currentTokenLocation().getCharOffset());
                    m.valueEnd = offset(p.currentLocation().getCharOffset());
                } else {
                    if (m.name.equals(container)) {
                        scan.containerNotObject = true;
                    }
                    finish(p, value);
                    m.valueEnd = offset(p.currentLocation().getCharOffset());
                }
                scan.rootMembers.add(m);
            }
            scan.rootEnd = offset(p.currentTokenLocation().getCharOffset());
        } catch (IOException e) {
            throw new ConfigException("Invalid JSON: " + firstLine(e.getMessage()));
        }
        return scan;
    }

    /** Moves the parser to the end of the value it has just started, so that its location is just after it. */
    private static void finish(JsonParser p, JsonToken value) throws IOException {
        if (value.isStructStart()) {
            p.skipChildren();
        } else {
            p.getText(); // a string is read lazily: this makes the parser consume all of it
        }
    }

    private static void requireObject(Scan scan, String container) {
        if (scan.containerNotObject) {
            throw new ConfigException("'" + container + "' in the file must be an object");
        }
    }

    private static Member last(List<Member> members, String key) {
        Member found = null;
        for (Member m : members) {
            if (m.name.equals(key)) {
                found = m; // with a duplicated key the last one is the one that counts
            }
        }
        return found;
    }

    // ---- inserting --------------------------------------------------------------------------------------------------

    /**
     * Adds {@code entry} as the last member of the object whose braces are at {@code open} and {@code close}.
     * A trailing comma the file already had stays where it was, after the new member.
     */
    private static String insert(String text, int open, int close, List<Member> members, String entry, String indent, String eol) {
        if (!members.isEmpty()) {
            Member lastMember = members.get(members.size() - 1);
            boolean multiline = lineIndent(text, members.get(0).nameStart) != null;
            String glue = multiline ? "," + eol + indent : ", ";
            return text.substring(0, lastMember.valueEnd) + glue + entry + text.substring(lastMember.valueEnd);
        }
        String between = text.substring(open + 1, close);
        String closingIndent = leading(text, open);
        String block = eol + indent + entry + eol + closingIndent;
        if (between.trim().isEmpty()) {
            return text.substring(0, open + 1) + block + text.substring(close);
        }
        return text.substring(0, open + 1) + eol + indent + entry + text.substring(open + 1);
    }

    /** How the entries of the object are indented: as the existing ones are, else one level in from the object's line. */
    private static String memberIndent(String text, int open, int close, List<Member> members) {
        if (!members.isEmpty()) {
            String indent = lineIndent(text, members.get(0).nameStart);
            if (indent != null) {
                return indent;
            }
        }
        return leading(text, open) + "  ";
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    private static String render(JsonNode value, String baseIndent, String eol) {
        DefaultIndenter indenter = new DefaultIndenter("  ", eol);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withObjectEmptySeparator("")
                        .withArrayEmptySeparator(""));
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        try {
            String json = MAPPER.writer((PrettyPrinter) printer).writeValueAsString(value);
            return json.replace(eol, eol + baseIndent);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- text helpers -----------------------------------------------------------------------------------------------

    /** The blanks before {@code offset} on its line, or {@code null} if something else is there too. */
    private static String lineIndent(String text, int offset) {
        int start = text.lastIndexOf('\n', offset - 1) + 1;
        String prefix = text.substring(start, offset);
        return prefix.chars().allMatch(c -> c == ' ' || c == '\t') ? prefix : null;
    }

    /** The indentation of the line {@code offset} is on, whatever else is on it. */
    private static String leading(String text, int offset) {
        int start = text.lastIndexOf('\n', offset - 1) + 1;
        int end = start;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && isSpace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipBlanks(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int offset(long charOffset) {
        return (int) charOffset;
    }

    private static void verify(String result, JsonNode expected) {
        JsonNode actual;
        try {
            actual = MAPPER.readTree(result);
        } catch (IOException e) {
            throw new IllegalStateException("Internal error: the edited file would not be valid JSON: " + firstLine(e.getMessage()), e);
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Internal error: the edit would have changed more than the entry it was for");
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
