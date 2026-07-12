package dev.customjukebox.song;

import com.xxmicloxx.NoteBlockAPI.model.Layer;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;
import dev.customjukebox.CustomJukeboxPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class SongLibrary {
    private final CustomJukeboxPlugin plugin;
    private final Path root;
    private volatile Map<String, SongMetadata> songs = Map.of();

    public SongLibrary(CustomJukeboxPlugin plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("songs");
    }

    public ScanResult scan() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            plugin.getLogger().severe("Cannot create songs directory: " + exception.getMessage());
            return new ScanResult(0, 1);
        }

        Map<String, SongMetadata> next = new LinkedHashMap<>();
        int invalid = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs"))
                    .sorted().toList();
            for (Path file : files) {
                try {
                    Song decoded = NBSDecoder.parse(file.toFile());
                    if (decoded == null) throw new IllegalArgumentException("decoder returned null");
                    String id = normalize(root.relativize(file).toString().replace('\\', '/'));
                    Set<Integer> instruments = new LinkedHashSet<>();
                    for (Layer layer : decoded.getLayerHashMap().values()) {
                        layer.getNotesAtTicks().values().forEach(note -> instruments.add(Byte.toUnsignedInt(note.getInstrument())));
                    }
                    float tempo = decoded.getSpeed();
                    Duration duration = tempo > 0
                            ? Duration.ofMillis(Math.round(decoded.getLength() * 1000.0 / tempo))
                            : Duration.ZERO;
                    Path relative = root.relativize(file);
                    String folder = relative.getParent() == null ? "" : relative.getParent().toString().replace('\\', '/');
                    next.put(id, new SongMetadata(id, file, decoded.getTitle(), bestAuthor(decoded),
                            tempo, duration, Set.copyOf(instruments), folder));
                } catch (RuntimeException exception) {
                    invalid++;
                    if (plugin.settings().logInvalidSongs()) {
                        plugin.getLogger().warning("Skipping invalid NBS file " + file + ": " + exception.getMessage());
                    }
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().severe("Cannot scan songs directory: " + exception.getMessage());
            invalid++;
        }
        songs = Map.copyOf(next);
        return new ScanResult(next.size(), invalid);
    }

    private static String bestAuthor(Song song) {
        if (song.getAuthor() != null && !song.getAuthor().isBlank()) return song.getAuthor();
        return song.getOriginalAuthor() == null ? "" : song.getOriginalAuthor();
    }

    public Collection<SongMetadata> all() {
        return songs.values().stream().sorted(Comparator.comparing(SongMetadata::id)).toList();
    }

    public Optional<SongMetadata> find(String query) {
        String key = normalize(query);
        SongMetadata exact = songs.get(key);
        if (exact != null) return Optional.of(exact);
        String withoutExtension = key.endsWith(".nbs") ? key.substring(0, key.length() - 4) : key;
        List<SongMetadata> matches = songs.values().stream().filter(song -> {
            String id = song.id().toLowerCase(Locale.ROOT);
            String stem = id.endsWith(".nbs") ? id.substring(0, id.length() - 4) : id;
            return stem.equals(withoutExtension)
                    || song.displayTitle().equalsIgnoreCase(query)
                    || stem.endsWith("/" + withoutExtension);
        }).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public List<SongMetadata> directFolder(String folder) {
        String normalized = folder.replace('\\', '/');
        return songs.values().stream().filter(song -> song.folder().equals(normalized))
                .sorted(Comparator.comparing(SongMetadata::id)).toList();
    }

    public List<String> childFolders(String folder) {
        String normalized = folder.replace('\\', '/');
        String prefix = normalized.isEmpty() ? "" : normalized + "/";
        return songs.values().stream()
                .map(SongMetadata::folder)
                .filter(candidate -> candidate.startsWith(prefix) && !candidate.equals(normalized))
                .map(candidate -> {
                    int separator = candidate.indexOf('/', prefix.length());
                    return separator < 0 ? candidate : candidate.substring(0, separator);
                })
                .distinct()
                .sorted()
                .toList();
    }

    public Song load(SongMetadata metadata) {
        Song song = NBSDecoder.parse(metadata.file().toFile());
        if (song == null) throw new IllegalArgumentException("Could not decode " + metadata.id());
        return song;
    }

    public List<String> folders() {
        return songs.values().stream().map(SongMetadata::folder).distinct().sorted().toList();
    }

    private static String normalize(String input) {
        String result = input.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }

    public record ScanResult(int songs, int invalid) { }
}
