package io.nightbeam.donutauction.sync;

import io.nightbeam.donutauction.model.AuctionListing;
import io.nightbeam.donutauction.storage.AuctionRepository;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis is a notification bus only. Receivers always re-read MySQL before touching the cache.
 * The subscriber reconnects with back-off when Redis restarts; MySQL polling covers the gap.
 */
public final class RedisListingSyncBus implements ListingSyncBus {

    private final Logger logger;
    private final AuctionRepository repository;
    private final String instanceId;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;

    private volatile boolean running;
    private volatile Consumer<AuctionListing> onUpdated = listing -> { };
    private volatile Consumer<UUID> onRemoved = id -> { };
    private Thread subscriberThread;
    private Jedis publisher;
    private volatile Jedis subscriber;
    private volatile JedisPubSub pubSub;

    public RedisListingSyncBus(
            Logger logger,
            AuctionRepository repository,
            String instanceId,
            String host,
            int port,
            String password,
            String channel
    ) {
        this.logger = logger;
        this.repository = repository;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.channel = channel;
    }

    @Override
    public void setRemoteHandler(Consumer<AuctionListing> updated, Consumer<UUID> removed) {
        this.onUpdated = updated;
        this.onRemoved = removed;
    }

    @Override
    public void start() {
        try {
            synchronized (this) {
                this.publisher = connect();
            }
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
            JedisPubSub current = pubSub;
            if (current != null && current.isSubscribed()) {
                current.unsubscribe();
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
        if (!running) {
            return;
        }
        String message = instanceId + "\t" + action.name() + "\t" + auctionId;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (publisher == null) publisher = connect();
                publisher.publish(channel, message);
                return;
            } catch (Exception exception) {
                closePublisher();
                if (attempt == 1) logger.warning("Failed to publish listing sync: " + exception.getMessage());
            }
        }
    }

    private void subscribeLoop() {
        long backoff = 1_000L;
        boolean warned = false;
        while (running) {
            try {
                subscriber = connect();
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        handleMessage(message);
                    }
                };
                if (warned) logger.info("Redis listing sync reconnected.");
                backoff = 1_000L;
                warned = false;
                subscriber.subscribe(pubSub, channel);
            } catch (Exception exception) {
                if (running && !warned) {
                    warned = true;
                    logger.warning("Redis listing subscriber disconnected (" + exception.getMessage()
                            + "); retrying. MySQL polling keeps servers in sync meanwhile.");
                }
            } finally {
                try {
                    if (subscriber != null) subscriber.close();
                } catch (Exception ignored) {
                }
            }
            if (!running) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                return;
            }
            backoff = Math.min(30_000L, backoff * 2);
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
        try {
            ListingSyncAction.valueOf(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        repository.findById(auctionId).thenAccept(optional -> {
            if (optional.isEmpty()) {
                onRemoved.accept(auctionId);
                return;
            }
            onUpdated.accept(optional.get());
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

    private synchronized void closePublisher() {
        try {
            if (publisher != null) {
                publisher.close();
            }
        } catch (Exception ignored) {
        }
        publisher = null;
    }

    private void closeQuietly() {
        closePublisher();
        try {
            if (subscriber != null) {
                subscriber.close();
            }
        } catch (Exception ignored) {
        }
        subscriber = null;
    }
}
