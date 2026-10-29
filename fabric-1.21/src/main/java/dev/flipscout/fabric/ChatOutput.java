package dev.flipscout.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Adds a message to the chat; this is the part of the API that differs between 1.21 and 26.x. */
final class ChatOutput {
    private ChatOutput() {
    }

    static void show(Minecraft client, Component message) {
        client.gui.getChat().addMessage(message);
    }
}
