package io.nightbeam.donutauction.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeeCalculatorTest {

    private static YamlConfiguration config(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    private static Player player(String... permissions) {
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(false);
        for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true);
        return player;
    }

    @Test
    void taxIsOffByDefault() throws Exception {
        FeeCalculator fees = new FeeCalculator(() -> new YamlConfiguration());
        assertEquals(0.0D, fees.taxRate(player()));
        assertEquals(0.0D, fees.tax(1000, 0.5));
    }

    @Test
    void lowestMatchingDottedPermissionTierWins() throws Exception {
        YamlConfiguration config = config("""
                economy:
                  sales-tax:
                    enabled: true
                    percent: 5
                    permissions:
                      donutauction.tax.vip: 2.5
                      donutauction.tax.mvp: 1
                """);
        FeeCalculator fees = new FeeCalculator(() -> config);
        assertEquals(0.05D, fees.taxRate(player()), 1e-12);
        assertEquals(0.025D, fees.taxRate(player("donutauction.tax.vip")), 1e-12);
        assertEquals(0.01D, fees.taxRate(player("donutauction.tax.vip", "donutauction.tax.mvp")), 1e-12);
    }

    @Test
    void storedRateIsUsedAndAmountsRoundToCents() throws Exception {
        YamlConfiguration config = config("economy:\n  sales-tax:\n    enabled: true\n    percent: 5\n");
        FeeCalculator fees = new FeeCalculator(() -> config);
        assertEquals(12.35D, fees.tax(247.0D, 0.05D));
        assertEquals(2.47D, fees.tax(247.0D, 0.01D));
        // Rows from 1.x or the API have no stored rate: the default applies.
        assertEquals(12.35D, fees.tax(247.0D, -1.0D));
    }

    @Test
    void listingFeeHasFlatPercentCapAndExemption() throws Exception {
        YamlConfiguration config = config("""
                economy:
                  listing-fee:
                    enabled: true
                    flat: 10
                    percent: 1
                    max: 500
                """);
        FeeCalculator fees = new FeeCalculator(() -> config);
        assertEquals(20.0D, fees.listingFee(player(), 1000));
        assertEquals(500.0D, fees.listingFee(player(), 1_000_000));
        assertEquals(0.0D, fees.listingFee(player("donutauction.fee.exempt"), 1000));
    }

    @Test
    void badPercentagesAreClamped() {
        assertEquals(1.0D, FeeCalculator.clampPercent(250));
        assertEquals(0.0D, FeeCalculator.clampPercent(-5));
        assertEquals(0.0D, FeeCalculator.clampPercent(Double.NaN));
    }
}
