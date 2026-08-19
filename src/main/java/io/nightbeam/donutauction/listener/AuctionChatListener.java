package io.nightbeam.donutauction.listener;

import io.nightbeam.donutauction.gui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class AuctionChatListener implements Listener {

    private final GuiManager guiManager;

    public AuctionChatListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!guiManager.isAwaitingSearch(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String query = event.getMessage();
        guiManager.plugin().schedulerAdapter().runEntity(player, () -> guiManager.handleSearchInput(player, query));
    }
}
