package io.nightbeam.donutauction.util;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SchedulerAdapterTest {
    @Test void entityAndGlobalWorkUsesPaperFoliaSchedulersAndDatabaseExecutorIsSeparate() throws Exception {
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        MockBukkit.mock(new ServerMock() {
            @Override public GlobalRegionScheduler getGlobalRegionScheduler() { return global; }
        });
        Plugin plugin = MockBukkit.createMockPlugin();
        SchedulerAdapter adapter = new SchedulerAdapter(plugin);
        try {
            Player player = mock(Player.class);
            EntityScheduler entity = mock(EntityScheduler.class);
            when(player.getScheduler()).thenReturn(entity);
            Runnable work = () -> { };
            Runnable retired = () -> { };
            adapter.runEntity(player, work, retired);
            verify(entity).execute(plugin, work, retired, 1L);
            adapter.runGlobal(work);
            verify(global).execute(plugin, work);
            String thread = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), adapter.asyncExecutor()).get();
            assertTrue(thread.startsWith("DonutAuctionHouse-Async-"));
            assertNotEquals(Thread.currentThread().getName(), thread);
        } finally { adapter.shutdown(); MockBukkit.unmock(); }
    }
}
