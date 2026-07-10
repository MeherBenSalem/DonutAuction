package io.nightbeam.donutauction.listener;

import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.service.AuctionService;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final GuiManager guiManager;
    private final AuctionService auctionService;
    private final PlayerPreferenceManager preferenceManager;

    public PlayerQuitListener(GuiManager guiManager, AuctionService auctionService, PlayerPreferenceManager preferenceManager) {
        this.guiManager = guiManager;
        this.auctionService = auctionService;
        this.preferenceManager = preferenceManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // InventoryCloseEvent may also fire; cancelPendingSaleForPlayer is atomic/idempotent.
        auctionService.cancelPendingSaleForPlayer(player);
        guiManager.clear(player);
        preferenceManager.unload(player.getUniqueId());
    }
}
