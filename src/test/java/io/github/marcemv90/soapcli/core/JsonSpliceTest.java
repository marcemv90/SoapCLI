package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSpliceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static final String COMMENTED = String.join("\n",
            "{",
            "  // Profiles for the acceptance and production environments",
            "  \"profiles\": {",
            "    /* shared by the others */",
            "    \"base\": { \"soapVersion\": \"1.1\" },",
            "",
            "    // the one used every day",
            "    \"acc\": {",
            "      \"endpoint\": \"https://acc.example.com/soap\", // not the production one",
            "      \"headers\": { \"X-Env\": \"acc\" },",
            "    },",
            "",
            "    \"prod\": { \"endpoint\": \"https://prod.example.com/soap\" }",
            "  },",
            "  \"other\": [1, 2, 3]",
            "}",
            "");

    @Test
    void replacingAnEntryKeepsEveryOtherByteOfTheFile() throws Exception {
        String out = JsonSplice.put(COMMENTED, "profiles", "acc", node("{\"endpoint\":\"https://new.example.com\",\"timeout\":30}"));
        String before = COMMENTED.substring(0, COMMENTED.indexOf("{\n      \"endpoint\""));
        String after = COMMENTED.substring(COMMENTED.indexOf("    \"prod\""));
        assertTrue(out.startsWith(before), out);
        assertTrue(out.endsWith(after), out);
        assertTrue(out.contains("// Profiles for the acceptance"), out);
        assertTrue(out.contains("/* shared by the others */"), out);
        assertTrue(out.contains("// the one used every day"), out);
        assertTrue(out.contains("\"endpoint\": \"https://new.example.com\""), out);
        assertTrue(out.contains("\"timeout\": 30"), out);
        assertFalse(out.contains("not the production one"), "a comment inside the replaced entry goes with it");
        assertEquals("https://new.example.com", JsonSplice.parse(out).get("profiles").get("acc").get("endpoint").asText());
        assertEquals("1.1", JsonSplice.parse(out).get("profiles").get("base").get("soapVersion").asText());
    }

    @Test
    void theNewEntryIsIndentedLikeTheOthers() throws Exception {
        String out = JsonSplice.put(COMMENTED, "profiles", "new", node("{\"endpoint\":\"http://n\",\"headers\":{\"A\":\"1\"}}"));
        assertTrue(out.contains("    \"prod\": { \"endpoint\": \"https://prod.example.com/soap\" },\n    \"new\": {\n      \"endpoint\": \"http://n\",\n"
                + "      \"headers\": {\n        \"A\": \"1\"\n      }\n    }\n  },"), out);
        assertTrue(out.contains("// the one used every day"));
        assertEquals(4, JsonSplice.parse(out).get("profiles").size());
    }

    @Test
    void anEntryAddedAfterATrailingCommaKeepsTheFilesStyle() throws Exception {
        String text = "{\n  \"profiles\": {\n    \"a\": {},\n    \"b\": {},\n  }\n}\n";
        String out = JsonSplice.put(text, "profiles", "c", node("{\"x\":1}"));
        assertEquals("{\n  \"profiles\": {\n    \"a\": {},\n    \"b\": {},\n    \"c\": {\n      \"x\": 1\n    },\n  }\n}\n", out);
    }

    @Test
    void anEntryCanBeAddedToAnEmptyContainer() throws Exception {
        for (String empty : new String[]{"{\"profiles\":{}}", "{ \"profiles\": { } }", "{\n  \"profiles\": {\n  }\n}\n"}) {
            String out = JsonSplice.put(empty, "profiles", "a", node("{\"endpoint\":\"http://a\"}"));
            assertEquals("http://a", JsonSplice.parse(out).get("profiles").get("a").get("endpoint").asText(), out);
        }
        String commented = "{\n  \"profiles\": {\n    // nothing yet\n  }\n}\n";
        String out = JsonSplice.put(commented, "profiles", "a", node("{}"));
        assertTrue(out.contains("// nothing yet"), out);
        assertEquals(1, JsonSplice.parse(out).get("profiles").size());
    }

    @Test
    void theContainerIsCreatedWhenTheFileHasNone() throws Exception {
        String out = JsonSplice.put("{\n  \"note\": \"hi\"\n}\n", "profiles", "a", node("{\"x\":1}"));
        assertEquals("{\n  \"note\": \"hi\",\n  \"profiles\": {\n    \"a\": {\n      \"x\": 1\n    }\n  }\n}\n", out);
        assertEquals("{\n  \"profiles\": {\n    \"a\": {\n      \"x\": 1\n    }\n  }\n}", JsonSplice.put("{}", "profiles", "a", node("{\"x\":1}")));
    }

    @Test
    void anEmptyOrMissingFileBecomesANewOne() throws Exception {
        for (String blank : new String[]{"", "  \n", "\r\n"}) {
            String out = JsonSplice.put(blank, "profiles", "a", node("{\"x\":1}"));
            assertEquals(1, JsonSplice.parse(out).get("profiles").get("a").get("x").asInt(), out);
            assertTrue(out.endsWith("}" + (blank.contains("\r") ? "\r\n" : "\n")), out);
        }
        assertEquals("", JsonSplice.remove("", "profiles", "a"));
    }

    @Test
    void removingAnEntryLeavesNoBlankLineAndNoStrayComma() throws Exception {
        String text = "{\n  \"profiles\": {\n    \"a\": { \"x\": 1 },\n    \"b\": { \"x\": 2 },\n    \"c\": { \"x\": 3 }\n  }\n}\n";
        assertEquals("{\n  \"profiles\": {\n    \"a\": { \"x\": 1 },\n    \"c\": { \"x\": 3 }\n  }\n}\n", JsonSplice.remove(text, "profiles", "b"));
        assertEquals("{\n  \"profiles\": {\n    \"b\": { \"x\": 2 },\n    \"c\": { \"x\": 3 }\n  }\n}\n", JsonSplice.remove(text, "profiles", "a"));
        assertEquals("{\n  \"profiles\": {\n    \"a\": { \"x\": 1 },\n    \"b\": { \"x\": 2 }\n  }\n}\n", JsonSplice.remove(text, "profiles", "c"));
        String only = "{\n  \"profiles\": {\n    \"a\": { \"x\": 1 }\n  }\n}\n";
        assertEquals("{\n  \"profiles\": {\n  }\n}\n", JsonSplice.remove(only, "profiles", "a"));
        assertEquals(text, JsonSplice.remove(text, "profiles", "nope"));
        assertEquals(text, JsonSplice.remove(text, "elsewhere", "a"));
    }

    @Test
    void removingKeepsTheCommentsAroundIt() throws Exception {
        String out = JsonSplice.remove(COMMENTED, "profiles", "acc");
        assertTrue(out.contains("// Profiles for the acceptance"), out);
        assertTrue(out.contains("/* shared by the others */"), out);
        assertFalse(out.contains("acc.example.com"), out);
        JsonNode profiles = JsonSplice.parse(out).get("profiles");
        assertEquals(2, profiles.size());
        assertTrue(profiles.has("base") && profiles.has("prod"));
        assertEquals(3, JsonSplice.parse(out).get("other").size());
    }

    @Test
    void theLastEntryTakesItsCommaWithIt() throws Exception {
        String out = JsonSplice.remove(COMMENTED, "profiles", "prod");
        JsonNode profiles = JsonSplice.parse(out).get("profiles");
        assertEquals(2, profiles.size());
        assertTrue(out.contains("    },\n  },\n  \"other\"") || out.contains("    }\n  },\n  \"other\""), out);
    }

    @Test
    void aOneLineFileIsEditedInPlace() throws Exception {
        String text = "{\"profiles\":{\"a\":{\"x\":1},\"b\":{\"x\":2}},\"other\":true}";
        String added = JsonSplice.put(text, "profiles", "c", node("{\"x\":3}"));
        assertTrue(added.startsWith("{\"profiles\":{\"a\":{\"x\":1},\"b\":{\"x\":2}, \"c\": {"), added);
        assertEquals(3, JsonSplice.parse(added).get("profiles").size());
        assertTrue(JsonSplice.parse(added).get("other").asBoolean());

        String replaced = JsonSplice.put(text, "profiles", "a", node("{\"x\":9}"));
        assertEquals(9, JsonSplice.parse(replaced).get("profiles").get("a").get("x").asInt());
        assertTrue(replaced.endsWith("\"b\":{\"x\":2}},\"other\":true}"), replaced);

        String removed = JsonSplice.remove(text, "profiles", "b");
        assertEquals("{\"profiles\":{\"a\":{\"x\":1}},\"other\":true}", removed);
        assertEquals("{\"profiles\":{\"b\":{\"x\":2}},\"other\":true}", JsonSplice.remove(text, "profiles", "a"));
    }

    @Test
    void lineEndingsAreKept() throws Exception {
        String crlf = "{\r\n  \"profiles\": {\r\n    \"a\": { \"x\": 1 }\r\n  }\r\n}\r\n";
        String out = JsonSplice.put(crlf, "profiles", "b", node("{\"x\":{\"y\":2}}"));
        assertFalse(out.replace("\r\n", "").contains("\n"), "no bare line feed was introduced: " + out.replace("\r", "\\r"));
        assertTrue(out.contains("\"b\": {\r\n      \"x\": {\r\n        \"y\": 2\r\n      }\r\n    }\r\n  }"), out.replace("\r", "\\r"));
        String removed = JsonSplice.remove(out, "profiles", "b");
        assertEquals(crlf, removed);
    }

    @Test
    void bracesQuotesCommentsAndEmojiInsideStringsDoNotConfuseIt() throws Exception {
        String text = "{ // } { \n\"profiles\": {\n"
                + "  \"a\": { \"v\": \"}{ \\\" // not a comment /* nor this */\", \"e\": \"😀\" },\n"
                + "  \"b\": { \"v\": \"x\" } // } trailing {\n"
                + "}, \"after\": \"😀}\" }";
        // the first line ends in a comment that holds braces
        String out = JsonSplice.put(text, "profiles", "b", node("{\"v\":\"y\"}"));
        assertEquals("y", JsonSplice.parse(out).get("profiles").get("b").get("v").asText());
        assertEquals("}{ \" // not a comment /* nor this */", JsonSplice.parse(out).get("profiles").get("a").get("v").asText());
        assertEquals("😀}", JsonSplice.parse(out).get("after").asText());
        String removed = JsonSplice.remove(text, "profiles", "a");
        assertFalse(JsonSplice.parse(removed).get("profiles").has("a"));
        assertEquals("😀}", JsonSplice.parse(removed).get("after").asText());
        String added = JsonSplice.put(text, "profiles", "😀", node("{}"));
        assertTrue(JsonSplice.parse(added).get("profiles").has("😀"));
    }

    @Test
    void valuesOfEveryKindRoundTrip() throws Exception {
        JsonNode value = node("{\"s\":\"a\\\"b\\\\c\\n\\u00e9\",\"n\":30,\"d\":1.5,\"t\":true,\"z\":null,\"o\":{},\"arr\":[],\"list\":[\"x\",{\"k\":\"v\"}]}");
        String out = JsonSplice.put("{\"profiles\":{}}", "profiles", "p", value);
        assertEquals(value, JsonSplice.parse(out).get("profiles").get("p"));
        String again = JsonSplice.put(out, "profiles", "p", value);
        assertEquals(out, again, "saving the same thing twice changes nothing");
    }

    @Test
    void aKeyThatNeedsEscapingIsQuoted() throws Exception {
        String out = JsonSplice.put("{\"profiles\":{}}", "profiles", "we\"ird\\name", node("{}"));
        assertTrue(JsonSplice.parse(out).get("profiles").has("we\"ird\\name"), out);
        assertTrue(JsonSplice.has(out, "profiles", "we\"ird\\name"));
        assertFalse(JsonSplice.has(out, "profiles", "other"));
        assertFalse(JsonSplice.has("", "profiles", "a"));
    }

    @Test
    void aDuplicatedKeyEditsTheOneThatCounts() throws Exception {
        String text = "{\"profiles\":{\"a\":{\"x\":1},\"a\":{\"x\":2}}}";
        String out = JsonSplice.put(text, "profiles", "a", node("{\"x\":3}"));
        assertEquals(3, JsonSplice.parse(out).get("profiles").get("a").get("x").asInt());
        assertTrue(out.contains("\"x\":1"), "the earlier duplicate is untouched: " + out);
    }

    @Test
    void thingsThatAreNotTheExpectedShapeAreRefused() {
        assertThrows(ConfigException.class, () -> JsonSplice.put("[1,2]", "profiles", "a", node("{}")));
        assertThrows(ConfigException.class, () -> JsonSplice.put("{ not json", "profiles", "a", node("{}")));
        assertThrows(ConfigException.class, () -> JsonSplice.remove("{\"profiles\": ", "profiles", "a"));
        assertThrows(ConfigException.class, () -> JsonSplice.parse("{ nope"));
    }

    @Test
    void aContainerThatIsNotAnObjectIsRefusedRatherThanDuplicated() {
        String text = "{\"profiles\": [1]}";
        assertThrows(ConfigException.class, () -> JsonSplice.put(text, "profiles", "a", node("{}")));
        assertThrows(ConfigException.class, () -> JsonSplice.remove(text, "profiles", "a"));
        assertFalse(JsonSplice.has(text, "profiles", "a"));
    }
}
