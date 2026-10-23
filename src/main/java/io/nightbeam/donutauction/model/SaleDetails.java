package io.nightbeam.donutauction.model;

/**
 * Bookkeeping written after a purchase settles: the tax taken from the seller, whether the item reached
 * the buyer's inventory (false means it waits in their collection) and the buyer's name for history.
 */
public record SaleDetails(double tax, boolean buyerClaimed, String buyerName) {

    public static final SaleDetails DELIVERED = new SaleDetails(0.0D, true, null);
}
