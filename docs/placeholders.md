# Placeholders

With PlaceholderAPI installed, DonutAuctionHouse registers `%donutauction_...%` placeholders for scoreboards, holograms, tab lists and menus. Turn them off with `placeholderapi.enabled: false`.

## Player

| Placeholder | Value |
| --- | --- |
| `%donutauction_active%` | Your active listings |
| `%donutauction_limit%` | Your listing limit |
| `%donutauction_slots_left%` | Listings you can still add |
| `%donutauction_collect%` | Items waiting in `/ah collect` |
| `%donutauction_sold%` | Items you've sold |
| `%donutauction_earned%` | Money earned from sales, after tax |
| `%donutauction_bought%` | Items you've bought |
| `%donutauction_spent%` | Money spent |

## Market

| Placeholder | Value |
| --- | --- |
| `%donutauction_total_listings%` | Listings on the market |
| `%donutauction_total_auctions%` | Auctions with bids on the market |

## Price insight per item

Replace `<ITEM>` with a material name, such as `DIAMOND` or `NETHERITE_INGOT`:

| Placeholder | Value |
| --- | --- |
| `%donutauction_average_<ITEM>%` | Average price each over the last `market.window-days` days |
| `%donutauction_sales_<ITEM>%` | Number of sales in that window |
| `%donutauction_lowest_<ITEM>%` | Cheapest Buy It Now price each right now (`-` when none) |
| `%donutauction_listed_<ITEM>%` | How many are listed right now |

## Plain numbers

Add `_raw` to a money placeholder for a plain number without currency formatting, for example `%donutauction_earned_raw%`, `%donutauction_spent_raw%`, `%donutauction_average_DIAMOND_raw%` or `%donutauction_lowest_DIAMOND_raw%`.
