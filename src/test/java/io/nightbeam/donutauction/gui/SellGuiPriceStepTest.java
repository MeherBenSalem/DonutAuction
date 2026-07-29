package io.nightbeam.donutauction.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SellGuiPriceStepTest {

    @Test
    void stepIsPointOneBelowTen() {
        assertEquals(0.1D, SellGui.priceStep(5.0D));
    }

    @Test
    void stepIsOneFromTenUp() {
        assertEquals(1.0D, SellGui.priceStep(10.0D));
        assertEquals(1.0D, SellGui.priceStep(99.0D));
    }

    @Test
    void stepIsTenFromOneHundredUp() {
        assertEquals(10.0D, SellGui.priceStep(100.0D));
        assertEquals(10.0D, SellGui.priceStep(999.0D));
    }

    @Test
    void stepIsOneHundredFromOneThousandUp() {
        assertEquals(100.0D, SellGui.priceStep(1000.0D));
        assertEquals(100.0D, SellGui.priceStep(5000.0D));
    }
}
