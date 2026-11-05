# Several servers

Share one market between servers: point every server at the same MySQL database, and optionally add Redis for instant updates.

## Set up MySQL

On **every** server:

```yaml
storage:
  type: MYSQL
  mysql:
    host: db.example.com
    port: 3306
    database: donut_auction_house
    username: auction
    password: change-me
    parameters: useSSL=false&characterEncoding=utf8
```

Storage settings need a full restart, not `/ah reload`. To move an existing single server from SQLite, back up `auctions.db` first.

## How it stays consistent

Each purchase, bid, auction end and reclaim is a conditional row update in MySQL, so only one server can win it: two servers can never sell, settle or return the same item twice. MySQL is the source of truth.

## Updates between servers

```yaml
sync:
  poll-interval-seconds: 3
  redis:
    enabled: true
    host: localhost
    port: 6379
    password: ""
    channel: donutauction:listings
```

- Without Redis, each server polls MySQL every `poll-interval-seconds` for changes.
- With Redis, changes reach the other servers immediately. Redis only carries notifications, so it can go down without losing anything.

Sellers are told about a sale on whichever server they're on. See [Notifications](notifications.md).
