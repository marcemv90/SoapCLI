package io.github.marcemv90.soapcli.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Text-level XML helpers: pretty printing and clean-up of responses.
 *
 * <p>Pretty printing works on the {@link XmlScanner} token stream instead of a DOM + {@code Transformer}. That means:
 * <ul>
 *   <li>no XXE / external entity resolution is even possible (DOCTYPEs are only echoed, never processed);</li>
 *   <li>text, CDATA, comments, entity references, attribute quoting and namespace prefixes are never rewritten:
 *       only whitespace <em>between</em> elements changes;</li>
 *   <li>mixed content ({@code <p>hello <b>x</b></p>}) is left untouched because its whitespace is significant;</li>
 *   <li>malformed input is returned unchanged rather than half-formatted.</li>
 * </ul>
 */
public final class XmlFormat {

    private static final String INDENT = "  ";

    private XmlFormat() {
    }

    /** Escapes the five predefined XML entities so a value can be used as element text or attribute value. */
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Removes what is never legitimate in an XML document: a leading byte order mark and the control characters XML 1.0
     * forbids. Zero-width characters inside the text are data and are left alone.
     */
    public static String clean(String xml) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        StringBuilder sb = new StringBuilder(xml.length());
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (i == 0 && c == '\uFEFF') {
                continue;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Pretty prints {@code xml}; returns it unchanged if it is empty or not well-formed. */
    public static String prettyPrint(String xml) {
        if (xml == null || xml.isBlank()) {
            return xml;
        }
        String normalized = xml.replace("\r\n", "\n");
        List<Node> roots = parse(normalized);
        if (roots == null || roots.stream().noneMatch(n -> n.element)) {
            return xml; // malformed, or not XML at all (plain text, an HTML error page without tags...)
        }
        StringBuilder out = new StringBuilder(normalized.length() + normalized.length() / 4);
        for (Node node : roots) {
            print(node, 0, normalized, out);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static final class Node {
        final XmlScanner.Token token;      // the (start) token
        int end;                           // end offset of the whole element in the source
        int closeStart;                    // start offset of its closing tag
        boolean element;                   // true for tags
        final List<Node> children = new ArrayList<>();

        Node(XmlScanner.Token token) {
            this.token = token;
            this.end = token.end;
        }
    }

    private static List<Node> parse(String xml) {
        List<Node> roots = new ArrayList<>();
        List<Node> stack = new ArrayList<>();
        for (XmlScanner.Token t : XmlScanner.scan(xml)) {
            List<Node> target = stack.isEmpty() ? roots : stack.get(stack.size() - 1).children;
            if (t.type == XmlScanner.Type.TAG) {
                if (t.isEndTag()) {
                    if (stack.isEmpty()) {
                        return null;
                    }
                    Node open = stack.remove(stack.size() - 1);
                    if (!open.token.tagName().equals(t.tagName())) {
                        return null;
                    }
                    open.end = t.end;
                    open.closeStart = t.start;
                } else {
                    Node node = new Node(t);
                    node.element = true;
                    target.add(node);
                    if (!t.isSelfClosing()) {
                        stack.add(node);
                    }
                }
            } else {
                target.add(new Node(t));
            }
        }
        return stack.isEmpty() ? roots : null;
    }

    private static boolean isSignificantText(Node n) {
        return !n.element && ((n.token.type == XmlScanner.Type.TEXT && !n.token.isBlankText())
                || n.token.type == XmlScanner.Type.CDATA);
    }

    private static void print(Node node, int depth, String xml, StringBuilder out) {
        if (!node.element) {
            if (node.token.type == XmlScanner.Type.TEXT) {
                if (!node.token.isBlankText()) {
                    indent(out, depth).append(node.token.text().strip()).append('\n');
                }
                return;
            }
            indent(out, depth).append(node.token.text()).append('\n');
            return;
        }

        boolean selfClosing = node.token.isSelfClosing();
        if (selfClosing || node.children.isEmpty()) {
            indent(out, depth).append(xml, node.token.start, node.end).append('\n');
            return;
        }

        boolean hasSignificantText = false;
        boolean hasStructure = false;
        for (Node child : node.children) {
            if (isSignificantText(child)) {
                hasSignificantText = true;
            } else if (child.element || child.token.type != XmlScanner.Type.TEXT) {
                hasStructure = true;
            }
        }

        // Text-only, whitespace-only or mixed content: the whitespace is data, so print the element verbatim.
        if (hasSignificantText || !hasStructure) {
            indent(out, depth).append(xml, node.token.start, node.end).append('\n');
            return;
        }

        indent(out, depth).append(node.token.text()).append('\n');
        for (Node child : node.children) {
            print(child, depth + 1, xml, out);
        }
        indent(out, depth).append(xml, node.closeStart, node.end).append('\n');
    }

    private static StringBuilder indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append(INDENT);
        }
        return out;
    }
}
