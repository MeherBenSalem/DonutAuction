package io.nightbeam.donutauction.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class PriceDisplayTest {
    private final YamlConfiguration config = new YamlConfiguration();

    private String compact(double price) {
        config.set("price-display.compact.enabled", true);
        return PriceDisplay.compact(price, config, value -> "$" + value, "coins");
    }

    @Test void missingConfigPreservesProviderExactly() {
        assertEquals("provider:1000000.125", PriceDisplay.compact(1000000.125, config, value -> "provider:" + value, "coins"));
    }

    @ParameterizedTest @CsvSource({"999,$999.0", "1000,1k coins", "999999,1M coins", "1000000,1M coins",
            "999949,999.9k coins", "999950,1M coins", "1234.567,1.2k coins", "12.345,$12.345",
            "1000000000000,1000000M coins", "-1250,-1.3k coins", "0,$0.0"})
    void boundariesAndRounding(double value, String expected) { assertEquals(expected, compact(value)); }

    @Test void localeCurrencyPrecisionAndSuffixes() {
        config.set("price-display.locale", "fr-FR");
        config.set("price-display.compact.precision", 2);
        config.set("price-display.compact.thousand-suffix", "K");
        config.set("price-display.compact.template", "€%amount%%suffix%");
        assertEquals("€1,23K", compact(1234.567));
    }

    @Test void exactAmountSurvivesRoundedProvider() {
        assertEquals("$1.23 (1234.56789)", PriceDisplay.precise(1234.56789, config, value -> "$1.23"));
        config.set("price-display.locale", "de-DE");
        assertEquals("€1 (0,0000001)", PriceDisplay.precise(0.0000001, config, value -> "€1"));
    }

    @Test void malformedConfigHasSafeDefaults() {
        config.set("price-display.locale", "bad_!");
        config.set("price-display.compact.precision", -100);
        config.set("price-display.compact.thousand-suffix", "");
        config.set("price-display.compact.template", "hidden price");
        assertEquals("1k coins", compact(1234));
    }

    @Test void largeFiniteValueAndInvalidInputs() {
        assertFalse(compact(Double.MAX_VALUE).contains("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> compact(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> compact(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> compact(Double.NEGATIVE_INFINITY));
    }
}
