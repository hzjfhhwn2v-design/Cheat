package dev.flipscout.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    @TempDir
    File dir;

    private FakePlatform platform;
    private FlipScout flipScout;
    private File configFile;

    @BeforeEach
    void setUp() {
        platform = new FakePlatform();
        flipScout = new FlipScout(platform, dir, new FakeFetcher(), new AtomicLong(1_800_000_000_000L)::get);
        configFile = new File(new File(dir, "flipscout"), "flipscout.properties");
    }

    private void run(String... args) {
        flipScout.command(args);
    }

    @Test
    void profitCommands() {
        flipScout.onChat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        flipScout.onChat("[Bazaar] Sold 1x Enchanted Diamond for 200 coins!");
        run("profits");
        String text = platform.allText();
        assertTrue(text.contains("Session (active): +40 in 1 flip"), text);
        assertTrue(text.contains("Total: +40 in 1 flip"), text);
        run("session", "pause");
        assertTrue(platform.allText().contains("Session paused"), platform.allText());
        run("session", "reset");
        run("session", "history");
        assertTrue(platform.allText().contains("Previous sessions"), platform.allText());
        run("flips");
        assertTrue(platform.allText().contains("Enchanted Diamond 160 → 200 +40"), platform.allText());
        run("total");
        assertTrue(platform.allText().matches("(?s).*Last few days:\\s+\\d{1,2} [A-Z][a-z]{2}: \\+40 in 1 flip.*"),
            platform.allText());
        run("total", "reset");
        assertTrue(platform.allText().contains("total reset confirm"), platform.allText());
        assertEquals(40, flipScout.tracker().total().profit, 1e-9);
        run("total", "reset", "confirm");
        assertEquals(0, flipScout.tracker().total().profit, 1e-9);
        assertEquals(Arrays.asList("pause", "resume", "reset", "history"), flipScout.complete(new String[] {"session", ""}));
        // The Italian names of earlier versions still work.
        platform.messages.clear();
        run("guadagni");
        assertTrue(platform.allText().contains("Profits"), platform.allText());
    }

    @Test
    void theOldProfitsFileIsRenamedOnce() throws IOException {
        File data = new File(dir, "data");
        assertTrue(data.mkdirs());
        File old = new File(data, "guadagni.json");
        Files.write(old.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        File renamed = FlipScout.profitsFile(data);
        assertEquals("profits.json", renamed.getName());
        assertTrue(renamed.isFile());
        assertFalse(old.exists());
        // Later starts find profits.json, and a new install starts with it.
        assertEquals(renamed, FlipScout.profitsFile(data));
        assertEquals("profits.json", FlipScout.profitsFile(new File(dir, "fresh")).getName());
    }

    @Test
    void recentShowsWhichAuctionsSold() {
        long t0 = 1_800_000_000_000L;
        AtomicLong clock = new AtomicLong(t0 + 5_000);
        FakeFetcher http = new FakeFetcher();
        FakePlatform platform = new FakePlatform();
        platform.uuid = "me";
        FlipScout fs = new FlipScout(platform, new File(dir, "recent"), http, clock::get);
        fs.config().sourceCoflnet = false;
        String h = "https://api.hypixel.net/v2/skyblock/";
        String hyperion = TestData.plainItem("HYPERION");
        http.put(h + "auctions?page=0", TestData.auctionPage(0, 1, t0,
            TestData.auction("a1", "s1", "Hyperion", "LEGENDARY", 900_000_000L, true, t0 - 3_600_000L, hyperion)));
        String[] sold = new String[5];
        for (int i = 0; i < sold.length; i++) {
            sold[i] = TestData.ended("e" + i, 893_000_000L + i * 1_000_000L, true, t0 - 30_000, hyperion);
        }
        http.put(h + "auctions_ended", TestData.endedPage(t0, sold));
        fs.engine().tick();

        long t1 = t0 + 60_000;
        http.put(h + "auctions?page=0", TestData.auctionPage(0, 1, t1,
            TestData.auction("a1", "s1", "Hyperion", "LEGENDARY", 900_000_000L, true, t0 - 3_600_000L, hyperion),
            TestData.auction("new", "s2", "Hyperion", "LEGENDARY", 800_000_000L, true, t1 - 10_000, hyperion)));
        clock.set(t1 + 1_000);
        fs.engine().tick();
        fs.command(new String[] {"replay"});
        assertTrue(platform.allText().contains("Notified auctions still up for sale"), platform.allText());
        assertTrue(platform.messages.get(platform.messages.size() - 1).toPlainString().contains("AH Hyperion"),
            platform.allText());
        long t2 = t1 + 60_000;
        http.put(h + "auctions_ended", TestData.endedPage(t2, TestData.ended("new", 800_000_000L, true, t1 + 2_000, hyperion)));
        clock.set(t2 + 1_000);
        fs.engine().tick();

        fs.command(new String[] {"status"});
        assertTrue(platform.allText().contains("Alert speed: on average 11 s from listing to alert (1 auction flip, fastest 11 s)"),
            platform.allText());
        fs.command(new String[] {"recent"});
        String text = platform.allText();
        assertTrue(text.contains("1 of 1 auctions already sold, half within 12s"), text);
        assertTrue(text.contains("[sold after 12s]"), text);
        fs.command(new String[] {"open"});
        assertTrue(platform.commands.isEmpty());
        assertTrue(platform.allText().contains("Someone else already bought this auction"), platform.allText());
        fs.command(new String[] {"replay"});
        assertTrue(platform.allText().contains("None of the notified auctions is still up for sale."), platform.allText());
    }

    @Test
    void exportWritesFilesNextToTheSettings() {
        flipScout.onChat("[Bazaar] Bought 1x Enchanted Diamond for 160 coins!");
        flipScout.onChat("[Bazaar] Sold 1x Enchanted Diamond for 200 coins!");
        run("export");
        File exports = new File(new File(dir, "flipscout"), "exports");
        assertEquals(1, exports.listFiles().length, java.util.Arrays.toString(exports.listFiles()));
        assertTrue(platform.allText().contains("Saved 1 flip to"), platform.allText());
        run("export", "notifications");
        assertEquals(2, exports.listFiles().length);
        run("export", "bazaar");
        assertTrue(platform.allText().contains("Bazaar data not downloaded yet"), platform.allText());
        run("export", "everything");
        assertTrue(platform.allText().contains("Usage: /flipscout export [flips|notifications|bazaar]"), platform.allText());
        run("flips", "2");
        assertTrue(platform.allText().contains("Latest flips (newest first"), platform.allText());
        run("recent", "x");
        assertTrue(platform.allText().contains("No notifications yet"), platform.allText());
    }

    @Test
    void configFileIsCreatedWithEveryOption() throws IOException {
        assertTrue(configFile.isFile());
        String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        for (Config.Setting setting : Config.allSettings()) {
            assertTrue(content.contains(setting.key + "="), setting.key);
        }
    }

    @Test
    void setChangesAndPersists() {
        run("set", "minprofit", "2m");
        assertEquals(2_000_000L, flipScout.config().ahMinProfit);
        run("set", "bz.tax", "1,125");
        assertEquals(1.125, flipScout.config().bzTax, 1e-9);
        run("set", "ah.strategy", "LBIN");
        assertEquals("lbin", flipScout.config().ahStrategy);

        Config reloaded = new Config();
        reloaded.load(configFile);
        assertEquals(2_000_000L, reloaded.ahMinProfit);
        assertEquals(1.125, reloaded.bzTax, 1e-9);
        assertEquals("lbin", reloaded.ahStrategy);
    }

    @Test
    void invalidValuesAreRejected() {
        run("set", "ah.minPercent", "lots");
        run("set", "bz.intervalSeconds", "1");
        run("set", "nope", "1");
        assertEquals(5, flipScout.config().ahMinPercent, 1e-9);
        assertEquals(20, flipScout.config().bzIntervalSeconds);
        String text = platform.allText();
        assertTrue(text.contains("ah.minPercent: a number is required"), text);
        assertTrue(text.contains("must be between 10 and 600"), text);
        assertTrue(text.contains("Unknown setting"), text);
    }

    @Test
    void handEditedFileIsReadAndBadLinesIgnored() throws IOException {
        String content = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8)
            .replace("ah.minProfit=500000", "ah.minProfit=3m")
            .replace("bz.maxPerCycle=3", "bz.maxPerCycle=banana");
        Files.write(configFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
        run("reload");
        assertEquals(3_000_000L, flipScout.config().ahMinProfit);
        assertEquals(3, flipScout.config().bzMaxPerCycle);
    }

    @Test
    void togglesAndBlacklist() {
        run("bz", "crash", "off");
        assertFalse(flipScout.config().bzCrashEnabled);
        run("ah", "off");
        assertFalse(flipScout.config().ahEnabled);
        run("off");
        assertFalse(flipScout.config().enabled);
        run("on");
        assertTrue(flipScout.config().enabled);

        run("blacklist", "add", "hyperion");
        assertTrue(flipScout.config().blacklist.contains("HYPERION"));
        assertTrue(flipScout.config().isBlacklisted("hyperion"));
        run("blacklist", "remove", "HYPERION");
        assertFalse(flipScout.config().blacklist.contains("HYPERION"));
    }

    @Test
    void informationalCommandsRespond() {
        run();
        run("help");
        run("status");
        run("config", "bz.crash");
        run("recent");
        run("open");
        run("top", "bz");
        run("wrong");
        String text = platform.allText();
        assertTrue(text.contains("FlipScout " + FlipScout.VERSION), text);
        assertTrue(text.contains("/flipscout set <key> <value>"), text);
        assertTrue(text.contains("Indexed BIN auctions"), text);
        assertTrue(text.contains("bz.crash.dropPercent"), text);
        assertTrue(text.contains("No notifications yet"), text);
        assertTrue(text.contains("Unknown command"), text);
        assertTrue(platform.commands.isEmpty());
    }

    @Test
    void discordWebhookIsCheckedAndNeverShownInChat() {
        String webhook = "https://discord.com/api/webhooks/1234567890/SecretToken_abc-123";
        run("set", "discord.webhook", "https://example.com/hook");
        assertEquals("", flipScout.config().discordWebhook);
        assertTrue(platform.allText().contains("a Discord webhook URL is required"), platform.allText());
        run("set", "discord.webhook", webhook);
        assertEquals(webhook, flipScout.config().discordWebhook);
        run("config", "discord");
        run("discord");
        run("status");
        String text = platform.allText();
        assertFalse(text.contains("SecretToken"), text);
        assertTrue(text.contains("set (hidden, change it from /flipscout gui)"), text);
        assertTrue(text.contains("Discord reports"), text);
        // No value empties it, which switches the reports off.
        run("set", "discord.webhook");
        assertEquals("", flipScout.config().discordWebhook);
        run("set", "discord.webhook", "https://canary.discord.com/api/v10/webhooks/1/abc/");
        assertEquals("https://canary.discord.com/api/v10/webhooks/1/abc", flipScout.config().discordWebhook);
    }

    @Test
    void coopCommandExplainsHowMembersAreFound() {
        run("coop");
        assertTrue(platform.allText().contains("No coop members found yet"), platform.allText());
    }

    @Test
    void testNotificationHasHoverAndClick() {
        run("test");
        Msg sample = platform.messages.get(platform.messages.size() - 1);
        Msg.Part part = sample.parts().get(0);
        assertEquals("/flipscout help", part.command);
        assertEquals(Msg.Click.SUGGEST, part.click);
        assertTrue(part.hover.contains("Profit"), part.hover);
        assertTrue(sample.toPlainString().contains("[OPEN]"));
    }

    @Test
    void creditsNameTheAuthorsAndTheSources() {
        run("credits");
        String text = platform.allText();
        assertTrue(text.contains("Made by yrqs and baldih"), text);
        assertTrue(text.contains("Kat, Craft, Forge, Fusion, NPC and Book flips"), text);
        assertTrue(text.contains("API by SkyCofl (sky.coflnet.com)"), text);
        Msg.Part link = platform.messages.get(2).parts().get(1);
        assertEquals(Msg.Click.URL, link.click);
        assertEquals("https://sky.coflnet.com/data", link.command);
    }

    @Test
    void skyCoflListsAreShownWithTheirSource() {
        FakeFetcher http = new FakeFetcher().put("https://sky.coflnet.com/api/craft/profit",
            "[{\"itemId\":\"HUNTER_RING\",\"itemName\":\"§5Hunter Ring\",\"sellPrice\":12000000,\"median\":12000000,"
            + "\"ingredients\":[{\"itemId\":\"X\",\"count\":2,\"cost\":1000000}],\"volume\":14.5,"
            + "\"reqCollection\":{\"name\":\"Hunter\",\"level\":2}}]");
        FakePlatform platform = new FakePlatform();
        FlipScout fs = new FlipScout(platform, new File(dir, "lists"), http, new AtomicLong(1_800_000_000_000L)::get);
        fs.command(new String[] {"craft", "5"});
        String text = platform.allText();
        assertTrue(text.contains("Craft flips (best 1 by profit) [SkyCofl]"), text);
        assertTrue(text.contains("CRAFT Hunter Ring Hunter II » 1M → 11.6M | +10.6M | 15/d [RECIPE]"), text);
        fs.command(new String[] {"kat", "lots"});
        assertTrue(platform.allText().contains("Usage: /flipscout kat [number]"), platform.allText());
        fs.config().sourceCoflnet = false;
        fs.command(new String[] {"forge"});
        assertTrue(platform.allText().contains("SkyCofl, which is turned off"), platform.allText());
    }

    @Test
    void historyAndPlayerCommands() {
        FakeFetcher http = new FakeFetcher()
            .put("https://sky.coflnet.com/api/item/price/HYPERION/history/week", "[{\"min\":1,\"max\":3,\"avg\":2,\"volume\":1,"
                + "\"time\":\"2027-01-14T00:00:00\"}]")
            .put("https://api.mojang.com/users/profiles/minecraft/baldih", "{\"id\":\"0123abcd\",\"name\":\"baldih\"}")
            .put("https://sky.coflnet.com/api/player/0123abcd/auctions?page=0", "[{\"auctionId\":\"x1\",\"highestBid\":5000000,"
                + "\"itemName\":\"Juju Shortbow\",\"tag\":\"JUJU_SHORTBOW\",\"end\":\"2027-01-14T00:00:00\",\"startingBid\":5000000,"
                + "\"bin\":true}]");
        FakePlatform platform = new FakePlatform();
        FlipScout fs = new FlipScout(platform, new File(dir, "history"), http, new AtomicLong(1_800_000_000_000L)::get);
        fs.command(new String[] {"history", "hyperion", "week"});
        String text = platform.allText();
        assertTrue(text.contains("Hyperion - auction price, last 7 days"), text);
        fs.command(new String[] {"history", "???"});
        assertTrue(platform.allText().contains("Unknown item: ???"), platform.allText());
        fs.command(new String[] {"player", "baldih"});
        text = platform.allText();
        assertTrue(text.contains("baldih - 0 BIN auctions up for sale"), text);
        assertTrue(text.contains("Juju Shortbow - sold for 5M"), text);
        fs.command(new String[] {"player", "not a name"});
        assertTrue(platform.allText().contains("Usage: /flipscout player <name>"), platform.allText());
        fs.command(new String[] {"blacklist", "add", "player:bad name"});
        fs.command(new String[] {"blacklist", "add", "player:Scammer1"});
        assertTrue(fs.config().blacklist.contains("PLAYER:SCAMMER1"));
        assertTrue(platform.allText().contains("auctions by this player are never notified"), platform.allText());
        assertTrue(fs.complete(new String[] {"blacklist", "add", "p"}).contains("player:"));
    }

    @Test
    void phaseTwoCommands() {
        long t0 = 1_800_000_000_000L;
        AtomicLong clock = new AtomicLong(t0 + 5_000);
        FakeFetcher http = new FakeFetcher();
        FakePlatform platform = new FakePlatform();
        FlipScout fs = new FlipScout(platform, new File(dir, "phase2"), http, clock::get);
        fs.config().sourceCoflnet = false;
        fs.config().mayorNotify = false;
        String h = "https://api.hypixel.net/v2/";
        http.put(h + "resources/skyblock/items", "{\"success\":true,\"items\":[{\"id\":\"HYPERION\",\"name\":\"Hyperion\","
            + "\"tier\":\"LEGENDARY\",\"museum_data\":{\"donation_xp\":10,\"category\":\"WEAPONS\"}},{\"id\":\"ARACHNE_BOOTS\","
            + "\"name\":\"Arachne Boots\",\"museum_data\":{\"armor_set_donation_xp\":{\"ARACHNE\":4}}}]}");
        String hyperion = TestData.plainItem("HYPERION");
        http.put(h + "skyblock/auctions?page=0", TestData.auctionPage(0, 1, t0,
            TestData.auction("a1", "s1", "Hyperion", "LEGENDARY", 900_000_000L, true, t0 - 3_600_000L, hyperion),
            TestData.auction("a2", "s2", "Hyperion", "LEGENDARY", 910_000_000L, true, t0 - 3_600_000L, hyperion)));
        String[] sold = new String[5];
        for (int i = 0; i < sold.length; i++) {
            sold[i] = TestData.ended("e" + i, 893_000_000L + i * 1_000_000L, true, t0 - 30_000, hyperion);
        }
        http.put(h + "skyblock/auctions_ended", TestData.endedPage(t0, sold));
        fs.engine().tick();
        long t1 = t0 + 60_000;
        http.put(h + "skyblock/auctions?page=0", TestData.auctionPage(0, 1, t1,
            TestData.auction("a1", "s1", "Hyperion", "LEGENDARY", 900_000_000L, true, t0 - 3_600_000L, hyperion),
            TestData.auction("a2", "s2", "Hyperion", "LEGENDARY", 910_000_000L, true, t0 - 3_600_000L, hyperion),
            TestData.auction("meh", "s5", "Hyperion", "LEGENDARY", 860_000_000L, true, t1 - 10_000, hyperion),
            TestData.auction("new", "s4", "Hyperion", "LEGENDARY", 800_000_000L, true, t1 - 10_000, hyperion)));
        clock.set(t1 + 5_000);
        fs.engine().tick();

        fs.command(new String[] {"blocked"});
        String text = platform.allText();
        assertTrue(text.contains("Hyperion » 860M | +3.68M (0.4%) | margin 0.4% below 5%"), text);
        fs.command(new String[] {"search", "hyper", "max:850m"});
        text = platform.allText();
        assertTrue(text.contains("Cheapest BIN auctions for \"hyper\" up to 850M (1 found):"), text);
        assertTrue(text.contains("Hyperion » 800M"), text);
        fs.command(new String[] {"museum"});
        text = platform.allText();
        assertTrue(text.contains("Hyperion » 800M for 10 XP (80M/XP)"), text);
        assertTrue(!text.contains("Arachne"), text);

        fs.command(new String[] {"set", "ah.itemMinProfit", "HYPERION=20m,juju_shortbow=1.5m"});
        assertEquals(Long.valueOf(20_000_000L), fs.config().ahItemMinProfit.get("HYPERION"));
        assertEquals(Long.valueOf(1_500_000L), fs.config().ahItemMinProfit.get("JUJU_SHORTBOW"));
        fs.command(new String[] {"set", "ah.itemMinProfit", "HYPERION"});
        assertTrue(platform.allText().contains("write ID=amount"), platform.allText());

        List<String> tooltip = fs.tooltip(TestData.rawNbt(TestData.map("ExtraAttributes", TestData.map("id", "HYPERION"))),
            "§6Hyperion", 1);
        assertTrue(tooltip.get(0).contains("FlipScout: §fworth"), tooltip.toString());
        assertEquals(tooltip, fs.tooltip(TestData.rawNbt(TestData.map("id", "HYPERION")), "§6Hyperion", 1));
        assertTrue(fs.tooltip(new byte[] {1, 2, 3}, "junk", 1).isEmpty());

        try {
            fs.command(new String[] {"set", "chat.prefixColor", "aqua"});
            fs.command(new String[] {"test"});
            assertTrue(platform.messages.get(platform.messages.size() - 1).parts().get(0).text.startsWith("§b[FlipScout]"));
        } finally {
            Msg.useColors("gold", "dark_green");
        }
    }

    @Test
    void tabCompletion() {
        assertTrue(flipScout.complete(new String[] {"se"}).contains("set"));
        assertEquals(Arrays.asList("ah.minProfit", "ah.minPercent", "ah.minPrice"), flipScout.complete(new String[] {"set", "ah.minP"}));
        assertEquals(Arrays.asList("ah.minProfit", "ah.minPrice"), flipScout.complete(new String[] {"set", "ah.minPr"}));
        assertEquals(Arrays.asList("conservative", "lbin", "sales"), flipScout.complete(new String[] {"set", "strategy", ""}));
        assertEquals(Arrays.asList("on", "off"), flipScout.complete(new String[] {"bz", "npc", ""}));
        List<String> coins = flipScout.complete(new String[] {"set", "budget", ""});
        assertTrue(coins.contains("1m"));
        assertTrue(flipScout.complete(new String[] {"unknown", "x"}).isEmpty());
    }
}
