package io.nightbeam.donutauction.gui;

import io.nightbeam.donutauction.sync.ListingSyncAction;
import io.nightbeam.donutauction.sync.ListingSyncBus;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the sell dupe: once a listing is saved, a failure in a later step (here the cross-server sync
 * publish, as when Redis is unreachable) used to send the item back to the seller while the listing stayed live.
 */
class ListingSideEffectFailureFlowTest extends FlowTestBase {

    @Override
    protected ListingSyncBus syncBus() {
        return new ListingSyncBus() {
            @Override public void start() { }
            @Override public void shutdown() { }
            @Override public void publish(UUID auctionId, ListingSyncAction action) {
                throw new IllegalStateException("simulated Redis outage");
            }
        };
    }

    private int diamonds() {
        return seller.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    @Test void aSavedListingIsNeverAlsoReturnedWhenALaterStepFails() throws Exception {
        assertEquals(3, diamonds());
        assertTrue(server.dispatchCommand(seller, "ah sell 100"));
        assertInstanceOf(SellGui.class, seller.getOpenInventory().getTopInventory().getHolder());
        assertEquals(0, diamonds(), "the sell menu holds the item while it is open");

        click(seller, SellGui.CONFIRM_SLOT);
        await(() -> repository.loadAll().join().size() == 1);
        // Let every queued player task run, including any (wrong) item return.
        for (int i = 0; i < 20; i++) { pump(); Thread.sleep(5); }

        assertEquals(1, repository.loadAll().get().size(), "the listing was saved");
        assertEquals(3, repository.loadAll().get().get(0).item().getAmount());
        assertEquals(0, diamonds(), "the seller must not get the listed item back");
    }
}
