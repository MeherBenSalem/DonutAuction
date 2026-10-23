package io.nightbeam.donutauction.model;

/** Lifetime completed trades of one player. Earnings are after tax. */
public record PlayerStats(long sold, double earned, long bought, double spent) {

    public static final PlayerStats EMPTY = new PlayerStats(0, 0.0D, 0, 0.0D);
}
