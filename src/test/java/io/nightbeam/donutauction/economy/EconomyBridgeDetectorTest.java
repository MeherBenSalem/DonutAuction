package io.nightbeam.donutauction.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EconomyBridgeDetectorTest {

    @Test
    void prefersEnabledVaultUnlockedWhenClassicVaultMissing() {
        Set<String> installed = Set.of("VaultUnlocked");
        Set<String> enabled = Set.of("VaultUnlocked");

        Optional<String> bridge = EconomyBridgeDetector.findInstalledBridgeName(
                installed::contains,
                enabled::contains
        );

        assertTrue(bridge.isPresent());
        assertEquals("VaultUnlocked", bridge.get());
        assertEquals("VaultUnlocked", EconomyBridgeDetector.describeBridgeName(bridge));
    }

    @Test
    void prefersEnabledVaultWhenBothArePresent() {
        Set<String> installed = Set.of("Vault", "VaultUnlocked");
        Set<String> enabled = Set.of("Vault", "VaultUnlocked");

        Optional<String> bridge = EconomyBridgeDetector.findInstalledBridgeName(
                installed::contains,
                enabled::contains
        );

        assertTrue(bridge.isPresent());
        assertEquals("Vault", bridge.get());
    }

    @Test
    void fallsBackToDisabledBridgeWhenNoneEnabled() {
        Map<String, Boolean> installed = Map.of("VaultUnlocked", true);
        Map<String, Boolean> enabled = Map.of("VaultUnlocked", false);

        Optional<String> bridge = EconomyBridgeDetector.findInstalledBridgeName(
                name -> Boolean.TRUE.equals(installed.get(name)),
                name -> Boolean.TRUE.equals(enabled.get(name))
        );

        assertTrue(bridge.isPresent());
        assertEquals("VaultUnlocked", bridge.get());
    }

    @Test
    void reportsMissingWhenNoKnownBridgeExists() {
        Optional<String> bridge = EconomyBridgeDetector.findInstalledBridgeName(
                name -> false,
                name -> false
        );

        assertTrue(bridge.isEmpty());
        assertFalse(bridge.isPresent());
        assertEquals(
                "none (looking for Vault or VaultUnlocked)",
                EconomyBridgeDetector.describeBridgeName(bridge)
        );
    }
}
