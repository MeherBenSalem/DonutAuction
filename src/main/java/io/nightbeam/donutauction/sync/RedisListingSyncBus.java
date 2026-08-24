package io.nightbeam.donutauction.sync;

import io.nightbeam.donutauction.storage.AuctionRepository;
import io.nightbeam.donutauction.service.AuctionManager;
import java.util.UUID;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis is a notification bus only. Receivers always re-read MySQL before touching the cache.
 */
public final class RedisListingSyncBus implements ListingSyncBus {

    private final Logger logger;
    private final AuctionRepository repository;
    private final AuctionManager auctionManager;
    private final String instanceId;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;

    private volatile boolean running;
    private Thread subscriberThread;
    private Jedis publisher;
    private Jedis subscriber;
    private JedisPubSub pubSub;

    public RedisListingSyncBus(
            Logger logger,
            AuctionRepository repository,
            AuctionManager auctionManager,
            String instanceId,
            String host,
            int port,
            String password,
            String channel
    ) {
        this.logger = logger;
        this.repository = repository;
        this.auctionManager = auctionManager;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.channel = channel;
    }

    @Override
    public void start() {
        try {
            this.publisher = connect();
            this.subscriber = connect();
            this.pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String ch, String message) {
                    handleMessage(message);
                }
            };
            this.running = true;
            this.subscriberThread = new Thread(this::subscribeLoop, "DonutAuction-Redis");
            this.subscriberThread.setDaemon(true);
            this.subscriberThread.start();
            logger.info("Listing Redis sync subscribed to channel " + channel + ".");
        } catch (Exception exception) {
            running = false;
            closeQuietly();
            logger.warning("Redis listing sync failed to start (MySQL claim still prevents double-buy): "
                    + exception.getMessage());
        }
    }

    @Override
    public void shutdown() {
        running = false;
        try {
            if (pubSub != null && pubSub.isSubscribed()) {
                pubSub.unsubscribe();
            }
        } catch (Exception ignored) {
        }
        closeQuietly();
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    @Override
    public synchronized void publish(UUID auctionId, ListingSyncAction action) {
        if (!running || publisher == null) {
            return;
        }
        try {
            publisher.publish(channel, instanceId + "\t" + action.name() + "\t" + auctionId);
        } catch (Exception exception) {
            logger.warning("Failed to publish listing sync: " + exception.getMessage());
        }
    }

    private void subscribeLoop() {
        try {
            subscriber.subscribe(pubSub, channel);
        } catch (Exception exception) {
            if (running) {
                logger.warning("Redis listing subscriber stopped: " + exception.getMessage());
            }
        }
    }

    private void handleMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String[] parts = message.split("\t", 3);
        if (parts.length != 3) {
            return;
        }
        if (instanceId.equals(parts[0])) {
            return;
        }
        UUID auctionId;
        try {
            auctionId = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        ListingSyncAction action;
        try {
            action = ListingSyncAction.valueOf(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        repository.findById(auctionId).thenAccept(optional -> {
            if (optional.isEmpty() || action == ListingSyncAction.REMOVE) {
                if (optional.isEmpty()) {
                    auctionManager.remove(auctionId);
                    return;
                }
            }
            optional.ifPresent(auctionManager::mergeRemote);
        }).exceptionally(throwable -> {
            logger.warning("Failed to apply remote listing " + auctionId + ": " + throwable.getMessage());
            return null;
        });
    }

    private Jedis connect() {
        Jedis jedis = new Jedis(host, port);
        if (!password.isBlank()) {
            jedis.auth(password);
        }
        jedis.ping();
        return jedis;
    }

    private void closeQuietly() {
        try {
            if (publisher != null) {
                publisher.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (subscriber != null) {
                subscriber.close();
            }
        } catch (Exception ignored) {
        }
        publisher = null;
        subscriber = null;
    }
}
