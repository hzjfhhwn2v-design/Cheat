package dev.flipscout.fabric;

import dev.flipscout.core.Msg;
import dev.flipscout.core.Platform;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;

final class FabricPlatform implements Platform {
    private volatile boolean onHypixel;
    private volatile boolean inSkyblock;
    private volatile String playerUuid;

    /** Called on the client thread about once a second. */
    void update(Minecraft client) {
        ServerData server = client.getCurrentServer();
        boolean hypixel = client.level != null && server != null && server.ip != null
            && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
        onHypixel = hypixel;
        inSkyblock = hypixel && sidebarSaysSkyblock(client);
        playerUuid = client.player != null ? client.player.getUUID().toString().replace("-", "") : null;
    }

    private static boolean sidebarSaysSkyblock(Minecraft client) {
        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return false;
        }
        String title = ChatFormatting.stripFormatting(objective.getDisplayName().getString());
        return title != null && title.toUpperCase(Locale.ROOT).contains("SKYBLOCK");
    }

    @Override
    public void showMessage(Msg message) {
        Component component = toComponent(message);
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                ChatOutput.show(client, component);
            }
        });
    }

    static Component toComponent(Msg message) {
        MutableComponent root = Component.empty();
        for (Msg.Part part : message.parts()) {
            Style style = Style.EMPTY;
            if (part.command != null) {
                style = style.withClickEvent(part.click == Msg.Click.SUGGEST
                    ? new ClickEvent.SuggestCommand(part.command)
                    : new ClickEvent.RunCommand(part.command));
            }
            if (part.hover != null) {
                style = style.withHoverEvent(new HoverEvent.ShowText(Component.literal(part.hover)));
            }
            root.append(Component.literal(part.text).setStyle(style));
        }
        return root;
    }

    @Override
    public void playSound(boolean auction) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, auction ? 2.0F : 1.4F));
            }
        });
    }

    @Override
    public void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.connection.sendCommand(command);
            }
        });
    }

    @Override
    public boolean isOnHypixel() {
        return onHypixel;
    }

    @Override
    public boolean isInSkyblock() {
        return inSkyblock;
    }

    @Override
    public String playerUuid() {
        return playerUuid;
    }
}
