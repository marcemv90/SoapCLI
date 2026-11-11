package io.github.marcemv90.soapcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArgParserAndOptionsTest {

    @Test
    void acceptsBothValueSyntaxesAndLeadingDashesInValues() {
        List<ArgParser.Occurrence> occ = ArgParser.parse(new String[]{"--endpoint=http://x/y?a=b", "--header", "--weird: v", "--arg=A=B=C", "--mtom"});
        assertEquals("http://x/y?a=b", occ.get(0).value);
        assertEquals("--weird: v", occ.get(1).value);
        assertEquals("A=B=C", occ.get(2).value);
        assertEquals(null, occ.get(3).value);
    }

    @Test
    void rejectsUnknownAndIncompleteOptions() {
        assertThrows(UsageException.class, () -> ArgParser.parse(new String[]{"--nope"}));
        assertThrows(UsageException.class, () -> ArgParser.parse(new String[]{"positional"}));
        assertThrows(UsageException.class, () -> ArgParser.parse(new String[]{"--endpoint"}));
        assertThrows(UsageException.class, () -> ArgParser.parse(new String[]{"--mtom=yes"}));
    }

    @Test
    void aValueThatLooksLikeAnotherOptionIsStillAValue() {
        // the old code searched the whole argument list for "--help"
        List<ArgParser.Occurrence> occ = ArgParser.parse(new String[]{"--header", "--help"});
        assertEquals(1, occ.size());
        assertEquals("--help", occ.get(0).value);
    }

    @Test
    void everyOptionNameIsUniqueAndDocumentedInTheHelp() {
        Set<String> seen = new HashSet<>();
        String help = Options.usage();
        for (OptionSpec spec : Options.ALL) {
            for (String name : spec.names) {
                assertTrue(seen.add(name), "duplicate option " + name);
                assertTrue(help.contains(name), name + " missing from --help");
            }
            assertTrue(!spec.help.isEmpty());
        }
    }

    @Test
    void themeNamesAcceptAllSpellings() {
        assertEquals(ColorTheme.SOLARIZED_DARK, ColorTheme.parse("solarized-dark"));
        assertEquals(ColorTheme.SOLARIZED_DARK, ColorTheme.parse("Solarized_Dark"));
        assertEquals(ColorTheme.SOLARIZED_DARK, ColorTheme.parse("solarizeddark"));
        assertEquals(ColorTheme.NORD, ColorTheme.parse(" NORD "));
        assertThrows(UsageException.class, () -> ColorTheme.parse("neon"));
    }

    @Test
    void ansiSupportOnWindowsDependsOnTheTerminal() {
        java.util.Map<String, String> none = new java.util.HashMap<>();
        assertTrue(Terminal.ansiSupported(none, "Linux"));
        assertTrue(!Terminal.ansiSupported(none, "Windows 11"));
        none.put("WT_SESSION", "x");
        assertTrue(Terminal.ansiSupported(none, "Windows 11"));
    }
}
