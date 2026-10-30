package dev.flipscout.core;

import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.profit.ProfitTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTest {
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456/abc-DEF_1";

    @TempDir
    File dir;

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final Config config = new Config();
    private final FakeFetcher http = new FakeFetcher();
    private final FakePlatform platform = new FakePlatform();
    private final AtomicReference<Engine.Notification> notified = new AtomicReference<Engine.Notification>();
    private Discord discord;
    private ProfitTracker tracker;

    @BeforeEach
    void setUp() {
        config.discordWebhook = WEBHOOK;
        platform.uuid = "0123abcd";
        platform.name = "baldih";
        Map<String, ItemCatalog.Info> items = new HashMap<String, ItemCatalog.Info>();
        items.put("HYPERION", new ItemCatalog.Info("HYPERION", "Hyperion", "LEGENDARY", 0));
        ItemCatalog catalog = new ItemCatalog(items);
        discord = new Discord(config, http, platform, clock::get, () -> catalog, (name, price) -> notified.get());
        tracker = new ProfitTracker(config, new File(dir, "guadagni.json"), clock::get, platform, Runnable::run);
        tracker.setListener(discord::onTrade);
    }

    /** Waits for the messages queued so far to go out. */
    private List<String[]> sent() {
        discord.stop();
        return http.posts;
    }

    private static Map<String, Object> embed(String json) throws Exception {
        Map<String, Object> root = JsonTree.obj(JsonTree.parse(new StringReader(json)));
        assertEquals("FlipScout", root.get("username"));
        return JsonTree.obj(JsonTree.arr(root.get("embeds")).get(0));
    }

    private static String fields(Map<String, Object> embed) {
        StringBuilder sb = new StringBuilder();
        for (Object o : JsonTree.arr(embed.get("fields"))) {
            Map<String, Object> field = JsonTree.obj(o);
            sb.append(field.get("name")).append('=').append(field.get("value")).append('\n');
        }
        return sb.toString();
    }

    @Test
    void aPurchaseAndTheSaleThatClosesTheFlip() throws Exception {
        notified.set(new Engine.Notification(0, Msg.plain("x"), "/viewauction a", "a", 0, 0, "Hyperion", 800_000_000L,
            900_000_000, 77_500_000));
        tracker.onChat("You purchased Hyperion for 800,000,000 coins!");
        clock.addAndGet(2 * 3_600_000L);
        tracker.onChat("[Auction] [MVP+] Bob_1 bought Hyperion for 900,000,000 coins CLICK");
        List<String[]> posts = sent();
        assertEquals(2, posts.size());
        assertEquals(WEBHOOK, posts.get(0)[0]);

        Map<String, Object> bought = embed(posts.get(0)[1]);
        assertEquals("🛒 Hai comprato Hyperion per 800M", bought.get("title"));
        assertEquals("baldih", JsonTree.obj(bought.get("author")).get("name"));
        assertEquals("https://mc-heads.net/avatar/0123abcd/64", JsonTree.obj(bought.get("author")).get("icon_url"));
        assertEquals("https://sky.coflnet.com/static/icon/HYPERION", JsonTree.obj(bought.get("thumbnail")).get("url"));
        String boughtFields = fields(bought);
        assertTrue(boughtFields.contains("Prezzo=**800M** (800,000,000)"), boughtFields);
        assertTrue(boughtFields.contains("Rivendita stimata=**900M**"), boughtFields);
        assertTrue(boughtFields.contains("Profitto previsto=+77.5M (9.7%)"), boughtFields);

        Map<String, Object> sold = embed(posts.get(1)[1]);
        assertEquals("💰 Hai venduto Hyperion per 900M (+68.5M)", sold.get("title"));
        // Underscores in names would turn into italics on Discord.
        assertEquals("Comprato da **Bob\\_1**", sold.get("description"));
        assertEquals(0x2ECC71, ((Number) sold.get("color")).intValue());
        String soldFields = fields(sold);
        assertTrue(soldFields.contains("Incasso dopo le tasse=**869M** (868,500,000)"), soldFields);
        assertTrue(soldFields.contains("Profitto=**+68.5M** (8.6%)"), soldFields);
        assertTrue(soldFields.contains("Tenuto per=2 h"), soldFields);
        assertTrue(String.valueOf(JsonTree.obj(sold.get("footer")).get("text")).contains("sessione +68.5M"));
        assertTrue(String.valueOf(sold.get("timestamp")).startsWith("2027-"));
        assertTrue(platform.messages.stream().noneMatch(m -> m.toPlainString().contains("Discord")));
    }

    @Test
    void onlyWhatTheSettingsAskForIsSent() {
        // The bazaar is off by default.
        tracker.onChat("[Bazaar] Bought 64x Enchanted Diamond for 10,000 coins!");
        // Below the minimum.
        config.discordMinCoins = 1_000_000;
        tracker.onChat("You purchased Grappling Hook for 300,000 coins!");
        config.discordMinCoins = 0;
        // Switched off, or no webhook.
        config.discordEnabled = false;
        tracker.onChat("You purchased Hyperion for 800,000,000 coins!");
        config.discordEnabled = true;
        config.discordWebhook = "";
        tracker.onChat("You purchased Hyperion for 800,000,000 coins!");
        config.discordWebhook = WEBHOOK;
        config.discordBazaar = true;
        tracker.onChat("[Bazaar] Sold 64x Enchanted Diamond for 11,000 coins!");
        List<String[]> posts = sent();
        assertEquals(1, posts.size());
        assertTrue(posts.get(0)[1].contains("Bazaar: hai venduto 64x Enchanted Diamond per 11k"), posts.get(0)[1]);
    }

    @Test
    void ratesLimitsAreWaitedOut() {
        http.postStatuses.add(429);
        tracker.onChat("You purchased Hyperion for 800,000,000 coins!");
        assertEquals(2, sent().size());
        assertEquals(null, discord.lastError());
        assertTrue(discord.lastSent() > 0);
    }

    @Test
    void aWebhookThatStoppedWorkingIsReportedOnce() {
        http.postStatuses.add(404);
        http.postStatuses.add(404);
        tracker.onChat("You purchased Hyperion for 800,000,000 coins!");
        tracker.onChat("You purchased Hyperion for 810,000,000 coins!");
        assertEquals(2, sent().size());
        long reports = platform.messages.stream().filter(m -> m.toPlainString().contains("il webhook non esiste")).count();
        assertEquals(1, reports);
        assertTrue(discord.lastError().contains("non esiste"));
    }

    @Test
    void testMessageSaysHowItWent() throws Exception {
        discord.test();
        List<String[]> posts = sent();
        assertEquals(1, posts.size());
        assertEquals("✅ FlipScout è collegato", embed(posts.get(0)[1]).get("title"));
        assertTrue(platform.allText().contains("Messaggio di prova inviato"));
    }

    @Test
    void testWithoutAWebhookExplainsWhereToPutIt() {
        config.discordWebhook = "";
        discord.test();
        assertTrue(sent().isEmpty());
        assertTrue(platform.allText().contains("Nessun webhook impostato"));
    }

    @Test
    void escapesDiscordFormatting() {
        assertEquals("a\\*b\\_c\\~d\\`e\\|f\\>g\\\\h", Discord.escape("a*b_c~d`e|f>g\\h"));
        assertFalse(Discord.escape("Hyperion ✪✪").contains("\\"));
    }
}
