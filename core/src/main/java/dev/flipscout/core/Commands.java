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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
        "help", "gui", "on", "off", "ah", "bz", "top", "recent", "open", "guadagni", "sessione", "totale", "flip",
        "magazzino", "set", "config", "blacklist", "status", "reload", "test");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");
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
    private final Platform platform;
    private final LongSupplier clock;

    Commands(Config config, File configFile, Engine engine, ProfitTracker tracker, Platform platform, LongSupplier clock) {
        this.config = config;
        this.configFile = configFile;
        this.engine = engine;
        this.tracker = tracker;
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
                case "guadagni":
                case "profitti":
                case "profit":
                    profits();
                    break;
                case "sessione":
                case "session":
                    session(args);
                    break;
                case "totale":
                    total(args);
                    break;
                case "flip":
                case "flips":
                    flips();
                    break;
                case "magazzino":
                    inventory(args);
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
        platform.showMessage(new Msg()
            .run("§6[IMPOSTAZIONI] ", "/flipscout gui", "§7Apre il menu delle impostazioni")
            .suggest("§7Scrivi §e/flipscout help §7per i comandi", "/flipscout help", "§eClicca per scriverlo"));
    }

    private void help() {
        info("§fComandi §8(clicca per scriverli):");
        helpLine("gui", "gui", "apre il menu delle impostazioni");
        helpLine("on|off", "", "attiva o spegne tutto");
        helpLine("ah on|off", "ah ", "notifiche delle aste BIN");
        helpLine("bz on|off", "bz ", "notifiche del bazaar (anche: bz flip|crash|npc on|off)");
        helpLine("top [ah|bz] [n]", "top ", "le migliori occasioni in questo momento");
        helpLine("recent", "recent", "ultime notifiche");
        helpLine("open", "open", "apre l'ultima notifica");
        helpLine("guadagni", "guadagni", "quanto hai guadagnato: sessione, oggi, totale");
        helpLine("sessione [pausa|riprendi|reset|storico]", "sessione ", "gestisce la sessione");
        helpLine("totale [reset]", "totale", "totale e ultimi giorni");
        helpLine("flip", "flip", "gli ultimi flip chiusi");
        helpLine("magazzino [svuota]", "magazzino", "comprati e non ancora venduti");
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
        info("§fUltime notifiche §8(la più recente in fondo)" + soldSummary(notifications) + "§f:");
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
            return " §8- delle " + auctions + " aste nessuna risulta ancora venduta";
        }
        Collections.sort(times);
        return " §8- " + times.size() + " aste su " + auctions + " già vendute, metà entro "
            + Text.shortDuration(times.get((times.size() - 1) / 2)) + " dalla messa in vendita";
    }

    private void addSaleState(Msg msg, Engine.Notification notification) {
        if (notification.auctionUuid == null) {
            return;
        }
        long soldAt = notification.soldAt();
        if (soldAt > 0) {
            if (notification.boughtByYou()) {
                msg.add(" §a[comprata da te]");
                return;
            }
            String after = Text.shortDuration(soldAt - notification.listedAt);
            long afterNotice = soldAt - notification.notifiedAt;
            msg.add(" §c[venduta dopo " + after + "]", "§7Comprata " + after + " dopo la messa in vendita, "
                + (afterNotice >= 0 ? Text.shortDuration(afterNotice) + " dopo l'avviso" : "prima dell'avviso"));
        } else if (!engine.index.contains(notification.auctionUuid)) {
            msg.add(" §8[non più in vendita]", "§7Non è più tra le aste attive: comprata, ritirata o scaduta");
        }
    }

    void open() {
        Engine.Notification last = engine.lastNotification();
        if (last == null || last.command == null) {
            info("§7Ancora nessuna notifica da aprire.");
            return;
        }
        if (last.soldAt() > 0) {
            info(last.boughtByYou() ? "§aQuest'asta l'hai già comprata tu."
                : "§cL'asta è già stata comprata da qualcun altro, non la apro.");
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
        if (s.lastRefreshDelay > 0) {
            line("Ultimo aggiornamento", "visto " + Coins.decimal(s.lastRefreshDelay / 1000.0, 1)
                + " s dopo che Hypixel l'ha preparato §8(di solito lo pubblica dopo "
                + Coins.decimal(s.publishDelay / 1000.0, 1) + " s)");
        }
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
            ? Coins.full(engine.coflnet.cachedPrices()) + " prezzi in memoria, ultima risposta "
                + Text.ago(now, engine.coflnet.lastSuccess())
                + (engine.coflnet.lastError() != null ? " §8(" + engine.coflnet.lastError() + ")" : "")
            : "§8disattivato");
        line("Oggetti conosciuti", Coins.full(engine.items().size()));
        line("Notifiche in questa sessione", s.auctionNotifications + " aste, " + s.bazaarNotifications + " bazaar");
        if (config.trackerEnabled) {
            ProfitTracker.Session session = tracker.session();
            line("Guadagni", "sessione " + ProfitTracker.signed(session.stats.profit) + (session.paused ? " §e(in pausa)" : "")
                + " §8| §7oggi " + ProfitTracker.signed(tracker.today().profit)
                + " §8| §7totale " + ProfitTracker.signed(tracker.total().profit));
        }
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
                case "sessione":
                    return startingWith(Arrays.asList("pausa", "riprendi", "reset", "storico"), last);
                case "totale":
                    return startingWith(Collections.singletonList("reset"), last);
                case "magazzino":
                    return startingWith(Collections.singletonList("svuota"), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3) {
            if ("totale".equals(sub) && "reset".equalsIgnoreCase(args[1])) {
                return startingWith(Collections.singletonList("conferma"), last);
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

    // ---- Helpers ----

    // ---- Profits ----

    private void profits() {
        if (!config.trackerEnabled) {
            info("§7Il conteggio dei guadagni è spento: §e/flipscout set tracker.enabled on");
            return;
        }
        ProfitTracker.Session session = tracker.session();
        info("§fGuadagni");
        line("Sessione " + (session.paused ? "§e(in pausa)" : "§a(attiva)"), stats(session.stats) + " §8| "
            + Text.duration(session.activeMillis) + perHour(session));
        line("Oggi", stats(tracker.today()));
        line("Totale", stats(tracker.total()) + " §8(dal " + date(tracker.since()) + ")");
        List<ProfitTracker.Lot> lots = tracker.lots();
        List<ProfitTracker.Unclaimed> unclaimed = tracker.unclaimed();
        if (!lots.isEmpty() || !unclaimed.isEmpty()) {
            double cost = 0;
            for (ProfitTracker.Lot lot : lots) {
                cost += lot.unitCost * lot.amount;
            }
            line("Magazzino", lots.size() + " acquisti da rivendere §8(" + Coins.compact(cost) + ")"
                + (unclaimed.isEmpty() ? "" : "§f, " + unclaimed.size() + " vendite da ritirare"));
        }
        platform.showMessage(new Msg()
            .suggest("§e[SESSIONE] ", "/flipscout sessione", "§7Dettagli, pausa e reset della sessione")
            .suggest("§e[ULTIMI FLIP] ", "/flipscout flip", "§7I flip chiusi più recenti")
            .suggest("§e[MAGAZZINO] ", "/flipscout magazzino", "§7Comprati e non ancora venduti")
            .suggest("§e[TOTALE]", "/flipscout totale", "§7Totale e ultimi giorni"));
    }

    private void session(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "":
                showSession();
                break;
            case "pausa":
            case "pause":
            case "stop":
                info(tracker.pauseSession() ? "§eSessione in pausa: §7acquisti e vendite contano solo nel totale."
                    : "§7La sessione è già in pausa.");
                break;
            case "riprendi":
            case "resume":
            case "start":
                info(tracker.resumeSession() ? "§aSessione ripresa." : "§7La sessione è già attiva.");
                break;
            case "reset":
            case "nuova":
                ProfitTracker.Session old = tracker.session();
                tracker.resetSession();
                info("§aNuova sessione iniziata. §7Quella di prima (" + ProfitTracker.signed(old.stats.profit)
                    + "§7) è in §e/flipscout sessione storico§7.");
                break;
            case "storico":
            case "history":
                showHistory();
                break;
            default:
                throw new IllegalArgumentException("Uso: /flipscout sessione [pausa|riprendi|reset|storico]");
        }
    }

    private void showSession() {
        ProfitTracker.Session s = tracker.session();
        info("§fSessione " + (s.paused ? "§e(in pausa)" : "§a(attiva)") + " §8- iniziata il " + dateTime(s.startedAt)
            + ", " + Text.duration(s.activeMillis) + " di gioco");
        details(s.stats);
        if (!Double.isNaN(s.profitPerHour())) {
            line("All'ora", ProfitTracker.signed(s.profitPerHour()));
        }
        Msg buttons = new Msg();
        if (s.paused) {
            buttons.suggest("§a[RIPRENDI] ", "/flipscout sessione riprendi", "§7Torna a contare in questa sessione");
        } else {
            buttons.suggest("§e[PAUSA] ", "/flipscout sessione pausa", "§7Smette di contare finché non riprendi");
        }
        buttons.suggest("§c[RESET] ", "/flipscout sessione reset", "§7Archivia questa sessione e ne inizia una nuova");
        buttons.suggest("§7[STORICO]", "/flipscout sessione storico", "§7Le sessioni precedenti");
        platform.showMessage(buttons);
    }

    private void showHistory() {
        List<ProfitTracker.PastSession> history = tracker.history();
        if (history.isEmpty()) {
            info("§7Nessuna sessione archiviata: si archiviano con §e/flipscout sessione reset§7.");
            return;
        }
        info("§fSessioni precedenti §8(la più recente in alto):");
        for (int i = 0; i < Math.min(10, history.size()); i++) {
            ProfitTracker.PastSession past = history.get(i);
            platform.showMessage(Msg.plain("§7  " + dateTime(past.startedAt) + " §8(" + Text.duration(past.activeMillis)
                + "): " + stats(past.stats)));
        }
    }

    private void total(String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if ("reset".equals(action)) {
            if (args.length > 2 && "conferma".equalsIgnoreCase(args[2])) {
                tracker.resetTotal();
                info("§aTotale azzerato. §7La sessione in corso e il magazzino restano.");
            } else {
                platform.showMessage(new Msg().suggest(Msg.PREFIX + "§eSicuro? Scrivi §f/flipscout totale reset conferma",
                    "/flipscout totale reset conferma", "§cCancella totale, giorni, sessioni passate e lista dei flip"));
            }
            return;
        }
        if (!action.isEmpty()) {
            throw new IllegalArgumentException("Uso: /flipscout totale [reset]");
        }
        info("§fTotale §8(dal " + date(tracker.since()) + ")");
        details(tracker.total());
        List<Map.Entry<String, ProfitTracker.Stats>> days =
            new ArrayList<Map.Entry<String, ProfitTracker.Stats>>(tracker.days().entrySet());
        if (!days.isEmpty()) {
            platform.showMessage(Msg.plain("§7  Ultimi giorni:"));
            Collections.reverse(days);
            for (int i = 0; i < Math.min(7, days.size()); i++) {
                String day = days.get(i).getKey();
                platform.showMessage(Msg.plain("§7    " + day.substring(8, 10) + "/" + day.substring(5, 7) + ": "
                    + stats(days.get(i).getValue())));
            }
        }
    }

    private void flips() {
        List<ProfitTracker.Flip> flips = tracker.flips();
        if (flips.isEmpty()) {
            info("§7Ancora nessun flip chiuso: si chiude quando vendi qualcosa che il mod ti ha visto comprare.");
            return;
        }
        info("§fUltimi flip §8(il più recente in alto, passa il mouse per i dettagli):");
        for (int i = 0; i < Math.min(10, flips.size()); i++) {
            ProfitTracker.Flip f = flips.get(i);
            platform.showMessage(new Msg().add("§7  " + f.item + (f.amount > 1 ? " x" + f.amount : "") + " §8"
                + Coins.compact(f.cost) + " → " + Coins.compact(f.revenue) + " " + ProfitTracker.signed(f.profit),
                "§7Comprato: §f" + dateTime(f.boughtAt) + "\n§7Venduto: §f" + dateTime(f.soldAt) + "\n§7Tenuto: §f"
                    + Text.duration(f.soldAt - f.boughtAt) + "\n§7Incasso dopo le tasse: §f" + Coins.full(f.revenue)));
        }
    }

    private void inventory(String[] args) {
        if (args.length > 1 && ("svuota".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1]))) {
            info("§7Magazzino svuotato: " + tracker.clearLots() + " acquisti dimenticati.");
            return;
        }
        List<ProfitTracker.Lot> lots = tracker.lots();
        List<ProfitTracker.Unclaimed> unclaimed = tracker.unclaimed();
        if (lots.isEmpty() && unclaimed.isEmpty()) {
            info("§7Il magazzino è vuoto: nessun acquisto da rivendere.");
            return;
        }
        long now = clock.getAsLong();
        if (!lots.isEmpty()) {
            info("§fComprati e non ancora venduti:");
            for (int i = 0; i < Math.min(15, lots.size()); i++) {
                ProfitTracker.Lot lot = lots.get(i);
                platform.showMessage(Msg.plain("§7  " + lot.item + (lot.amount > 1 ? " x" + lot.amount : "") + " §8per §6"
                    + Coins.compact(lot.unitCost * lot.amount) + " §8(" + Text.duration(now - lot.time) + " fa)"));
            }
            if (lots.size() > 15) {
                platform.showMessage(Msg.plain("§8  ... e altri " + (lots.size() - 15)));
            }
            platform.showMessage(new Msg().suggest("§7  §c[SVUOTA]", "/flipscout magazzino svuota",
                "§7Dimentica questi acquisti (per esempio se li hai usati invece di rivenderli)"));
        }
        if (!unclaimed.isEmpty()) {
            info("§fVenduti all'asta, coins ancora da ritirare:");
            for (ProfitTracker.Unclaimed u : unclaimed) {
                platform.showMessage(Msg.plain("§7  " + u.item + " §8a §6" + Coins.compact(u.price) + " §8(" + u.buyer + ")"));
            }
        }
    }

    private void details(ProfitTracker.Stats s) {
        line("Profitto", ProfitTracker.signed(s.profit) + " §7in " + s.flips + " flip");
        if (s.bestItem != null) {
            line("Miglior flip", s.bestItem + " " + ProfitTracker.signed(s.bestProfit));
        }
        line("Acquisti", s.bought + " §8per §6" + Coins.compact(s.spent));
        line("Vendite", s.sold + " §8per §6" + Coins.compact(s.earned) + " §8(dopo le tasse)");
        if (s.unmatched > 0) {
            line("Senza acquisto", s.unmatched + " vendite §8(non contate nel profitto)");
        }
    }

    private static String stats(ProfitTracker.Stats s) {
        return ProfitTracker.signed(s.profit) + " §7in " + s.flips + " flip"
            + (s.unmatched > 0 ? " §8(+" + s.unmatched + " vendite senza acquisto)" : "");
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
