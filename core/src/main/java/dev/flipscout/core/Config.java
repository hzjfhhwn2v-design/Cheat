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

/** All settings, stored in config/flipscout/flipscout.properties. */
public final class Config {
    // General
    public volatile boolean enabled = true;
    public volatile boolean onlyOnHypixel = true;
    public volatile boolean onlyInSkyblock = true;
    public volatile boolean sound = true;
    public volatile long budget = 0;
    public volatile Set<String> blacklist = Collections.emptySet();
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

    // Sources
    // Profit tracker
    public volatile boolean trackerEnabled = true;
    public volatile boolean trackerMessages = true;

    public volatile boolean sourceNeu = true;
    public volatile boolean sourceCoflnet = true;
    public volatile String urlHypixel = "https://api.hypixel.net";
    public volatile String urlNeu = "https://moulberry.codes";
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
        new Section("Generale",
            new BoolSetting("enabled", "Mod attivo", "Attiva o disattiva tutto il mod (niente richieste quando è spento)") {
                boolean read(Config c) { return c.enabled; }
                void write(Config c, boolean v) { c.enabled = v; }
            },
            new BoolSetting("onlyOnHypixel", "Solo su Hypixel", "Scarica i dati solo quando sei connesso a Hypixel") {
                boolean read(Config c) { return c.onlyOnHypixel; }
                void write(Config c, boolean v) { c.onlyOnHypixel = v; }
            },
            new BoolSetting("onlyInSkyblock", "Notifiche solo in SkyBlock", "Mostra le notifiche solo quando sei in SkyBlock") {
                boolean read(Config c) { return c.onlyInSkyblock; }
                void write(Config c, boolean v) { c.onlyInSkyblock = v; }
            },
            new BoolSetting("sound", "Suono delle notifiche", "Suono quando arriva una notifica") {
                boolean read(Config c) { return c.sound; }
                void write(Config c, boolean v) { c.sound = v; }
            },
            new CoinsSetting("budget", "Budget massimo per flip", "Soldi massimi da spendere per un flip (0 = nessun limite)", 0) {
                long read(Config c) { return c.budget; }
                void write(Config c, long v) { c.budget = v; }
            },
            new ListSetting("blacklist", "Oggetti ignorati", "ID da ignorare, separati da virgola (es. HYPERION,ENCHANTMENT_SHARPNESS_6)") {
                Set<String> read(Config c) { return c.blacklist; }
                void write(Config c, Set<String> v) { c.blacklist = v; }
            },
            new BoolSetting("debug", "Log dettagliato (debug)", "Scrive più dettagli nel log del gioco") {
                boolean read(Config c) { return c.debug; }
                void write(Config c, boolean v) { c.debug = v; Log.debugEnabled = v; }
            }),
        new Section("Asta (BIN)",
            new BoolSetting("ah.enabled", "Notifiche delle aste", "Notifiche per le aste BIN sotto prezzo") {
                boolean read(Config c) { return c.ahEnabled; }
                void write(Config c, boolean v) { c.ahEnabled = v; }
            },
            new CoinsSetting("ah.minProfit", "Profitto minimo", "Profitto minimo dopo le tasse (es. 500k, 2m)", 0) {
                long read(Config c) { return c.ahMinProfit; }
                void write(Config c, long v) { c.ahMinProfit = v; }
            },
            new DecimalSetting("ah.minPercent", "Profitto minimo in %", "Profitto minimo in percentuale del prezzo", 0, 10_000) {
                double read(Config c) { return c.ahMinPercent; }
                void write(Config c, double v) { c.ahMinPercent = v; }
            },
            new DecimalSetting("ah.minVolume", "Vendite minime al giorno", "Vendite al giorno minime dell'oggetto (se il dato c'è)", 0, 1_000_000) {
                double read(Config c) { return c.ahMinVolume; }
                void write(Config c, double v) { c.ahMinVolume = v; }
            },
            new BoolSetting("ah.requireVolume", "Solo oggetti con vendite note", "Scarta gli oggetti di cui non si conoscono le vendite") {
                boolean read(Config c) { return c.ahRequireVolume; }
                void write(Config c, boolean v) { c.ahRequireVolume = v; }
            },
            new BoolSetting("ah.upgrades", "Conta gli upgrade", "Conta il valore degli upgrade: recombobulator, potato book, incantesimi,"
                + " master star, gemme, pergamene, oggetti dei pet...") {
                boolean read(Config c) { return c.ahUpgrades; }
                void write(Config c, boolean v) { c.ahUpgrades = v; }
            },
            new IntSetting("ah.upgradePercent", "Quota degli upgrade (%)", "Quanta parte del valore degli upgrade contare, in %", 0, 100) {
                int read(Config c) { return c.ahUpgradePercent; }
                void write(Config c, int v) { c.ahUpgradePercent = v; }
            },
            new ChoiceSetting("ah.strategy", "Strategia di valutazione", "Come stimare il valore: conservative = il minore tra LBIN e vendite recenti,"
                + " lbin = prezzo BIN più basso, sales = mediana delle vendite", "conservative", "lbin", "sales") {
                String read(Config c) { return c.ahStrategy; }
                void write(Config c, String v) { c.ahStrategy = v; }
            },
            new IntSetting("ah.minSalesSamples", "Vendite per usare la mediana", "Vendite registrate necessarie per usare la mediana delle vendite", 1, 100) {
                int read(Config c) { return c.ahMinSalesSamples; }
                void write(Config c, int v) { c.ahMinSalesSamples = v; }
            },
            new IntSetting("ah.fullScanMinutes", "Scansione completa ogni (min)", "Ogni quanti minuti riscaricare tutte le aste (pesante: decine di MB ogni volta)", 5, 240) {
                int read(Config c) { return c.ahFullScanMinutes; }
                void write(Config c, int v) { c.ahFullScanMinutes = v; }
            },
            new IntSetting("ah.pollPages", "Pagine controllate ogni minuto", "Pagine di aste nuove controllate ogni minuto", 1, 10) {
                int read(Config c) { return c.ahPollPages; }
                void write(Config c, int v) { c.ahPollPages = v; }
            },
            new DecimalSetting("ah.fee.under10m", "Tassa asta sotto 10M (%)", "Tassa di creazione asta in % sotto i 10M", 0, 100) {
                double read(Config c) { return c.ahFeeUnder10m; }
                void write(Config c, double v) { c.ahFeeUnder10m = v; }
            },
            new DecimalSetting("ah.fee.10mTo100m", "Tassa asta 10M-100M (%)", "Tassa di creazione asta in % tra 10M e 100M", 0, 100) {
                double read(Config c) { return c.ahFee10mTo100m; }
                void write(Config c, double v) { c.ahFee10mTo100m = v; }
            },
            new DecimalSetting("ah.fee.over100m", "Tassa asta sopra 100M (%)", "Tassa di creazione asta in % sopra i 100M", 0, 100) {
                double read(Config c) { return c.ahFeeOver100m; }
                void write(Config c, double v) { c.ahFeeOver100m = v; }
            },
            new DecimalSetting("ah.fee.claim", "Tassa sul ritiro (%)", "Tassa in % sul ritiro dei coins (vendite da 1M in su)", 0, 100) {
                double read(Config c) { return c.ahFeeClaim; }
                void write(Config c, double v) { c.ahFeeClaim = v; }
            },
            new IntSetting("ah.salesWindowDays", "Giorni di storico vendite", "Giorni di vendite da conservare per le statistiche", 1, 14) {
                int read(Config c) { return c.salesWindowDays; }
                void write(Config c, int v) { c.salesWindowDays = v; }
            }),
        new Section("Bazaar",
            new BoolSetting("bz.enabled", "Notifiche del bazaar", "Notifiche del bazaar") {
                boolean read(Config c) { return c.bzEnabled; }
                void write(Config c, boolean v) { c.bzEnabled = v; }
            },
            new IntSetting("bz.intervalSeconds", "Controllo bazaar ogni (s)", "Ogni quanti secondi controllare il bazaar", 10, 600) {
                int read(Config c) { return c.bzIntervalSeconds; }
                void write(Config c, int v) { c.bzIntervalSeconds = v; }
            },
            new DecimalSetting("bz.tax", "Tassa del bazaar (%)", "Tassa del bazaar sulle vendite in %", 0, 100) {
                double read(Config c) { return c.bzTax; }
                void write(Config c, double v) { c.bzTax = v; }
            },
            new IntSetting("bz.maxPerCycle", "Notifiche massime per controllo", "Notifiche bazaar massime per ogni aggiornamento", 1, 20) {
                int read(Config c) { return c.bzMaxPerCycle; }
                void write(Config c, int v) { c.bzMaxPerCycle = v; }
            },
            new BoolSetting("bz.flip.enabled", "Flip con ordini", "Flip con buy order + sell offer") {
                boolean read(Config c) { return c.bzFlipEnabled; }
                void write(Config c, boolean v) { c.bzFlipEnabled = v; }
            },
            new DecimalSetting("bz.flip.minMarginPercent", "Flip: margine minimo (%)", "Margine minimo in % dopo la tassa", 0, 10_000) {
                double read(Config c) { return c.bzFlipMinMarginPercent; }
                void write(Config c, double v) { c.bzFlipMinMarginPercent = v; }
            },
            new DecimalSetting("bz.flip.minHourlyVolume", "Flip: scambi minimi all'ora", "Pezzi scambiati all'ora minimi (lato più lento)", 0, 1e9) {
                double read(Config c) { return c.bzFlipMinHourlyVolume; }
                void write(Config c, double v) { c.bzFlipMinHourlyVolume = v; }
            },
            new CoinsSetting("bz.flip.minProfitPerHour", "Flip: profitto minimo all'ora", "Profitto potenziale minimo all'ora", 0) {
                long read(Config c) { return c.bzFlipMinProfitPerHour; }
                void write(Config c, long v) { c.bzFlipMinProfitPerHour = v; }
            },
            new IntSetting("bz.flip.cooldownMinutes", "Flip: pausa tra avvisi (min)", "Minuti prima di riavvisare per lo stesso prodotto", 1, 1440) {
                int read(Config c) { return c.bzFlipCooldownMinutes; }
                void write(Config c, int v) { c.bzFlipCooldownMinutes = v; }
            },
            new BoolSetting("bz.crash.enabled", "Avvisi di crollo", "Avvisa quando il prezzo d'acquisto crolla rispetto all'ultima ora") {
                boolean read(Config c) { return c.bzCrashEnabled; }
                void write(Config c, boolean v) { c.bzCrashEnabled = v; }
            },
            new DecimalSetting("bz.crash.dropPercent", "Crollo: calo minimo (%)", "Calo minimo in % rispetto alla mediana recente", 1, 99) {
                double read(Config c) { return c.bzCrashDropPercent; }
                void write(Config c, double v) { c.bzCrashDropPercent = v; }
            },
            new DecimalSetting("bz.crash.minHourlyVolume", "Crollo: acquisti minimi all'ora", "Acquisti all'ora minimi per considerare un crollo", 0, 1e9) {
                double read(Config c) { return c.bzCrashMinHourlyVolume; }
                void write(Config c, double v) { c.bzCrashMinHourlyVolume = v; }
            },
            new CoinsSetting("bz.crash.minProfit", "Crollo: profitto minimo", "Profitto minimo stimato rivendendo al prezzo normale", 0) {
                long read(Config c) { return c.bzCrashMinProfit; }
                void write(Config c, long v) { c.bzCrashMinProfit = v; }
            },
            new IntSetting("bz.crash.cooldownMinutes", "Crollo: pausa tra avvisi (min)", "Minuti prima di riavvisare per lo stesso crollo", 1, 1440) {
                int read(Config c) { return c.bzCrashCooldownMinutes; }
                void write(Config c, int v) { c.bzCrashCooldownMinutes = v; }
            },
            new BoolSetting("bz.npc.enabled", "Flip bazaar → NPC", "Avvisa quando comprare dal bazaar e vendere all'NPC conviene") {
                boolean read(Config c) { return c.bzNpcEnabled; }
                void write(Config c, boolean v) { c.bzNpcEnabled = v; }
            },
            new CoinsSetting("bz.npc.minProfit", "NPC: profitto minimo", "Profitto minimo per un flip bazaar -> NPC", 0) {
                long read(Config c) { return c.bzNpcMinProfit; }
                void write(Config c, long v) { c.bzNpcMinProfit = v; }
            },
            new IntSetting("bz.npc.cooldownMinutes", "NPC: pausa tra avvisi (min)", "Minuti prima di riavvisare per lo stesso flip NPC", 1, 1440) {
                int read(Config c) { return c.bzNpcCooldownMinutes; }
                void write(Config c, int v) { c.bzNpcCooldownMinutes = v; }
            }),
        new Section("Guadagni",
            new BoolSetting("tracker.enabled", "Conta i guadagni", "Riconosce in chat acquisti e vendite (aste e bazaar) e calcola i guadagni") {
                boolean read(Config c) { return c.trackerEnabled; }
                void write(Config c, boolean v) { c.trackerEnabled = v; }
            },
            new BoolSetting("tracker.messages", "Messaggi del tracker in chat", "Scrive in chat quando registra un acquisto all'asta o chiude un flip") {
                boolean read(Config c) { return c.trackerMessages; }
                void write(Config c, boolean v) { c.trackerMessages = v; }
            }),
        new Section("Fonti dati",
            new BoolSetting("source.neu", "Usa i prezzi di NEU", "Usa i prezzi di NotEnoughUpdates (moulberry.codes)") {
                boolean read(Config c) { return c.sourceNeu; }
                void write(Config c, boolean v) { c.sourceNeu = v; }
            },
            new BoolSetting("source.coflnet", "Usa i prezzi di Coflnet", "Usa i prezzi di Coflnet (sky.coflnet.com) per confermare i flip") {
                boolean read(Config c) { return c.sourceCoflnet; }
                void write(Config c, boolean v) { c.sourceCoflnet = v; }
            },
            new UrlSetting("url.hypixel", "Indirizzo API di Hypixel", "Indirizzo dell'API di Hypixel") {
                String read(Config c) { return c.urlHypixel; }
                void write(Config c, String v) { c.urlHypixel = v; }
            },
            new UrlSetting("url.neu", "Indirizzo dei dati NEU", "Indirizzo dei dati NEU") {
                String read(Config c) { return c.urlNeu; }
                void write(Config c, String v) { c.urlNeu = v; }
            },
            new UrlSetting("url.coflnet", "Indirizzo API di Coflnet", "Indirizzo dell'API di Coflnet") {
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
        Set<String> list = blacklist;
        if (list.isEmpty()) {
            return false;
        }
        for (String id : ids) {
            if (id != null && list.contains(id.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
                Log.warn("Impossibile leggere " + file, e);
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
                    Log.warn("Valore ignorato per " + setting.key + ": " + e.getMessage());
                }
            }
        }
        save(file);
    }

    public void save(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.warn("Impossibile creare la cartella " + parent);
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            writer.write("# FlipScout - impostazioni\n");
            writer.write("# Modifica i valori e scrivi /flipscout reload, oppure usa /flipscout set <chiave> <valore>.\n");
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
            Log.warn("Impossibile salvare " + file, e);
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
        if (v.equals("true") || v.equals("on") || v.equals("si") || v.equals("sì") || v.equals("yes") || v.equals("1")) {
            return true;
        }
        if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException("usa on oppure off");
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
            return "importo, es. 500k o 2m" + (min > 0 ? ", almeno " + Coins.compact(min) : "");
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
                throw new IllegalArgumentException("serve un importo, es. 500k o 2m");
            }
            if (parsed < min) {
                throw new IllegalArgumentException("deve essere almeno " + Coins.compact(min));
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
                throw new IllegalArgumentException("serve un numero, es. 5 o 2.5");
            }
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("deve essere tra " + Coins.decimal(min, 3) + " e " + Coins.decimal(max, 3));
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
                throw new IllegalArgumentException("serve un numero intero");
            }
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("deve essere tra " + min + " e " + max);
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
                throw new IllegalArgumentException("valori possibili: " + choices);
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
            return "indirizzo che inizia con https://";
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
                throw new IllegalArgumentException("deve iniziare con https://");
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
            return "ID separati da virgola";
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
