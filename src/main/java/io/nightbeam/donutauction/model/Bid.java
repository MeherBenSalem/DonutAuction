package io.nightbeam.donutauction.model;

import java.util.UUID;

/** The leading bid of a BID listing. The amount is held in escrow until the bidder is outbid or wins. */
public record Bid(double amount, UUID bidder, String bidderName, int count) {

    public static final Bid NONE = new Bid(0.0D, null, null, 0);

    public boolean present() {
        return bidder != null && count > 0;
    }
}
