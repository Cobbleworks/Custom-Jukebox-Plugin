package dev.customjukebox.gui;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.sign.RedstoneMode;
import dev.customjukebox.sign.SignConfig;
import dev.customjukebox.song.SongMetadata;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class JukeboxGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private final CustomJukeboxPlugin plugin;

    public JukeboxGui(CustomJukeboxPlugin plugin) { this.plugin = plugin; }

    public void openPersonal(Player player) {
        open(new Session(player, Mode.PERSONAL, null, null));
    }

    public void openSign(Player player, Sign sign) {
        SignConfig config = plugin.signs().read(sign).orElse(new SignConfig("",
                plugin.settings().defaultVolume(), false, RedstoneMode.IGNORE));
        open(new Session(player, Mode.SIGN, sign.getLocation(), config));
    }

    private void open(Session session) {
        session.render();
        session.player.openInventory(session.inventory);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Session session)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        session.click(event.getSlot());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Session) event.setCancelled(true);
    }

    private final class Session implements InventoryHolder {
        private final Player player;
        private final Mode mode;
        private final Location signLocation;
        private Inventory inventory;
        private SignConfig draft;
        private int page;
        private boolean folders;
        private List<SongMetadata> visibleSongs = List.of();
        private List<String> visibleFolders = List.of();

        private Session(Player player, Mode mode, Location signLocation, SignConfig draft) {
            this.player = player; this.mode = mode; this.signLocation = signLocation; this.draft = draft;
            inventory = Bukkit.createInventory(this, 54, Component.text(
                    mode == Mode.SIGN ? "Configure Jukebox Sign" : "Personal Jukebox"));
        }

        private void render() {
            inventory.clear();
            if (folders) renderFolders(); else renderSongs();
            inventory.setItem(45, item(Material.ARROW, "Previous page"));
            inventory.setItem(53, item(Material.ARROW, "Next page"));
            if (mode == Mode.SIGN) renderSignControls(); else renderPersonalControls();
        }

        private void renderSongs() {
            List<SongMetadata> all = new ArrayList<>(plugin.library().all());
            int from = Math.min(page * PAGE_SIZE, all.size());
            int to = Math.min(from + PAGE_SIZE, all.size());
            visibleSongs = all.subList(from, to);
            visibleFolders = List.of();
            for (int i = 0; i < visibleSongs.size(); i++) {
                SongMetadata song = visibleSongs.get(i);
                boolean selected = mode == Mode.SIGN && song.id().equalsIgnoreCase(draft.songId());
                inventory.setItem(i, songItem(song, selected));
            }
        }

        private void renderFolders() {
            List<String> all = plugin.library().folders();
            int from = Math.min(page * PAGE_SIZE, all.size());
            int to = Math.min(from + PAGE_SIZE, all.size());
            visibleFolders = all.subList(from, to);
            visibleSongs = List.of();
            for (int i = 0; i < visibleFolders.size(); i++) {
                String folder = visibleFolders.get(i);
                String label = folder.isEmpty() ? "(songs root)" : folder;
                inventory.setItem(i, item(Material.CHEST, label,
                        plugin.library().directFolder(folder).size() + " direct songs"));
            }
        }

        private void renderSignControls() {
            inventory.setItem(46, item(Material.RED_DYE, "Volume -", "Current: " + draft.volume()));
            inventory.setItem(47, item(Material.LIME_DYE, "Volume +", "Current: " + draft.volume()));
            inventory.setItem(48, item(draft.loop() ? Material.SLIME_BALL : Material.CLAY_BALL,
                    "Loop: " + draft.loop()));
            inventory.setItem(49, item(Material.REDSTONE, "Redstone: " + draft.redstoneMode().display()));
            boolean playing = currentSign() != null && plugin.signs().isPlaying(currentSign());
            inventory.setItem(50, item(playing ? Material.BARRIER : Material.JUKEBOX,
                    playing ? "Stop playback" : "Start playback"));
            inventory.setItem(51, item(Material.LIME_CONCRETE, "Confirm",
                    draft.songId().isBlank() ? "No song selected" : draft.songId()));
            inventory.setItem(52, item(Material.RED_CONCRETE, "Cancel"));
        }

        private void renderPersonalControls() {
            inventory.setItem(46, item(Material.NOTE_BLOCK, "Songs"));
            inventory.setItem(47, item(Material.CHEST, "Queue a folder", "Direct contents only"));
            inventory.setItem(48, item(Material.REPEATER,
                    plugin.playback().isPaused(player.getUniqueId()) ? "Resume" : "Pause"));
            inventory.setItem(49, item(Material.BARRIER, "Stop"));
            inventory.setItem(50, item(Material.ARROW, "Skip"));
            inventory.setItem(51, item(plugin.playback().personalLoop(player.getUniqueId())
                    ? Material.SLIME_BALL : Material.CLAY_BALL,
                    "Loop: " + plugin.playback().personalLoop(player.getUniqueId())));
            inventory.setItem(52, item(Material.OAK_DOOR, "Close"));
        }

        private void click(int slot) {
            if (slot < PAGE_SIZE) {
                if (folders && slot < visibleFolders.size()) {
                    List<SongMetadata> songs = plugin.library().directFolder(visibleFolders.get(slot));
                    if (songs.isEmpty()) player.sendMessage("That folder has no direct songs.");
                    else {
                        plugin.playback().queue(player.getUniqueId(), songs);
                        plugin.playback().skip(player.getUniqueId());
                        player.sendMessage("Queued " + songs.size() + " songs.");
                        folders = false; page = 0;
                    }
                } else if (!folders && slot < visibleSongs.size()) {
                    SongMetadata song = visibleSongs.get(slot);
                    if (mode == Mode.SIGN) draft = draft.withSong(song.id());
                    else if (!plugin.playback().playPersonal(player, song, plugin.settings().personalVolume())) {
                        player.sendMessage("Could not start playback (the source limit may be reached).");
                    }
                }
                render();
                return;
            }
            if (slot == 45) { page = Math.max(0, page - 1); render(); return; }
            if (slot == 53) {
                int count = folders ? plugin.library().folders().size() : plugin.library().all().size();
                if ((page + 1) * PAGE_SIZE < count) page++;
                render(); return;
            }
            if (mode == Mode.SIGN) clickSign(slot); else clickPersonal(slot);
        }

        private void clickSign(int slot) {
            switch (slot) {
                case 46 -> draft = draft.withVolume(plugin.settings().clampVolume(draft.volume() - 1));
                case 47 -> draft = draft.withVolume(plugin.settings().clampVolume(draft.volume() + 1));
                case 48 -> draft = draft.withLoop(!draft.loop());
                case 49 -> draft = draft.withRedstone(draft.redstoneMode().next());
                case 50 -> {
                    Sign sign = currentSign();
                    if (sign != null) {
                        plugin.signs().write(sign, draft, true);
                        if (plugin.signs().isPlaying(sign)) plugin.signs().stop(sign);
                        else if (!plugin.signs().play(sign)) player.sendMessage("Select a valid song or free a playback slot first.");
                    }
                }
                case 51 -> {
                    Sign sign = currentSign();
                    if (sign == null) player.sendMessage("That sign no longer exists.");
                    else {
                        plugin.signs().write(sign, draft, true);
                        player.sendMessage("Jukebox sign saved.");
                    }
                    player.closeInventory();
                    return;
                }
                case 52 -> { player.closeInventory(); return; }
                default -> { return; }
            }
            render();
        }

        private void clickPersonal(int slot) {
            switch (slot) {
                case 46 -> { folders = false; page = 0; }
                case 47 -> { folders = true; page = 0; }
                case 48 -> {
                    boolean paused = plugin.playback().togglePause(player.getUniqueId());
                    player.sendMessage(paused ? "Playback paused." : "Playback resumed.");
                }
                case 49 -> plugin.playback().stopPersonal(player.getUniqueId());
                case 50 -> plugin.playback().skip(player.getUniqueId());
                case 51 -> plugin.playback().togglePersonalLoop(player.getUniqueId());
                case 52 -> { player.closeInventory(); return; }
                default -> { return; }
            }
            render();
        }

        private Sign currentSign() {
            if (signLocation == null || !(signLocation.getBlock().getState() instanceof Sign sign)) return null;
            return plugin.signs().read(sign).isPresent() ? sign : null;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static ItemStack songItem(SongMetadata song, boolean selected) {
        String duration = formatDuration(song.duration());
        return item(selected ? Material.MUSIC_DISC_CAT : Material.MUSIC_DISC_13,
                (selected ? "✓ " : "") + song.displayTitle(),
                song.author().isBlank() ? "Unknown author" : song.author(),
                song.id(), String.format("%.2f ticks/s • %s", song.tempo(), duration),
                "Instruments: " + song.instruments());
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = ItemStack.of(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        if (lore.length > 0) meta.lore(java.util.Arrays.stream(lore).map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private enum Mode { SIGN, PERSONAL }
}
