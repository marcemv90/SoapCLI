package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeaderMapAndMaskerTest {

    @Test
    void namesAreCaseInsensitiveAndKeepFirstSpellingAndPosition() {
        HeaderMap h = new HeaderMap();
        h.put("Content-Type", "a");
        h.put("Accept", "b");
        h.put("content-type", "c");
        assertEquals("c", h.get("CONTENT-TYPE"));
        assertEquals(2, h.size());
        assertEquals("Content-Type", h.entries().get(0).getKey());
        assertEquals("c", h.entries().get(0).getValue());
    }

    @Test
    void removeAndCopyAreIndependent() {
        HeaderMap h = new HeaderMap().put("A", "1");
        HeaderMap copy = h.copy();
        copy.remove("a");
        assertNull(copy.get("A"));
        assertEquals("1", h.get("A"));
        assertFalse(h.isEmpty());
    }

    @Test
    void masksCredentialsButKeepsTheScheme() {
        assertEquals("Basic ****", SecretMasker.maskHeader("Authorization", "Basic YWxpY2U6cHc="));
        assertEquals("Bearer ****", SecretMasker.maskHeader("proxy-authorization", "Bearer abc"));
        assertEquals("****", SecretMasker.maskHeader("Cookie", "sid=1"));
        assertEquals("****", SecretMasker.maskHeader("X-Api-Key", "k"));
        assertEquals("text/xml", SecretMasker.maskHeader("Content-Type", "text/xml"));
        assertTrue(SecretMasker.isSecretName("keystore.password"));
        assertFalse(SecretMasker.isSecretName("endpoint"));
    }
}
