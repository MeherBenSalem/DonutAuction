# FAQ

Answers to the questions server owners ask most about DonutAuctionHouse.

## Is it really free?

Yes, all of it: auctions, tax, price insight, webhooks, PlaceholderAPI, cross-server and the API. The source is on GitHub under Apache-2.0.

## I'm updating from 1.x. Will I lose anything?

No. Replace the jar and restart. Your listings, history, config values and messages are kept, and new settings are added. Back up your database first, as with any update.

## Does it work on Spigot?

No. DonutAuctionHouse uses Paper's API (Adventure text, region schedulers). Paper is a drop-in replacement for Spigot, and Purpur and other Paper forks work too.

## Can players dupe items with it?

Every purchase, bid, auction end, cancel and reclaim is a conditional database update that only one action can win, and items only move as whole stacks that fit. See [How items stay safe](safety.md).

## How do I share one market between servers?

Set `storage.type: MYSQL` on every server with the same database, and optionally enable `sync.redis`. See [Several servers](multi-server.md).

## Can I charge tax only to some players?

Yes. Set `economy.sales-tax.percent` for everyone and add tiers under `economy.sales-tax.permissions`, for example `donutauction.tax.vip: 2.5`. The lowest matching tier applies.

## Can I stop people selling certain items?

Use `blacklist` (materials, names, lore, custom model data, plugin data keys) and `auction.price-limits` for price floors and ceilings per item. See [Economy and rules](economy-and-rules.md).

## How do I translate the plugin?

Edit [`messages.yml`](../src/main/resources/messages.yml): every word players see is there, including menu titles and buttons. MiniMessage, `&` codes and `&#RRGGBB` hex all work. Run `/ah reload` to apply.

## Where do I report a bug?

On [GitHub issues](https://github.com/MeherBenSalem/DonutAuction/issues) or in the bug reports forum on our [Discord](https://discord.gg/TJc3CxR4jK). Include your server software and version, the plugin version and your `latest.log`.
