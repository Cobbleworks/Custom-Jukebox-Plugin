package dev.customjukebox.playback;

import com.xxmicloxx.NoteBlockAPI.model.Layer;
import com.xxmicloxx.NoteBlockAPI.model.Note;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import dev.customjukebox.CustomJukeboxPlugin;
import dev.customjukebox.song.SongMetadata;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import dev.customjukebox.ui.Text;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enforces the source limit and owns personal and sign-backed NoteBlockAPI players.
 */
public final class PlaybackManager implements Listener {
    private static final Sound[] VANILLA_INSTRUMENTS = {
            Sound.BLOCK_NOTE_BLOCK_HARP, Sound.BLOCK_NOTE_BLOCK_BASS,
            Sound.BLOCK_NOTE_BLOCK_BASEDRUM, Sound.BLOCK_NOTE_BLOCK_SNARE,
            Sound.BLOCK_NOTE_BLOCK_HAT, Sound.BLOCK_NOTE_BLOCK_GUITAR,
            Sound.BLOCK_NOTE_BLOCK_FLUTE, Sound.BLOCK_NOTE_BLOCK_BELL,
            Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, Sound.BLOCK_NOTE_BLOCK_COW_BELL,
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Sound.BLOCK_NOTE_BLOCK_BIT,
            Sound.BLOCK_NOTE_BLOCK_BANJO, Sound.BLOCK_NOTE_BLOCK_PLING
    };

    private final CustomJukeboxPlugin plugin;
    private final Map<String, Playback> active = new HashMap<>();
    private final Map<UUID, PersonalState> personal = new HashMap<>();

    public PlaybackManager(CustomJukeboxPlugin plugin) { this.plugin = plugin; }

    public boolean playSign(String key, Location location, SongMetadata metadata, int volume,
                            boolean loop, Runnable onEnd) {
        return playWorldSource(key, location, metadata, volume, loop, onEnd);
    }

    public boolean playJukebox(String key, Location location, SongMetadata metadata, int volume,
                               Runnable onEnd) {
        return playWorldSource(key, location, metadata, volume, false, onEnd);
    }

    private boolean playWorldSource(String key, Location location, SongMetadata metadata, int volume,
                                    boolean loop, Runnable onEnd) {
        stop(key);
        if (!hasCapacity()) return false;
        try {
            Playback playback = new Playback(key, plugin.library().load(metadata), volume, loop,
                    tick -> playWorld(location, tick), onEnd, () -> { });
            active.put(key, playback);
            playback.runTaskTimer(plugin, 0L, 1L);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not play " + metadata.id() + ": " + exception.getMessage());
            return false;
        }
    }

    public boolean playPersonal(Player player, SongMetadata metadata) {
        PersonalState state = state(player.getUniqueId());
        state.queue.clear();
        return startPersonal(player, metadata, state);
    }

    /** Replaces the queue with {@code songs} (optionally shuffled) and starts the first one. */
    public boolean playAll(Player player, List<SongMetadata> songs, boolean shuffle) {
        if (songs.isEmpty()) return false;
        List<SongMetadata> order = new ArrayList<>(songs);
        if (shuffle) Collections.shuffle(order);
        PersonalState state = state(player.getUniqueId());
        state.queue.clear();
        state.queue.addAll(order);
        return startPersonal(player, state.queue.removeFirst(), state);
    }

    private boolean startPersonal(Player player, SongMetadata metadata, PersonalState state) {
        String key = personalKey(player.getUniqueId());
        stop(key);
        if (!hasCapacity()) return false;
        state.current = metadata;
        try {
            Playback playback = new Playback(key, plugin.library().load(metadata), state.volume, state.loop,
                    tick -> playPlayer(player.getUniqueId(), tick), () -> advance(player.getUniqueId()),
                    () -> showPersonalStatus(player.getUniqueId()));
            active.put(key, playback);
            playback.runTaskTimer(plugin, 0L, 1L);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not play " + metadata.id() + ": " + exception.getMessage());
            return false;
        }
    }

    public void enqueue(UUID player, SongMetadata song) {
        state(player).queue.addLast(song);
    }

    public void skip(UUID player) {
        stop(personalKey(player));
        advance(player);
    }

