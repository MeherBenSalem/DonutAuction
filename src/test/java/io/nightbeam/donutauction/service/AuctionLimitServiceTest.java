package io.nightbeam.donutauction.service;

import io.nightbeam.donutauction.AuctionHousePlugin;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression: permission nodes contain dots, which YAML stores as nested sections. 1.x ignored every tier. */
class AuctionLimitServiceTest {

    private static AuctionLimitService service() {
        AuctionHousePlugin plugin = mock(AuctionHousePlugin.class);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                AuctionLimitServiceTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        when(plugin.getConfig()).thenReturn(config);
        return new AuctionLimitService(plugin);
    }

    private static Player player(String... permissions) {
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(false);
        for (String permission : permissions) when(player.hasPermission(permission)).thenReturn(true);
        return player;
    }

    @Test
    void defaultLimitWithoutTiers() {
        assertEquals(5, service().getEffectiveLimit(player()));
    }

    @Test
    void permissionTiersFromTheBundledConfigApply() {
        AuctionLimitService limits = service();
        assertEquals(25, limits.getEffectiveLimit(player("donutauction.limit.25")));
        assertEquals(100, limits.getEffectiveLimit(player("donutauction.limit.10", "donutauction.limit.100")));
        assertEquals(50, limits.getEffectiveLimit(player("donutauction.slots.50")));
        assertEquals(-1, limits.getEffectiveLimit(player("donutauction.limit.unlimited")));
        assertTrue(limits.canCreateListing(player("donutauction.limit.10"), 9));
        assertFalse(limits.canCreateListing(player("donutauction.limit.10"), 10));
    }
}
