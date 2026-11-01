package dev.flipscout.core;

import dev.flipscout.core.util.Coins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** All settings, stored in config/flipscout/flipscout.properties. */
public final class Config {
    // General
    public volatile boolean enabled = true;
    public volatile boolean onlyOnHypixel = true;
    public volatile boolean onlyInSkyblock = true;
    public volatile boolean sound = true;
    public volatile long budget = 0;
    public volatile Set<String> blacklist = Collections.emptySet();
    public volatile boolean blockedSummary = true;
    public volatile boolean debug = false;

    // Auction house (BIN)
    public volatile boolean ahEnabled = true;
    public volatile long ahMinProfit = 500_000;
    public volatile double ahMinPercent = 5;
    public volatile double ahMinVolume = 1;
    public volatile boolean ahRequireVolume = false;
    public volatile boolean ahUpgrades = true;
    public volatile int ahUpgradePercent = 80;
    public volatile String ahStrategy = "conservative";
    public volatile int ahMinSalesSamples = 3;
    public volatile int ahFullScanMinutes = 30;
    public volatile int ahPollPages = 2;
    public volatile double ahFeeUnder10m = 1.0;
    public volatile double ahFee10mTo100m = 2.0;
    public volatile double ahFeeOver100m = 2.5;
    public volatile double ahFeeClaim = 1.0;
    public volatile int salesWindowDays = 3;

    // Bazaar
    public volatile boolean bzEnabled = true;
    public volatile int bzIntervalSeconds = 20;
    public volatile double bzTax = 1.25;
    public volatile int bzMaxPerCycle = 3;
    public volatile boolean bzFlipEnabled = true;
    public volatile double bzFlipMinMarginPercent = 5;
    public volatile double bzFlipMinHourlyVolume = 200;
    public volatile long bzFlipMinProfitPerHour = 1_000_000;
    public volatile int bzFlipCooldownMinutes = 30;
    public volatile boolean bzCrashEnabled = true;
    public volatile double bzCrashDropPercent = 20;
    public volatile double bzCrashMinHourlyVolume = 50;
    public volatile long bzCrashMinProfit = 500_000;
    public volatile int bzCrashCooldownMinutes = 15;
    public volatile boolean bzNpcEnabled = true;
    public volatile long bzNpcMinProfit = 200_000;
    public volatile int bzNpcCooldownMinutes = 15;

    // Profit tracker
    public volatile boolean trackerEnabled = true;
    public volatile boolean trackerMessages = true;

    // Discord
    public volatile boolean discordEnabled = true;
    public volatile String discordWebhook = "";
    public volatile boolean discordAuctions = true;
    public volatile boolean discordBazaar = false;
    public volatile long discordMinCoins = 0;

    // Sources
    public volatile boolean sourceCoflnet = true;
    public volatile String urlHypixel = "https://api.hypixel.net";
    public volatile String urlCoflnet = "https://sky.coflnet.com";

    /** A named, typed setting that can be shown, parsed and saved. */
    public abstract static class Setting {
        /** How a setting is edited in the settings screen. */
        public enum Kind { TOGGLE, CHOICE, NUMBER, TEXT }

        public final String key;
        /** Short name for the settings screen. */
        public final String label;
        public final String help;

        Setting(String key, String label, String help) {
            this.key = key;
            this.label = label;
            this.help = help;
        }

        public abstract Kind kind();

        /** True for values not to be shown in chat (they would end up in screenshots). */
        public boolean secret() {
            return false;
        }

        /** What the value may be ("10 - 600", "importo, es. 500k o 2m"), or "" when there is nothing to add. */
        public String hint() {
            return "";
        }

        public abstract String get(Config config);

        /** @throws IllegalArgumentException with an Italian message when the value is invalid */
        public abstract void set(Config config, String value);

        /** Values offered by tab completion. */
        public List<String> suggestions() {
            return Collections.emptyList();
        }
    }

