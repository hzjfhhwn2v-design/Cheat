package dev.flipscout.core;

import dev.flipscout.core.http.HttpFetcher;
import dev.flipscout.core.http.SslSupport;
import dev.flipscout.core.http.UrlFetcher;

import java.io.File;
import java.util.List;
import java.util.function.LongSupplier;

/** Entry point used by the Forge and Fabric mods. */
public final class FlipScout {
    public static final String VERSION = "1.0.0";
    public static final String MOD_ID = "flipscout";

    private final Config config = new Config();
    private final Engine engine;
    private final Commands commands;
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
        engine = new Engine(config, platform, http, dataDir, clock);
        commands = new Commands(config, configFile, engine, platform, clock);
    }

    public synchronized void start() {
        engine.start();
        if (!shutdownHookAdded) {
            shutdownHookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "FlipScout-shutdown"));
        }
    }

    public void stop() {
        engine.stop();
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
}
