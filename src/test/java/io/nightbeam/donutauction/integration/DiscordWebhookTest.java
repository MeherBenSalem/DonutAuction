package io.nightbeam.donutauction.integration;

import be.seeseemelk.mockbukkit.MockBukkit;
import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.model.AuctionStatus;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void enchantmentsReadAsInGame() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 5);
        sword.addUnsafeEnchantment(Enchantment.MENDING, 1);
        List<String> lines = DiscordWebhook.details(sword);
        assertTrue(lines.contains("Sharpness V"), lines.toString());
        assertTrue(lines.contains("Mending"), lines.toString());
        assertEquals("X", DiscordWebhook.roman(10));
        assertEquals("255", DiscordWebhook.roman(255));
    }

    @Test
    void soldEmbedEscapesPlayerTextAndListsTheTrade() {
        ItemStack item = new ItemStack(Material.DIAMOND, 32);
        item.editMeta(meta -> meta.displayName(Component.text("Shiny \"*bold*\" \\ gems")));
        AuctionListing listing = new AuctionListing(UUID.randomUUID(), item, UUID.randomUUID(), 3200, 1L, 2L,
                AuctionStatus.SOLD, UUID.randomUUID(), 3L, true, 3L);
        DiscordWebhook.Post post = new DiscordWebhook.Post(DiscordWebhook.Kind.SOLD, listing, "$3,200.00", 3200, "Willow", "Kiri", null);

        String json = DiscordWebhook.json(post, new YamlConfiguration());

        assertTrue(json.contains("\"title\":\"Sold\""), json);
        // Quotes and backslashes are JSON-escaped; markdown characters are escaped for Discord.
        assertTrue(json.contains("**Shiny \\\"\\\\*bold\\\\*\\\" \\\\\\\\ gems** ×32"), json);
        assertTrue(json.contains("{\"name\":\"Price\",\"value\":\"$3,200.00\",\"inline\":true}"), json);
        assertTrue(json.contains("{\"name\":\"Seller\",\"value\":\"Willow\",\"inline\":true}"), json);
        assertTrue(json.contains("{\"name\":\"Buyer\",\"value\":\"Kiri\",\"inline\":true}"), json);
        assertTrue(json.contains("{\"name\":\"Type\",\"value\":\"Buy It Now\",\"inline\":true}"), json);
        assertTrue(json.contains("\"allowed_mentions\":{\"parse\":[]}"), json);
        assertFalse(json.contains("\"Ends\""), json);
    }
}
