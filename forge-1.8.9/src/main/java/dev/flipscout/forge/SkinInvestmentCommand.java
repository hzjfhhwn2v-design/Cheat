package dev.flipscout.forge;

import dev.flipscout.core.FlipScout;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.List;

/** /skininvestment: a shortcut for /flipscout skins. */
final class SkinInvestmentCommand extends CommandBase {
    private final FlipScout flipScout;

    SkinInvestmentCommand(FlipScout flipScout) {
        this.flipScout = flipScout;
    }

    @Override
    public String getCommandName() {
        return "skininvestment";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/skininvestment [n] | detail <skin> | debug on|off";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        flipScout.skinCommand(args);
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
        return flipScout.completeSkinCommand(args);
    }
}
