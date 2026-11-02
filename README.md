# FlipScout

A **Hypixel SkyBlock** mod that looks for underpriced BIN auctions and bazaar deals and tells you about them in chat.
Click a notification and the auction (`/viewauction <id>`) or the bazaar (`/bz <item>`) opens.

> **It never buys anything by itself.** It shows information and, when you click, opens the screen: you make the
> purchase. Hypixel forbids mods that buy or click for you; this one does not. Use it at your own risk anyway.

Available for **Forge 1.8.9** and for **Fabric** from 1.21.7 to 26.3 (with Fabric API).

## What it finds

| Type | When you get a notification |
|---|---|
| **AH** | A BIN auction that was just listed costs less than the item resells for, after taxes, with at least the profit and percentage you set. |
| **BZ flip** | Placing a buy order and a sell offer leaves a high margin after tax, and the product trades often enough per hour. If the sell price is more than 25% above the usual (someone is holding the price up) the flip is skipped; in the first 15 minutes, without history, margins above 100% are skipped. |
| **BZ crash** | The instant-buy price fell far below the median of the last hour and a half (someone dumped). |
| **BZ→NPC** | Buying at the bazaar and selling to an NPC pays. |
| **AH→NPC** | An auction costs less than what an NPC pays for the item: a sure profit, no taxes. |
| **KAT, CRAFT, FORGE, FUSION, NPC, BOOK** | Pets worth upgrading at Kat, items worth crafting or forging, shard fusions, items worth buying from an NPC and reselling, enchanted books worth combining: from SkyCofl's lists, priced again with live prices (see [Other flips](#other-flips-skycofl)). |
| **Election** | Once per election, when one candidate is clearly ahead: the items that usually rise or fall under them (see [Mayors](#mayors-and-elections)). |

Every notification shows the price, the estimated value, the profit and the sales per day; **hover over it** to see
where the estimate comes from (lowest BIN of the other sellers, sales median, Coflnet) and the taxes.

**Pets** say whether they have pet candy: `[no candy]` or `[3 candy]` right after the name. The hover also tells you
whether the cheapest other listing (the price reference) has candy, and warns you when this pet has candy but the
reference does not, since candied pets often sell for less.

If you have items in the blacklist, after each round of auctions a message says how many flips it hid (*"Your filters
blocked 3 flips"*): hover to see them, with the item, the prices and the blacklist entry that stopped them; click to open
the blacklist. Turn it off with `blockedSummary off`.

Example:

```
[FlipScout] AH Hyperion » 820M → 900M | +48.5M (5.9%) | vol 6.5/d | 4s [OPEN]
[FlipScout] AH [Lvl 100] Ender Dragon [no candy] » 520M → 600M | +59M (11.3%) | vol 3.1/d | 9s [OPEN]
[FlipScout] BZ crash Enchanted Diamond » buy at 120.5 (usually 162, -26%) | ~+1.2M [BAZAAR]
```

The last part of an AH line (`4s`) is how long ago the auction was listed.

**Item tooltips** show what a SkyBlock item resells for (lowest BIN, recent sales, sales a day, upgrades counted) and a
**lowball** price: by default 85% of what it fetches after fees (`tooltip.lowballPercent`). Bazaar items show what the
bazaar pays. Turn it off with `tooltip.enabled off`.

`/flipscout blocked` lists the auctions that looked like a flip but missed one of your filters, and which one (margin,
minimum profit, sales a day, budget, minimum price, blacklist...).

## How fast it is

Hypixel's API builds the new auctions **every 60 seconds** and publishes them about **8 seconds later** (measured): no
mod using the API can see them earlier. FlipScout tries not to add delays of its own:

- it learns when Hypixel publishes and checks right then, accounting for the difference between your PC clock and
  Hypixel's. Until the data has changed it only reads the start of the response and closes it;
- as soon as it is out, it reads the first pages, where new auctions land, and tells you right away: usually within a
  second;
- the full auction scan (45 pages in about 3 seconds), the sales, the bazaar and the Coflnet requests run in parallel
  and never hold up notifications. Only when a flip rests on a single price, or its sales per day are unknown, does the
  mod first ask Coflnet to confirm it (usually under a second).

In practice an auction reaches your chat roughly 9 to 70 seconds after it is listed (about 40 on average), depending on
when it falls relative to Hypixel's update. `/flipscout status` shows the measured delay. Players browsing the auction
house in game see it immediately, so the most obvious flips sometimes vanish before you click.

## Installation

1. Get the jar for your game version (from Modrinth, or from the **Actions** page of the repository: open the latest
   successful **Build** run and, at the bottom under *Artifacts*, download):
   - `flipscout-forge-1.8.9` for Forge 1.8.9;
   - for Fabric (you also need [Fabric API](https://modrinth.com/mod/fabric-api)) the one for your version:

     | Artifact | Minecraft versions |
     |---|---|
     | `flipscout-fabric-1.21.8` | 1.21.7, 1.21.8 |
     | `flipscout-fabric-1.21.10` | 1.21.9, 1.21.10 |
     | `flipscout-fabric-1.21.11` | 1.21.11 |
     | `flipscout-fabric-26.1.2` | 26.1.x |
     | `flipscout-fabric-26.2` | 26.2.x |
     | `flipscout-fabric-26.3` | 26.3.x |
2. Put the `.jar` in your `mods` folder (artifacts from Actions come zipped: extract them first).
3. Join Hypixel. On the first start the mod downloads every auction (a few tens of MB): auction notifications start
   once that is done. `/flipscout status` shows the progress.

## Building it yourself

```bash
cd core && ./gradlew test             # the shared code and its tests
cd forge-1.8.9 && ./gradlew build     # Gradle on Java 17; it downloads Java 8 for the mod by itself
cd fabric-1.21 && ./gradlew build     # Gradle on Java 25; for another 1.21.x: -PminecraftVersion=... -PfabricVersion=...
cd fabric-26 && ./gradlew build       # Java 25; Minecraft 26.x
```

The jar ends up in `build/libs`. The shared code lives in `core/`, the code shared by the Fabric builds in
`fabric-common/`; the versions used for each Fabric build are in `.github/workflows/build.yml`, which builds all of them
on every push.

## Commands

`/fsc` is short for `/flipscout`. Tab completes commands, settings and item IDs.

| Command | What it does |
|---|---|
| `/flipscout` | short status |
| `/flipscout gui` | opens the settings menu (also `/flipscout settings`) |
| `/flipscout on` / `off` | turns everything on or off (when off it makes no requests at all) |
| `/flipscout ah on\|off` | auction notifications |
| `/flipscout bz on\|off` | bazaar notifications; also `bz flip\|crash\|npc on\|off` for each type |
| `/flipscout top [ah\|bz] [n]` | the best deals right now, including ones listed a while ago |
| `/flipscout recent [page]` | the last 100 notifications, still clickable; marks auctions that already sold and how fast |
| `/flipscout replay` | the notified auctions nobody has bought yet |
| `/flipscout blocked` | auctions your filters turned down, and why |
| `/flipscout kat\|craft\|forge\|fusion\|npc\|books [n]` | the other kinds of flip, from SkyCofl's lists (see below) |
| `/flipscout lowsupply [n]` | items with few auctions up for sale, most traded first |
| `/flipscout mayor` | mayor, minister, the open election and what usually moves under the favourite |
| `/flipscout history <item> [day\|week\|month\|all]` | an item's price history as a chart, by name or ID |
| `/flipscout player <name>` | a player's auctions: up for sale now and the last ones they created |
| `/flipscout search <name> [max:<price>]` | the cheapest BIN auctions of an item, e.g. `search hyperion max:900m` |
| `/flipscout museum [n]` | the cheapest museum experience: items and whole armor sets, by coins per XP |
| `/flipscout open` | opens the latest notification, or tells you it was already bought (Forge also has a key for it in *Controls → FlipScout*) |
| `/flipscout profits` | how much you made: session, today, total |
| `/flipscout session [pause\|resume\|reset\|history]` | the session: details, pause, resume, reset (the old one goes to the history) |
| `/flipscout total [reset]` | total and the last 7 days; `total reset confirm` clears it |
| `/flipscout flips [page]` | closed flips (up to 1000): bought, sold, profit |
| `/flipscout export [flips\|notifications\|bazaar]` | saves them as CSV (the bazaar as JSON) in `config/flipscout/exports` |
| `/flipscout stock [clear]` | things you bought and have not resold yet, and sales to collect |
| `/flipscout discord [test]` | Discord report status; `test` sends a test message to the channel |
| `/flipscout coop` | the members of your coop the mod has recognized |
| `/flipscout set <key> <value>` | changes a setting, e.g. `set ah.minProfit 2m`; without a value it empties a text field |
| `/flipscout config [filter]` | shows the settings (click one to change it) |
| `/flipscout blacklist add\|remove\|list [ID\|player:NAME]` | items or sellers to ignore, e.g. `blacklist add HYPERION`, `blacklist add player:Someone` |
| `/flipscout status` | the state of each data source and any errors |
| `/flipscout credits` | who made the mod and where its data comes from |
| `/flipscout reload` | reloads the settings file |
| `/flipscout test` | shows a test notification |

## Settings

The easiest way is the **menu**: `/flipscout gui`, the *Config* button in the mod list (Forge), or a key you can bind
in *Controls → FlipScout* (Forge).

- **Sections on the left:** General, Auctions (BIN), Tooltips, Bazaar, Profits, Discord, Other flips (SkyCofl), Mayors,
  Data sources. **At the top**, a search over the
  names and descriptions of every setting.
- **ON/OFF switches**, choices that change on click (the strategy) and **fields** for numbers and amounts (`500k`,
  `2m`): an invalid value turns red and the reason shows at the bottom; it is not applied until you fix it.
- **Hover over a setting** to see what it does, the possible values and the default; changed settings are yellow and
  have a **↺** button to go back to the default.
- **Pages:** scroll wheel or the arrows at the bottom. Changes apply at once and are saved when you close the menu.

They also live in `config/flipscout/flipscout.properties` (every entry is commented): edit them there and run
`/flipscout reload`, or use `/flipscout set`. Amounts accept `500k`, `2m`, `1.5b`.

| Key | Default | Meaning |
|---|---|---|
| `budget` | `0` | most you will spend on one flip (0 = no limit) |
| `onlyInSkyblock` | `on` | notifications only while you are in SkyBlock |
| `sound` | `on` | a sound with each notification |
| `blacklist` | empty | IDs to ignore, comma-separated |
| `blockedSummary` | `on` | after each round of auctions, says how many flips the blacklist hid |
| `chat.prefixColor` / `chat.profitColor` | `gold` / `dark_green` | colours of the `[FlipScout]` tag and of profits |
| `ah.minProfit` | `500k` | minimum profit after taxes |
| `ah.minPercent` | `5` | minimum profit as a % of the price |
| `ah.minVolume` | `1` | minimum sales per day (when known) |
| `ah.requireVolume` | `off` | skips items whose sales are unknown |
| `ah.strategy` | `conservative` | `conservative` = the lower of the lowest BIN and the sales median; `lbin`; `sales` |
| `ah.upgrades` | `on` | counts the value of upgrades (see below) |
| `ah.upgradePercent` | `80` | how much of the upgrades' value to count, in % |
| `ah.fullScanMinutes` | `30` | how often to download every auction again |
| `ah.pollPages` | `2` | pages of new auctions checked every minute |
| `whitelist` | empty | IDs notified whenever they are a flip at all, whatever the minimum profit, percentage and sales |
| `ah.itemMinProfit` | empty | own minimum profit for some items, e.g. `HYPERION=20m,JUJU_SHORTBOW=1m` |
| `ah.minPrice` | `0` | ignores auctions cheaper than this |
| `ah.npc.enabled` / `ah.npc.minProfit` | `on` / `100k` | auction → NPC flips |
| `tooltip.enabled` / `tooltip.lowballPercent` | `on` / `85` | value and lowball price in item tooltips |
| `bz.intervalSeconds` | `20` | how often to check the bazaar |
| `bz.flip.minMarginPercent` | `5` | minimum margin of order flips |
| `bz.flip.minProfitPerHour` | `1m` | minimum potential profit per hour |
| `bz.crash.dropPercent` | `20` | minimum drop to report a crash |
| `bz.crash.minProfit` | `500k` | minimum estimated profit of a crash |
| `bz.npc.minProfit` | `200k` | minimum profit of a bazaar → NPC flip |
| `other.<kind>.notify` | `on` | alerts for Kat, Craft, Forge, Fusion, NPC and Book flips, one switch each |
| `other.kat.minProfit` | `5m` | minimum profit of a Kat flip |
| `other.craft.minProfit` / `other.craft.minVolume` | `2m` / `5` | minimum profit and sales a day of a craft |
| `other.forge.minProfitPerHour` | `1m` | minimum profit per hour of forging |
| `other.forge.hotm` | `0` | your Heart of the Mountain level: hides the forges you cannot do (0 = show all) |
| `other.fusion.minProfit` | `200k` | minimum profit of a shard fusion |
| `other.npc.minProfit` / `other.npc.minVolume` | `1m` / `1` | minimum profit and sales a day of an NPC flip |
| `other.books.minProfit` | `500k` | minimum profit of a book combination |
| `other.refreshMinutes` / `other.cooldownHours` | `5` / `6` | how often the lists are read, and when the same flip may be alerted again |
| `mayor.notify` | `on` | the election alert (and the analysis behind it) |
| `mayor.items` / `mayor.minChange` | `80` / `8` | items the mayor analysis reads, and the smallest change worth showing (%) |
| `source.coflnet` | `on` | uses SkyCofl data (sales of rare items, volumes, bazaar names, the lists, history, mayors) |
| `tracker.enabled` | `on` | recognizes purchases and sales in chat and counts your profits |
| `tracker.messages` | `on` | says in chat when it records an auction purchase or closes a flip |
| `discord.webhook` | empty | Discord channel webhook for trade reports (see below) |
| `discord.auctions` / `discord.bazaar` | `on` / `off` | which trades to send to Discord |
| `discord.minCoins` | `0` | skips trades below this amount |

The taxes (`ah.fee.*`, `bz.tax`) are Hypixel's current ones: 1% to create an auction under 10M, 2% up to 100M, 2.5%
above, plus 1% when collecting 1M or more; bazaar 1.25%. If Hypixel changes them, update them there.

## How prices are estimated

Sources:

- **Hypixel's official API** (public, no key): every active auction, the auctions sold in the last minute, the bazaar
  and NPC sell prices. From the sales the mod builds its own history, saved in `config/flipscout/sales.bin`, so the
  estimates get better after a few hours of play.
- **Coflnet** (`sky.coflnet.com`): median and number of sales over the last days, for items the mod does not have
  enough sales of yet. It asks in advance, in the background, starting from the most expensive items (at most 6
  requests a minute, each answer is kept for 6 hours), and to confirm a possible flip. At most 15 requests a minute in
  all. Coflnet also provides the names of items missing from Hypixel's list (shards, many enchantments): they are needed
  to open the right product with `/bz`.

An item's value (`conservative` strategy) is the **lower** of the lowest BIN of the *other* sellers and the median of
recent sales: an item is worth what it sells for, but no more than what it can be bought for. With a single reference
the minimum percentage doubles and the notification gets a ⚠; if that reference is the lowest BIN, at least 3 other
auctions of the same item are also required, because with one or two it is often just an inflated price. And if in that
case the margin is above 100%, the flip is only shown when Coflnet confirms it: for rarely traded items every listing
can be inflated. A bazaar item put up for auction is valued at the bazaar's instant-sell price.

So that different things are never compared: pets are grouped by type, rarity and level range (and skin), and their
sales per day come from Coflnet with the same filters; books by enchantment and level, runes by type and level, armor
with attributes by attribute combination, Midas weapons by the range of the winning Dark Auction bid, party hats by
color, emoji and year. Pet candy is shown (see above) rather than used to split pets, which would leave too few
comparable listings.

**Upgrades.** A weapon full of upgrades listed at the price of a plain one is a deal. The mod recognizes:

- recombobulators, hot and fuming potato books;
- stars: essence stars (and items) according to the costs Hypixel publishes for each item, and master stars after the
  fifth star of dungeon items. The extra stars of Crimson Isle armor are paid with crimson essence, not master stars;
- enchantments that do not come from the table, gemstones;
- Necron's Blade scrolls, Art of War and Art of Peace, Wood Singularity, Farming for Dummies, Transmission Tuners and
  the like;
- dyes, applied skins, drill parts (each counted once, even when the item stores it in two formats) and fishing rod
  parts, pet held items.

Each piece is worth its price (bazaar instant-sell, otherwise auction) times a share that depends on its kind: 100% for
master stars, gemstones and scrolls, 85% for enchantments, 80% for the recombobulator, 75% for star essence, and so on.

- **What counts:** the difference from the upgrades of the cheapest auction of the same item, and only 80% of it
  (`ah.upgradePercent`). Upgrades can at most double the value.
- **Fewer upgrades count too:** if the cheapest auction has more upgrades than the one you are looking at, the value
  goes down (by up to half), so a plain item does not look like a deal just because the other listings are upgraded.
- **More margin when needed:** if an auction is only a flip thanks to its upgrades, the minimum percentage doubles and
  the notification says `+upgrades`.
- **Details:** hover to see the pieces counted.

## Profits

The mod reads Hypixel's chat messages and keeps track of what you buy and sell:

- **Auctions:**
  - "You purchased …" when you buy a BIN;
  - "[Auction] … bought …" when someone buys your auction while you are online;
  - "You collected … coins from selling …" when you collect the coins, including sales made while you were offline.

  Each sale counts once.
- **Bazaar:** instant buys and sells, buy orders and sell offers when you claim them, and buy orders "flipped" straight
  into sell offers (at the buy order's price).

Each sale is matched with the oldest purchase of the same item, even if you reforged it in the meantime. The profit is
the revenue after taxes minus what you paid. Sales of things you did not buy (drops, farming) do not count towards the
profit.

- **Session:** starts by itself. `/flipscout session pause` stops counting (the total goes on), `resume` restarts it and
  `reset` moves it to the history and starts a new one. Time only counts while you play with the session running.
- **Today and total:** always count. `/flipscout total reset confirm` clears the total.
- **Where it is stored:** everything is saved in `config/flipscout/profits.json` (versions up to 1.2.0 called it
  `guadagni.json`: it is renamed automatically).

Auctions won with a bid (not BIN) have no price in the message, so they are not counted as purchases. If a purchase or
a sale is not recognized, the exact line is in `logs/latest.log`: it helps fix the recognition.

## Discord reports

Every auction purchase and sale (and, if you want, bazaar ones) can be posted to a channel of your Discord server, with
the item, the price and, when a sale closes a flip, what you paid, the profit and how long you held it. A purchase the
mod notified also shows the expected profit. The color tells how it went: green profit, red loss, yellow a sale of
something the mod did not see you buy.

1. On Discord: *Edit Channel → Integrations → Webhooks → New Webhook → Copy Webhook URL*.
2. In game: `/flipscout gui` → **Discord** → paste the address in the *Channel webhook* field (Ctrl+V).
3. `/flipscout discord test` sends a test message.

Anyone with the webhook address can post in the channel: the mod never shows it in chat, and you should not share it.
If several people use the same webhook, each message carries the name and head of whoever made the trade. Reports cover
the trades the profit tracker recognizes, so with `tracker.enabled off` they stop too.

## Coop

Every Hypixel auction lists the members of the profile it was created on, so the mod recognizes your coop by itself as
soon as you or a member list something (`/flipscout coop` shows who it found). Your own and your coop's auctions are
never notified as flips (buying them back would only cost the taxes), and in `/flipscout recent` a flip taken by a
member is marked with their name instead of as sold to someone else. The profit tracker stays personal: everyone sees
their own purchases and sales.

## Other flips (SkyCofl)

Some flips need recipes and costs Hypixel does not publish. FlipScout takes them from SkyCofl's free API and prices each
one again with live data: ingredients and results on the bazaar at their current instant prices, auction items with the
fees and your valuation strategy, Kat flips only while their auction is still listed (at its current price).

| Command | Flip |
|---|---|
| `/flipscout kat` | buy a pet, upgrade its rarity at Kat, sell it (cost of Kat and materials, upgrade time, profit per hour); click opens the auction |
| `/flipscout craft` | craft an item from bazaar ingredients and sell it; the hover lists the ingredients and the collection it needs; click types `/viewrecipe` |
| `/flipscout forge` | forge an item in the Dwarven Mines (time, profit per hour, HotM level needed). Set your level with `other.forge.hotm` to hide the forges you cannot do |
| `/flipscout fusion` | fuse shards in the Fusion Machine |
| `/flipscout npc` | buy from an NPC (coins and items) and resell |
| `/flipscout books` | combine enchanted books in an anvil (two per level) |
| `/flipscout lowsupply` | items with few auctions listed, most traded first |

Each kind can also alert you in chat (`other.<kind>.notify`, on by default, each with its own thresholds). After joining,
the first read of each list only sums up in one line how many flips are above your thresholds; afterwards new ones are
alerted, at most three per list each time, and the same flip again only after `other.cooldownHours`.

## Mayors and elections

`/flipscout mayor` shows the mayor and minister with their perks, when the next mayor takes office, and the open
election with each candidate's share of the votes. For the **favourite** (and for the mayor in office) it lists the items
that usually rise or fall during their term.

How: for about 80 of the most traded bazaar products and auction items, the mod reads a year of daily prices and a year of
election results (SkyCofl), and compares the price during each term with the ten days around it, so long trends do not
count as a mayor's effect. The effect is the median over that mayor's terms, kept when most terms agree (e.g. `+15%
(2/2 terms)`). This runs in the background every three days (one request every 1.2 seconds, about two minutes) and is
saved in `config/flipscout/mayor.json`. It shows a tendency, not a promise.

With `mayor.notify` on, once per election, when a candidate is clearly ahead, a chat line names the items that usually
move under them.

## History, players, search and the museum

- `/flipscout history <item> [day|week|month|all]`: the price history as a bar chart in chat (hover a bar for its date,
  average, lowest and highest price and sales), plus now, average, lowest, highest and change. Bazaar products show the
  instant-buy price.
- `/flipscout player <name>`: the player's BIN auctions up for sale now (from the mod's own copy of the auction house)
  and the last auctions they created with what they sold for (SkyCofl). `/flipscout blacklist add player:<name>` stops
  their auctions from ever being notified.
- `/flipscout search <name> [max:<price>]`: the cheapest BIN auctions whose name contains every word.
- `/flipscout museum`: the cheapest museum experience, items and whole armor sets (each piece at its cheapest), by
  coins per XP. The mod cannot know what you already donated.
- `/flipscout export`: the closed flips or the notifications as CSV, or the bazaar as JSON, for spreadsheets.

## Credits

Made by **yrqs** and **baldih**. `/flipscout credits` lists the sources in game.

- **Hypixel public API**: auctions, sold auctions, bazaar, item list (with museum data), elections.
- **SkyCofl API** ([sky.coflnet.com](https://sky.coflnet.com)): Kat, Craft, Forge, Fusion, NPC and Book flips, low
  supply items, price and bazaar history, past mayors, player auctions, sales prices and item names.
- **Mojang API**: player names and UUIDs.

## Privacy and network use

FlipScout only talks to these services, and only for what is listed:

| Service | What for | What it sends |
|---|---|---|
| `api.hypixel.net` | auctions, sold auctions, bazaar, item list | nothing about you (public endpoints, no API key) |
| `sky.coflnet.com` | prices, sales volumes and names of items; the flip lists; price history; past mayors; a player's auctions when you ask | item IDs; the UUID of a player you look up |
| `sessionserver.mojang.com` | the names of your coop members | their public UUIDs, taken from auctions |
| `api.mojang.com` | the UUID of a player you look up or blacklist (`/flipscout player`, `player:NAME`) | that player's name |
| your Discord webhook | trade reports, only if you set one | your Minecraft name, the item, prices and profit; Discord loads your head from `mc-heads.net` and the item picture from Coflnet |

There is no analytics or telemetry, and nothing is sent to the author. Everything the mod saves stays in
`config/flipscout/` on your PC: `flipscout.properties` (settings), `profits.json` (profits), `sales.bin` (sales
history), `mayor.json` (the mayor analysis) and `exports/` (files you export). Network use is a few MB a minute, mostly for the full auction scan (the checks every half second only read the
start of the response). To lower it, raise `ah.fullScanMinutes` and `bz.intervalSeconds`, or set `ah.pollPages` to 1.

## Good to know

- `/bz` away from the hub needs a Booster Cookie.
- On Fabric 1.21.6 and later, if Hypixel does not declare the `/viewauction` command, Minecraft asks for confirmation
  before running it (one extra click). `/flipscout open` sends it directly.
- If a source does not answer, the mod carries on with the others; `/flipscout status` shows which one has problems. The
  source addresses can be changed (`url.hypixel`, `url.coflnet`).
- The official launcher runs 1.8.9 on an old Java 8 that does not know some recent HTTPS certificates: the mod bundles
  the roots it needs and uses them only for its own connections.
- Commands from versions before 1.3.0 (`guadagni`, `sessione`, `totale`, `magazzino`, ...) still work.

## Publishing on Modrinth

FlipScout is **All Rights Reserved** (see `LICENSE`): you can upload the official jars to Modrinth, and nobody else may
redistribute or modify them. To publish it:

1. **Build the jars**: push to GitHub and download the artifacts of the **Build** run (all 8 jobs must be green), or
   build them yourself as above. Upload the jar named like `flipscout-fabric-1.21.8-1.4.0.jar` or
   `flipscout-forge-1.8.9-1.4.0.jar` (not one ending in `-sources` or `-dev`, if present).
2. **Create the project** on [modrinth.com](https://modrinth.com) → *Create a project*: type **Mod**, name
   **FlipScout**, URL `flipscout`, summary e.g. *"Flip notifier for Hypixel SkyBlock: underpriced BIN auctions and bazaar
   deals in chat, with a profit tracker. Never buys anything by itself."*
3. **Project settings:**
   - *Icon*: `fabric-common/src/main/resources/assets/flipscout/icon.png` (512×512);
   - *Description*: the sections of this README from "What it finds" to "Privacy and network use" work as they are.
     Keep the privacy section: it tells players, and Modrinth's reviewers, exactly what the mod connects to;
   - *Categories*: **Economy** and **Utility**;
   - *Environment*: **client-side only** (required on the client, not on servers);
   - *License*: **All Rights Reserved**;
   - *Links*: source code and issues are optional; leave them out if the repository stays private.
4. **Versions**: one Modrinth version per jar, since each targets different game versions:

   | Jar | Loader | Game versions | Dependency |
   |---|---|---|---|
   | `flipscout-forge-1.8.9-1.4.0.jar` | Forge | 1.8.9 | none |
   | `flipscout-fabric-1.21.8-1.4.0.jar` | Fabric | 1.21.7, 1.21.8 | Fabric API (required) |
   | `flipscout-fabric-1.21.10-1.4.0.jar` | Fabric | 1.21.9, 1.21.10 | Fabric API (required) |
   | `flipscout-fabric-1.21.11-1.4.0.jar` | Fabric | 1.21.11 | Fabric API (required) |
   | `flipscout-fabric-26.1.2-1.4.0.jar` | Fabric | 26.1.x | Fabric API (required) |
   | `flipscout-fabric-26.2-1.4.0.jar` | Fabric | 26.2.x | Fabric API (required) |
   | `flipscout-fabric-26.3-1.4.0.jar` | Fabric | 26.3.x | Fabric API (required) |

   Give each a version number like `1.4.0+fabric-1.21.8` or `1.4.0+forge-1.8.9`, channel **Release**, and paste the
   version's section of `CHANGELOG.md` into the changelog.
5. **Submit for review.** Modrinth's moderators check new projects before they go public, which can take a few days.

For a new release: raise the version in `core/build.gradle.kts`, `FlipScout.VERSION` and the three `gradle.properties`
files, push, and upload the new jars as new versions of the same project.
