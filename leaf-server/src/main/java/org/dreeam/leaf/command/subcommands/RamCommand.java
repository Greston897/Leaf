package org.dreeam.leaf.command.subcommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.dreeam.leaf.command.LeafCommand;
import org.dreeam.leaf.command.PermissionedLeafSubcommand;
import org.dreeam.leaf.perf.ram.LeafRam;
import org.dreeam.leaf.perf.ram.RamJanitor;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Leaf - {@code /leaf ram}: what the server holds, what the janitor did, and a way to run it now.
 * <p>
 * The command exists because a memory system that cannot be inspected is a memory system nobody trusts:
 * an administrator should be able to see the watermarks in effect, the caches with their sizes and hit
 * counters, what the last pass released, and force a pass (or a dry run of one) without restarting.
 */
@DefaultQualifier(NonNull.class)
public final class RamCommand extends PermissionedLeafSubcommand {

    public static final String LITERAL_ARGUMENT = "ram";
    public static final String PERM = LeafCommand.BASE_PERM + "." + LITERAL_ARGUMENT;

    private static final Component HEADER = Component.text("━━━━━━━━━━━━━ ").color(NamedTextColor.GOLD)
        .append(Component.text("RAM janitor").color(NamedTextColor.YELLOW))
        .append(Component.text(" ━━━━━━━━━━━━━").color(NamedTextColor.GOLD));

    public RamCommand() {
        super(PERM, PermissionDefault.OP);
    }

    @Override
    public boolean execute(final CommandSender sender, final String subCommand, final String[] args) {
        final String action = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);

        switch (action) {
            case "trim", "clean" -> {
                final RamJanitor janitor = LeafRam.janitor();
                final Runtime runtime = Runtime.getRuntime();
                final RamJanitor.Pass pass = janitor.force(runtime.totalMemory() - runtime.freeMemory(),
                    runtime.maxMemory(), false);
                sender.sendMessage(Component.text(pass.summary(), NamedTextColor.GREEN));
                return true;
            }
            case "dry-run", "dryrun" -> {
                final RamJanitor janitor = LeafRam.janitor();
                final Runtime runtime = Runtime.getRuntime();
                final RamJanitor.Pass pass = janitor.force(runtime.totalMemory() - runtime.freeMemory(),
                    runtime.maxMemory(), true);
                sender.sendMessage(Component.text("dry run: " + pass.summary(), NamedTextColor.GRAY));
                return true;
            }
            case "players" -> {
                sender.sendMessage(Component.text(LeafRam.cleaner().describe(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("known player ids: "
                    + LeafRam.cleaner().knownPlayerIds().size(), NamedTextColor.GRAY));
                return true;
            }
            default -> {
                sender.sendMessage(HEADER);
                for (final String line : LeafRam.describe()) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
                return true;
            }
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String subCommand, final String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "trim", "dry-run", "players");
        }
        return Collections.emptyList();
    }
}
