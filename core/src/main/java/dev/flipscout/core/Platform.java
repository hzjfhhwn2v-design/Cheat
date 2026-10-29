package dev.flipscout.core;

/**
 * What the core needs from the Minecraft side. Every method may be called from any thread:
 * implementations hop to the client thread themselves where needed.
 */
public interface Platform {
    void showMessage(Msg message);

    /** Plays the notification sound; {@code auction} picks the higher pitch used for auction flips. */
    void playSound(boolean auction);

    /** Sends a command to the server as if the player typed it, without the leading slash. */
    void sendCommand(String command);

    boolean isOnHypixel();

    boolean isInSkyblock();

    /** UUID of the local player, lowercase without dashes, or null when unknown. */
    String playerUuid();

    /**
     * Opens the settings screen. Called from a command, so the screen should open on the next
     * client tick: otherwise closing the chat would close it straight away.
     */
    void openConfigScreen();
}
