package io.github.marcemv90.soapcli.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP header names are case-insensitive; this keeps insertion order and the spelling that was first used. */
public final class HeaderMap {

    private final Map<String, String[]> entries = new LinkedHashMap<>(); // lower-case name -> {name, value}

    public HeaderMap put(String name, String value) {
        String key = name.toLowerCase(Locale.ROOT);
        String[] existing = entries.get(key);
        // Keep the original spelling and position, but let the new value win.
        entries.put(key, new String[]{existing != null ? existing[0] : name, value});
        return this;
    }

    public String get(String name) {
        String[] e = entries.get(name.toLowerCase(Locale.ROOT));
        return e == null ? null : e[1];
    }

    public boolean contains(String name) {
        return entries.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public void remove(String name) {
        entries.remove(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Snapshot of the entries, in order, as (name, value) pairs. */
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> list = new ArrayList<>(entries.size());
        for (String[] e : entries.values()) {
            list.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e[0], e[1]));
        }
        return list;
    }

    public HeaderMap copy() {
        HeaderMap copy = new HeaderMap();
        for (Map.Entry<String, String> e : entries()) {
            copy.put(e.getKey(), e.getValue());
        }
        return copy;
    }
}