    public static final class Section {
        public final String title;
        public final List<Setting> settings;

        Section(String title, Setting... settings) {
            this.title = title;
            this.settings = Collections.unmodifiableList(Arrays.asList(settings));
        }
    }

    public static final List<Section> SECTIONS = Collections.unmodifiableList(Arrays.asList(
        new Section("General",
            new BoolSetting("enabled", "Mod enabled", "Turns the whole mod on or off (no requests while off)") {
                boolean read(Config c) { return c.enabled; }
                void write(Config c, boolean v) { c.enabled = v; }
            },
            new BoolSetting("onlyOnHypixel", "Only on Hypixel", "Only fetch data while connected to Hypixel") {
                boolean read(Config c) { return c.onlyOnHypixel; }
                void write(Config c, boolean v) { c.onlyOnHypixel = v; }
            },
            new BoolSetting("onlyInSkyblock", "Notify only in SkyBlock", "Only show notifications while you are in SkyBlock") {
                boolean read(Config c) { return c.onlyInSkyblock; }
                void write(Config c, boolean v) { c.onlyInSkyblock = v; }
            },
            new BoolSetting("sound", "Notification sound", "Play a sound when a notification arrives") {
                boolean read(Config c) { return c.sound; }
                void write(Config c, boolean v) { c.sound = v; }
            },
            new CoinsSetting("budget", "Max budget per flip", "Most coins to spend on one flip (0 = no limit)", 0) {
                long read(Config c) { return c.budget; }
                void write(Config c, long v) { c.budget = v; }
            },
            new ListSetting("blacklist", "Ignored items", "Item IDs to ignore, comma-separated (e.g. HYPERION,ENCHANTMENT_SHARPNESS_6)") {
                Set<String> read(Config c) { return c.blacklist; }
                void write(Config c, Set<String> v) { c.blacklist = v; }
            },
            new BoolSetting("blockedSummary", "Blocked-flips summary", "After each auction round, says how many flips the blacklist hid"
                + " — hover the message to see them") {
                boolean read(Config c) { return c.blockedSummary; }
                void write(Config c, boolean v) { c.blockedSummary = v; }
            },
            new BoolSetting("debug", "Verbose log (debug)", "Writes more detail to the game log") {
                boolean read(Config c) { return c.debug; }
                void write(Config c, boolean v) { c.debug = v; Log.debugEnabled = v; }
            }),
        new Section("Auctions (BIN)",
            new BoolSetting("ah.enabled", "Auction notifications", "Notify about underpriced BIN auctions") {
                boolean read(Config c) { return c.ahEnabled; }
                void write(Config c, boolean v) { c.ahEnabled = v; }
            },
            new CoinsSetting("ah.minProfit", "Minimum profit", "Minimum profit after fees (e.g. 500k, 2m)", 0) {
                long read(Config c) { return c.ahMinProfit; }
                void write(Config c, long v) { c.ahMinProfit = v; }
            },
            new DecimalSetting("ah.minPercent", "Minimum profit (%)", "Minimum profit as a percentage of the price", 0, 10_000) {
                double read(Config c) { return c.ahMinPercent; }
                void write(Config c, double v) { c.ahMinPercent = v; }
            },
            new DecimalSetting("ah.minVolume", "Minimum sales per day", "Minimum sales per day for the item (when that data is known)", 0, 1_000_000) {
                double read(Config c) { return c.ahMinVolume; }
                void write(Config c, double v) { c.ahMinVolume = v; }
            },
            new BoolSetting("ah.requireVolume", "Only items with known sales", "Skip items whose sales volume is unknown") {
                boolean read(Config c) { return c.ahRequireVolume; }
                void write(Config c, boolean v) { c.ahRequireVolume = v; }
            },
            new BoolSetting("ah.upgrades", "Count upgrades", "Count the value of upgrades: recombobulator, potato books, enchantments,"
                + " master stars, gemstones, scrolls, pet items...") {
                boolean read(Config c) { return c.ahUpgrades; }
                void write(Config c, boolean v) { c.ahUpgrades = v; }
            },
            new IntSetting("ah.upgradePercent", "Upgrade share (%)", "How much of the upgrades' value to count, as a percentage", 0, 100) {
                int read(Config c) { return c.ahUpgradePercent; }
                void write(Config c, int v) { c.ahUpgradePercent = v; }
            },
            new ChoiceSetting("ah.strategy", "Valuation strategy", "How to estimate value: conservative = the lower of lowest BIN and recent sales,"
                + " lbin = lowest BIN price, sales = median of sales", "conservative", "lbin", "sales") {
                String read(Config c) { return c.ahStrategy; }
                void write(Config c, String v) { c.ahStrategy = v; }
            },
            new IntSetting("ah.minSalesSamples", "Sales needed for the median", "Recorded sales needed before using the sales median", 1, 100) {
                int read(Config c) { return c.ahMinSalesSamples; }
                void write(Config c, int v) { c.ahMinSalesSamples = v; }
            },
            new IntSetting("ah.fullScanMinutes", "Full scan every (min)", "How often to re-download every auction (heavy: tens of MB each time)", 5, 240) {
                int read(Config c) { return c.ahFullScanMinutes; }
                void write(Config c, int v) { c.ahFullScanMinutes = v; }
            },
            new IntSetting("ah.pollPages", "Pages checked each minute", "Pages of new auctions checked each minute", 1, 10) {
                int read(Config c) { return c.ahPollPages; }
                void write(Config c, int v) { c.ahPollPages = v; }
            },
            new DecimalSetting("ah.fee.under10m", "Auction fee under 10M (%)", "Listing fee percentage under 10M", 0, 100) {
                double read(Config c) { return c.ahFeeUnder10m; }
                void write(Config c, double v) { c.ahFeeUnder10m = v; }
            },
            new DecimalSetting("ah.fee.10mTo100m", "Auction fee 10M-100M (%)", "Listing fee percentage between 10M and 100M", 0, 100) {
                double read(Config c) { return c.ahFee10mTo100m; }
                void write(Config c, double v) { c.ahFee10mTo100m = v; }
            },
            new DecimalSetting("ah.fee.over100m", "Auction fee over 100M (%)", "Listing fee percentage over 100M", 0, 100) {
                double read(Config c) { return c.ahFeeOver100m; }
                void write(Config c, double v) { c.ahFeeOver100m = v; }
            },
            new DecimalSetting("ah.fee.claim", "Claim fee (%)", "Fee percentage when claiming coins (sales of 1M and up)", 0, 100) {
                double read(Config c) { return c.ahFeeClaim; }
                void write(Config c, double v) { c.ahFeeClaim = v; }
            },
            new IntSetting("ah.salesWindowDays", "Days of sales history", "Days of sales to keep for statistics", 1, 14) {
                int read(Config c) { return c.salesWindowDays; }
                void write(Config c, int v) { c.salesWindowDays = v; }
            }),
        new Section("Bazaar",
            new BoolSetting("bz.enabled", "Bazaar notifications", "Bazaar notifications") {
                boolean read(Config c) { return c.bzEnabled; }
                void write(Config c, boolean v) { c.bzEnabled = v; }
            },
            new IntSetting("bz.intervalSeconds", "Check bazaar every (s)", "How often to check the bazaar, in seconds", 10, 600) {
                int read(Config c) { return c.bzIntervalSeconds; }
                void write(Config c, int v) { c.bzIntervalSeconds = v; }
            },
            new DecimalSetting("bz.tax", "Bazaar tax (%)", "Bazaar tax on sales, as a percentage", 0, 100) {
                double read(Config c) { return c.bzTax; }
                void write(Config c, double v) { c.bzTax = v; }
            },
            new IntSetting("bz.maxPerCycle", "Max notifications per check", "Most bazaar notifications per update", 1, 20) {
                int read(Config c) { return c.bzMaxPerCycle; }
                void write(Config c, int v) { c.bzMaxPerCycle = v; }
            },
            new BoolSetting("bz.flip.enabled", "Order flips", "Flips using a buy order + sell offer") {
                boolean read(Config c) { return c.bzFlipEnabled; }
                void write(Config c, boolean v) { c.bzFlipEnabled = v; }
            },
            new DecimalSetting("bz.flip.minMarginPercent", "Flip: minimum margin (%)", "Minimum margin percentage after tax", 0, 10_000) {
                double read(Config c) { return c.bzFlipMinMarginPercent; }
                void write(Config c, double v) { c.bzFlipMinMarginPercent = v; }
            },
            new DecimalSetting("bz.flip.minHourlyVolume", "Flip: minimum hourly volume", "Minimum items traded per hour (slower side)", 0, 1e9) {
                double read(Config c) { return c.bzFlipMinHourlyVolume; }
                void write(Config c, double v) { c.bzFlipMinHourlyVolume = v; }
            },
            new CoinsSetting("bz.flip.minProfitPerHour", "Flip: minimum profit per hour", "Minimum potential profit per hour", 0) {
                long read(Config c) { return c.bzFlipMinProfitPerHour; }
                void write(Config c, long v) { c.bzFlipMinProfitPerHour = v; }
            },
            new IntSetting("bz.flip.cooldownMinutes", "Flip: cooldown between alerts (min)", "Minutes before alerting again for the same product", 1, 1440) {
                int read(Config c) { return c.bzFlipCooldownMinutes; }
                void write(Config c, int v) { c.bzFlipCooldownMinutes = v; }
            },
            new BoolSetting("bz.crash.enabled", "Crash alerts", "Alert when the buy price crashes versus the last hour") {
                boolean read(Config c) { return c.bzCrashEnabled; }
                void write(Config c, boolean v) { c.bzCrashEnabled = v; }
            },
            new DecimalSetting("bz.crash.dropPercent", "Crash: minimum drop (%)", "Minimum drop percentage versus the recent median", 1, 99) {
                double read(Config c) { return c.bzCrashDropPercent; }
                void write(Config c, double v) { c.bzCrashDropPercent = v; }
            },
            new DecimalSetting("bz.crash.minHourlyVolume", "Crash: minimum hourly buys", "Minimum buys per hour to consider a crash", 0, 1e9) {
                double read(Config c) { return c.bzCrashMinHourlyVolume; }
                void write(Config c, double v) { c.bzCrashMinHourlyVolume = v; }
            },
            new CoinsSetting("bz.crash.minProfit", "Crash: minimum profit", "Minimum estimated profit reselling at the normal price", 0) {
                long read(Config c) { return c.bzCrashMinProfit; }
                void write(Config c, long v) { c.bzCrashMinProfit = v; }
            },
            new IntSetting("bz.crash.cooldownMinutes", "Crash: cooldown between alerts (min)", "Minutes before alerting again for the same crash", 1, 1440) {
                int read(Config c) { return c.bzCrashCooldownMinutes; }
                void write(Config c, int v) { c.bzCrashCooldownMinutes = v; }
            },
            new BoolSetting("bz.npc.enabled", "Bazaar → NPC flip", "Alert when buying from the bazaar and selling to an NPC is worth it") {
                boolean read(Config c) { return c.bzNpcEnabled; }
                void write(Config c, boolean v) { c.bzNpcEnabled = v; }
            },
            new CoinsSetting("bz.npc.minProfit", "NPC: minimum profit", "Minimum profit for a bazaar -> NPC flip", 0) {
                long read(Config c) { return c.bzNpcMinProfit; }
                void write(Config c, long v) { c.bzNpcMinProfit = v; }
            },
            new IntSetting("bz.npc.cooldownMinutes", "NPC: cooldown between alerts (min)", "Minutes before alerting again for the same NPC flip", 1, 1440) {
                int read(Config c) { return c.bzNpcCooldownMinutes; }
                void write(Config c, int v) { c.bzNpcCooldownMinutes = v; }
            }),
        new Section("Profits",
            new BoolSetting("tracker.enabled", "Track profits", "Recognises buys and sells in chat (auctions and bazaar) and computes profit") {
                boolean read(Config c) { return c.trackerEnabled; }
                void write(Config c, boolean v) { c.trackerEnabled = v; }
            },
            new BoolSetting("tracker.messages", "Tracker chat messages", "Writes in chat when it records an auction buy or closes a flip") {
                boolean read(Config c) { return c.trackerMessages; }
                void write(Config c, boolean v) { c.trackerMessages = v; }
            }),
        new Section("Discord",
            new BoolSetting("discord.enabled", "Discord reports", "Sends buys, sells and closed flips to the webhook below") {
                boolean read(Config c) { return c.discordEnabled; }
                void write(Config c, boolean v) { c.discordEnabled = v; }
            },
            new WebhookSetting("discord.webhook", "Channel webhook", "Webhook URL: in Discord, Edit Channel →"
                + " Integrations → Webhooks → New Webhook → Copy Webhook URL. Empty = no reports") {
                String read(Config c) { return c.discordWebhook; }
                void write(Config c, String v) { c.discordWebhook = v; }
            },
            new BoolSetting("discord.auctions", "Auctions: buys and sells", "Report every auction buy and sell, with the flip's profit") {
                boolean read(Config c) { return c.discordAuctions; }
                void write(Config c, boolean v) { c.discordAuctions = v; }
            },
            new BoolSetting("discord.bazaar", "Bazaar: buys and sells", "Also report bazaar trades (there can be many)") {
                boolean read(Config c) { return c.discordBazaar; }
                void write(Config c, boolean v) { c.discordBazaar = v; }
            },
            new CoinsSetting("discord.minCoins", "Minimum amount", "Do not send trades below this amount (0 = all)", 0) {
                long read(Config c) { return c.discordMinCoins; }
                void write(Config c, long v) { c.discordMinCoins = v; }
            }),
        new Section("Data sources",
            new BoolSetting("source.coflnet", "Use Coflnet data", "Use Coflnet (sky.coflnet.com) for rare-item sales, volumes"
                + " and bazaar item names") {
                boolean read(Config c) { return c.sourceCoflnet; }
                void write(Config c, boolean v) { c.sourceCoflnet = v; }
            },
            new UrlSetting("url.hypixel", "Hypixel API URL", "Hypixel API address") {
                String read(Config c) { return c.urlHypixel; }
                void write(Config c, String v) { c.urlHypixel = v; }
            },
            new UrlSetting("url.coflnet", "Coflnet API URL", "Coflnet API address") {
                String read(Config c) { return c.urlCoflnet; }
                void write(Config c, String v) { c.urlCoflnet = v; }
            })));

