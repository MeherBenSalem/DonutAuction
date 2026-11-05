# Permissions

Players can use and sell on the auction house out of the box. Limits, tax tiers and fee exemptions are granted with permissions, and staff tools default to operators.

## Players

| Permission | Default | Grants |
| --- | --- | --- |
| `donutauction.use` | Everyone | Open the auction house |
| `donutauction.sell` | Everyone | List items |
| `donutauction.sell.auction` | Everyone | Start auctions with bids (`bidding.permission`) |
| `donutauction.fastbuy` | Everyone | Toggle fast buy |
| `donutauction.fastsell` | Everyone | Toggle fast sell |
| `donutauction.broadcast.receive` | Everyone | Receive new-listing broadcasts |

## Limits, tax and fees

| Permission | Default | Grants |
| --- | --- | --- |
| `donutauction.limit.5` | Everyone | Up to 5 listings |
| `donutauction.limit.10`, `.25`, `.50`, `.100` | Nobody | Up to that many listings (`auction-limits`) |
| `donutauction.limit.unlimited` | Nobody | No listing limit |
| `donutauction.slots.10`, `.25`, `.50`, `.100` | Nobody | Extra listing slots (`auction-slots`) |
| `donutauction.tax.vip` | Nobody | Example sales-tax tier (2.5%) |
| `donutauction.tax.exempt` | Nobody | No sales tax (example tier) |
| `donutauction.fee.exempt` | Nobody | No listing fee |

Tax tiers are whatever you list under `economy.sales-tax.permissions`, and listing limits whatever you list under `auction-limits.permissions`; the nodes above are the defaults. See [Economy and rules](economy-and-rules.md).

## Staff

| Permission | Default | Grants |
| --- | --- | --- |
| `donutauction.admin` | Operators | `/ah admin`, `/ah reload`, removing listings; includes `donutauction.admin.open` |
| `donutauction.admin.open` | Operators | `/ah open <player>` |
| `donutauction.blacklist.bypass` | Operators | List blacklisted items |
| `donutauction.restrictions.bypass` | Operators | List in disabled worlds and blocked game modes |
| `donutauction.cooldown.bypass` | Operators | Ignore the listing cooldown |
| `donutauction.update.notify` | Operators | Update notices on join |

## 1.x permissions

The 1.x nodes `donutcore.auction.use`, `donutcore.auction.sell` and `donutcore.auction.admin` keep working.
