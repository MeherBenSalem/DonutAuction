package io.nightbeam.donutauction.model;

import java.util.Objects;
import java.util.UUID;

public final class PlayerPreference {

    private final UUID playerId;
    private boolean fastBuyEnabled;
    private boolean fastSellEnabled;
    private int lastDurationHours;
    private String lastCategory;
    private double lastPrice;

    public PlayerPreference(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fastBuyEnabled = false;
        this.fastSellEnabled = false;
        this.lastDurationHours = 48;
        this.lastCategory = "ALL";
        this.lastPrice = 0.0D;
    }

    public PlayerPreference(UUID playerId, boolean fastBuyEnabled, boolean fastSellEnabled, int lastDurationHours, String lastCategory, double lastPrice) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fastBuyEnabled = fastBuyEnabled;
        this.fastSellEnabled = fastSellEnabled;
        this.lastDurationHours = Math.max(1, lastDurationHours);
        this.lastCategory = lastCategory == null ? "ALL" : lastCategory;
        this.lastPrice = Math.max(0.0D, lastPrice);
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean fastBuyEnabled() {
        return fastBuyEnabled;
    }

    public void fastBuyEnabled(boolean fastBuyEnabled) {
        this.fastBuyEnabled = fastBuyEnabled;
    }

    public boolean fastSellEnabled() {
        return fastSellEnabled;
    }

    public void fastSellEnabled(boolean fastSellEnabled) {
        this.fastSellEnabled = fastSellEnabled;
    }

    public int lastDurationHours() {
        return lastDurationHours;
    }

    public void lastDurationHours(int lastDurationHours) {
        this.lastDurationHours = Math.max(1, lastDurationHours);
    }

    public String lastCategory() {
        return lastCategory;
    }

    public void lastCategory(String lastCategory) {
        this.lastCategory = lastCategory == null ? "ALL" : lastCategory;
    }

    public double lastPrice() {
        return lastPrice;
    }

    public void lastPrice(double lastPrice) {
        this.lastPrice = Math.max(0.0D, lastPrice);
    }
}
