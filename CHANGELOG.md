# Changelog

## 1.5.0

Skin investments:

- `/flipscout skins` (also `/skininvestment`): the skins worth buying now to sell in 30 days, after fees, best return
  for its risk first. Hover a skin for the price, expected profit and return, risk, 7- and 30-day change, volatility,
  listings, sales, fire sale and confidence; click it to open its cheapest BIN. A line says how fresh the data is.
- `/flipscout skin <name>` (also `/skininvestment detail <name>`): one skin in full, with a chart of its last 30 days
  and a link to its full chart. Tab completes skin IDs, also from a word of the name.
- Every skin on the auction house counts, recognised by its rarity line: pet, helmet, minion, barn, backpack, power orb.
- Daily prices from SkyCofl, read in the background (about 20 a minute at first, then each skin once a day) and kept in
  `skins.json.gz`; coins moved between accounts by "selling" a skin for hundreds of times its price are filtered out.
- The expected price does not follow trends (they do not continue for skins): it is what the skin sells for now, moved by
  what skins of the same age usually did. The confidence is how often a purchase at this price would have been in profit.
- Skins that are hard to sell, too new, falling hard, blacklisted or on a fire sale are left out (`skins.*` settings).

Fire sales: a chat alert when Hypixel announces one (`skins.fireSaleAlert`), kept in `firesales.json`.

Debug: `/flipscout debug on|off` shows the mod's warnings and errors in chat, so you can report them (`debug.chat`).

## 1.4.0

New kinds of flip, from SkyCofl's free API and priced again with live data:

- **Kat, Craft, Forge, Fusion, NPC and Book flips**: `/flipscout kat|craft|forge|fusion|npc|books`, clickable lists
  with ingredients, times and requirements; automatic chat alerts for each kind, each with its own switch and
  thresholds. Set your Heart of the Mountain level (`other.forge.hotm`) to hide the forges you cannot do.
- **Low supply items**: `/flipscout lowsupply`.
- **Auction → NPC flips**: auctions cheaper than what an NPC pays for the item.

Mayors:

- `/flipscout mayor`: mayor and minister, the open election with the favourite, and which items usually rise or fall
  under the favourite and under the current mayor, from a year of prices laid over a year of elections.
- Election alert once per election, when a candidate is clearly ahead.

Tools:

- **Price in item tooltips**, with a **lowball** price after fees.
- `/flipscout history <item>`: price history as a chart in chat.
- `/flipscout player <name>`: a player's auctions now and their last ones.
- `/flipscout search <name> [max:<price>]`: cheapest auctions of an item.
- `/flipscout museum`: cheapest museum experience, items and armor sets, per XP.
- `/flipscout blocked`: auctions your filters turned down, and why.
- `/flipscout replay`: notified auctions still up for sale.
- `/flipscout export`: closed flips and notifications as CSV, the bazaar as JSON.
- `/flipscout credits`.

Filters and settings:

- Blacklist sellers: `/flipscout blacklist add player:NAME`.
- Always-notified items (`whitelist`), a minimum profit per item (`ah.itemMinProfit`), a minimum auction price.
- Chat colours for the `[FlipScout]` tag and for profits.
- Longer history: 100 notifications, 1000 closed flips, 100 sessions, a year of daily totals.
- `/flipscout status` shows the average time from an auction's listing to its alert.

Fixes: the settings menu fits small windows; `/flipscout open` opens the latest notification that can be opened.

## 1.3.0

- Everything in English; commands have English names (`profits`, `session`, `total`, `flips`, `stock`), the Italian
  ones still work. The profits file is now `profits.json` (renamed automatically).
- Pet flips say whether the pet has pet candy, and warn when the price reference does not.
- All Rights Reserved license, mod icon and metadata for Modrinth.
