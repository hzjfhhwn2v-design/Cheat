package dev.flipscout.core;

import java.util.ArrayList;
import java.util.List;

public final class FakePlatform implements Platform {
    public final List<Msg> messages = new ArrayList<Msg>();
    public final List<String> commands = new ArrayList<String>();
    public int sounds;
    public int configScreens;
    public volatile boolean onHypixel = true;
    public volatile boolean inSkyblock = true;
    public volatile String uuid;
    public volatile String name = "Tester";

    @Override
    public synchronized void showMessage(Msg message) {
        messages.add(message);
    }

    @Override
    public synchronized void playSound(boolean auction) {
        sounds++;
    }

    @Override
    public synchronized void sendCommand(String command) {
        commands.add(command);
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
        return uuid;
    }

    @Override
    public String playerName() {
        return name;
    }

    @Override
    public synchronized void openConfigScreen() {
        configScreens++;
    }

    public synchronized List<Msg> withCommand(String command) {
        List<Msg> out = new ArrayList<Msg>();
        for (Msg message : messages) {
            for (Msg.Part part : message.parts()) {
                if (command.equals(part.command)) {
                    out.add(message);
                    break;
                }
            }
        }
        return out;
    }

    public synchronized String allText() {
        StringBuilder sb = new StringBuilder();
        for (Msg message : messages) {
            sb.append(message.toPlainString()).append('\n');
        }
        return sb.toString();
    }
}
