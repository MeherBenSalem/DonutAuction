package io.nightbeam.donutauction.util;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class SchedulerAdapter {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final boolean regionScheduler;

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
        this.asyncExecutor = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors() / 2), new NamedThreadFactory());
        this.regionScheduler = hasGlobalRegionScheduler();
    }

    public Executor asyncExecutor() {
        return asyncExecutor;
    }

    public void runAsync(Runnable runnable) {
        asyncExecutor.execute(runnable);
    }

    public void runGlobal(Runnable runnable) {
        if (regionScheduler) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public void runEntity(Entity entity, Runnable runnable) {
        if (regionScheduler) {
            entity.getScheduler().execute(plugin, runnable, null, 1L);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public CancellableTask runGlobalRepeating(Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (regionScheduler) {
            io.papermc.paper.threadedregions.scheduler.ScheduledTask paperTask =
                    Bukkit.getGlobalRegionScheduler()
                            .runAtFixedRate(plugin, task -> runnable.run(), initialDelayTicks, periodTicks);
            return paperTask::cancel;
        }
        BukkitTask bukkitTask = Bukkit.getScheduler()
                .runTaskTimer(plugin, runnable, initialDelayTicks, periodTicks);
        return bukkitTask::cancel;
    }

    public void shutdown() {
        asyncExecutor.shutdownNow();
    }

    private static boolean hasGlobalRegionScheduler() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    @FunctionalInterface
    public interface CancellableTask {
        void cancel();
    }

    private static final class NamedThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "DonutAuctionHouse-Async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
