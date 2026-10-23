package io.nightbeam.donutauction.model;

/** BIN listings sell instantly at their price; BID listings go to the highest bidder when they end. */
public enum ListingType {
    BIN,
    BID;

    public static ListingType parse(String value) {
        return "BID".equalsIgnoreCase(value) ? BID : BIN;
    }
}
