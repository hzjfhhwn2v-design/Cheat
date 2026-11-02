package dev.flipscout.fabric;

import dev.flipscout.core.FlipScout;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** The item's value and a lowball price under SkyBlock items' tooltips. */
final class Tooltips {
    private Tooltips() {
    }

    /** SkyBlock keeps its item data (id, upgrades, petInfo...) in the custom_data component. */
    static List<String> lines(FlipScout flipScout, ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(data.copyTag(), new DataOutputStream(bytes));
            return flipScout.tooltip(bytes.toByteArray(), stack.getHoverName().getString(), stack.getCount());
        } catch (IOException | RuntimeException e) {
            // A tooltip must never break.
            return Collections.emptyList();
        }
    }
}
