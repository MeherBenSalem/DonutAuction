package io.nightbeam.donutauction.util;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item names for messages. Renamed items keep their styled name; everything else is a translatable
 * component, so every player reads the name in their own client language.
 */
public final class ItemNames {

    private ItemNames() {
    }

    /** A trusted MiniMessage fragment for {@code %item%} placeholders. */
    public static String fragment(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) {
            return MessageUtil.trusted(MiniMessage.miniMessage().serialize(custom));
        }
        return MessageUtil.trusted("<lang:" + translationKey(item) + ">");
    }

    public static Component component(ItemStack item) {
        Component custom = customName(item);
        return custom != null ? custom : Component.translatable(translationKey(item));
    }

    /** English plain-text name for logs, webhooks and search. */
    public static String plain(ItemStack item) {
        Component custom = customName(item);
        if (custom != null) return PlainTextComponentSerializer.plainText().serialize(custom);
        return pretty(item.getType());
    }

    public static String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static Component customName(ItemStack item) {
        try {
            if (!item.hasItemMeta()) return null;
            ItemMeta meta = item.getItemMeta();
            return meta != null && meta.hasDisplayName() ? meta.displayName() : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static String translationKey(ItemStack item) {
        try {
            return item.translationKey();
        } catch (RuntimeException | LinkageError unavailable) {
            Material material = item.getType();
            String path = material.name().toLowerCase(Locale.ROOT);
            return (material.isBlock() ? "block.minecraft." : "item.minecraft.") + path;
        }
    }
}
