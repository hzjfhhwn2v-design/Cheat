package dev.flipscout.forge;

import dev.flipscout.core.FlipScout;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.Collections;
import java.util.List;

final class FlipScoutCommand extends CommandBase {
    private final FlipScout flipScout;

    FlipScoutCommand(FlipScout flipScout) {
        this.flipScout = flipScout;
    }

    @Override
    public String getCommandName() {
        return "flipscout";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("fsc");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/flipscout help";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        flipScout.command(args);
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        return flipScout.complete(args);
    }
}
