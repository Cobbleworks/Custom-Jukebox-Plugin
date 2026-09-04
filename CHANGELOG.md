# Changelog

Notable changes to Custom Jukebox are documented in GitHub release notes.

## [Unreleased]

### Added

- Added `/jukebox disc <player> <song>` for creating persistent song-bound records.
- Added randomized intact music-disc artwork while excluding the cracked `11` record and disc fragments.
- Added physical jukebox playback for custom records, including player interaction and hopper transfers.
- Added playback restoration when loaded jukebox chunks return or the server restarts.
- Added cleanup when a custom-record jukebox is broken or destroyed by an explosion.

### Changed

- Shared world-source playback now supports signs and physical jukebox blocks through the same source limit.

### Security

- Custom record identity and song paths are stored in persistent item data instead of trusting display names or disc materials.
