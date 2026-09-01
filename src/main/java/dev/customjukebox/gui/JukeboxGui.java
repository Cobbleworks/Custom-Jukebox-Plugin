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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Renders folder, song, and playback controls and validates inventory interactions.
 */
public final class JukeboxGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final List<Material> MUSIC_DISCS = Arrays.stream(Material.values())
            .filter(material -> material.name().startsWith("MUSIC_DISC_"))
            .sorted(Comparator.comparing(Enum::name))
            .toList();
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
        private String currentFolder = "";
        private List<BrowserEntry> visibleEntries = List.of();

        private Session(Player player, Mode mode, Location signLocation, SignConfig draft) {
            this.player = player; this.mode = mode; this.signLocation = signLocation; this.draft = draft;
            inventory = Bukkit.createInventory(this, 54, Component.text(
                    mode == Mode.SIGN ? "Configure Jukebox Sign" : "Personal Jukebox"));
        }

        private void render() {
            inventory.clear();
            renderBrowser();
            inventory.setItem(45, item(Material.ARROW,
                    page > 0 ? "Previous page" : currentFolder.isEmpty() ? "No parent folder" : "Back to parent folder"));
            inventory.setItem(53, item(Material.ARROW, "Next page"));
            if (mode == Mode.SIGN) renderSignControls(); else renderPersonalControls();
        }

        private void renderBrowser() {
            List<BrowserEntry> all = new ArrayList<>();
            plugin.library().childFolders(currentFolder).stream()
                    .map(FolderEntry::new)
                    .forEach(all::add);
            plugin.library().directFolder(currentFolder).stream()
                    .map(SongEntry::new)
                    .forEach(all::add);
            int from = Math.min(page * PAGE_SIZE, all.size());
            int to = Math.min(from + PAGE_SIZE, all.size());
            visibleEntries = all.subList(from, to);
            for (int i = 0; i < visibleEntries.size(); i++) {
                BrowserEntry entry = visibleEntries.get(i);
                if (entry instanceof FolderEntry folder) {
                    inventory.setItem(i, item(Material.CHEST, folderName(folder.path()), "Open folder"));
                } else if (entry instanceof SongEntry songEntry) {
                    SongMetadata song = songEntry.song();
                    boolean selected = mode == Mode.SIGN && song.id().equalsIgnoreCase(draft.songId());
                    inventory.setItem(i, songItem(song, selected));
                }
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
            inventory.setItem(46, item(Material.NOTE_BLOCK, "Songs root"));
            inventory.setItem(47, item(Material.CHEST, "Queue this folder", "Direct contents only"));
            boolean paused = plugin.playback().isPaused(player.getUniqueId());
            boolean playing = plugin.playback().isPersonalActive(player.getUniqueId());
            inventory.setItem(48, item(paused ? Material.LIME_DYE : playing ? Material.YELLOW_DYE : Material.GRAY_DYE,
                    paused ? "Resume" : "Pause", playing ? "Playback active" : "No song playing"));
            inventory.setItem(49, item(Material.BARRIER, "Stop"));
            inventory.setItem(50, item(Material.ARROW, "Skip"));
            inventory.setItem(51, item(plugin.playback().personalLoop(player.getUniqueId())
                    ? Material.SLIME_BALL : Material.CLAY_BALL,
                    "Loop: " + plugin.playback().personalLoop(player.getUniqueId())));
            inventory.setItem(52, item(Material.OAK_DOOR, "Close"));
        }

        private void click(int slot) {
            if (slot < PAGE_SIZE) {
                if (slot < visibleEntries.size()) {
                    BrowserEntry entry = visibleEntries.get(slot);
                    if (entry instanceof FolderEntry folder) {
                        currentFolder = folder.path();
                        page = 0;
                    } else if (entry instanceof SongEntry songEntry) {
                        SongMetadata song = songEntry.song();
                        if (mode == Mode.SIGN) draft = draft.withSong(song.id());
                        else if (!plugin.playback().playPersonal(player, song, plugin.settings().personalVolume())) {
                            player.sendMessage("Could not start playback (the source limit may be reached).");
                        }
                    }
                }
                render();
                return;
            }
            if (slot == 45) {
                if (page > 0) page--;
                else if (!currentFolder.isEmpty()) currentFolder = parentFolder(currentFolder);
                render(); return;
            }
            if (slot == 53) {
                int count = plugin.library().childFolders(currentFolder).size()
                        + plugin.library().directFolder(currentFolder).size();
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
                case 46 -> { currentFolder = ""; page = 0; }
                case 47 -> {
                    List<SongMetadata> songs = plugin.library().directFolder(currentFolder);
                    if (songs.isEmpty()) player.sendMessage("This folder has no direct songs.");
                    else {
                        plugin.playback().queue(player.getUniqueId(), songs);
                        plugin.playback().skip(player.getUniqueId());
                        player.sendMessage("Queued " + songs.size() + " songs.");
                    }
                }
                case 48 -> {
                    if (!plugin.playback().isPersonalActive(player.getUniqueId())) {
                        player.sendMessage("No song is currently playing.");
                        break;
                    }
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
        Material disc = MUSIC_DISCS.get(Math.floorMod(song.id().hashCode(), MUSIC_DISCS.size()));
        return item(disc,
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

    private static String folderName(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static String parentFolder(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private sealed interface BrowserEntry permits FolderEntry, SongEntry { }
    private record FolderEntry(String path) implements BrowserEntry { }
    private record SongEntry(SongMetadata song) implements BrowserEntry { }

    private enum Mode { SIGN, PERSONAL }
}
