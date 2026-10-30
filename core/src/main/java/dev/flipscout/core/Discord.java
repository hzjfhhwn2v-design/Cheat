package dev.flipscout.core;

import com.google.gson.stream.JsonWriter;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.json.JsonTree;
import dev.flipscout.core.profit.ProfitTracker;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Reports each purchase and sale to a Discord channel through a webhook, as an embed: what, for
 * how much, and the profit of the flip a sale closes. Messages go out one at a time on a thread of
 * their own, within Discord's rate limits; a webhook that stops working is reported once in chat.
 */
final class Discord {
    private static final int MAX_QUEUED = 50;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BLUE = 0x3498DB;
    private static final int PURPLE = 0x9B59B6;
    private static final int GREEN = 0x2ECC71;
    private static final int RED = 0xE74C3C;
    private static final int GOLD = 0xF1C40F;
    private static final String ICONS = "https://sky.coflnet.com/static/icon/";
    private static final String HEADS = "https://mc-heads.net/avatar/";

    private final Config config;
    private final HttpFetcher http;
    private final Platform platform;
    private final LongSupplier clock;
    private final Supplier<ItemCatalog> items;
    /** Finds the flip notification behind a purchase: (item name, price) -> notification or null. */
    private final BiFunction<String, Double, Engine.Notification> notified;
    private final ThreadPoolExecutor sender;
    private volatile long lastSent;
    private volatile String lastError;
    /** The webhook whose failure was last reported in chat, so it is reported once. */
    private volatile String reportedBroken;

