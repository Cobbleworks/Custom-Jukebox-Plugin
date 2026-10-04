package dev.customjukebox.gui;

import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.playback.PlaybackManager.NowPlaying;
import dev.customjukebox.sign.RedstoneMode;
import dev.customjukebox.sign.SignConfig;
import dev.customjukebox.song.SongMetadata;
import dev.customjukebox.ui.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Renders folder, song, and playback controls and validates inventory interactions.
 *
 * <p>Layout: rows 1-5 hold the folder browser, row 6 holds navigation and controls.
 */
public final class JukeboxGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int SLOT_BACK = 45, SLOT_PREVIOUS = 46, SLOT_NEXT = 53;
    private static final int VANILLA_INSTRUMENT_COUNT = 16;
    private static final List<Material> MUSIC_DISCS = Arrays.stream(Material.values())
            .filter(material -> material.name().startsWith("MUSIC_DISC_"))
            .sorted(Comparator.comparing(Enum::name))
            .toList();
    private static final TextColor LABEL = NamedTextColor.GRAY;
    private static final TextColor HINT = NamedTextColor.YELLOW;
    private final CustomJukeboxPlugin plugin;

    public JukeboxGui(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        // Keeps progress, play state and queue counts current while a menu is open.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpen, 10L, 10L);
    }

    public void openPersonal(Player player) {
        new Session(player, Mode.PERSONAL, null, null).open();
    }

    public void openSign(Player player, Sign sign) {
        SignConfig config = plugin.signs().read(sign).orElse(new SignConfig("",
                plugin.settings().defaultVolume(), false, RedstoneMode.IGNORE));
        Session session = new Session(player, Mode.SIGN, sign.getLocation(), config);
        // Start where the configured song lives so it is visible immediately.
        plugin.library().find(config.songId()).ifPresent(session::reveal);
        session.open();
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Session session)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        session.click(event.getSlot(), event.getClick());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Session) event.setCancelled(true);
    }

    private void refreshOpen() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Session session) session.refresh();
        }
    }

    private final class Session implements InventoryHolder {
        private final Player player;
        private final Mode mode;
        private final Location signLocation;
        private final SignConfig saved;
        private Inventory inventory;
        private SignConfig draft;
        private int page;
        private String currentFolder = "";
        private List<BrowserEntry> entries = List.of();
        private String renderedPlaying;

        private Session(Player player, Mode mode, Location signLocation, SignConfig draft) {
            this.player = player; this.mode = mode; this.signLocation = signLocation;
            this.draft = draft; this.saved = draft;
        }

        private void open() {
            inventory = Bukkit.createInventory(this, 54, title());
            render();
            player.openInventory(inventory);
        }

        /** Moves to the folder and page that contain {@code song}. */
        private void reveal(SongMetadata song) {
            currentFolder = song.folder();
            List<BrowserEntry> all = loadEntries();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) instanceof SongEntry entry && entry.song().id().equals(song.id())) page = i / PAGE_SIZE;
            }
        }

        private void navigate(String folder) {
            currentFolder = folder;
            page = 0;
            render();
            // The title shows the folder, and titles can only change by opening a new inventory.
            // Opening inventories inside a click event is unsafe, so do it on the next tick.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && player.getOpenInventory().getTopInventory() == inventory) open();
            });
        }

        private Component title() {
            Component base = Component.text(mode == Mode.SIGN ? "Jukebox Sign" : "Jukebox");
            if (currentFolder.isEmpty()) return base;
            String path = currentFolder;
            String[] parts = path.split("/");
            if (parts.length > 2) path = "… / " + parts[parts.length - 2] + " / " + parts[parts.length - 1];
            else path = path.replace("/", " / ");
            return base.append(Component.text(" » " + path, NamedTextColor.DARK_GRAY));
        }

        private int pageCount() { return Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE); }

        private void render() {
            inventory.clear();
            entries = loadEntries();
            page = Math.min(page, pageCount() - 1);
            renderedPlaying = playingId();
            renderBrowser();
            renderControls();
        }

        /** Re-renders everything when the playing song changed, otherwise only the control row. */
        private void refresh() {
            if (!Objects.equals(playingId(), renderedPlaying)) render();
            else renderControls();
        }

        private String playingId() {
            if (mode == Mode.SIGN) {
                Sign sign = currentSign();
                return sign != null && plugin.signs().isPlaying(sign) ? "sign" : null;
            }
            return plugin.playback().nowPlaying(player.getUniqueId()).map(now -> now.song().id()).orElse(null);
        }

        private List<BrowserEntry> loadEntries() {
            List<BrowserEntry> all = new ArrayList<>();
            plugin.library().childFolders(currentFolder).forEach(path -> all.add(new FolderEntry(path)));
            plugin.library().directFolder(currentFolder).forEach(song -> all.add(new SongEntry(song)));
            return all;
        }

        private void renderBrowser() {
            if (entries.isEmpty()) {
                inventory.setItem(22, currentFolder.isEmpty()
                        ? item(Material.BOOK, "No songs yet", NamedTextColor.RED, "Add .nbs files to",
                        "plugins/CustomJukebox/songs/", "and run /jukebox reload.")
                        : item(Material.BOOK, "This folder is empty", NamedTextColor.RED));
                return;
            }
            int from = page * PAGE_SIZE;
            List<BrowserEntry> visible = entries.subList(from, Math.min(from + PAGE_SIZE, entries.size()));
            String highlight = mode == Mode.SIGN ? draft.songId() : renderedPlaying;
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i) instanceof FolderEntry folder) inventory.setItem(i, folderItem(folder.path(), highlight));
                else if (visible.get(i) instanceof SongEntry song) inventory.setItem(i, songItem(song.song()));
            }
        }

        private void renderControls() {
            for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, filler());
            if (!currentFolder.isEmpty()) {
                inventory.setItem(SLOT_BACK, item(Material.OAK_DOOR, "◀ Back", NamedTextColor.WHITE,
                        "To " + (parentFolder(currentFolder).isEmpty() ? "all songs" : folderName(parentFolder(currentFolder))),
                        "", "Right-click: back to all songs"));
            }
            String pageInfo = "Page " + (page + 1) + " of " + pageCount();
            if (page > 0) inventory.setItem(SLOT_PREVIOUS, item(Material.ARROW, "◀ Previous page", NamedTextColor.WHITE, pageInfo));
            if (page + 1 < pageCount()) inventory.setItem(SLOT_NEXT, item(Material.ARROW, "Next page ▶", NamedTextColor.WHITE, pageInfo));
            if (mode == Mode.SIGN) renderSignControls(); else renderPersonalControls();
        }

        private void renderSignControls() {
            inventory.setItem(47, volumeItem(draft.volume()));
            inventory.setItem(48, toggleItem("Loop", draft.loop(), "Restart the song when it ends."));
            inventory.setItem(49, redstoneItem());

            boolean playing = Objects.equals(renderedPlaying, "sign");
            inventory.setItem(50, playing
                    ? item(Material.BARRIER, "■ Stop test", NamedTextColor.RED, "Stops playback at this sign.")
                    : item(Material.JUKEBOX, "▶ Test playback", NamedTextColor.GREEN,
                    "Plays the selected song at this sign", "for everyone nearby. Saves the sign."));

            String song = plugin.library().find(draft.songId()).map(SongMetadata::displayTitle)
                    .orElse(draft.songId().isBlank() ? "none" : draft.songId() + " (missing)");
            List<Component> lore = new ArrayList<>();
            lore.add(Text.plain("Song: ", LABEL).append(Text.plain(song, NamedTextColor.GOLD)));
            lore.add(Text.plain("Volume " + draft.volume() + (draft.loop() ? " · Loop" : "")
                    + " · Redstone: " + draft.redstoneMode().display(), LABEL));
            if (!draft.equals(saved)) {
                lore.add(Component.empty());
                lore.add(Text.plain("You have unsaved changes.", HINT));
            }
            inventory.setItem(51, item(Material.LIME_CONCRETE, Text.plain("✔ Save & close", NamedTextColor.GREEN), lore));
            inventory.setItem(52, item(Material.RED_CONCRETE, "✖ Discard changes", NamedTextColor.RED,
                    "Closes without saving."));
        }

        private ItemStack redstoneItem() {
            List<Component> lore = new ArrayList<>();
            for (RedstoneMode mode : RedstoneMode.values()) {
                boolean current = mode == draft.redstoneMode();
                lore.add(Text.plain((current ? "▶ " : "   ") + mode.display() + ": " + describe(mode),
                        current ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            int radius = plugin.settings().redstoneRadius();
            lore.add(Text.plain(radius == 0 ? "Reacts when the sign itself is powered."
                    : "Reacts to redstone within " + radius + (radius == 1 ? " block." : " blocks."), LABEL));
            lore.add(Component.empty());
            lore.add(Text.plain("Left-click: next · Right-click: previous", HINT));
            Material icon = switch (draft.redstoneMode()) {
                case TOGGLE -> Material.LEVER;
                case PULSE -> Material.STONE_BUTTON;
                case IGNORE -> Material.GUNPOWDER;
            };
            return item(icon, Text.plain("Redstone: " + draft.redstoneMode().display(), NamedTextColor.RED), lore);
        }

        private void renderPersonalControls() {
            var playback = plugin.playback();
            var id = player.getUniqueId();
            inventory.setItem(47, volumeItem(playback.personalVolume(id)));

            int total = plugin.library().recursiveFolder(currentFolder).size();
            inventory.setItem(48, item(Material.HOPPER, "Play this folder", NamedTextColor.AQUA,
                    total + (total == 1 ? " song" : " songs") + " including subfolders",
                    "", "Left-click: play in order", "Right-click: shuffle"));

            NowPlaying now = playback.nowPlaying(id).orElse(null);
            inventory.setItem(49, nowPlayingItem(now));

            int queued = playback.queuedCount(id);
            inventory.setItem(50, queued > 0
                    ? item(Material.SPECTRAL_ARROW, "⏭ Next song", NamedTextColor.WHITE, queued + " queued",
                    "", "Click: skip to the next song")
                    : item(Material.SPECTRAL_ARROW, "⏭ Next song", NamedTextColor.DARK_GRAY, "The queue is empty."));
            inventory.setItem(51, toggleItem("Loop", playback.personalLoop(id), "Repeat the current song."));
            inventory.setItem(52, now == null
                    ? item(Material.BARRIER, "■ Stop", NamedTextColor.DARK_GRAY, "Nothing is playing.")
                    : item(Material.BARRIER, "■ Stop", NamedTextColor.RED, "Stops playback and clears the queue."));
        }

        private ItemStack nowPlayingItem(NowPlaying now) {
            if (now == null) {
                return item(Material.JUKEBOX, "Nothing playing", NamedTextColor.GRAY,
                        "Click a song above to start.");
            }
            SongMetadata song = now.song();
            long total = Math.max(1, song.duration().toMillis());
            List<Component> lore = new ArrayList<>();
            if (!song.author().isBlank()) lore.add(Text.plain("by " + song.author(), LABEL));
            lore.add(Text.bar((double) now.elapsed().toMillis() / total, 20, NamedTextColor.GOLD));
            lore.add(Text.plain(Text.duration(now.elapsed()) + " / " + Text.duration(song.duration())
                    + (now.paused() ? "  ·  Paused" : "") + (now.loop() ? "  ·  Loop" : ""), LABEL));
            lore.add(Component.empty());
            lore.add(Text.plain(now.paused() ? "Click: resume" : "Click: pause", HINT));
            Component name = Text.plain(now.paused() ? "⏸ " : "♪ ", now.paused() ? NamedTextColor.YELLOW : NamedTextColor.GREEN)
                    .append(Text.plain(song.displayTitle(), NamedTextColor.GOLD));
            return glint(item(discFor(song), name, lore));
        }

        private ItemStack songItem(SongMetadata song) {
            List<Component> lore = new ArrayList<>();
            lore.add(Text.plain(song.author().isBlank() ? "Unknown author" : "by " + song.author(),
                    song.author().isBlank() ? NamedTextColor.DARK_GRAY : LABEL));
            lore.add(Text.plain("Length " + Text.duration(song.duration()), LABEL));
            if (song.instruments().stream().anyMatch(i -> i >= VANILLA_INSTRUMENT_COUNT)) {
                lore.add(Text.plain("Custom instruments are not played", NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            boolean marked;
            if (mode == Mode.SIGN) {
                marked = song.id().equalsIgnoreCase(draft.songId());
                lore.add(marked ? Text.plain("✔ Selected for this sign", NamedTextColor.GREEN)
                        : Text.plain("Click: select for this sign", HINT));
            } else {
                marked = song.id().equals(renderedPlaying);
                if (marked) {
                    lore.add(Text.plain("♪ Now playing", NamedTextColor.GREEN));
                    lore.add(Text.plain("Click: pause / resume", HINT));
                } else {
                    lore.add(Text.plain("Click: play", HINT));
                    lore.add(Text.plain("Shift-click: add to queue", HINT));
                }
            }
            Component name = Text.plain(marked ? (mode == Mode.SIGN ? "✔ " : "♪ ") : "",
                    NamedTextColor.GREEN).append(Text.plain(song.displayTitle(), NamedTextColor.GOLD));
            ItemStack stack = item(discFor(song), name, lore);
            return marked ? glint(stack) : stack;
        }

        private ItemStack folderItem(String path, String highlightedSong) {
            int count = plugin.library().recursiveFolder(path).size();
            List<Component> lore = new ArrayList<>();
            lore.add(Text.plain(count + (count == 1 ? " song" : " songs"), LABEL));
            boolean contains = highlightedSong != null
                    && highlightedSong.startsWith(path.toLowerCase(Locale.ROOT) + "/");
            if (contains) {
                lore.add(Text.plain(mode == Mode.SIGN ? "Contains the selected song" : "Contains the playing song",
                        NamedTextColor.GREEN));
            }
            lore.add(Component.empty());
            lore.add(Text.plain("Click: open", HINT));
            ItemStack stack = item(Material.CHEST, Text.plain(folderName(path), NamedTextColor.AQUA), lore);
            return contains ? glint(stack) : stack;
        }

        private ItemStack volumeItem(int volume) {
            int min = plugin.settings().minVolume(), max = plugin.settings().maxVolume();
            double fraction = max == min ? 1 : (double) (volume - min) / (max - min);
            return item(Material.NOTE_BLOCK, Text.plain("Volume: " + volume + (volume == 0 ? " (muted)" : ""),
                            NamedTextColor.AQUA),
                    List.of(Text.bar(fraction, 10, NamedTextColor.AQUA),
                            Text.plain("Range " + min + "-" + max, NamedTextColor.DARK_GRAY),
                            Component.empty(),
                            Text.plain("Left-click: louder · Right-click: quieter", HINT)));
        }

        private void click(int slot, ClickType click) {
            if (slot < PAGE_SIZE) {
                int index = page * PAGE_SIZE + slot;
                if (index >= entries.size()) return;
                BrowserEntry entry = entries.get(index);
                feedback();
                if (entry instanceof FolderEntry folder) { navigate(folder.path()); return; }
                if (entry instanceof SongEntry songEntry) clickSong(songEntry.song(), click);
                render();
                return;
            }
            switch (slot) {
                case SLOT_BACK -> {
                    if (currentFolder.isEmpty()) return;
                    feedback();
                    navigate(click.isRightClick() ? "" : parentFolder(currentFolder));
                    return;
                }
                case SLOT_PREVIOUS -> { if (page == 0) return; page--; }
                case SLOT_NEXT -> { if (page + 1 >= pageCount()) return; page++; }
                default -> {
                    boolean handled = mode == Mode.SIGN ? clickSign(slot, click) : clickPersonal(slot, click);
                    if (!handled) return;
                }
            }
            feedback();
            if (player.getOpenInventory().getTopInventory() == inventory) render();
        }

        private void clickSong(SongMetadata song, ClickType click) {
            if (mode == Mode.SIGN) {
                draft = draft.withSong(song.id());
                return;
            }
            var playback = plugin.playback();
            var id = player.getUniqueId();
            if (song.id().equals(playingId())) {
                playback.togglePause(id);
            } else if (click.isShiftClick() && playback.isPersonalActive(id)) {
                playback.enqueue(id, song);
                Text.info(player, "Added to the queue: ", song.displayTitle());
            } else if (!playback.playPersonal(player, song)) {
                Text.error(player, "Could not start playback. The server's playback limit may be reached.");
            }
        }

        private boolean clickSign(int slot, ClickType click) {
            switch (slot) {
                case 47 -> draft = draft.withVolume(plugin.settings().clampVolume(draft.volume() + (click.isRightClick() ? -1 : 1)));
                case 48 -> draft = draft.withLoop(!draft.loop());
                case 49 -> draft = draft.withRedstone(click.isRightClick() ? previous(draft.redstoneMode()) : draft.redstoneMode().next());
                case 50 -> {
                    Sign sign = currentSign();
                    if (sign == null) { signGone(); return true; }
                    if (plugin.signs().isPlaying(sign)) { plugin.signs().stop(sign); return true; }
                    if (plugin.library().find(draft.songId()).isEmpty()) {
                        Text.error(player, "Select a song first.");
                        return true;
                    }
                    plugin.signs().write(sign, draft, true);
                    if (!plugin.signs().play(sign)) Text.error(player, "Could not start playback. The server's playback limit may be reached.");
                }
                case 51 -> {
                    Sign sign = currentSign();
                    if (sign == null) signGone();
                    else {
                        plugin.signs().write(sign, draft, true);
                        Text.success(player, "Jukebox sign saved.");
                    }
                    player.closeInventory();
                }
                case 52 -> {
                    if (!draft.equals(saved)) Text.info(player, "Changes discarded.");
                    player.closeInventory();
                }
                default -> { return false; }
            }
            return true;
        }

        private boolean clickPersonal(int slot, ClickType click) {
            var playback = plugin.playback();
            var id = player.getUniqueId();
            switch (slot) {
                case 47 -> playback.setPersonalVolume(id, playback.personalVolume(id) + (click.isRightClick() ? -1 : 1));
                case 48 -> {
                    List<SongMetadata> songs = plugin.library().recursiveFolder(currentFolder);
                    if (songs.isEmpty()) { Text.error(player, "This folder has no songs."); return true; }
                    boolean shuffle = click.isRightClick();
                    if (playback.playAll(player, songs, shuffle)) {
                        Text.info(player, (shuffle ? "Shuffling " : "Playing ") + songs.size()
                                + (songs.size() == 1 ? " song from " : " songs from "),
                                currentFolder.isEmpty() ? "all songs" : folderName(currentFolder));
                    } else {
                        Text.error(player, "Could not start playback. The server's playback limit may be reached.");
                    }
                }
                case 49 -> { if (!playback.isPersonalActive(id)) return false; playback.togglePause(id); }
                case 50 -> { if (playback.queuedCount(id) == 0) return false; playback.skip(id); }
                case 51 -> playback.togglePersonalLoop(id);
                case 52 -> { if (!playback.isPersonalActive(id)) return false; playback.stopPersonal(id); }
                default -> { return false; }
            }
            return true;
        }

        private void signGone() {
            Text.error(player, "That sign no longer exists.");
            player.closeInventory();
        }

        private void feedback() {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.0f);
        }

        private Sign currentSign() {
            if (signLocation == null || !signLocation.isChunkLoaded()
                    || !(signLocation.getBlock().getState() instanceof Sign sign)) return null;
            return plugin.signs().read(sign).isPresent() ? sign : null;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static String describe(RedstoneMode mode) {
        return switch (mode) {
            case TOGGLE -> "plays while powered";
            case PULSE -> "starts on each pulse";
            case IGNORE -> "no redstone";
        };
    }

    private static RedstoneMode previous(RedstoneMode mode) {
        RedstoneMode[] values = RedstoneMode.values();
        return values[(mode.ordinal() + values.length - 1) % values.length];
    }

    private static Material discFor(SongMetadata song) {
        return MUSIC_DISCS.get(Math.floorMod(song.id().hashCode(), MUSIC_DISCS.size()));
    }

    private static ItemStack toggleItem(String label, boolean on, String description) {
        return item(on ? Material.LIME_DYE : Material.GRAY_DYE,
                Text.plain(label + ": ", NamedTextColor.WHITE).append(on
                        ? Text.plain("On", NamedTextColor.GREEN) : Text.plain("Off", NamedTextColor.RED)),
                List.of(Text.plain(description, LABEL), Component.empty(),
                        Text.plain("Click: turn " + (on ? "off" : "on"), HINT)));
    }

    private static ItemStack filler() {
        ItemStack stack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return stack;
    }

    private static ItemStack glint(ItemStack stack) {
        stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static ItemStack item(Material material, String name, TextColor color, String... lore) {
        return item(material, Text.plain(name, color),
                Arrays.stream(lore).map(line -> line.isEmpty() ? Component.empty() : Text.plain(line, LABEL)).toList());
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) meta.lore(lore);
        stack.setItemMeta(meta);
        // Music discs would otherwise list their vanilla track under our lore.
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .hiddenComponents(Set.of(DataComponentTypes.JUKEBOX_PLAYABLE)).build());
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
