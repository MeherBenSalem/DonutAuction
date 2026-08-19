package io.nightbeam.donutauction.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void isNewerComparesSemver() {
        assertTrue(UpdateChecker.isNewer("1.4.0", "1.3.1"));
        assertFalse(UpdateChecker.isNewer("1.4.0", "1.4"));
        assertFalse(UpdateChecker.isNewer("1.3.1", "1.3.1"));
        assertFalse(UpdateChecker.isNewer("1.3.1", "1.4.0"));
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"));
    }

    @Test
    void parsePicksNewestReleaseAndIgnoresSnapshots() {
        String json = """
                [
                  {"version_number":"1.5.0-beta","version_type":"beta"},
                  {"version_number":"1.3.1","version_type":"release"},
                  {"version_number":"1.4.0","version_type":"release"},
                  {"version_number":"1.4.1-SNAPSHOT","version_type":"alpha"}
                ]
                """;
        assertEquals("1.4.0", UpdateChecker.parseLatestReleaseVersion(json));
    }

    @Test
    void parseReturnsNullWhenNoRelease() {
        String json = """
                [{"version_number":"1.5.0-rc.1","version_type":"beta"}]
                """;
        assertNull(UpdateChecker.parseLatestReleaseVersion(json));
        assertNull(UpdateChecker.parseLatestReleaseVersion(""));
        assertNull(UpdateChecker.parseLatestReleaseVersion(null));
    }
}
