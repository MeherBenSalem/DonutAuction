package io.nightbeam.donutauction.model;

public enum ListingPriceValidationResult {
    VALID,
    BELOW_MINIMUM,
    INVALID_OR_ABOVE_MAX;

    public static ListingPriceValidationResult validate(double price, double minPrice, double maxPrice) {
        if (!Double.isFinite(price) || !Double.isFinite(minPrice) || !Double.isFinite(maxPrice)) {
            return INVALID_OR_ABOVE_MAX;
        }
        double effectiveMinPrice = Math.max(0.0D, minPrice);

        if (price < effectiveMinPrice) {
            return BELOW_MINIMUM;
        }
        if (price <= 0.0D || price > maxPrice) {
            return INVALID_OR_ABOVE_MAX;
        }
        return VALID;
    }
}
