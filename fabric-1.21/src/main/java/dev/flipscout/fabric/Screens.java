package dev.flipscout.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Opening a screen: Minecraft.setScreen on 1.21. */
final class Screens {
    private Screens() {
    }

    static void open(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }
}
