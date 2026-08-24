package io.nightbeam.donutauction.sync;

import io.nightbeam.donutauction.AuctionHousePlugin;
import io.nightbeam.donutauction.service.AuctionManager;
import io.nightbeam.donutauction.storage.AuctionRepository;
import java.util.UUID;

public final class ListingSyncBusFactory {

    private ListingSyncBusFactory() {
    }

    public static ListingSyncBus create(
            AuctionHousePlugin plugin,
            AuctionRepository repository,
            AuctionManager auctionManager
    ) {
        if (!plugin.getConfig().getBoolean("sync.redis.enabled", false)) {
            return new NoopListingSyncBus();
        }
        String host = plugin.getConfig().getString("sync.redis.host", "localhost");
        int port = plugin.getConfig().getInt("sync.redis.port", 6379);
        String password = plugin.getConfig().getString("sync.redis.password", "");
        String channel = plugin.getConfig().getString("sync.redis.channel", "donutauction:listings");
        String instanceId = UUID.randomUUID().toString();
        RedisListingSyncBus bus = new RedisListingSyncBus(
                plugin.getLogger(),
                repository,
                auctionManager,
                instanceId,
                host,
                port,
                password,
                channel
        );
        bus.start();
        return bus;
    }
}
