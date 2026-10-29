package dev.flipscout.core;

import dev.flipscout.core.api.BazaarSnapshot;
import dev.flipscout.core.api.ItemCatalog;
import dev.flipscout.core.flip.AuctionFlip;
import dev.flipscout.core.flip.BazaarFlip;
import dev.flipscout.core.item.ItemKey;
import dev.flipscout.core.market.AuctionIndex;
import dev.flipscout.core.market.Valuation;
import dev.flipscout.core.util.Coins;
import dev.flipscout.core.util.Text;

import java.io.File;
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

/** /flipscout and its sub-commands, with messages in Italian. */
final class Commands {
    private static final List<String> SUBCOMMANDS = Arrays.asList(
        "help", "on", "off", "ah", "bz", "top", "recent", "open", "set", "config", "blacklist", "status", "reload", "test");
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
    private final Platform platform;
    private final LongSupplier clock;

    Commands(Config config, File configFile, Engine engine, Platform platform, LongSupplier clock) {
        this.config = config;
        this.configFile = configFile;
        this.engine = engine;
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
                case "impostazioni":
                    showConfig(args);
                    break;
                case "blacklist":
                    blacklist(args);
                    break;
                case "status":
                case "stato":
                    status();
                    break;
                case "reload":
                    config.load(configFile);
                    Log.debugEnabled = config.debug;
                    info("§aImpostazioni ricaricate da §f" + configFile.getName());
                    break;
                case "test":
                    test();
                    break;
                default:
                    error("Comando sconosciuto: " + args[0] + ". Scrivi /flipscout help");
            }
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
        }
    }

    private void summary() {
        String state = !config.enabled ? "§cspento"
            : config.onlyOnHypixel && !platform.isOnHypixel() ? "§eacceso, in attesa di Hypixel" : "§aattivo";
        info("§fFlipScout " + FlipScout.VERSION + " §8- " + state
            + " §8| §7aste " + onOff(config.ahEnabled) + " §7bazaar " + onOff(config.bzEnabled));
        platform.showMessage(new Msg().suggest("§7Scrivi §e/flipscout help §7per i comandi", "/flipscout help",
            "§eClicca per scriverlo"));
    }

    private void help() {
        info("§fComandi §8(clicca per scriverli):");
        helpLine("on|off", "", "attiva o spegne tutto");
        helpLine("ah on|off", "ah ", "notifiche delle aste BIN");
        helpLine("bz on|off", "bz ", "notifiche del bazaar (anche: bz flip|crash|npc on|off)");
        helpLine("top [ah|bz] [n]", "top ", "le migliori occasioni in questo momento");
        helpLine("recent", "recent", "ultime notifiche");
        helpLine("open", "open", "apre l'ultima notifica");
        helpLine("set <chiave> <valore>", "set ", "cambia un'impostazione (es. set ah.minProfit 1m)");
        helpLine("config [filtro]", "config", "mostra le impostazioni");
        helpLine("blacklist add|remove|list [ID]", "blacklist ", "oggetti da ignorare");
        helpLine("status", "status", "stato delle fonti dati");
        helpLine("reload", "reload", "rilegge il file delle impostazioni");
        helpLine("test", "test", "mostra una notifica di prova");
    }

    private void helpLine(String usage, String suggestion, String description) {
        platform.showMessage(new Msg().suggest("§e/flipscout " + usage + " §7- " + description,
            "/flipscout " + suggestion, "§7Clicca per scrivere il comando"));
    }

    private void toggleAll(boolean on) {
        config.enabled = on;
        config.save(configFile);
        info(on ? "§aFlipScout acceso." : "§cFlipScout spento: nessuna richiesta finché non lo riaccendi.");
    }

    private void auctions(String[] args) {
        if (args.length < 2) {
            info("§7Notifiche aste: " + onOff(config.ahEnabled));
            return;
        }
        config.ahEnabled = Config.parseBoolean(args[1]);
        config.save(configFile);
        info("§7Notifiche aste: " + onOff(config.ahEnabled));
    }

    private void bazaar(String[] args) {
        if (args.length < 2) {
            info("§7Bazaar: " + onOff(config.bzEnabled) + " §8| §7flip " + onOff(config.bzFlipEnabled)
                + " §7crollo " + onOff(config.bzCrashEnabled) + " §7NPC " + onOff(config.bzNpcEnabled));
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        if (ON_OFF.contains(what)) {
            config.bzEnabled = Config.parseBoolean(what);
        } else {
            if (args.length < 3) {
                throw new IllegalArgumentException("Uso: /flipscout bz " + what + " on|off");
            }
            boolean on = Config.parseBoolean(args[2]);
            if ("flip".equals(what)) {
                config.bzFlipEnabled = on;
            } else if ("crash".equals(what) || "crollo".equals(what)) {
                config.bzCrashEnabled = on;
            } else if ("npc".equals(what)) {
                config.bzNpcEnabled = on;
            } else {
                throw new IllegalArgumentException("Uso: /flipscout bz on|off oppure bz flip|crash|npc on|off");
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
                    throw new IllegalArgumentException("Uso: /flipscout top [ah|bz] [numero]");
                }
            }
        }
        final boolean auctions = "ah".equals(which);
        final int n = limit;
        if (auctions && !engine.initialScanDone()) {
            info("§eSto ancora scaricando le aste, riprova tra poco.");
            return;
        }
        info("§7Cerco...");
        engine.runAsync(new Runnable() {
            @Override
            public void run() {
                long now = engine.serverTime();
                if (auctions) {
                    List<AuctionFlip> flips = engine.topAuctions(n);
                    if (flips.isEmpty()) {
                        info("§7Nessuna asta BIN sopra le soglie in questo momento.");
                        return;
                    }
                    info("§fMigliori aste BIN adesso:");
                    for (AuctionFlip flip : flips) {
                        platform.showMessage(engine.notifier().auction(flip, now));
                    }
                } else {
                    BazaarSnapshot snapshot = engine.prices.bazaar();
                    if (snapshot == null) {
                        info("§eDati del bazaar non ancora scaricati, riprova tra poco.");
                        return;
                    }
                    List<BazaarFlip> flips = engine.topBazaar(n);
                    if (flips.isEmpty()) {
                        info("§7Nessuna occasione al bazaar sopra le soglie in questo momento.");
                        return;
                    }
                    info("§fMigliori occasioni al bazaar adesso:");
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
            info("§7Ancora nessuna notifica in questa sessione.");
            return;
        }
        info("§fUltime notifiche §8(la più recente in fondo):");
        long now = clock.getAsLong();
        List<Engine.Notification> ordered = new ArrayList<Engine.Notification>(notifications);
        Collections.reverse(ordered);
        for (Engine.Notification notification : ordered) {
            Msg msg = new Msg().add("§8" + Text.duration(now - notification.time) + " fa ");
            for (Msg.Part part : notification.message.parts()) {
                String text = part.text.startsWith(Msg.PREFIX) ? part.text.substring(Msg.PREFIX.length()) : part.text;
                if (part.command != null && part.click == Msg.Click.RUN) {
                    msg.run(text, part.command, part.hover);
                } else {
                    msg.add(text, part.hover);
                }
            }
            platform.showMessage(msg);
        }
    }

    void open() {
        Engine.Notification last = engine.lastNotification();
        if (last == null || last.command == null) {
            info("§7Ancora nessuna notifica da aprire.");
            return;
        }
        platform.sendCommand(last.command.startsWith("/") ? last.command.substring(1) : last.command);
    }

    private void set(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Uso: /flipscout set <chiave> <valore>. Vedi /flipscout config");
        }
        Config.Setting setting = findSetting(args[1]);
        if (setting == null) {
            throw new IllegalArgumentException("Impostazione sconosciuta: " + args[1] + ". Vedi /flipscout config");
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
                    "/flipscout set " + setting.key + " ", "§7" + setting.help + "\n§eClicca per cambiarla"));
            }
        }
        if (!any) {
            info("§7Nessuna impostazione contiene \"" + filter + "\".");
        }
    }

    private String display(Config.Setting setting) {
        String value = setting.get(config);
        if (setting instanceof Config.CoinsSetting) {
            long coins = Long.parseLong(value);
            return coins == 0 && "budget".equals(setting.key) ? "nessun limite" : Coins.compact(coins);
        }
        if ("true".equals(value) || "false".equals(value)) {
            return onOff(Boolean.parseBoolean(value));
        }
        return value.isEmpty() ? "§8(vuoto)" : value;
    }

    private void blacklist(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if ("list".equals(action)) {
            if (config.blacklist.isEmpty()) {
                info("§7La blacklist è vuota.");
                return;
            }
            info("§fBlacklist §8(clicca per togliere):");
            for (String id : config.blacklist) {
                platform.showMessage(new Msg().suggest("§7- §f" + id, "/flipscout blacklist remove " + id,
                    "§eClicca per toglierlo"));
            }
            return;
        }
        if (args.length < 3 || !("add".equals(action) || "remove".equals(action))) {
            throw new IllegalArgumentException("Uso: /flipscout blacklist add|remove|list [ID]");
        }
        String id = args[2].toUpperCase(Locale.ROOT);
        Set<String> updated = new LinkedHashSet<String>(config.blacklist);
        boolean changed = "add".equals(action) ? updated.add(id) : updated.remove(id);
        config.blacklist = Collections.unmodifiableSet(updated);
        config.save(configFile);
        if ("add".equals(action)) {
            info(changed ? "§a" + id + " §7aggiunto alla blacklist." : "§7" + id + " era già nella blacklist.");
        } else {
            info(changed ? "§a" + id + " §7tolto dalla blacklist." : "§7" + id + " non era nella blacklist.");
        }
    }

    private void status() {
        Status s = engine.status;
        long now = clock.getAsLong();
        info("§fStato");
        String active = !config.enabled ? "§cno (spento)"
            : s.active ? "§asì" : "§eno, non sei su Hypixel";
        line("Attivo", active);
        line("Aste BIN indicizzate", Coins.full(engine.index.size()) + " §8(" + Coins.full(engine.index.keyCount())
            + " oggetti diversi)");
        if (s.scanning) {
            line("Scansione completa", "§ein corso...");
        } else if (s.lastFullScan > 0) {
            line("Ultima scansione completa", Text.ago(now, s.lastFullScan) + " §8(" + s.fullScanPages + " pagine, "
                + Coins.full(s.fullScanAuctions) + " aste, " + Text.duration(s.fullScanMillis) + ")");
        } else {
            line("Ultima scansione completa", "mai");
        }
        line("Controllo aste nuove", Text.ago(now, s.lastAuctionPoll));
        line("Vendite registrate", Coins.full(engine.sales.sampleCount()) + " §8(" + Coins.full(engine.sales.keyCount())
            + " oggetti, " + Text.duration(engine.sales.observedMinutes() * 60_000L) + " di dati)");
        BazaarSnapshot bazaar = engine.prices.bazaar();
        line("Bazaar", bazaar == null ? "non ancora scaricato"
            : bazaar.products.size() + " prodotti, controllato " + Text.ago(now, s.lastBazaarPoll));
        line("NEU", config.sourceNeu
            ? Coins.full(engine.prices.neuLowestBinCount()) + " BIN più bassi, " + Coins.full(engine.prices.neuAverageCount())
                + " medie §8(" + Text.ago(now, s.lastNeu) + ")"
            : "§8disattivato");
        line("Coflnet", config.sourceCoflnet
            ? "ultima risposta " + Text.ago(now, engine.coflnet.lastSuccess())
                + (engine.coflnet.lastError() != null ? " §8(" + engine.coflnet.lastError() + ")" : "")
            : "§8disattivato");
        line("Oggetti conosciuti", Coins.full(engine.items().size()));
        line("Notifiche in questa sessione", s.auctionNotifications + " aste, " + s.bazaarNotifications + " bazaar");
        for (Map.Entry<String, String> error : s.errors().entrySet()) {
            platform.showMessage(Msg.plain("§c  Errore " + error.getKey() + ": §7" + error.getValue()));
        }
    }

    private void test() {
        long now = clock.getAsLong();
        ItemKey key = new ItemKey("HYPERION", "HYPERION", "HYPERION", true, "HYPERION", null);
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
        info("§7Notifica di prova: passa il mouse sopra per i dettagli. Quelle vere aprono l'asta col clic.");
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
                    return startingWith(Arrays.asList("ah.", "bz.", "source.", "url."), last);
                case "blacklist":
                    return startingWith(Arrays.asList("add", "remove", "list"), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3) {
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

    // ---- Helpers ----

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