    Discord(Config config, HttpFetcher http, Platform platform, LongSupplier clock, Supplier<ItemCatalog> items,
            BiFunction<String, Double, Engine.Notification> notified) {
        this.config = config;
        this.http = http;
        this.platform = platform;
        this.clock = clock;
        this.items = items;
        this.notified = notified;
        sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(MAX_QUEUED),
            runnable -> {
                Thread thread = new Thread(runnable, "FlipScout-discord");
                thread.setDaemon(true);
                return thread;
            });
    }

    /** A trade recognised in chat: reported when the settings ask for it. */
    void onTrade(ProfitTracker.Event event) {
        String webhook = config.discordWebhook;
        if (!config.discordEnabled || webhook.isEmpty()) {
            return;
        }
        if (event.isAuction() ? !config.discordAuctions : !config.discordBazaar) {
            return;
        }
        if (event.coins < config.discordMinCoins) {
            return;
        }
        Engine.Notification flip = event.kind == ProfitTracker.Event.Kind.AUCTION_BOUGHT
            ? notified.apply(event.item, event.coins) : null;
        send(webhook, report(event, flip), false);
    }

    /** Sends a test message and says in chat how it went. */
    void test() {
        String webhook = config.discordWebhook;
        if (webhook.isEmpty()) {
            platform.showMessage(Msg.prefixed("§cNessun webhook impostato: §7incollalo in §e/flipscout gui §7→ Discord."));
            return;
        }
        send(webhook, testMessage(), true);
    }

    long lastSent() {
        return lastSent;
    }

    String lastError() {
        return lastError;
    }

    /** Lets the messages already queued go out, for a little while, before the game closes. */
    void stop() {
        sender.shutdown();
        try {
            sender.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(final String webhook, final String json, final boolean test) {
        try {
            sender.execute(() -> deliver(webhook, json.getBytes(StandardCharsets.UTF_8), test));
        } catch (RejectedExecutionException e) {
            lastError = "troppi messaggi in coda, alcuni sono andati persi";
        }
    }

    private void deliver(String webhook, byte[] body, boolean test) {
        for (int attempt = 1; ; attempt++) {
            int status;
            long retryAfter;
            try (HttpFetcher.Response response = http.post(webhook, "application/json", body)) {
                status = response.status;
                retryAfter = status == 429 ? retryAfter(response) : 0;
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS && pause(2_000L * attempt)) {
                    continue;
                }
                fail(webhook, "Discord non raggiungibile (" + e.getMessage() + ")", test, false);
                return;
            }
            if (status >= 200 && status < 300) {
                lastSent = clock.getAsLong();
                lastError = null;
                reportedBroken = null;
                if (test) {
                    platform.showMessage(Msg.prefixed("§aMessaggio di prova inviato: §7controlla il canale su Discord."));
                }
                return;
            }
            boolean again = status == 429 || status >= 500;
            if (again && attempt < MAX_ATTEMPTS && pause(status == 429 ? Math.max(500L, Math.min(retryAfter, 30_000L))
                : 2_000L * attempt)) {
                continue;
            }
            fail(webhook, explain(status), test, status == 401 || status == 403 || status == 404);
            return;
        }
    }

    /** Discord says how long to wait in the Retry-After header or in the body ("retry_after": 1.5). */
    private static long retryAfter(HttpFetcher.Response response) {
        if (response.retryAfterMs > 0) {
            return response.retryAfterMs;
        }
        try {
            double seconds = JsonTree.num(JsonTree.obj(JsonTree.parse(
                new InputStreamReader(response.body, StandardCharsets.UTF_8))).get("retry_after"), 1);
            return (long) Math.ceil(seconds * 1000);
        } catch (IOException | RuntimeException e) {
            return 1_000L;
        }
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String explain(int status) {
        switch (status) {
            case 404:
                return "il webhook non esiste (cancellato, o indirizzo sbagliato)";
            case 401:
            case 403:
                return "il webhook non è valido";
            case 429:
                return "troppi messaggi, Discord ha chiesto di rallentare";
            default:
                return "Discord ha risposto HTTP " + status;
        }
    }

    private void fail(String webhook, String why, boolean test, boolean broken) {
        lastError = why;
        Log.warn("Discord: " + why);
        if (test || (broken && !webhook.equals(reportedBroken))) {
            reportedBroken = webhook;
            platform.showMessage(Msg.prefixed("§cDiscord: " + why + ". §7Controlla il webhook in §e/flipscout gui §7→ Discord."));
        }
    }

    // ---- Messages ----

    /** The webhook message for {@code e}; {@code flip} is the notification behind a purchase, or null. */
    String report(ProfitTracker.Event e, Engine.Notification flip) {
        Embed embed = new Embed();
        String what = e.amount > 1 ? e.amount + "x " + e.item : e.item;
        switch (e.kind) {
            case AUCTION_BOUGHT:
                embed.title = "🛒 Hai comprato " + what + " per " + Coins.compact(e.coins);
                embed.color = BLUE;
                embed.field("Prezzo", coins(e.coins));
                if (flip != null) {
                    embed.field("Rivendita stimata", coins(flip.resale));
                    embed.field("Profitto previsto", signed(flip.profit) + percent(flip.profit, e.coins));
                }
                break;
            case AUCTION_SOLD:
                embed.title = "💰 Hai venduto " + what + " per " + Coins.compact(e.coins)
                    + (e.flip != null ? " (" + signed(e.flip.profit) + ")" : "");
                if (e.player != null && !e.player.isEmpty()) {
                    embed.description = "Comprato da **" + escape(e.player) + "**" + (e.whileAway ? " mentre non c'eri" : "");
                }
                embed.color = e.flip == null ? GOLD : e.flip.profit >= 0 ? GREEN : RED;
                embed.field("Prezzo", coins(e.coins));
                embed.field("Incasso dopo le tasse", coins(e.net));
                flipFields(embed, e.flip);
                break;
            case BAZAAR_BOUGHT:
                embed.title = "🛒 Bazaar: hai comprato " + e.amount + "x " + e.item + " per " + Coins.compact(e.coins);
                embed.color = PURPLE;
                embed.field("Spesa", coins(e.coins));
                embed.field("Prezzo unitario", Coins.unit(e.coins / e.amount));
                break;
            default:
                embed.title = "💰 Bazaar: hai venduto " + e.amount + "x " + e.item + " per " + Coins.compact(e.net)
                    + (e.flip != null ? " (" + signed(e.flip.profit) + ")" : "");
                embed.color = e.flip == null ? GOLD : e.flip.profit >= 0 ? GREEN : RED;
                embed.field("Incasso dopo le tasse", coins(e.net));
                embed.field("Prezzo unitario", Coins.unit(e.net / e.amount));
                flipFields(embed, e.flip);
                break;
        }
        embed.icon = icon(e.item);
        embed.footer = "FlipScout · sessione " + signed(e.sessionProfit) + (e.sessionPaused ? " (in pausa)" : "")
            + " · oggi " + signed(e.todayProfit);
        embed.time = e.time;
        return embed.toJson(platform.playerName(), platform.playerUuid());
    }

    private static void flipFields(Embed embed, ProfitTracker.Flip flip) {
        if (flip == null) {
            embed.field("Profitto", "non calcolato: il mod non ha visto l'acquisto", false);
            return;
        }
        embed.field("Pagato", coins(flip.cost));
        embed.field("Profitto", "**" + signed(flip.profit) + "**" + percent(flip.profit, flip.cost));
        if (flip.boughtAt > 0) {
            embed.field("Tenuto per", Text.duration(flip.soldAt - flip.boughtAt));
        }
    }

    String testMessage() {
        Embed embed = new Embed();
        embed.title = "✅ FlipScout è collegato";
        embed.description = "Da qui in poi arrivano in questo canale i resoconti di acquisti e vendite.";
        embed.color = GREEN;
        embed.field("Aste", config.discordAuctions ? "sì" : "no");
        embed.field("Bazaar", config.discordBazaar ? "sì" : "no");
        embed.field("Importo minimo", config.discordMinCoins > 0 ? Coins.compact(config.discordMinCoins) : "nessuno");
        embed.footer = "FlipScout " + FlipScout.VERSION;
        embed.time = clock.getAsLong();
        return embed.toJson(platform.playerName(), platform.playerUuid());
    }

    /** Coflnet's picture of the item, when the name can be traced back to an id. */
    private String icon(String itemName) {
        String id = items.get().idForName(itemName);
        return id == null ? null : ICONS + id;
    }

    private static String coins(double coins) {
        return "**" + Coins.compact(coins) + "** (" + Coins.full(coins) + ")";
    }

    private static String signed(double coins) {
        return coins >= 0 ? "+" + Coins.compact(coins) : Coins.compact(coins);
    }

    private static String percent(double profit, double cost) {
        return cost > 0 ? " (" + Coins.decimal(profit * 100.0 / cost, 1) + "%)" : "";
    }

    /** Item and player names must not turn into Discord formatting. */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("\\*_~`|>".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** One embed: what the messages are made of. */
    private static final class Embed {
        String title;
        String description;
        int color;
        String icon;
        String footer;
        long time;
        private final List<String[]> fields = new ArrayList<String[]>();

        void field(String name, String value) {
            field(name, value, true);
        }

        void field(String name, String value, boolean inline) {
            fields.add(new String[] {name, value, String.valueOf(inline)});
        }

        String toJson(String player, String uuid) {
            try {
                StringWriter out = new StringWriter();
                JsonWriter w = new JsonWriter(out);
                w.beginObject();
                w.name("username").value("FlipScout");
                w.name("allowed_mentions").beginObject().name("parse").beginArray().endArray().endObject();
                w.name("embeds").beginArray().beginObject();
                if (player != null) {
                    w.name("author").beginObject().name("name").value(player);
                    w.name("icon_url").value(HEADS + (uuid != null ? uuid : player) + "/64");
                    w.endObject();
                }
                w.name("title").value(limit(escape(title), 256));
                if (description != null) {
                    w.name("description").value(limit(description, 2000));
                }
                w.name("color").value(color);
                if (!fields.isEmpty()) {
                    w.name("fields").beginArray();
                    for (String[] field : fields) {
                        w.beginObject().name("name").value(field[0]).name("value").value(limit(field[1], 1024))
                            .name("inline").value(Boolean.parseBoolean(field[2])).endObject();
                    }
                    w.endArray();
                }
                if (icon != null) {
                    w.name("thumbnail").beginObject().name("url").value(icon).endObject();
                }
                if (footer != null) {
                    w.name("footer").beginObject().name("text").value(footer).endObject();
                }
                if (time > 0) {
                    w.name("timestamp").value(Instant.ofEpochMilli(time).toString());
                }
                w.endObject().endArray();
                w.endObject();
                w.close();
                return out.toString();
            } catch (IOException e) {
                // A StringWriter does not fail.
                throw new IllegalStateException(e);
            }
        }

        private static String limit(String text, int max) {
            return text.length() <= max ? text : text.substring(0, max - 1) + "…";
        }
    }
}
