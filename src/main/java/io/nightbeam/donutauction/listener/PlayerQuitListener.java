package io.nightbeam.donutauction.listener;

import io.nightbeam.donutauction.gui.GuiManager;
import io.nightbeam.donutauction.service.PlayerPreferenceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final GuiManager guiManager;
    private final PlayerPreferenceManager preferenceManager;

    public PlayerQuitListener(GuiManager guiManager, PlayerPreferenceManager preferenceManager) {
        this.guiManager = guiManager;
        this.preferenceManager = preferenceManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        guiManager.clear(player);
        preferenceManager.unload(player.getUniqueId());
    }
}
