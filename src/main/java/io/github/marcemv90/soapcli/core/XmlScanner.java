package io.github.marcemv90.soapcli.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, forgiving XML tokenizer.
 *
 * <p>It never builds a DOM and never resolves entities or DTDs, so it cannot be abused with XXE, and it keeps
 * every character of the input: concatenating {@code token.text()} of all tokens gives back the original string.
 * That makes it safe for "edit only some parts of the document" tasks (inlining attachments, placeholders) and for
 * syntax colouring, where re-serialising through a DOM would silently rewrite the message.
 *
 * <p>It does <em>not</em> validate: malformed input simply yields {@link Type#TEXT} tokens for what it cannot parse.
 */
public final class XmlScanner {

    public enum Type { TEXT, TAG, COMMENT, CDATA, PI, DOCTYPE }

    public static final class Token {
        public final Type type;
        public final int start;
        public final int end;
        private final String source;

        Token(Type type, int start, int end, String source) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.source = source;
        }

        public String text() {
            return source.substring(start, end);
        }

        /** For TAG tokens: {@code </name>}. */
        public boolean isEndTag() {
            return type == Type.TAG && source.charAt(start + 1) == '/';
        }

        /** For TAG tokens: {@code <name .../>}. */
        public boolean isSelfClosing() {
            return type == Type.TAG && !isEndTag() && source.charAt(end - 2) == '/';
        }

        /** For TAG tokens: the qualified name, e.g. {@code soapenv:Envelope}. */
        public String tagName() {
            if (type != Type.TAG) {
                return null;
            }
            int i = start + 1;
            if (source.charAt(i) == '/') {
                i++;
            }
            int j = i;
            while (j < end - 1) {
                char c = source.charAt(j);
                if (Character.isWhitespace(c) || c == '/' || c == '>') {
                    break;
                }
                j++;
            }
            return source.substring(i, j);
        }

        public boolean isBlankText() {
            if (type != Type.TEXT) {
                return false;
            }
            for (int i = start; i < end; i++) {
                if (!Character.isWhitespace(source.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return type + "[" + text() + "]";
        }
    }

    private XmlScanner() {
    }

    public static List<Token> scan(String xml) {
        List<Token> tokens = new ArrayList<>();
        int n = xml.length();
        int i = 0;
        while (i < n) {
            if (xml.charAt(i) != '<') {
                int j = xml.indexOf('<', i);
                if (j < 0) {
                    j = n;
                }
                tokens.add(new Token(Type.TEXT, i, j, xml));
                i = j;
                continue;
            }
            int end;
            Type type;
            if (xml.startsWith("<!--", i)) {
                type = Type.COMMENT;
                end = endOf(xml, "-->", i + 4);
            } else if (xml.startsWith("<![CDATA[", i)) {
                type = Type.CDATA;
                end = endOf(xml, "]]>", i + 9);
            } else if (xml.startsWith("<?", i)) {
                type = Type.PI;
                end = endOf(xml, "?>", i + 2);
            } else if (xml.startsWith("<!", i)) {
                type = Type.DOCTYPE;
                end = doctypeEnd(xml, i);
            } else {
                type = Type.TAG;
                end = tagEnd(xml, i);
            }
            if (end < 0) {
                // Unterminated construct: keep the rest as plain text instead of guessing.
                tokens.add(new Token(Type.TEXT, i, n, xml));
                break;
            }
            tokens.add(new Token(type, i, end, xml));
            i = end;
        }
        return tokens;
    }

    private static int endOf(String xml, String terminator, int from) {
        int j = xml.indexOf(terminator, from);
        return j < 0 ? -1 : j + terminator.length();
    }

    /** End (exclusive) of a start/end tag, honouring {@code >} inside quoted attribute values. */
    private static int tagEnd(String xml, int start) {
        char quote = 0;
        for (int i = start + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            } else if (c == '<') {
                return -1; // a new tag starts before this one closed: malformed
            }
        }
        return -1;
    }

    /** End (exclusive) of a {@code <!DOCTYPE ...>} including an internal subset in square brackets. */
    private static int doctypeEnd(String xml, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start + 2; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