    public static List<Setting> allSettings() {
        List<Setting> all = new ArrayList<Setting>();
        for (Section section : SECTIONS) {
            all.addAll(section.settings);
        }
        return all;
    }

    /** Finds a setting by key, ignoring case. */
    public static Setting find(String key) {
        for (Setting setting : allSettings()) {
            if (setting.key.equalsIgnoreCase(key)) {
                return setting;
            }
        }
        return null;
    }

    public boolean isBlacklisted(String... ids) {
        return blacklistMatch(ids) != null;
    }

    /** The blacklist entry that matches one of {@code ids}, or null. */
    public String blacklistMatch(String... ids) {
        Set<String> list = blacklist;
        if (list.isEmpty()) {
            return null;
        }
        for (String id : ids) {
            String upper = id == null ? null : id.toUpperCase(Locale.ROOT);
            if (upper != null && list.contains(upper)) {
                return upper;
            }
        }
        return null;
    }

    /** Loads the file if it exists, then rewrites it so it always lists every option. */
    public void load(File file) {
        if (file.isFile()) {
            Properties properties = new Properties();
            Reader reader = null;
            try {
                reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                properties.load(reader);
            } catch (IOException e) {
                Log.warn("Could not read " + file, e);
            } finally {
                closeQuietly(reader);
            }
            for (Setting setting : allSettings()) {
                String value = properties.getProperty(setting.key);
                if (value == null) {
                    continue;
                }
                try {
                    setting.set(this, value.trim());
                } catch (IllegalArgumentException e) {
                    Log.warn("Ignored value for " + setting.key + ": " + e.getMessage());
                }
            }
        }
        save(file);
    }

