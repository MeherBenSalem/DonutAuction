package io.nightbeam.donutauction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import java.util.function.Supplier;

/**
 * Sales tax (taken from the seller's proceeds when an item sells) and listing fees (paid when listing).
 * Permission tiers lower the tax: the lowest matching rate applies. Amounts are rounded to cents.
 */
public final class FeeCalculator {

    private final Supplier<FileConfiguration> config;

    public FeeCalculator(Supplier<FileConfiguration> config) {
        this.config = config;
    }

    public boolean taxEnabled() {
        return config.get().getBoolean("economy.sales-tax.enabled", false);
    }

    /** The seller's tax rate as a fraction (0.05 for 5%), from the default and their permission tiers. */
    public double taxRate(Player seller) {
        FileConfiguration cfg = config.get();
        if (!taxEnabled()) return 0.0D;
        double rate = clampPercent(cfg.getDouble("economy.sales-tax.percent", 0.0D));
        ConfigurationSection tiers = cfg.getConfigurationSection("economy.sales-tax.permissions");
        if (seller != null && tiers != null) {
            for (String permission : tiers.getKeys(true)) {
                if (tiers.isConfigurationSection(permission)) continue;
                if (seller.hasPermission(permission)) rate = Math.min(rate, clampPercent(tiers.getDouble(permission, rate * 100.0D)));
            }
        }
        return rate;
    }

    /** The default rate, used when a listing has no stored rate (older rows or server listings). */
    public double defaultTaxRate() {
        return taxEnabled() ? clampPercent(config.get().getDouble("economy.sales-tax.percent", 0.0D)) : 0.0D;
    }

    /** Tax on a sale. A negative stored rate means "use the default". */
    public double tax(double price, double storedRate) {
        if (!taxEnabled()) return 0.0D;
        double rate = storedRate < 0.0D ? defaultTaxRate() : Math.min(1.0D, storedRate);
        return money(price * rate);
    }

    /** Where collected tax goes: a player name or UUID, or blank to remove it from the economy. */
    public String taxAccount() {
        String account = config.get().getString("economy.sales-tax.deposit-to", "");
        return account == null ? "" : account.trim();
    }

    public double listingFee(Player seller, double price) {
        FileConfiguration cfg = config.get();
        if (!cfg.getBoolean("economy.listing-fee.enabled", false)) return 0.0D;
        String exempt = cfg.getString("economy.listing-fee.exempt-permission", "donutauction.fee.exempt");
        if (seller != null && exempt != null && !exempt.isBlank() && seller.hasPermission(exempt)) return 0.0D;
        double flat = Math.max(0.0D, cfg.getDouble("economy.listing-fee.flat", 0.0D));
        double percent = clampPercent(cfg.getDouble("economy.listing-fee.percent", 0.0D));
        double max = cfg.getDouble("economy.listing-fee.max", 0.0D);
        double fee = money(flat + price * percent);
        return max > 0.0D ? Math.min(fee, max) : fee;
    }

    public boolean refundFeeOnCancel() {
        return config.get().getBoolean("economy.listing-fee.refund-on-cancel", false);
    }

    static double clampPercent(double percent) {
        if (!Double.isFinite(percent)) return 0.0D;
        return Math.max(0.0D, Math.min(100.0D, percent)) / 100.0D;
    }

    static double money(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0D) return 0.0D;
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
