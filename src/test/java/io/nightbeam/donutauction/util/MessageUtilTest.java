package io.nightbeam.donutauction.util;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageUtilTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static boolean anyClick(Component component) {
        if (component.clickEvent() != null) return true;
        return component.children().stream().anyMatch(MessageUtilTest::anyClick);
    }

    private static TextColor firstColor(Component component) {
        if (component.color() != null) return component.color();
        for (Component child : component.children()) {
            TextColor color = firstColor(child);
            if (color != null) return color;
        }
        return null;
    }

    private static boolean hasColor(Component component, TextColor color) {
        if (color.equals(component.color())) return true;
        return component.children().stream().anyMatch(child -> hasColor(child, color));
    }

    @Test
    void legacyCodesHexAndMiniMessageMix() {
        Component legacy = MessageUtil.parse("&aGreen");
        assertEquals("Green", plain(legacy));
        assertEquals(NamedTextColor.GREEN, firstColor(legacy));
        Component hex = MessageUtil.parse("&#ff8800Orange");
        assertEquals(TextColor.color(0xff8800), firstColor(hex));
        Component mixed = MessageUtil.parse("&6Gold <bold>and</bold> <#00ff00>green");
        assertEquals("Gold and green", plain(mixed));
        Component bungee = MessageUtil.parse("&x&1&2&3&4&5&6Hex");
        assertEquals(TextColor.color(0x123456), firstColor(bungee));
    }

    @Test
    void doubledAmpersandIsALiteral() {
        assertEquals("R&D &a", plain(MessageUtil.parse("R&&D &&a")));
    }

    @Test
    void placeholderValuesCannotInjectFormattingOrClicks() {
        String template = "&aListed %item% by %seller%";
        String result = MessageUtil.substitute(template, Map.of(
                "item", "<click:run_command:'/op Hacker'>Free diamonds</click>",
                "seller", "&4&lAdmin"));
        Component component = MessageUtil.parse(result);
        assertEquals("Listed <click:run_command:'/op Hacker'>Free diamonds</click> by &4&lAdmin", plain(component));
        assertFalse(anyClick(component));
    }

    @Test
    void trustedFragmentsKeepTheirTags() {
        String result = MessageUtil.substitute("You got %item%", Map.of("item", MessageUtil.trusted("<lang:item.minecraft.diamond>")));
        Component component = MessageUtil.parse(result);
        assertTrue(component.children().stream().anyMatch(child -> child instanceof net.kyori.adventure.text.TranslatableComponent)
                || component instanceof net.kyori.adventure.text.TranslatableComponent);
    }

    @Test
    void forgedTrustMarkersAreStripped() {
        // A player cannot type the per-boot marker, but even a lookalike prefix is treated as plain text.
        String escaped = MessageUtil.escape("deadbeef<red>x");
        assertFalse(plain(MessageUtil.parse(escaped)).isEmpty());
        assertNull(firstColor(MessageUtil.parse(escaped)));
    }

    @Test
    void substitutionIsSinglePass() {
        String result = MessageUtil.substitute("%a% %b%", Map.of("a", "%b%", "b", "x"));
        assertEquals("%b% x", plain(MessageUtil.parse(result)));
    }

    @Test
    void menuTextIsNotItalicAndLoreIsGray() {
        Component name = MessageUtil.noItalic(MessageUtil.parse("Plain"));
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        Component lore = MessageUtil.loreLine(MessageUtil.parse("Plain"));
        assertEquals(NamedTextColor.GRAY, lore.color());
        Component colored = MessageUtil.loreLine(MessageUtil.parse("<red>Red"));
        assertTrue(hasColor(colored, NamedTextColor.RED));
    }

    @Test
    void unknownTagsStayReadable() {
        assertEquals("a < b and <3", plain(MessageUtil.parse("a < b and <3")));
        assertTrue(ClickEvent.Action.RUN_COMMAND != null);
    }
}