    private void advance(UUID playerId) {
        PersonalState state = personal.get(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (state == null || player == null || state.queue.isEmpty()) return;
        SongMetadata next = state.queue.removeFirst();
        startPersonal(player, next, state);
    }

    public boolean togglePause(UUID player) {
        Playback playback = active.get(personalKey(player));
        if (playback == null) return false;
        playback.paused = !playback.paused;
        return playback.paused;
    }

    public boolean togglePersonalLoop(UUID player) {
        PersonalState state = state(player);
        state.loop = !state.loop;
        Playback playback = active.get(personalKey(player));
        if (playback != null) playback.loop = state.loop;
        return state.loop;
    }

    public boolean personalLoop(UUID player) {
        PersonalState state = personal.get(player);
        return state != null && state.loop;
    }

    public int personalVolume(UUID player) {
        PersonalState state = personal.get(player);
        return state == null ? plugin.settings().personalVolume() : state.volume;
    }

    /** Sets the player's personal volume (clamped to the configured range) and applies it immediately. */
    public int setPersonalVolume(UUID player, int volume) {
        PersonalState state = state(player);
        state.volume = plugin.settings().clampVolume(volume);
        Playback playback = active.get(personalKey(player));
        if (playback != null) playback.sourceVolume = state.volume;
        return state.volume;
    }

    public int queuedCount(UUID player) {
        PersonalState state = personal.get(player);
        return state == null ? 0 : state.queue.size();
    }

    public Optional<NowPlaying> nowPlaying(UUID player) {
        PersonalState state = personal.get(player);
        Playback playback = active.get(personalKey(player));
        if (state == null || playback == null || state.current == null) return Optional.empty();
        return Optional.of(new NowPlaying(state.current, playback.elapsed(), playback.paused, playback.loop,
                state.queue.size()));
    }

    public boolean isPaused(UUID player) {
        Playback playback = active.get(personalKey(player));
        return playback != null && playback.paused;
    }

    public boolean isPersonalActive(UUID player) {
        return active.containsKey(personalKey(player));
    }

    public void stopPersonal(UUID player) {
        stop(personalKey(player));
        PersonalState state = personal.get(player);
        if (state != null) state.queue.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopPersonal(event.getPlayer().getUniqueId());
        personal.remove(event.getPlayer().getUniqueId());
    }

    public void stop(String key) {
        Playback playback = active.remove(key);
        if (playback != null) playback.cancelSilently();
    }

    public boolean isActive(String key) { return active.containsKey(key); }
    public int activeCount() { return active.size(); }
    public void stopAll() { active.values().forEach(Playback::cancelSilently); active.clear(); }

    private boolean hasCapacity() { return active.size() < plugin.settings().maxActiveSources(); }
    private static String personalKey(UUID id) { return "player:" + id; }

    private PersonalState state(UUID player) {
        return personal.computeIfAbsent(player, ignored -> new PersonalState(plugin.settings().personalVolume()));
    }

    private static void playWorld(Location source, NoteTick tick) {
        World world = source.getWorld();
        if (world == null) return;
        for (Player player : world.getPlayers()) {
            player.playSound(source, tick.sound, SoundCategory.RECORDS, tick.volume, tick.pitch);
        }
    }

    private void playPlayer(UUID playerId, NoteTick tick) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            player.playSound(player.getLocation(), tick.sound, SoundCategory.RECORDS, tick.volume, tick.pitch);
        }
    }

    private void showPersonalStatus(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        NowPlaying now = nowPlaying(playerId).orElse(null);
        if (player == null || now == null) return;
        Component status = Component.text(now.paused() ? "⏸ " : "♪ ", now.paused() ? NamedTextColor.YELLOW : NamedTextColor.GOLD)
                .append(Component.text(now.song().displayTitle(), now.paused() ? NamedTextColor.GRAY : NamedTextColor.GOLD))
                .append(Component.text("  " + Text.duration(now.elapsed()) + " / " + Text.duration(now.song().duration()),
                        NamedTextColor.DARK_GRAY));
        if (now.paused()) status = status.append(Component.text("  paused", NamedTextColor.YELLOW));
        player.sendActionBar(status);
    }

    private final class Playback extends BukkitRunnable {
        private final String key;
        private final Song song;
        private int sourceVolume;
        private final Consumer<NoteTick> output;
        private final Runnable onEnd;
        private final Runnable status;
        private double songTickAccumulator;
        private int songTick = -1;
        private int statusTick;
        private boolean loop;
        private boolean paused;

        private Playback(String key, Song song, int sourceVolume, boolean loop,
                         Consumer<NoteTick> output, Runnable onEnd, Runnable status) {
            this.key = key;
            this.song = song;
            this.sourceVolume = sourceVolume;
            this.loop = loop;
            this.output = output;
            this.onEnd = onEnd;
            this.status = status;
        }

        @Override public void run() {
            if (statusTick++ % 20 == 0) status.run();
            if (paused) return;
            songTickAccumulator += song.getSpeed() / 20.0;
            while (songTickAccumulator >= 1.0) {
                songTickAccumulator -= 1.0;
                songTick++;
                if (songTick > song.getLength()) {
                    if (loop) songTick = 0;
                    else {
                        active.remove(key, this);
                        cancel();
                        onEnd.run();
                        return;
                    }
                }
                emit(songTick);
            }
        }

        private void emit(int tick) {
            if (sourceVolume <= 0) return;
            for (Layer layer : song.getLayerHashMap().values()) {
                Note note = layer.getNote(tick);
                if (note == null) continue;
                int instrument = Byte.toUnsignedInt(note.getInstrument());
                if (instrument >= VANILLA_INSTRUMENTS.length) continue;
                float velocity = Byte.toUnsignedInt(note.getVelocity()) / 100.0f;
                float layerVolume = Byte.toUnsignedInt(layer.getVolume()) / 100.0f;
                float volume = sourceVolume * velocity * layerVolume;
                float pitch = (float) Math.pow(2.0, (Byte.toUnsignedInt(note.getKey()) - 45) / 12.0);
                output.accept(new NoteTick(VANILLA_INSTRUMENTS[instrument], volume, pitch));
            }
        }

        private Duration elapsed() {
            float speed = song.getSpeed();
            if (speed <= 0 || songTick <= 0) return Duration.ZERO;
            return Duration.ofMillis(Math.round(songTick * 1000.0 / speed));
        }

        private void cancelSilently() {
            cancel();
        }
    }

    private record NoteTick(Sound sound, float volume, float pitch) { }

    /** Snapshot of a player's personal playback for status displays. */
    public record NowPlaying(SongMetadata song, Duration elapsed, boolean paused, boolean loop, int queued) { }

    private static final class PersonalState {
        private final Deque<SongMetadata> queue = new ArrayDeque<>();
        private SongMetadata current;
        private boolean loop;
        private int volume;

        private PersonalState(int volume) { this.volume = volume; }
    }
}
