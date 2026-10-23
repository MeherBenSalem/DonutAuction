package io.nightbeam.donutauction.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceParserTest {

    @ParameterizedTest
    @CsvSource({
            "250, 250", "1234.567, 1234.567", "'1,250', 1250", "'12,345,678.5', 12345678.5", "1k, 1000", "1.5K, 1500",
            "2.25m, 2250000", "3b, 3000000000", "1t, 1000000000000", "' 7.5k ', 7500", "$1k, 1000", ".5k, 500", "1_000, 1000"
    })
    void readsPlainAndShorthandPrices(String input, double expected) {
        assertEquals(expected, PriceParser.parse(input, true).orElseThrow(), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "NaN", "Infinity", "-1", "-1k", "10d", "1e5", "0x10", "k", "1kk", "1,23", "12,34,567", "abc", "1.2.3"})
    void rejectsEverythingElse(String input) {
        assertTrue(PriceParser.parse(input, true).isEmpty(), input);
    }

    @Test
    void shorthandCanBeTurnedOff() {
        assertTrue(PriceParser.parse("1k", false).isEmpty());
        assertEquals(1000.0D, PriceParser.parse("1000", false).orElseThrow());
    }

    @Test
    void nullIsEmpty() {
        assertTrue(PriceParser.parse(null, true).isEmpty());
    }
}
