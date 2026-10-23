package io.nightbeam.auctionlab;

import io.nightbeam.donutauction.api.DonutAuctionApi;
import io.nightbeam.donutauction.model.ListingType;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import io.nightbeam.donutauction.model.AuctionListing;
import java.util.Comparator;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only fixture. Registers an in-memory Vault economy before DonutAuctionHouse enables and
 * exposes console helpers the smoke runner uses: money, balance, give, sudo and report lines that
 * start with "[LAB]" so the runner can parse them.
 */
public final class AuctionLabPlugin extends JavaPlugin {

    private final LabEconomy economy = new LabEconomy();

    @Override
    public void onLoad() {
        // Vault's ServicesManager is Bukkit's own, so the economy can be registered before any plugin enables.
        Bukkit.getServicesManager().register(net.milkbowl.vault.economy.Economy.class, economy, this, ServicePriority.Highest);
    }

    @Override
    public void onEnable() {
        report("fixture enabled on " + Bukkit.getName() + " " + Bukkit.getBukkitVersion());
    }

    private static void enchant(ItemStack item, String key, int level) {
        org.bukkit.enchantments.Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (enchantment != null) item.addUnsafeEnchantment(enchantment, level);
    }

    /** Enchantments, names and shulker contents, so the market looks like a real one. */
    private static ItemStack decorate(ItemStack item, Random random) {
        switch (item.getType().name()) {
            case "DIAMOND_SWORD" -> {
                enchant(item, "sharpness", 5); enchant(item, "looting", 3); enchant(item, "unbreaking", 3); enchant(item, "mending", 1);
                item.editMeta(meta -> meta.displayName(Component.text("Dawnbreaker", TextColor.color(0xFFB23F)).decoration(TextDecoration.ITALIC, false)));
            }
            case "NETHERITE_PICKAXE" -> { enchant(item, "efficiency", 5); enchant(item, "fortune", 3); enchant(item, "unbreaking", 3); enchant(item, "mending", 1); }
            case "NETHERITE_CHESTPLATE" -> { enchant(item, "protection", 4); enchant(item, "unbreaking", 3); enchant(item, "mending", 1); }
            case "TRIDENT" -> { enchant(item, "loyalty", 3); enchant(item, "channeling", 1); enchant(item, "impaling", 5); }
            case "ELYTRA" -> { enchant(item, "unbreaking", 3); enchant(item, "mending", 1); }
            case "SHULKER_BOX" -> item.editMeta(meta -> {
                if (meta instanceof BlockStateMeta state && state.getBlockState() instanceof ShulkerBox box) {
                    Material[] contents = {Material.DIAMOND_BLOCK, Material.GOLDEN_APPLE, Material.ENDER_PEARL, Material.EMERALD_BLOCK, Material.NETHERITE_SCRAP};
                    for (int i = 0; i < 12 + random.nextInt(14); i++) box.getInventory().setItem(i, new ItemStack(contents[i % contents.length], 1 + random.nextInt(16)));
                    state.setBlockState(box);
                }
                meta.displayName(Component.text("Starter Kit", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            });
            default -> { }
        }
        return item;
    }

    private void report(String line) {
        getLogger().info("[LAB] " + line);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "money" -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    economy.set(target.getUniqueId(), Double.parseDouble(args[2]));
                    report("money " + args[1] + " " + economy.balance(target.getUniqueId()));
                }
                case "balance" -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                    report("balance " + args[1] + " " + economy.balance(target.getUniqueId()));
                }
                case "give" -> {
                    Player player = Bukkit.getPlayerExact(args[1]);
                    Material material = Material.matchMaterial(args[2]);
                    int amount = args.length > 3 ? Integer.parseInt(args[3]) : 1;
                    player.getScheduler().run(this, task -> {
                        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
                        report("give " + args[1] + " " + material + " " + amount);
                    }, null);
                }
                case "count" -> {
                    Player player = Bukkit.getPlayerExact(args[1]);
                    Material material = Material.matchMaterial(args[2]);
                    player.getScheduler().run(this, task -> {
                        int total = 0;
                        for (ItemStack stack : player.getInventory().getContents()) {
                            if (stack != null && stack.getType() == material) total += stack.getAmount();
                        }
                        report("count " + args[1] + " " + material + " " + total);
                    }, null);
                }
                case "auction" -> {
                    // lab auction <seller> <material> <amount> <starting bid> <seconds>: a short auction through the public API.
                    DonutAuctionApi api = DonutAuctionApi.get().orElseThrow();
                    OfflinePlayer seller = Bukkit.getOfflinePlayer(args[1]);
                    ItemStack item = new ItemStack(Material.matchMaterial(args[2]), Integer.parseInt(args[3]));
                    api.createListing(seller.getUniqueId(), args[1], item, Double.parseDouble(args[4]),
                                    Duration.ofSeconds(Long.parseLong(args[5])), ListingType.BID)
                            .whenComplete((listing, failure) -> report(failure == null ? "auction " + listing.auctionId()
                                    : "error auction -> " + failure));
                }
                case "api" -> {
                    DonutAuctionApi api = DonutAuctionApi.get().orElseThrow();
                    report("api active=" + api.activeListings().size() + " price=" + api.formatPrice(1234.5));
                }
                case "list" -> {
                    // lab list <seller> <material> <amount> <price> <seconds> [bin|bid]
                    DonutAuctionApi api = DonutAuctionApi.get().orElseThrow();
                    ItemStack item = new ItemStack(Material.matchMaterial(args[2]), Integer.parseInt(args[3]));
                    ListingType type = args.length > 6 && args[6].equalsIgnoreCase("bid") ? ListingType.BID : ListingType.BIN;
                    api.createListing(Bukkit.getOfflinePlayer(args[1]).getUniqueId(), args[1], item, Double.parseDouble(args[4]),
                                    Duration.ofSeconds(Long.parseLong(args[5])), type)
                            .whenComplete((listing, failure) -> report(failure == null ? "list " + listing.auctionId() : "error list -> " + failure));
                }
                case "order" -> {
                    // The first browser page in default order (newest first): MATERIAL:TYPE:ENCHANTED per slot.
                    DonutAuctionApi api = DonutAuctionApi.get().orElseThrow();
                    String order = api.activeListings().stream()
                            .sorted(Comparator.comparingLong(AuctionListing::listingTime).reversed().thenComparing(AuctionListing::auctionId))
                            .limit(45)
                            .map(listing -> listing.item().getType() + ":" + listing.type() + ":" + (listing.item().getEnchantments().isEmpty() ? "-" : "E")
                                    + ":" + listing.sellerName())
                            .collect(Collectors.joining(","));
                    report("order " + order);
                }
                case "seed" -> {
                    // lab seed <count>: a believable market from made-up sellers, for screenshots.
                    DonutAuctionApi api = DonutAuctionApi.get().orElseThrow();
                    int count = Integer.parseInt(args[1]);
                    Random random = new Random(42);
                    List<String> sellers = List.of("Kiri", "BlazeKing", "Willow", "Pixelore", "Draxen", "Mossbeard", "Aurelia", "TheBaker");
                    Material[] pool = {Material.DIAMOND_SWORD, Material.ELYTRA, Material.NETHERITE_INGOT, Material.DIAMOND,
                            Material.ENCHANTED_GOLDEN_APPLE, Material.TOTEM_OF_UNDYING, Material.SHULKER_BOX, Material.NETHERITE_PICKAXE,
                            Material.BEACON, Material.TRIDENT, Material.GOLDEN_CARROT, Material.EXPERIENCE_BOTTLE, Material.ENDER_PEARL,
                            Material.NETHERITE_CHESTPLATE, Material.BLAZE_ROD, Material.OBSIDIAN, Material.SPONGE, Material.HEART_OF_THE_SEA,
                            Material.NAME_TAG, Material.CHERRY_LOG, Material.AMETHYST_SHARD, Material.SADDLE};
                    double[] unit = {9000, 185000, 12000, 95, 26000, 22000, 4500, 14000, 60000, 31000, 12, 55, 30, 18000, 140, 22,
                            900, 7000, 1200, 6, 15, 2500};
                    for (int i = 0; i < count; i++) {
                        int index = i % pool.length;
                        Material material = pool[index];
                        int amount = material.getMaxStackSize() == 1 ? 1 : (material.getMaxStackSize() == 16 ? 16 : 1 + random.nextInt(64));
                        ItemStack item = decorate(new ItemStack(material, amount), random);
                        double price = Math.max(10, Math.round(unit[index] * amount * (0.8 + random.nextDouble() * 0.5) / 10.0) * 10.0);
                        String name = sellers.get(i % sellers.size());
                        ListingType type = (i % 7 == 3) ? ListingType.BID : ListingType.BIN;
                        api.createListing(Bukkit.getOfflinePlayer(name).getUniqueId(), name, item, type == ListingType.BID ? Math.round(price * 0.6) : price,
                                Duration.ofHours(4 + random.nextInt(44)).plusMinutes(random.nextInt(59)), type);
                    }
                    report("seed " + count);
                }
                case "sudo" -> {
                    Player player = Bukkit.getPlayerExact(args[1]);
                    String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    player.getScheduler().run(this, task -> report("sudo " + args[1] + " " + line + " -> " + player.performCommand(line)), null);
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException exception) {
            report("error " + String.join(" ", args) + " -> " + exception);
        }
        return true;
    }
}
