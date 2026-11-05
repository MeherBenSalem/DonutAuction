# Integrations

Discord webhooks, PlaceholderAPI, the transaction log and the other plugins DonutAuctionHouse works with.

## Discord webhooks

Post trades to a Discord channel as embeds with the item, amount, enchantments, price and the seller's head.

```yaml
discord:
  enabled: true
  webhook-url: 'https://discord.com/api/webhooks/...'
  username: 'Auction House'
  avatar-url: ''
  events: [LISTED, SOLD, WON]     # LISTED, SOLD, BID, WON, EXPIRED
  min-price: 0                    # only post listings and sales at or above this price
  head-url: 'https://mc-heads.net/avatar/{uuid}/64'
  item-icon-url: ''               # {material} becomes e.g. diamond_sword
  footer: 'DonutAuctionHouse'
```

Create the webhook in Discord under **Channel settings → Integrations → Webhooks** and paste its URL. Keep the URL private: anyone with it can post in that channel.

## PlaceholderAPI

Player stats, market totals and price insight per item. See [Placeholders](placeholders.md).

## Economy

Vault or VaultUnlocked, with any economy that registers Vault's Economy service (EssentialsX, CMI and others). Prices use your economy's formatting unless you turn on [compact prices](collection-and-history.md#compact-prices).

## DonutCore

When DonutCore is installed, seller and buyer names use its display names, and the 1.x `donutcore.auction.*` permissions keep working.

## Transaction log

`transaction-log.enabled` (on by default) writes one line per listing, sale, bid, win, cancel and reclaim to `plugins/DonutAuctionHouse/logs/transactions-YYYY-MM-DD.log`, in UTC.

## Other plugins

Use the [developer API](developer-api.md) and its events, or `/ah open <player>` from NPC and menu plugins.

## bStats and update checks

Anonymous usage statistics go to bStats (`metrics.enabled`). The update checker tells the console and players with `donutauction.update.notify` about new versions (`update-checker`).
