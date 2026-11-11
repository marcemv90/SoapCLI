package io.github.marcemv90.soapcli.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code cid:XXX} references in the <em>character data</em> of a SOAP message and replaces them, leaving tags,
 * attributes, comments and everything else byte-for-byte as written.
 *
 * <p>A reference ends where the content-id ends: {@code cid:message1} does not match inside {@code cid:message10} or
 * {@code cid:message1-b}. (The MTOM code used to do a plain {@code String.replace}, which turned {@code message10}
 * into {@code message1} plus a stray {@code 0} and silently sent the wrong attachment.)
 */
public final class CidReplacer {

    /** Characters that can be part of a content-id (RFC 2392 / RFC 2045 tokens, roughly). */
    private static final String ID_CHARS = "A-Za-z0-9._~%@+\\-";

    /** Result of a replacement pass. */
    public static final class Result {
        public final String text;
        /** The CIDs that were actually referenced somewhere in the text. */
        public final Set<String> referenced;

        Result(String text, Set<String> referenced) {
            this.text = text;
            this.referenced = referenced;
        }
    }

    private CidReplacer() {
    }

    /**
     * @param xml         the SOAP message
     * @param cids        the content-ids to look for (without the {@code cid:} prefix)
     * @param replacement computes the replacement text for a CID; called at most once per CID
     * @param inCdata     whether CDATA sections are searched too (right for base64 text, wrong for XML elements)
     */
    public static Result replace(String xml, Collection<String> cids, Function<String, String> replacement,
                                 boolean inCdata) {
        Set<String> referenced = new LinkedHashSet<>();
        if (cids.isEmpty() || xml == null || xml.isEmpty()) {
            return new Result(xml, referenced);
        }

        List<String> sorted = new ArrayList<>(cids);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alternation = new StringBuilder();
        for (String cid : sorted) {
            if (alternation.length() > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(cid));
        }
        Pattern pattern = Pattern.compile(
                "(?<![" + ID_CHARS + "])cid:(" + alternation + ")(?![" + ID_CHARS + "])");

        Map<String, String> cache = new HashMap<>();
        StringBuilder out = new StringBuilder(xml.length());
        for (XmlScanner.Token token : XmlScanner.scan(xml)) {
            boolean eligible = token.type == XmlScanner.Type.TEXT || (inCdata && token.type == XmlScanner.Type.CDATA);
            if (!eligible) {
                out.append(xml, token.start, token.end);
                continue;
            }
            Matcher m = pattern.matcher(xml);
            m.region(token.start, token.end);
            m.useTransparentBounds(true);   // let the look-behind / look-ahead see the neighbouring '>' and '<'
            m.useAnchoringBounds(false);
            int last = token.start;
            while (m.find()) {
                String cid = m.group(1);
                referenced.add(cid);
                out.append(xml, last, m.start()).append(cache.computeIfAbsent(cid, replacement));
                last = m.end();
            }
            out.append(xml, last, token.end);
        }
        return new Result(out.toString(), referenced);
    }

    /** Whether {@code cid:<cid>} appears anywhere in the document, attributes included. */
    public static boolean mentions(String xml, String cid) {
        return Pattern.compile("(?<![" + ID_CHARS + "])cid:" + Pattern.quote(cid) + "(?![" + ID_CHARS + "])")
                .matcher(xml).find();
    }

    /** The XOP element that MTOM puts where the inline base64 would have been. */
    public static String xopInclude(String cid) {
        return "<inc:Include href=\"cid:" + XmlFormat.escape(cid)
                + "\" xmlns:inc=\"http://www.w3.org/2004/08/xop/include\"/>";
    }
}
