package dev.flipscout.forge;

import dev.flipscout.core.Msg;
import dev.flipscout.core.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.Locale;

final class ForgePlatform implements Platform {
    private volatile boolean onHypixel;
    private volatile boolean inSkyblock;
    private volatile String playerUuid;

    /** Called on the client thread about once a second. */
    void update() {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc.getCurrentServerData();
        boolean hypixel = mc.theWorld != null && !mc.isSingleplayer() && server != null && server.serverIP != null
            && server.serverIP.toLowerCase(Locale.ROOT).contains("hypixel");
        onHypixel = hypixel;
        inSkyblock = hypixel && sidebarSaysSkyblock(mc);
        playerUuid = mc.thePlayer != null ? mc.thePlayer.getUniqueID().toString().replace("-", "") : null;
    }

    private static boolean sidebarSaysSkyblock(Minecraft mc) {
        ScoreObjective objective = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return false;
        }
        String title = EnumChatFormatting.getTextWithoutFormattingCodes(objective.getDisplayName());
        return title != null && title.toUpperCase(Locale.ROOT).contains("SKYBLOCK");
    }

    @Override
    public void showMessage(Msg message) {
        final IChatComponent component = toComponent(message);
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
                if (player != null) {
                    player.addChatMessage(component);
                }
            }
        });
    }

    static IChatComponent toComponent(Msg message) {
        ChatComponentText root = new ChatComponentText("");
        for (Msg.Part part : message.parts()) {
            ChatComponentText text = new ChatComponentText(part.text);
            ChatStyle style = new ChatStyle();
            if (part.command != null) {
                ClickEvent.Action action = part.click == Msg.Click.SUGGEST
                    ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND;
                style.setChatClickEvent(new ClickEvent(action, part.command));
            }
            if (part.hover != null) {
                style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(part.hover)));
            }
            text.setChatStyle(style);
            root.appendSibling(text);
        }
        return root;
    }

    @Override
    public void playSound(final boolean auction) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.thePlayer != null) {
                    mc.getSoundHandler().playSound(
                        PositionedSoundRecord.create(new ResourceLocation("note.pling"), auction ? 2.0F : 1.4F));
                }
            }
        });
    }

    @Override
    public void sendCommand(final String command) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
                if (player == null) {
                    return;
                }
                String line = "/" + command;
                if (ClientCommandHandler.instance.executeCommand(player, line) == 0) {
                    player.sendChatMessage(line);
                }
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
