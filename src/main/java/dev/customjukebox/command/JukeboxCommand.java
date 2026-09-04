package dev.customjukebox.command;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.sign.SignManager.BlockKey;
import dev.customjukebox.song.SongLibrary;
import dev.customjukebox.song.SongMetadata;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JukeboxCommand implements CommandExecutor, TabCompleter {
    private final CustomJukeboxPlugin plugin;

    public JukeboxCommand(CustomJukeboxPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return help(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "play" -> play(sender, args);
            case "stop" -> stop(sender);
            case "disc", "give-disc" -> disc(sender, args);
            case "reload" -> reload(sender);
            case "list-signs" -> listSigns(sender);
            default -> help(sender);
        };
    }

    private boolean play(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use personal playback.");
            return true;
        }
        if (!player.hasPermission("customjukebox.play")) return denied(player);
        if (args.length == 1) {
            plugin.guis().openPersonal(player);
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        SongMetadata song = plugin.library().find(query).orElse(null);
        if (song == null) {
            player.sendMessage("No unique song matched '" + query + "'. Use its relative path if titles collide.");
        } else if (plugin.playback().playPersonal(player, song, plugin.settings().personalVolume())) {
            player.sendMessage("Now playing: " + song.displayTitle());
        } else {
            player.sendMessage("Could not start playback (the source limit may be reached).");
        }
        return true;
    }

    private boolean stop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players have personal playback.");
            return true;
        }
        if (!player.hasPermission("customjukebox.play")) return denied(player);
        plugin.playback().stopPersonal(player.getUniqueId());
        player.sendMessage("Personal playback stopped.");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("customjukebox.admin")) return denied(sender);
        plugin.reloadSettings();
        SongLibrary.ScanResult result = plugin.library().scan();
        sender.sendMessage("CustomJukebox indexed " + result.songs() + " songs (" + result.invalid() + " invalid)." );
        return true;
    }

    private boolean disc(CommandSender sender, String[] args) {
        if (!sender.hasPermission("customjukebox.disc.create")) return denied(sender);
        if (args.length < 3) {
            sender.sendMessage("Usage: /jukebox disc <player> <song>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("Player not found: " + args[1]);
            return true;
        }

        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        SongMetadata song = plugin.library().find(query).orElse(null);
        if (song == null) {
            sender.sendMessage("No unique song matched '" + query + "'. Use its relative path if titles collide.");
            return true;
        }

        var leftovers = target.getInventory().addItem(plugin.discs().createDisc(song, 1));
        leftovers.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        target.sendMessage("You received a custom record for: " + song.displayTitle());
        if (sender != target) sender.sendMessage("Created a custom record for " + target.getName() + ".");
        return true;
    }

    private boolean listSigns(CommandSender sender) {
        if (!sender.hasPermission("customjukebox.admin")) return denied(sender);
        List<BlockKey> signs = plugin.signs().validateAndList();
        sender.sendMessage("Registered jukebox signs: " + signs.size());
        for (BlockKey sign : signs) {
            sender.sendMessage("- " + sign.world() + " @ " + sign.x() + ", " + sign.y() + ", " + sign.z());
        }
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage("/jukebox play [song], /jukebox stop");
        if (sender.hasPermission("customjukebox.disc.create")) {
            sender.sendMessage("/jukebox disc <player> <song>");
        }
        if (sender.hasPermission("customjukebox.admin")) {
            sender.sendMessage("/jukebox reload, /jukebox list-signs");
        }
        return true;
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage("You do not have permission to do that.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("play", "stop"));
            if (sender.hasPermission("customjukebox.disc.create")) options.add("disc");
            if (sender.hasPermission("customjukebox.admin")) options.addAll(List.of("reload", "list-signs"));
            return prefix(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play") && sender.hasPermission("customjukebox.play")) {
            return prefix(plugin.library().all().stream().map(SongMetadata::id).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("disc")
                && sender.hasPermission("customjukebox.disc.create")) {
            return prefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("disc")
                && sender.hasPermission("customjukebox.disc.create")) {
            String query = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            return prefix(plugin.library().all().stream().map(SongMetadata::id).toList(), query);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).limit(100).toList();
    }
}
