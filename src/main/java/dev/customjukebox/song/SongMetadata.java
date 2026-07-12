package dev.customjukebox.song;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public record SongMetadata(String id, Path file, String title, String author,
                           float tempo, Duration duration, Set<Integer> instruments,
                           String folder) {
    public String displayTitle() {
        return title == null || title.isBlank() ? id : title;
    }
}
