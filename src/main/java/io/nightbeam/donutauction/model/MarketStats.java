package io.nightbeam.donutauction.model;

/** Completed sales of one material within a window. Unit prices are per single item. */
public record MarketStats(String material, long sales, double averageUnit, double lowestUnit, double highestUnit,
                          double lastUnit, long since) {

    public static MarketStats empty(String material, long since) {
        return new MarketStats(material, 0, 0.0D, 0.0D, 0.0D, 0.0D, since);
    }

    public boolean hasSales() {
        return sales > 0;
    }
}
