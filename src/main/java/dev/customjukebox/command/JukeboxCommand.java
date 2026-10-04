package dev.customjukebox.command;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.sign.SignManager.BlockKey;
import dev.customjukebox.song.SongLibrary;
import dev.customjukebox.song.SongMetadata;
import dev.customjukebox.sign.SignConfig;
import dev.customjukebox.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Sign;
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
            Text.error(sender, "Only players can use personal playback.");
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
            Text.error(player, "No unique song matched '" + query + "'. Use its path if titles collide.");
        } else if (plugin.playback().playPersonal(player, song)) {
            Text.info(player, "Now playing: ", song.displayTitle());
        } else {
            Text.error(player, "Could not start playback. The server's playback limit may be reached.");
        }
        return true;
    }

    private boolean stop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Text.error(sender, "Only players have personal playback.");
            return true;
        }
        if (!player.hasPermission("customjukebox.play")) return denied(player);
        if (!plugin.playback().isPersonalActive(player.getUniqueId())) {
            Text.info(player, "Nothing is playing.");
            return true;
        }
        plugin.playback().stopPersonal(player.getUniqueId());
        Text.info(player, "Playback stopped.");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("customjukebox.admin")) return denied(sender);
        plugin.reloadSettings();
        SongLibrary.ScanResult result = plugin.library().scan();
        if (result.invalid() == 0) Text.success(sender, "Indexed " + result.songs() + " songs.");
        else Text.success(sender, "Indexed " + result.songs() + " songs. Skipped " + result.invalid()
                + " invalid files; see the server log.");
        return true;
    }

    private boolean disc(CommandSender sender, String[] args) {
        if (!sender.hasPermission("customjukebox.disc.create")) return denied(sender);
        if (args.length < 3) {
            Text.error(sender, "Usage: /jukebox disc <player> <song>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            Text.error(sender, "Player not found: ", args[1]);
            return true;
        }

        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        SongMetadata song = plugin.library().find(query).orElse(null);
        if (song == null) {
            Text.error(sender, "No unique song matched '" + query + "'. Use its path if titles collide.");
            return true;
        }

        var leftovers = target.getInventory().addItem(plugin.discs().createDisc(song, 1));
        leftovers.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        Text.success(target, "You received a record: ", song.displayTitle());
        if (sender != target) Text.success(sender, "Gave a record to ", target.getName());
        return true;
    }

    private boolean listSigns(CommandSender sender) {
        if (!sender.hasPermission("customjukebox.admin")) return denied(sender);
        List<BlockKey> signs = plugin.signs().validateAndList();
        if (signs.isEmpty()) {
            Text.info(sender, "No jukebox signs yet. Write [jukebox] on the first line of a sign.");
            return true;
        }
        Text.info(sender, "Jukebox signs: ", String.valueOf(signs.size()));
        for (BlockKey key : signs) sender.sendMessage(signLine(key));
        return true;
    }

    private Component signLine(BlockKey key) {
        String coordinates = key.x() + " " + key.y() + " " + key.z();
        String song = null;
        boolean playing = false;
        World world = Bukkit.getWorld(key.world());
        if (world != null && world.isChunkLoaded(key.x() >> 4, key.z() >> 4)
                && world.getBlockAt(key.x(), key.y(), key.z()).getState() instanceof Sign sign) {
            SignConfig config = plugin.signs().read(sign).orElse(null);
            if (config != null) {
                song = plugin.library().find(config.songId()).map(SongMetadata::displayTitle).orElse("no song");
                playing = plugin.signs().isPlaying(sign);
            }
        }
        Component line = Component.text(" • ", NamedTextColor.DARK_GRAY)
                .append(Component.text(key.world() + " " + coordinates, NamedTextColor.AQUA))
                .append(Component.text(song == null ? "  (chunk not loaded)" : "  " + song,
                        song == null ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY));
        if (playing) line = line.append(Component.text("  ♪", NamedTextColor.GREEN));
        return line.hoverEvent(HoverEvent.showText(Component.text("Click to prepare a teleport command", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.suggestCommand("/execute in " + world(key) + " run tp @s " + coordinates));
    }

    private static String world(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        return world == null ? key.world() : world.getKey().asString();
    }

    private boolean help(CommandSender sender) {
        Text.info(sender, "Commands:");
        helpLine(sender, "/jukebox play", "Open the song browser");
        helpLine(sender, "/jukebox play <song>", "Play a song directly");
        helpLine(sender, "/jukebox stop", "Stop your music");
        if (sender.hasPermission("customjukebox.disc.create")) {
            helpLine(sender, "/jukebox disc <player> <song>", "Give a song-bound record");
        }
        if (sender.hasPermission("customjukebox.admin")) {
            helpLine(sender, "/jukebox reload", "Rescan the songs folder");
            helpLine(sender, "/jukebox list-signs", "Show all jukebox signs");
        }
        if (sender.hasPermission("customjukebox.sign.place")) {
            Text.info(sender, "Write [jukebox] on a sign to create a world jukebox.");
        }
        return true;
    }

    private static void helpLine(CommandSender sender, String usage, String description) {
        String suggestion = usage.contains("<") ? usage.substring(0, usage.indexOf('<')) : usage;
        sender.sendMessage(Component.text(" " + usage, NamedTextColor.GOLD)
                .append(Component.text(" - " + description, NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text("Click to type this command", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.suggestCommand(suggestion)));
    }

    private boolean denied(CommandSender sender) {
        Text.error(sender, "You do not have permission to do that.");
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
