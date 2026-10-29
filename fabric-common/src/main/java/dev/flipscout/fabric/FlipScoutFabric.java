package dev.flipscout.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.flipscout.core.FlipScout;
import dev.flipscout.core.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public final class FlipScoutFabric implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("FlipScout");

    private final FabricPlatform platform = new FabricPlatform();
    private FlipScout flipScout;
    private int ticks;

    @Override
    public void onInitializeClient() {
        Log.setSink((level, message, error) -> {
            switch (level) {
                case ERROR -> LOGGER.error(message, error);
                case WARN -> LOGGER.warn(message, error);
                default -> LOGGER.info(message, error);
            }
        });
        flipScout = new FlipScout(platform, FabricLoader.getInstance().getConfigDir().toFile());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks % 20 == 0) {
                platform.update(client);
            }
        });
        // Server messages (purchases, sales); the action bar ("overlay") never has any.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                flipScout.onChat(message.getString());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flipScout.stop());
        flipScout.start();
    }

    // Plain Brigadier builders: Fabric's helper class for them was renamed in 26.x.
    private void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("flipscout")
                .executes(context -> run(""))
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("args",
                        StringArgumentType.greedyString())
                    .suggests(this::suggest)
                    .executes(context -> run(StringArgumentType.getString(context, "args")))));
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fsc")
            .executes(context -> run(""))
            .redirect(root));
    }

    private int run(String args) {
        String trimmed = args.trim();
        flipScout.command(trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+"));
        return 1;
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<FabricClientCommandSource> context,
                                                   SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        SuggestionsBuilder lastWord = builder.createOffset(builder.getStart() + typed.lastIndexOf(' ') + 1);
        for (String suggestion : flipScout.complete(typed.split(" ", -1))) {
            lastWord.suggest(suggestion);
        }
        return lastWord.buildFuture();
    }
}