    /** Synchronized: the settings screen and commands may save at the same time. */
    public synchronized void save(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.warn("Could not create folder " + parent);
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            writer.write("# FlipScout settings\n");
            writer.write("# Edit the values and run /flipscout reload, or use /flipscout set <key> <value>.\n");
            for (Section section : SECTIONS) {
                writer.write("\n# ===== " + section.title + " =====\n");
                for (Setting setting : section.settings) {
                    writer.write("# " + setting.help + "\n");
                    writer.write(setting.key + "=" + escape(setting.get(this)) + "\n");
                }
            }
            writer.close();
            writer = null;
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Could not save " + file, e);
        } finally {
            closeQuietly(writer);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    static boolean parseBoolean(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1")) {
            return true;
        }
        if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException("use on or off");
    }

    // Setting kinds

    abstract static class BoolSetting extends Setting {
        BoolSetting(String key, String label, String help) {
            super(key, label, help);
        }

        @Override
        public Kind kind() {
            return Kind.TOGGLE;
        }

        abstract boolean read(Config c);

        abstract void write(Config c, boolean v);

        @Override
        public String get(Config config) {
            return String.valueOf(read(config));
        }

        @Override
        public void set(Config config, String value) {
            write(config, parseBoolean(value));
        }

        @Override
        public List<String> suggestions() {
            return Arrays.asList("on", "off");
        }
    }

