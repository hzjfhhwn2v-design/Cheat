package dev.flipscout.forge;

import dev.flipscout.core.FlipScout;
import dev.flipscout.core.Log;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

@Mod(modid = FlipScout.MOD_ID, name = "FlipScout", version = FlipScout.VERSION, clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]")
public class FlipScoutForge {
    private static final Logger LOGGER = LogManager.getLogger("FlipScout");

    private final ForgePlatform platform = new ForgePlatform();
    private FlipScout flipScout;
    private KeyBinding openKey;
    private int ticks;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Log.setSink(new Log.Sink() {
            @Override
            public void log(Log.Level level, String message, Throwable error) {
                if (level == Log.Level.ERROR) {
                    LOGGER.error(message, error);
                } else if (level == Log.Level.WARN) {
                    LOGGER.warn(message, error);
                } else {
                    LOGGER.info(message, error);
                }
            }
        });
        flipScout = new FlipScout(platform, event.getModConfigurationDirectory());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new FlipScoutCommand(flipScout));
        openKey = new KeyBinding("Apri l'ultima notifica", Keyboard.KEY_NONE, "FlipScout");
        ClientRegistry.registerKeyBinding(openKey);
        MinecraftForge.EVENT_BUS.register(this);
        flipScout.start();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (openKey.isPressed()) {
            flipScout.openLast();
        }
        if (++ticks % 20 == 0) {
            platform.update();
        }
    }
}
