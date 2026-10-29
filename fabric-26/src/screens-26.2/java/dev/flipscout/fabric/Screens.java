package dev.flipscout.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Opening a screen: from 26.2 Minecraft.setScreen is called setScreenAndShow. */
final class Screens {
    private Screens() {
    }

    static void open(Screen screen) {
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
