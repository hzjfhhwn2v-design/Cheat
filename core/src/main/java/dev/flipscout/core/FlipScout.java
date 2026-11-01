package dev.flipscout.core;

import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.http.SslSupport;
import dev.flipscout.core.http.UrlFetcher;
import dev.flipscout.core.profit.ProfitTracker;

import java.io.File;
import java.util.List;
import java.util.function.LongSupplier;

/** Entry point used by the Forge and Fabric mods. */
public final class FlipScout {
    public static final String VERSION = "1.2.0";
    public static final String MOD_ID = "flipscout";

    private final Config config = new Config();
    private final Platform platform;
    private final Engine engine;
    private final ProfitTracker tracker;
    private final Discord discord;
    private final Commands commands;
    private final ConfigEditor editor;
    private boolean shutdownHookAdded;

    /** @param configDir the game's config folder; data goes into its flipscout subfolder */
    public FlipScout(Platform platform, File configDir) {
        this(platform, configDir, new UrlFetcher("FlipScout/" + VERSION, SslSupport.createSocketFactory()),
            System::currentTimeMillis);
    }

    public FlipScout(Platform platform, File configDir, HttpFetcher http, LongSupplier clock) {
        File dataDir = new File(configDir, MOD_ID);
        File configFile = new File(dataDir, "flipscout.properties");
        config.load(configFile);
        Log.debugEnabled = config.debug;
        this.platform = platform;
        engine = new Engine(config, platform, http, dataDir, clock);
        tracker = new ProfitTracker(config, profitsFile(dataDir), clock, platform, engine::runAsync);
        tracker.load();
        discord = new Discord(config, http, platform, clock, engine::items, engine::notifiedPurchase);
        tracker.setListener(discord::onTrade);
        commands = new Commands(config, configFile, engine, tracker, discord, platform, clock);
        editor = new ConfigEditor(config, configFile);
    }

    /** profits.json. Versions up to 1.2.0 called it guadagni.json: that file is renamed once. */
    static File profitsFile(File dataDir) {
        File file = new File(dataDir, "profits.json");
        File old = new File(dataDir, "guadagni.json");
        if (!file.exists() && old.isFile() && !old.renameTo(file)) {
            Log.warn("Could not rename " + old + " to " + file.getName() + ", using the old name");
            return old;
        }
        return file;
    }

    /** For the settings screen. */
    public ConfigEditor editor() {
        return editor;
    }

    public synchronized void start() {
        engine.start();
        if (!shutdownHookAdded) {
            shutdownHookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "FlipScout-shutdown"));
        }
    }

    public void stop() {
        engine.stop();
        tracker.stop();
        discord.stop();
    }

    /** A chat message received from the server (not the mod's own), as plain text. */
    public void onChat(String message) {
        if (config.trackerEnabled && (!config.onlyOnHypixel || platform.isOnHypixel())) {
            tracker.onChat(message);
        }
    }

    /** Handles /flipscout with the given arguments. */
    public void command(String[] args) {
        commands.execute(args);
    }

    /** Tab completion for /flipscout; the last argument is the one being typed (may be empty). */
    public List<String> complete(String[] args) {
        return commands.complete(args);
    }

    /** Opens the most recent notification (for a key binding). */
    public void openLast() {
        commands.open();
    }

    public Config config() {
        return config;
    }

    Engine engine() {
        return engine;
    }

    ProfitTracker tracker() {
        return tracker;
    }
}
