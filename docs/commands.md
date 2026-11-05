# Commands

Everything runs under `/ah` (aliases `/auction` and `/auctionhouse`). Players see the auction house with no permissions set up; staff commands need `donutauction.admin`.

## Players

| Command | What it does | Permission |
| --- | --- | --- |
| `/ah` | Open the auction house | `donutauction.use` |
| `/ah sell <price> [auction]` | List the held item; `1k`, `2.5m`, `1b` work | `donutauction.sell` |
| `/ah auction <starting bid>` | Auction the held item | `donutauction.sell.auction` |
| `/ah search <text>` | Search by name, lore, enchantment or seller | `donutauction.use` |
| `/ah view <player>` or `/ah <player>` | One seller's listings | `donutauction.use` |
| `/ah price` | Recent prices of the held item | `donutauction.use` |
| `/ah collect` (`/ah cancel`) | Your listings and items to collect | `donutauction.use` |
| `/ah history [sold]` | Your purchases and sales | `donutauction.use` |
| `/ah limit` | Your listing limit | `donutauction.use` |
| `/ah fastbuy` | Toggle skipping the purchase confirmation | `donutauction.fastbuy` |
| `/ah fastsell` | Toggle listing without the sell menu | `donutauction.fastsell` |
| `/ah help` | Command list | `donutauction.use` |

## Staff

| Command | What it does | Permission |
| --- | --- | --- |
| `/ah open <player> [browse\|items\|history\|search <text>]` | Open a menu for a player (console, NPCs, menu plugins) | `donutauction.admin.open` |
| `/ah admin view <player>` | A player's listings, click to remove | `donutauction.admin` |
| `/ah admin remove <id>` | Remove a listing by id | `donutauction.admin` |
| `/ah admin info` | Storage, active listings and unreadable rows | `donutauction.admin` |
| `/ah admin purge <days>` | Delete settled history older than this | `donutauction.admin` |
| `/ah reload` | Reload `config.yml` and `messages.yml` | `donutauction.admin` |

Storage and sync settings need a full restart; everything else applies on `/ah reload`. See [Staff tools](staff-tools.md) and [Permissions](permissions.md).
