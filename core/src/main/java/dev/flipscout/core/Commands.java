package dev.flipscout.core;

import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.flip.BazaarFlip;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import dev.flipscout.core.profit.ProfitTracker;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** /flipscout and its sub-commands. Italian names from earlier versions still work as aliases. */
final class Commands {
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "gui", "on", "off", "ah", "bz", "top", "recent", "open", "profits", "session", "total", "flips",
        "stock", "discord", "coop", "set", "config", "blacklist", "status", "reload", "test");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final List<String> ON_OFF = Arrays.asList("on", "off");
    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();

    static {
        ALIASES.put("minprofit", "ah.minProfit");
        ALIASES.put("minpercent", "ah.minPercent");
        ALIASES.put("minvolume", "ah.minVolume");
        ALIASES.put("strategy", "ah.strategy");
    }

    private final Config config;
    private final File configFile;
    private final Engine engine;
    private final ProfitTracker tracker;
    private final Discord discord;
    private final Platform platform;
    private final LongSupplier clock;

    Commands(Config config, File configFile, Engine engine, ProfitTracker tracker, Discord discord, Platform platform,
             LongSupplier clock) {
        this.config = config;
        this.configFile = configFile;
        this.engine = engine;
        this.tracker = tracker;
        this.discord = discord;
        this.platform = platform;
        this.clock = clock;
    }

    void execute(String[] rawArgs) {
        String[] args = clean(rawArgs);
        if (args.length == 0) {
            summary();
            return;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "help":
                case "aiuto":
                case "?":
                    help();
                    break;
                case "gui":
                case "menu":
                case "impostazioni":
                case "settings":
                    platform.openConfigScreen();
                    break;
                case "on":
                    toggleAll(true);
                    break;
                case "off":
                    toggleAll(false);
                    break;
                case "ah":
                    auctions(args);
                    break;
                case "bz":
                    bazaar(args);
                    break;
                case "top":
                    top(args);
                    break;
                case "recent":
                case "ultimi":
                    recent();
                    break;
                case "open":
                case "apri":
                    open();
                    break;
                case "set":
                    set(args);
                    break;
                case "config":
                    showConfig(args);
                    break;
                case "blacklist":
                    blacklist(args);
                    break;
                case "profits":
                case "profit":
                case "guadagni":
                case "profitti":
                    profits();
                    break;
                case "session":
                case "sessione":
                    session(args);
                    break;
                case "total":
                case "totale":
                    total(args);
                    break;
                case "flips":
                case "flip":
                    flips();
                    break;
                case "stock":
                case "inventory":
                case "magazzino":
                    inventory(args);
                    break;
                case "discord":
                    discord(args);
                    break;
                case "coop":
                    coop();
                    break;
                case "status":
                case "stato":
                    status();
                    break;
                case "reload":
                    config.load(configFile);
                    Log.debugEnabled = config.debug;
                    info("§aSettings reloaded from §f" + configFile.getName());
                    break;
                case "test":
                    test();
                    break;
                default:
                    error("Unknown command: " + args[0] + ". Type /flipscout help");
            }
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
        }
    }

    private void summary() {
        String state = !config.enabled ? "§cdisabled"
            : config.onlyOnHypixel && !platform.isOnHypixel() ? "§eenabled, waiting for Hypixel" : "§aactive";
        info("§fFlipScout " + FlipScout.VERSION + " §8- " + state
            + " §8| §7auctions " + onOff(config.ahEnabled) + " §7bazaar " + onOff(config.bzEnabled));
        platform.showMessage(new Msg()
            .run("§6[SETTINGS] ", "/flipscout gui", "§7Opens the settings menu")
            .suggest("§7Type §e/flipscout help §7for the commands", "/flipscout help", "§eClick to type it"));
    }

    private void help() {
        info("§fCommands §8(click to type them):");
        helpLine("gui", "gui", "opens the settings menu");
        helpLine("on|off", "", "turns everything on or off");
        helpLine("ah on|off", "ah ", "BIN auction notifications");
        helpLine("bz on|off", "bz ", "bazaar notifications (also: bz flip|crash|npc on|off)");
        helpLine("top [ah|bz] [n]", "top ", "the best deals right now");
        helpLine("recent", "recent", "latest notifications");
        helpLine("open", "open", "opens the latest notification");
        helpLine("profits", "profits", "how much you made: session, today, total");
        helpLine("session [pause|resume|reset|history]", "session ", "manages the session");
        helpLine("total [reset]", "total", "total and the last few days");
        helpLine("flips", "flips", "the latest closed flips");
        helpLine("stock [clear]", "stock", "bought and not sold yet");
        helpLine("discord [test]", "discord", "Discord reports: status and a test message");
        helpLine("coop", "coop", "your coop members, recognized from their auctions");
        helpLine("set <key> <value>", "set ", "changes a setting (e.g. set ah.minProfit 1m)");
        helpLine("config [filter]", "config", "shows the settings");
        helpLine("blacklist add|remove|list [ID]", "blacklist ", "items to ignore");
        helpLine("status", "status", "data source status");
        helpLine("reload", "reload", "reloads the settings file");
        helpLine("test", "test", "shows a test notification");
    }

    private void helpLine(String usage, String suggestion, String description) {
        platform.showMessage(new Msg().suggest("§e/flipscout " + usage + " §7- " + description,
            "/flipscout " + suggestion, "§7Click to type the command"));
    }

    private void toggleAll(boolean on) {
        config.enabled = on;
        config.save(configFile);
        info(on ? "§aFlipScout enabled." : "§cFlipScout disabled: no requests until you turn it back on.");
    }

    private void auctions(String[] args) {
        if (args.length < 2) {
            info("§7Auction notifications: " + onOff(config.ahEnabled));
            return;
        }
        config.ahEnabled = Config.parseBoolean(args[1]);
        config.save(configFile);
        info("§7Auction notifications: " + onOff(config.ahEnabled));
    }

    private void bazaar(String[] args) {
        if (args.length < 2) {
            info("§7Bazaar: " + onOff(config.bzEnabled) + " §8| §7flip " + onOff(config.bzFlipEnabled)
                + " §7crash " + onOff(config.bzCrashEnabled) + " §7NPC " + onOff(config.bzNpcEnabled));
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        if (ON_OFF.contains(what)) {
            config.bzEnabled = Config.parseBoolean(what);
        } else {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: /flipscout bz " + what + " on|off");
            }
            boolean on = Config.parseBoolean(args[2]);
            if ("flip".equals(what)) {
                config.bzFlipEnabled = on;
            } else if ("crash".equals(what) || "crollo".equals(what)) {
                config.bzCrashEnabled = on;
            } else if ("npc".equals(what)) {
                config.bzNpcEnabled = on;
            } else {
                throw new IllegalArgumentException("Usage: /flipscout bz on|off or bz flip|crash|npc on|off");
            }
        }
        config.save(configFile);
        bazaar(new String[] {"bz"});
    }

    private void top(String[] args) {
        String which = "ah";
        int limit = 10;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if ("ah".equals(arg) || "bz".equals(arg)) {
                which = arg;
            } else {
                try {
                    limit = Math.max(1, Math.min(30, Integer.parseInt(arg)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Usage: /flipscout top [ah|bz] [number]");
                }
            }
        }
        final boolean auctions = "ah".equals(which);
        final int n = limit;
        if (auctions && !engine.initialScanDone()) {
            info("§eStill downloading the auctions, try again shortly.");
            return;
        }
        info("§7Searching...");
        engine.runAsync(new Runnable() {
            @Override
            public void run() {
                long now = engine.serverTime();
                if (auctions) {
                    List<AuctionFlip> flips = engine.topAuctions(n);
                    if (flips.isEmpty()) {
                        info("§7No BIN auctions above your thresholds right now.");
                        return;
                    }
                    info("§fBest BIN auctions right now:");
                    for (AuctionFlip flip : flips) {
                        platform.showMessage(engine.notifier().auction(flip, now));
                    }
                } else {
                    BazaarSnapshot snapshot = engine.prices.bazaar();
                    if (snapshot == null) {
                        info("§eBazaar data not downloaded yet, try again shortly.");
                        return;
                    }
                    List<BazaarFlip> flips = engine.topBazaar(n);
                    if (flips.isEmpty()) {
                        info("§7No bazaar deals above your thresholds right now.");
                        return;
                    }
                    info("§fBest bazaar deals right now:");
                    for (BazaarFlip flip : flips) {
                        platform.showMessage(engine.notifier().bazaar(flip));
                    }
                }
            }
        });
    }

    private void recent() {
        List<Engine.Notification> notifications = engine.recent();
        if (notifications.isEmpty()) {
            info("§7No notifications yet in this session.");
            return;
        }
        info("§fLatest notifications §8(newest at the bottom)" + soldSummary(notifications) + "§f:");
        long now = clock.getAsLong();
        List<Engine.Notification> ordered = new ArrayList<Engine.Notification>(notifications);
        Collections.reverse(ordered);
        for (Engine.Notification notification : ordered) {
            Msg msg = new Msg().add("§8" + Text.duration(now - notification.time) + " ago ");
            for (Msg.Part part : notification.message.parts()) {
                String text = part.text.startsWith(Msg.PREFIX) ? part.text.substring(Msg.PREFIX.length()) : part.text;
                if (part.command != null && part.click == Msg.Click.RUN) {
                    msg.run(text, part.command, part.hover);
                } else {
                    msg.add(text, part.hover);
                }
            }
            addSaleState(msg, notification);
            platform.showMessage(msg);
        }
    }

    /** How many of the notified auctions already sold, and how fast. */
    private static String soldSummary(List<Engine.Notification> notifications) {
        int auctions = 0;
        List<Long> times = new ArrayList<Long>();
        for (Engine.Notification notification : notifications) {
            if (notification.auctionUuid == null) {
                continue;
            }
            auctions++;
            if (notification.soldAt() > 0 && notification.listedAt > 0) {
                times.add(Math.max(0, notification.soldAt() - notification.listedAt));
            }
        }
        if (auctions == 0) {
            return "";
        }
        if (times.isEmpty()) {
            return " §8- none of the " + auctions + " auctions has sold yet";
        }
        Collections.sort(times);
        return " §8- " + times.size() + " of " + auctions + " auctions already sold, half within "
            + Text.shortDuration(times.get((times.size() - 1) / 2)) + " of being listed";
    }

    private void addSaleState(Msg msg, Engine.Notification notification) {
        if (notification.auctionUuid == null) {
            return;
        }
        long soldAt = notification.soldAt();
        if (soldAt > 0) {
            if (notification.buyer() == Engine.Buyer.YOU) {
                msg.add(" §a[bought by you]");
                return;
            }
            if (notification.buyer() == Engine.Buyer.COOP) {
                msg.add(" §a[bought by " + coopBuyer(notification) + "]");
                return;
            }
            String after = Text.shortDuration(soldAt - notification.listedAt);
            long afterNotice = soldAt - notification.notifiedAt;
            msg.add(" §c[sold after " + after + "]", "§7Bought " + after + " after being listed, "
                + (afterNotice >= 0 ? Text.shortDuration(afterNotice) + " after the notification" : "before the notification"));
        } else if (!engine.index.contains(notification.auctionUuid)) {
            msg.add(" §8[no longer listed]", "§7No longer among the active auctions: bought, cancelled or expired");
        }
    }

    void open() {
        Engine.Notification last = engine.lastNotification();
        if (last == null || last.command == null) {
            info("§7No notification to open yet.");
            return;
        }
        if (last.soldAt() > 0) {
            info(last.buyer() == Engine.Buyer.YOU ? "§aYou already bought this auction."
                : last.buyer() == Engine.Buyer.COOP ? "§aThis auction was already bought by " + coopBuyer(last) + "."
                : "§cSomeone else already bought this auction, not opening it.");
            return;
        }
        platform.sendCommand(last.command.startsWith("/") ? last.command.substring(1) : last.command);
    }

    private static String coopBuyer(Engine.Notification notification) {
        return notification.buyerName() != null ? notification.buyerName() + " §7(your coop)§a" : "your coop";
    }

    private void set(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: /flipscout set <key> <value>. See /flipscout config");
        }
        Config.Setting setting = findSetting(args[1]);
        if (setting == null) {
            throw new IllegalArgumentException("Unknown setting: " + args[1] + ". See /flipscout config");
        }
        // Text settings may be emptied by giving no value.
        if (args.length < 3 && setting.kind() != Config.Setting.Kind.TEXT) {
            throw new IllegalArgumentException("Usage: /flipscout set " + setting.key + " <value>");
        }
        String value = join(args, 2);
        try {
            setting.set(config, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting.key + ": " + e.getMessage());
        }
        config.save(configFile);
        info("§a" + setting.key + " §7= §f" + display(setting));
    }

    private static Config.Setting findSetting(String name) {
        String alias = ALIASES.get(name.toLowerCase(Locale.ROOT));
        return Config.find(alias != null ? alias : name);
    }

    private void showConfig(String[] args) {
        String filter = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean any = false;
        for (Config.Section section : Config.SECTIONS) {
            boolean header = false;
            for (Config.Setting setting : section.settings) {
                if (!setting.key.toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                if (!header) {
                    platform.showMessage(Msg.plain("§6" + section.title + ":"));
                    header = true;
                }
                any = true;
                platform.showMessage(new Msg().suggest("§e" + setting.key + " §7= §f" + display(setting),
                    "/flipscout set " + setting.key + " ", "§7" + setting.help + "\n§eClick to change it"));
            }
        }
        if (!any) {
            info("§7No setting contains \"" + filter + "\".");
        }
    }

    private String display(Config.Setting setting) {
        String value = setting.get(config);
        if (setting.secret()) {
            // Never in chat: it would end up in screenshots.
            return value.isEmpty() ? "§8(empty)" : "§aset §8(hidden, change it from /flipscout gui)";
        }
        if (setting instanceof Config.CoinsSetting) {
            long coins = Long.parseLong(value);
            return coins == 0 && "budget".equals(setting.key) ? "no limit" : Coins.compact(coins);
        }
        if ("true".equals(value) || "false".equals(value)) {
            return onOff(Boolean.parseBoolean(value));
        }
        return value.isEmpty() ? "§8(empty)" : value;
    }

    private void blacklist(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if ("list".equals(action)) {
            if (config.blacklist.isEmpty()) {
                info("§7The blacklist is empty.");
                return;
            }
            info("§fBlacklist §8(click to remove):");
            for (String id : config.blacklist) {
                platform.showMessage(new Msg().suggest("§7- §f" + id, "/flipscout blacklist remove " + id,
                    "§eClick to remove it"));
            }
            return;
        }
        if (args.length < 3 || !("add".equals(action) || "remove".equals(action))) {
            throw new IllegalArgumentException("Usage: /flipscout blacklist add|remove|list [ID]");
        }
        String id = args[2].toUpperCase(Locale.ROOT);
        Set<String> updated = new LinkedHashSet<String>(config.blacklist);
        boolean changed = "add".equals(action) ? updated.add(id) : updated.remove(id);
        config.blacklist = Collections.unmodifiableSet(updated);
        config.save(configFile);
        if ("add".equals(action)) {
            info(changed ? "§a" + id + " §7added to the blacklist." : "§7" + id + " was already in the blacklist.");
        } else {
            info(changed ? "§a" + id + " §7removed from the blacklist." : "§7" + id + " was not in the blacklist.");
        }
    }

    private void status() {
        Status s = engine.status;
        long now = clock.getAsLong();
        info("§fStatus");
        String active = !config.enabled ? "§cno (disabled)"
            : s.active ? "§ayes" : "§eno, you are not on Hypixel";
        line("Active", active);
        line("Indexed BIN auctions", Coins.full(engine.index.size()) + " §8(" + Coins.full(engine.index.keyCount())
            + " different items)");
        if (s.scanning) {
            line("Full scan", "§ein progress...");
        } else if (s.lastFullScan > 0) {
            line("Last full scan", Text.ago(now, s.lastFullScan) + " §8(" + s.fullScanPages + " pages, "
                + Coins.full(s.fullScanAuctions) + " auctions, " + Text.duration(s.fullScanMillis) + ")");
        } else {
            line("Last full scan", "never");
        }
        line("New auctions check", Text.ago(now, s.lastAuctionPoll));
        if (s.lastRefreshDelay > 0) {
            line("Last update", "seen " + Coins.decimal(s.lastRefreshDelay / 1000.0, 1)
                + " s after Hypixel built it §8(it usually publishes it after "
                + Coins.decimal(s.publishDelay / 1000.0, 1) + " s)");
        }
        line("Recorded sales", Coins.full(engine.sales.sampleCount()) + " §8(" + Coins.full(engine.sales.keyCount())
            + " items, " + Text.duration(engine.sales.observedMinutes() * 60_000L) + " of data)");
        BazaarSnapshot bazaar = engine.prices.bazaar();
        line("Bazaar", bazaar == null ? "not downloaded yet"
            : bazaar.products.size() + " products, checked " + Text.ago(now, s.lastBazaarPoll));
        line("Coflnet", config.sourceCoflnet
            ? Coins.full(engine.coflnet.cachedPrices()) + " prices cached, last answer "
                + Text.ago(now, engine.coflnet.lastSuccess())
                + (engine.coflnet.lastError() != null ? " §8(" + engine.coflnet.lastError() + ")" : "")
            : "§8disabled");
        line("Known items", Coins.full(engine.items().size()) + " §8(+ " + Coins.full(engine.items().nameCount())
            + " names from Coflnet)");
        List<String> members = engine.coop.memberNames();
        if (!members.isEmpty()) {
            line("Coop", String.join(", ", members) + " §8(their auctions are never notified)");
        }
        line("Notifications this session", s.auctionNotifications + " auctions, " + s.bazaarNotifications + " bazaar");
        line("Discord", discordState(now));
        if (config.trackerEnabled) {
            ProfitTracker.Session session = tracker.session();
            line("Profits", "session " + ProfitTracker.signed(session.stats.profit) + (session.paused ? " §e(paused)" : "")
                + " §8| §7today " + ProfitTracker.signed(tracker.today().profit)
                + " §8| §7total " + ProfitTracker.signed(tracker.total().profit));
        }
        for (Map.Entry<String, String> error : s.errors().entrySet()) {
            platform.showMessage(Msg.plain("§c  Error " + error.getKey() + ": §7" + error.getValue()));
        }
    }

    private String discordState(long now) {
        if (config.discordWebhook.isEmpty()) {
            return "§8no webhook";
        }
        if (!config.discordEnabled) {
            return "§8off";
        }
        String state = "§aactive §8(last sent " + Text.ago(now, discord.lastSent()) + ")";
        return discord.lastError() != null ? state + " §c" + discord.lastError() : state;
    }

    private void discord(String[] args) {
        if (args.length > 1 && "test".equalsIgnoreCase(args[1])) {
            info("§7Sending a test message...");
            discord.test();
            return;
        }
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: /flipscout discord [test]");
        }
        info("§fDiscord reports: " + discordState(clock.getAsLong()));
        line("Auctions", onOff(config.discordAuctions) + " §8| §7bazaar " + onOff(config.discordBazaar)
            + " §8| §7minimum amount §f" + (config.discordMinCoins > 0 ? Coins.compact(config.discordMinCoins) : "none"));
        platform.showMessage(new Msg()
            .run("§6[SETTINGS] ", "/flipscout gui", "§7Paste the webhook in the menu, Discord section")
            .run("§a[TEST]", "/flipscout discord test", "§7Sends a test message to the channel"));
    }

    private void coop() {
        List<String> members = engine.coop.memberNames();
        if (members.isEmpty()) {
            info("§7No coop members found yet. §8They are recognized from auctions: as soon as you or a member"
                + " list something, they show up here.");
            return;
        }
        info("§fYour coop: §a" + String.join("§7, §a", members));
        line("What changes", "their auctions are never notified as flips, and §e/flipscout recent §fshows when"
            + " they took a flip");
    }

    private void test() {
        long now = clock.getAsLong();
        ItemKey key = new ItemKey("HYPERION", "HYPERION", "HYPERION", null);
        AuctionIndex.Listing listing = new AuctionIndex.Listing("test", null, "Hyperion", "LEGENDARY", key,
            820_000_000L, 1, now - 40_000L, now + 3_600_000L, now, 0);
        Valuation valuation = new Valuation();
        valuation.lbinOther = 905_000_000;
        valuation.otherListings = 12;
        valuation.salesMedian = 900_000_000;
        valuation.salesSamples = 24;
        valuation.value = 900_000_000;
        valuation.references = 2;
        valuation.volumePerDay = 6.5;
        valuation.volumeSource = "Hypixel";
        double fees = dev.flipscout.core.flip.Fees.auctionSellFees(900_000_000, config);
        AuctionFlip flip = new AuctionFlip(listing, valuation, 900_000_000, fees, 900_000_000 - fees - 820_000_000,
            (900_000_000 - fees - 820_000_000) * 100.0 / 820_000_000, false);
        Msg sample = engine.notifier().auction(flip, now);
        Msg.Part part = sample.parts().get(0);
        info("§7Test notification: hover over it for the details. Real ones open the auction when clicked.");
        platform.showMessage(new Msg().suggest(part.text, "/flipscout help", part.hover));
        if (config.sound) {
            platform.playSound(true);
        }
    }

    // ---- Tab completion ----

    List<String> complete(String[] args) {
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        String last = args[args.length - 1];
        if (args.length == 1) {
            return startingWith(SUBCOMMANDS, last);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "ah":
                    return startingWith(ON_OFF, last);
                case "bz":
                    return startingWith(Arrays.asList("on", "off", "flip", "crash", "npc"), last);
                case "top":
                    return startingWith(Arrays.asList("ah", "bz"), last);
                case "set": {
                    List<String> keys = new ArrayList<String>(ALIASES.keySet());
                    for (Config.Setting setting : Config.allSettings()) {
                        keys.add(setting.key);
                    }
                    return startingWith(keys, last);
                }
                case "config":
                    return startingWith(Arrays.asList("ah.", "bz.", "tracker.", "discord.", "source.", "url."), last);
                case "discord":
                    return startingWith(Collections.singletonList("test"), last);
                case "blacklist":
                    return startingWith(Arrays.asList("add", "remove", "list"), last);
                case "session":
                    return startingWith(Arrays.asList("pause", "resume", "reset", "history"), last);
                case "total":
                    return startingWith(Collections.singletonList("reset"), last);
                case "stock":
                    return startingWith(Collections.singletonList("clear"), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3) {
            if ("total".equals(sub) && "reset".equalsIgnoreCase(args[1])) {
                return startingWith(Collections.singletonList("confirm"), last);
            }
            if ("bz".equals(sub)) {
                return startingWith(ON_OFF, last);
            }
            if ("top".equals(sub)) {
                return startingWith(Arrays.asList("5", "10", "20"), last);
            }
            if ("set".equals(sub)) {
                Config.Setting setting = findSetting(args[1]);
                if (setting == null) {
                    return Collections.emptyList();
                }
                List<String> values = setting.suggestions();
                if (values.isEmpty() && setting instanceof Config.CoinsSetting) {
                    values = Arrays.asList("100k", "500k", "1m", "5m", "10m");
                }
                return startingWith(values, last);
            }
            if ("blacklist".equals(sub)) {
                if ("remove".equalsIgnoreCase(args[1])) {
                    return startingWith(new ArrayList<String>(config.blacklist), last);
                }
                if ("add".equalsIgnoreCase(args[1])) {
                    return itemIds(last);
                }
            }
        }
        return Collections.emptyList();
    }

    private List<String> itemIds(String prefix) {
        if (prefix.length() < 2) {
            return Collections.emptyList();
        }
        Set<String> ids = new TreeSet<String>();
        String upper = prefix.toUpperCase(Locale.ROOT);
        ItemCatalog items = engine.items();
        for (String id : items.ids()) {
            if (id.startsWith(upper)) {
                ids.add(id);
            }
        }
        BazaarSnapshot bazaar = engine.prices.bazaar();
        if (bazaar != null) {
            for (String id : bazaar.products.keySet()) {
                if (id.startsWith(upper)) {
                    ids.add(id);
                }
            }
        }
        List<String> out = new ArrayList<String>(ids);
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }

    // ---- Profits ----

    private void profits() {
        if (!config.trackerEnabled) {
            info("§7Profit tracking is off: §e/flipscout set tracker.enabled on");
            return;
        }
        ProfitTracker.Session session = tracker.session();
        info("§fProfits");
        line("Session " + (session.paused ? "§e(paused)" : "§a(active)"), stats(session.stats) + " §8| "
            + Text.duration(session.activeMillis) + perHour(session));
        line("Today", stats(tracker.today()));
        line("Total", stats(tracker.total()) + " §8(since " + date(tracker.since()) + ")");
        List<ProfitTracker.Lot> lots = tracker.lots();
        List<ProfitTracker.Unclaimed> unclaimed = tracker.unclaimed();
        if (!lots.isEmpty() || !unclaimed.isEmpty()) {
            double cost = 0;
            for (ProfitTracker.Lot lot : lots) {
                cost += lot.unitCost * lot.amount;
            }
            line("Stock", count(lots.size(), "purchase") + " to resell §8(" + Coins.compact(cost) + ")"
                + (unclaimed.isEmpty() ? "" : "§f, " + count(unclaimed.size(), "sale") + " to collect"));
        }
        platform.showMessage(new Msg()
            .suggest("§e[SESSION] ", "/flipscout session", "§7Session details, pause and reset")
            .suggest("§e[LATEST FLIPS] ", "/flipscout flips", "§7The most recent closed flips")
            .suggest("§e[STOCK] ", "/flipscout stock", "§7Bought and not sold yet")
            .suggest("§e[TOTAL]", "/flipscout total", "§7Total and the last few days"));
    }

    private void session(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "":
                showSession();
                break;
            case "pause":
            case "stop":
            case "pausa":
                info(tracker.pauseSession() ? "§eSession paused: §7purchases and sales only count towards the total."
                    : "§7The session is already paused.");
                break;
            case "resume":
            case "start":
            case "riprendi":
                info(tracker.resumeSession() ? "§aSession resumed." : "§7The session is already active.");
                break;
            case "reset":
            case "new":
            case "nuova":
                ProfitTracker.Session old = tracker.session();
                tracker.resetSession();
                info("§aNew session started. §7The previous one (" + ProfitTracker.signed(old.stats.profit)
                    + "§7) is in §e/flipscout session history§7.");
                break;
            case "history":
            case "storico":
                showHistory();
                break;
            default:
                throw new IllegalArgumentException("Usage: /flipscout session [pause|resume|reset|history]");
        }
    }

    private void showSession() {
        ProfitTracker.Session s = tracker.session();
        info("§fSession " + (s.paused ? "§e(paused)" : "§a(active)") + " §8- started " + dateTime(s.startedAt)
            + ", " + Text.duration(s.activeMillis) + " played");
        details(s.stats);
        if (!Double.isNaN(s.profitPerHour())) {
            line("Per hour", ProfitTracker.signed(s.profitPerHour()));
        }
        Msg buttons = new Msg();
        if (s.paused) {
            buttons.suggest("§a[RESUME] ", "/flipscout session resume", "§7Counts in this session again");
        } else {
            buttons.suggest("§e[PAUSE] ", "/flipscout session pause", "§7Stops counting until you resume");
        }
        buttons.suggest("§c[RESET] ", "/flipscout session reset", "§7Archives this session and starts a new one");
        buttons.suggest("§7[HISTORY]", "/flipscout session history", "§7Previous sessions");
        platform.showMessage(buttons);
    }

    private void showHistory() {
        List<ProfitTracker.PastSession> history = tracker.history();
        if (history.isEmpty()) {
            info("§7No archived sessions: §e/flipscout session reset §7archives the current one.");
            return;
        }
        info("§fPrevious sessions §8(newest first):");
        for (int i = 0; i < Math.min(10, history.size()); i++) {
            ProfitTracker.PastSession past = history.get(i);
            platform.showMessage(Msg.plain("§7  " + dateTime(past.startedAt) + " §8(" + Text.duration(past.activeMillis)
                + "): " + stats(past.stats)));
        }
    }

    private void total(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("reset".equals(action)) {
            if (args.length > 2 && ("confirm".equalsIgnoreCase(args[2]) || "conferma".equalsIgnoreCase(args[2]))) {
                tracker.resetTotal();
                info("§aTotal reset. §7The current session and the stock are kept.");
            } else {
                platform.showMessage(new Msg().suggest(Msg.PREFIX + "§eAre you sure? Type §f/flipscout total reset confirm",
                    "/flipscout total reset confirm", "§cDeletes the total, the days, past sessions and the flip list"));
            }
            return;
        }
        if (!action.isEmpty()) {
            throw new IllegalArgumentException("Usage: /flipscout total [reset]");
        }
        info("§fTotal §8(since " + date(tracker.since()) + ")");
        details(tracker.total());
        List<Map.Entry<String, ProfitTracker.Stats>> days =
            new ArrayList<Map.Entry<String, ProfitTracker.Stats>>(tracker.days().entrySet());
        if (!days.isEmpty()) {
            platform.showMessage(Msg.plain("§7  Last few days:"));
            Collections.reverse(days);
            for (int i = 0; i < Math.min(7, days.size()); i++) {
                String day = days.get(i).getKey();
                platform.showMessage(Msg.plain("§7    " + shortDay(day) + ": " + stats(days.get(i).getValue())));
            }
        }
    }

    private void flips() {
        List<ProfitTracker.Flip> flips = tracker.flips();
        if (flips.isEmpty()) {
            info("§7No closed flips yet: a flip closes when you sell something the mod saw you buy.");
            return;
        }
        info("§fLatest flips §8(newest first, hover for the details):");
        for (int i = 0; i < Math.min(10, flips.size()); i++) {
            ProfitTracker.Flip f = flips.get(i);
            platform.showMessage(new Msg().add("§7  " + f.item + (f.amount > 1 ? " x" + f.amount : "") + " §8"
                + Coins.compact(f.cost) + " → " + Coins.compact(f.revenue) + " " + ProfitTracker.signed(f.profit),
                "§7Bought: §f" + dateTime(f.boughtAt) + "\n§7Sold: §f" + dateTime(f.soldAt) + "\n§7Held for: §f"
                    + Text.duration(f.soldAt - f.boughtAt) + "\n§7Revenue after taxes: §f" + Coins.full(f.revenue)));
        }
    }

    private void inventory(String[] args) {
        if (args.length > 1 && ("svuota".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1]))) {
            info("§7Stock cleared: " + count(tracker.clearLots(), "purchase") + " forgotten.");
            return;
        }
        List<ProfitTracker.Lot> lots = tracker.lots();
        List<ProfitTracker.Unclaimed> unclaimed = tracker.unclaimed();
        if (lots.isEmpty() && unclaimed.isEmpty()) {
            info("§7The stock is empty: nothing to resell.");
            return;
        }
        long now = clock.getAsLong();
        if (!lots.isEmpty()) {
            info("§fBought and not sold yet:");
            for (int i = 0; i < Math.min(15, lots.size()); i++) {
                ProfitTracker.Lot lot = lots.get(i);
                platform.showMessage(Msg.plain("§7  " + lot.item + (lot.amount > 1 ? " x" + lot.amount : "") + " §8for §6"
                    + Coins.compact(lot.unitCost * lot.amount) + " §8(" + Text.duration(now - lot.time) + " ago)"));
            }
            if (lots.size() > 15) {
                platform.showMessage(Msg.plain("§8  ... and " + (lots.size() - 15) + " more"));
            }
            platform.showMessage(new Msg().suggest("§7  §c[CLEAR]", "/flipscout stock clear",
                "§7Forgets these purchases (for example if you used them instead of reselling them)"));
        }
        if (!unclaimed.isEmpty()) {
            info("§fSold at auction, coins still to collect:");
            for (ProfitTracker.Unclaimed u : unclaimed) {
                platform.showMessage(Msg.plain("§7  " + u.item + " §8for §6" + Coins.compact(u.price) + " §8(" + u.buyer + ")"));
            }
        }
    }

    private void details(ProfitTracker.Stats s) {
        line("Profit", ProfitTracker.signed(s.profit) + " §7in " + count(s.flips, "flip"));
        if (s.bestItem != null) {
            line("Best flip", s.bestItem + " " + ProfitTracker.signed(s.bestProfit));
        }
        line("Purchases", s.bought + " §8for §6" + Coins.compact(s.spent));
        line("Sales", s.sold + " §8for §6" + Coins.compact(s.earned) + " §8(after taxes)");
        if (s.unmatched > 0) {
            line("Without a purchase", count(s.unmatched, "sale") + " §8(not counted in the profit)");
        }
    }

    private static String stats(ProfitTracker.Stats s) {
        return ProfitTracker.signed(s.profit) + " §7in " + count(s.flips, "flip")
            + (s.unmatched > 0 ? " §8(+" + count(s.unmatched, "sale") + " without a purchase)" : "");
    }

    private static String perHour(ProfitTracker.Session session) {
        double perHour = session.profitPerHour();
        return Double.isNaN(perHour) ? "" : " §8| " + ProfitTracker.signed(perHour) + "§8/h";
    }

    private static String date(long time) {
        return DATE.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    private static String dateTime(long time) {
        return DATE_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    /** "2026-11-01" as "1 Nov"; anything else as it is. */
    private static String shortDay(String isoDay) {
        try {
            return DAY.format(LocalDate.parse(isoDay));
        } catch (DateTimeParseException e) {
            return isoDay;
        }
    }

    /** "1 flip", "3 flips". */
    private static String count(int n, String noun) {
        return n + " " + (n == 1 ? noun : noun + "s");
    }

    private void info(String text) {
        platform.showMessage(Msg.prefixed(text));
    }

    private void error(String text) {
        platform.showMessage(Msg.prefixed("§c" + text));
    }

    private void line(String label, String value) {
        platform.showMessage(Msg.plain("§7  " + label + ": §f" + value));
    }

    private static String onOff(boolean on) {
        return on ? "§aon" : "§coff";
    }

    private static String[] clean(String[] args) {
        if (args == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<String>();
        for (String arg : args) {
            if (arg != null && !arg.trim().isEmpty()) {
                out.add(arg.trim());
            }
        }
        return out.toArray(new String[0]);
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