    abstract static class CoinsSetting extends Setting {
        private final long min;

        CoinsSetting(String key, String label, String help, long min) {
            super(key, label, help);
            this.min = min;
        }

        @Override
        public Kind kind() {
            return Kind.NUMBER;
        }

        @Override
        public String hint() {
            return "amount, e.g. 500k or 2m" + (min > 0 ? ", at least " + Coins.compact(min) : "");
        }

        abstract long read(Config c);

        abstract void write(Config c, long v);

        @Override
        public String get(Config config) {
            return String.valueOf(read(config));
        }

        @Override
        public void set(Config config, String value) {
            long parsed;
            try {
                parsed = Coins.parse(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("an amount is required, e.g. 500k or 2m");
            }
            if (parsed < min) {
                throw new IllegalArgumentException("must be at least " + Coins.compact(min));
            }
            write(config, parsed);
        }
    }

    abstract static class DecimalSetting extends Setting {
        private final double min;
        private final double max;

        DecimalSetting(String key, String label, String help, double min, double max) {
            super(key, label, help);
            this.min = min;
            this.max = max;
        }

        @Override
        public Kind kind() {
            return Kind.NUMBER;
        }

        @Override
        public String hint() {
            return Coins.decimal(min, 3) + " - " + Coins.decimal(max, 3);
        }

