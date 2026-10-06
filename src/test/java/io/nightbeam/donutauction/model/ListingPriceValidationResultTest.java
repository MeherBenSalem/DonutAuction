package io.nightbeam.donutauction.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListingPriceValidationResultTest {

    @Test
    void nonFinitePricesAreRejected() {
        assertEquals(ListingPriceValidationResult.INVALID_OR_ABOVE_MAX,
                ListingPriceValidationResult.validate(Double.NaN, 10, 1000));
        assertEquals(ListingPriceValidationResult.INVALID_OR_ABOVE_MAX,
                ListingPriceValidationResult.validate(Double.POSITIVE_INFINITY, 10, 1000));
    }

    @Test
    void priceBelowMinIsBlocked() {
        ListingPriceValidationResult result = ListingPriceValidationResult.validate(9.99D, 10.0D, 1_000.0D);
        assertEquals(ListingPriceValidationResult.BELOW_MINIMUM, result);
    }

    @Test
    void priceEqualToMinIsAllowed() {
        ListingPriceValidationResult result = ListingPriceValidationResult.validate(10.0D, 10.0D, 1_000.0D);
        assertEquals(ListingPriceValidationResult.VALID, result);
    }

    @Test
    void priceAboveMinIsAllowed() {
        ListingPriceValidationResult result = ListingPriceValidationResult.validate(150.0D, 10.0D, 1_000.0D);
        assertEquals(ListingPriceValidationResult.VALID, result);
    }

    @Test
    void maxPriceStillWorks() {
        ListingPriceValidationResult result = ListingPriceValidationResult.validate(1_000.01D, 10.0D, 1_000.0D);
        assertEquals(ListingPriceValidationResult.INVALID_OR_ABOVE_MAX, result);
    }
}
