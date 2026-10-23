package io.nightbeam.donutauction.listener;

import io.nightbeam.donutauction.gui.GuiManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Answers to chat questions (search, exact price, custom bid). Uses Paper's chat event, which every
 * supported version fires, at the lowest priority so the answer never reaches public chat.
 */
public final class AuctionChatListener implements Listener {

    private final GuiManager guiManager;

    public AuctionChatListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!guiManager.isAwaitingInput(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.handleSearchInput(player, text));
    }
}