        abstract double read(Config c);

        abstract void write(Config c, double v);

        @Override
        public String get(Config config) {
            return Coins.decimal(read(config), 3);
        }

        @Override
        public void set(Config config, String value) {
            double parsed;
            try {
                parsed = Coins.parseDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a number is required, e.g. 5 or 2.5");
            }
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("must be between " + Coins.decimal(min, 3) + " and " + Coins.decimal(max, 3));
            }
            write(config, parsed);
        }
    }

    abstract static class IntSetting extends Setting {
        private final int min;
        private final int max;

        IntSetting(String key, String label, String help, int min, int max) {
            super(key, label, help);
            this.min = min;
            this.max = max;
        }

        @Override
        public Kind kind() {
            return Kind.NUMBER;
        }

        @Override
        public String hint() {
            return min + " - " + max;
        }

        abstract int read(Config c);

        abstract void write(Config c, int v);

        @Override
        public String get(Config config) {
            return String.valueOf(read(config));
        }

        @Override
        public void set(Config config, String value) {
            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("a whole number is required");
            }
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("must be between " + min + " and " + max);
            }
            write(config, parsed);
        }
    }

    abstract static class ChoiceSetting extends Setting {
        private final List<String> choices;

        ChoiceSetting(String key, String label, String help, String... choices) {
            super(key, label, help);
            this.choices = Arrays.asList(choices);
        }

        @Override
        public Kind kind() {
            return Kind.CHOICE;
        }

        @Override
        public String hint() {
            StringBuilder sb = new StringBuilder();
            for (String choice : choices) {
                sb.append(sb.length() > 0 ? " / " : "").append(choice);
            }
            return sb.toString();
        }

        abstract String read(Config c);

        abstract void write(Config c, String v);

        @Override
        public String get(Config config) {
            return read(config);
        }

        @Override
        public void set(Config config, String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (!choices.contains(v)) {
                throw new IllegalArgumentException("allowed values: " + choices);
            }
            write(config, v);
        }

        @Override
        public List<String> suggestions() {
            return choices;
        }
    }

    abstract static class UrlSetting extends Setting {
        UrlSetting(String key, String label, String help) {
            super(key, label, help);
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        @Override
        public String hint() {
            return "address starting with https://";
        }

        abstract String read(Config c);

        abstract void write(Config c, String v);

        @Override
        public String get(Config config) {
            return read(config);
        }

        @Override
        public void set(Config config, String value) {
            String v = value.trim();
            if (!v.startsWith("https://") && !v.startsWith("http://")) {
                throw new IllegalArgumentException("must start with https://");
            }
            write(config, v.endsWith("/") ? v.substring(0, v.length() - 1) : v);
        }
    }

    /** A Discord webhook address, or nothing. Kept out of chat: whoever has it can post in the channel. */
    abstract static class WebhookSetting extends Setting {
        private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+/?");

        WebhookSetting(String key, String label, String help) {
            super(key, label, help);
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        @Override
        public boolean secret() {
            return true;
        }

        @Override
        public String hint() {
            return "https://discord.com/api/webhooks/... or empty";
        }

        abstract String read(Config c);

        abstract void write(Config c, String v);

        @Override
        public String get(Config config) {
            return read(config);
        }

        @Override
        public void set(Config config, String value) {
            String v = value.trim();
            if (!v.isEmpty() && !WEBHOOK.matcher(v).matches()) {
                throw new IllegalArgumentException("a Discord webhook URL is required (https://discord.com/api/webhooks/...)");
            }
            write(config, v.endsWith("/") ? v.substring(0, v.length() - 1) : v);
        }
    }

    abstract static class ListSetting extends Setting {
        ListSetting(String key, String label, String help) {
            super(key, label, help);
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        @Override
        public String hint() {
            return "IDs separated by commas";
        }

        abstract Set<String> read(Config c);

        abstract void write(Config c, Set<String> v);

        @Override
        public String get(Config config) {
            StringBuilder sb = new StringBuilder();
            for (String entry : read(config)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry);
            }
            return sb.toString();
        }

        @Override
        public void set(Config config, String value) {
            Set<String> entries = new LinkedHashSet<String>();
            for (String part : value.split("[,\\s]+")) {
                String id = part.trim().toUpperCase(Locale.ROOT);
                if (!id.isEmpty()) {
                    entries.add(id);
                }
            }
            write(config, Collections.unmodifiableSet(entries));
        }
    }
}
