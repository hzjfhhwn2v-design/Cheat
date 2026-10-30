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
        run("guadagni");
        String text = platform.allText();
        assertTrue(text.contains("Sessione (attiva): +40 in 1 flip"), text);
        assertTrue(text.contains("Totale: +40 in 1 flip"), text);
        run("sessione", "pausa");
        assertTrue(platform.allText().contains("Sessione in pausa"), platform.allText());
        run("sessione", "reset");
        run("sessione", "storico");
        assertTrue(platform.allText().contains("Sessioni precedenti"), platform.allText());
        run("flip");
        assertTrue(platform.allText().contains("Enchanted Diamond 160 → 200 +40"), platform.allText());
        run("totale", "reset");
        assertTrue(platform.allText().contains("totale reset conferma"), platform.allText());
        assertEquals(40, flipScout.tracker().total().profit, 1e-9);
        run("totale", "reset", "conferma");
        assertEquals(0, flipScout.tracker().total().profit, 1e-9);
        assertEquals(Arrays.asList("pausa", "riprendi", "reset", "storico"), flipScout.complete(new String[] {"sessione", ""}));
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
        long t2 = t1 + 60_000;
        http.put(h + "auctions_ended", TestData.endedPage(t2, TestData.ended("new", 800_000_000L, true, t1 + 2_000, hyperion)));
        clock.set(t2 + 1_000);
        fs.engine().tick();

        fs.command(new String[] {"recent"});
        String text = platform.allText();
        assertTrue(text.contains("1 aste su 1 già vendute, metà entro 12s"), text);
        assertTrue(text.contains("[venduta dopo 12s]"), text);
        fs.command(new String[] {"open"});
        assertTrue(platform.commands.isEmpty());
        assertTrue(platform.allText().contains("già stata comprata"), platform.allText());
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
        run("set", "ah.minPercent", "tanti");
        run("set", "bz.intervalSeconds", "1");
        run("set", "nope", "1");
        assertEquals(5, flipScout.config().ahMinPercent, 1e-9);
        assertEquals(20, flipScout.config().bzIntervalSeconds);
        String text = platform.allText();
        assertTrue(text.contains("ah.minPercent: serve un numero"), text);
        assertTrue(text.contains("deve essere tra 10 e 600"), text);
        assertTrue(text.contains("Impostazione sconosciuta"), text);
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
        run("sbagliato");
        String text = platform.allText();
        assertTrue(text.contains("FlipScout " + FlipScout.VERSION), text);
        assertTrue(text.contains("/flipscout set <chiave> <valore>"), text);
        assertTrue(text.contains("Aste BIN indicizzate"), text);
        assertTrue(text.contains("bz.crash.dropPercent"), text);
        assertTrue(text.contains("Ancora nessuna notifica"), text);
        assertTrue(text.contains("Comando sconosciuto"), text);
        assertTrue(platform.commands.isEmpty());
    }

    @Test
    void discordWebhookIsCheckedAndNeverShownInChat() {
        String webhook = "https://discord.com/api/webhooks/1234567890/SecretToken_abc-123";
        run("set", "discord.webhook", "https://example.com/hook");
        assertEquals("", flipScout.config().discordWebhook);
        assertTrue(platform.allText().contains("webhook di Discord"), platform.allText());
        run("set", "discord.webhook", webhook);
        assertEquals(webhook, flipScout.config().discordWebhook);
        run("config", "discord");
        run("discord");
        run("status");
        String text = platform.allText();
        assertFalse(text.contains("SecretToken"), text);
        assertTrue(text.contains("impostato"), text);
        assertTrue(text.contains("Resoconti su Discord"), text);
        // No value empties it, which switches the reports off.
        run("set", "discord.webhook");
        assertEquals("", flipScout.config().discordWebhook);
        run("set", "discord.webhook", "https://canary.discord.com/api/v10/webhooks/1/abc/");
        assertEquals("https://canary.discord.com/api/v10/webhooks/1/abc", flipScout.config().discordWebhook);
    }

    @Test
    void coopCommandExplainsHowMembersAreFound() {
        run("coop");
        assertTrue(platform.allText().contains("Nessun membro della coop"), platform.allText());
    }

    @Test
    void testNotificationHasHoverAndClick() {
        run("test");
        Msg sample = platform.messages.get(platform.messages.size() - 1);
        Msg.Part part = sample.parts().get(0);
        assertEquals("/flipscout help", part.command);
        assertEquals(Msg.Click.SUGGEST, part.click);
        assertTrue(part.hover.contains("Profitto"), part.hover);
        assertTrue(sample.toPlainString().contains("[APRI]"));
    }

    @Test
    void tabCompletion() {
        assertTrue(flipScout.complete(new String[] {"se"}).contains("set"));
        assertEquals(Arrays.asList("ah.minProfit", "ah.minPercent"), flipScout.complete(new String[] {"set", "ah.minP"}));
        assertEquals(Arrays.asList("ah.minProfit"), flipScout.complete(new String[] {"set", "ah.minPr"}));
        assertEquals(Arrays.asList("conservative", "lbin", "sales"), flipScout.complete(new String[] {"set", "strategy", ""}));
        assertEquals(Arrays.asList("on", "off"), flipScout.complete(new String[] {"bz", "npc", ""}));
        List<String> coins = flipScout.complete(new String[] {"set", "budget", ""});
        assertTrue(coins.contains("1m"));
        assertTrue(flipScout.complete(new String[] {"unknown", "x"}).isEmpty());
    }
}
