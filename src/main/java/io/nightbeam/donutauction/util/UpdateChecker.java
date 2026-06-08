package io.nightbeam.donutauction.util;

import io.nightbeam.donutauction.AuctionHousePlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateChecker implements Listener {

    private static final String PROJECT_ID = "8XgyeSRH";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/donutauction";

    private final AuctionHousePlugin plugin;
    private final HttpClient httpClient;
    private final AtomicReference<String> latestVersion = new AtomicReference<>();
    private volatile boolean updateAvailable = false;
    private volatile long lastCheckTime = 0;

    public UpdateChecker(AuctionHousePlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void checkNow() {
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) {
            return;
        }

        long intervalHours = plugin.getConfig().getLong("update-checker.check-interval-hours", 12);
        long intervalMillis = intervalHours * 3_600_000L;
        long now = System.currentTimeMillis();

        if (now - lastCheckTime < intervalMillis && lastCheckTime > 0) {
            return;
        }

        lastCheckTime = now;
        String currentVersion = plugin.getDescription().getVersion();

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Accept", "application/json")
                        .header("User-Agent", "DonutAuction/" + currentVersion)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Update check failed: HTTP " + response.statusCode());
                    return;
                }

                String body = response.body();
                String parsedVersion = parseLatestVersion(body);
                if (parsedVersion == null) {
                    plugin.getLogger().warning("Update check failed: could not parse version from response.");
                    return;
                }

                latestVersion.set(parsedVersion);

                if (isNewer(parsedVersion, currentVersion)) {
                    updateAvailable = true;
                    if (plugin.getConfig().getBoolean("update-checker.notify-console", true)) {
                        plugin.getLogger().info("");
                        plugin.getLogger().info("A new version is available!");
                        plugin.getLogger().info("");
                        plugin.getLogger().info("Current Version: " + currentVersion);
                        plugin.getLogger().info("Latest Version: " + parsedVersion);
                        plugin.getLogger().info("");
                        plugin.getLogger().info("Download:");
                        plugin.getLogger().info(DOWNLOAD_URL);
                        plugin.getLogger().info("");
                    }
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.FINE, "Update check failed (non-critical): " + exception.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable) {
            return;
        }
        if (!plugin.getConfig().getBoolean("update-checker.notify-admins", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("donutauction.update.notify")) {
            return;
        }

        String currentVersion = plugin.getDescription().getVersion();
        String latest = latestVersion.get();

        plugin.schedulerAdapter().runEntity(player, () -> {
            player.sendMessage(net.kyori.adventure.text.Component.empty());
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "A newer version of DonutAuction is available.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Current: " + currentVersion + "  Latest: " + latest, net.kyori.adventure.text.format.NamedTextColor.GRAY));
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    DOWNLOAD_URL, net.kyori.adventure.text.format.NamedTextColor.AQUA));
            player.sendMessage(net.kyori.adventure.text.Component.empty());
        });
    }

    private String parseLatestVersion(String jsonBody) {
        try {
            int firstVersionIndex = jsonBody.indexOf("\"version_number\":\"");
            if (firstVersionIndex == -1) {
                return null;
            }
            int start = firstVersionIndex + "\"version_number\":\"".length();
            int end = jsonBody.indexOf("\"", start);
            if (end == -1) {
                return null;
            }
            return jsonBody.substring(start, end);
        } catch (Exception exception) {
            return null;
        }
    }

    static boolean isNewer(String latest, String current) {
        try {
            int[] latestParts = parseVersion(latest);
            int[] currentParts = parseVersion(current);

            for (int i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
                int l = i < latestParts.length ? latestParts[i] : 0;
                int c = i < currentParts.length ? currentParts[i] : 0;
                if (l > c) return true;
                if (l < c) return false;
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private static int[] parseVersion(String version) {
        String cleaned = version.replaceAll("[^0-9.]", "");
        String[] parts = cleaned.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].isEmpty() ? "0" : parts[i]);
        }
        return result;
    }
}
