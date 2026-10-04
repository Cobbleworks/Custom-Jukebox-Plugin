# Changelog

Notable changes to Custom Jukebox are documented in GitHub release notes.

## [Unreleased]

### Added

- Added `/jukebox disc <player> <song>` for creating persistent song-bound records.
- Added randomized intact music-disc artwork while excluding the cracked `11` record and disc fragments.
- Added physical jukebox playback for custom records, including player interaction and hopper transfers.
- Added playback restoration when loaded jukebox chunks return or the server restarts.
- Added cleanup when a custom-record jukebox is broken or destroyed by an explosion.
- Added `redstone.trigger-radius` (default `2`): jukebox signs react to redstone activity near them, not only when the sign itself is powered. One switch can trigger several signs.
- Redstone blocks placed or broken near a sign now update it.
- Added a live now-playing control with a progress bar, pause/resume, per-player volume, shuffle, and shift-click queueing to the song browser.
- Added page indicators, a separate back button, folder titles, song counts on folders, an empty-library hint, and click sounds to the browser.
- Added unsaved-change hints, redstone mode descriptions, and right-click to cycle backwards to the sign editor.

### Changed

- Shared world-source playback now supports signs and physical jukebox blocks through the same source limit.
- Plugin messages share a coloured prefix; `/jukebox help` and `/jukebox list-signs` entries are clickable.
- GUI items use non-italic coloured names and no longer show vanilla disc track names.
- Jukebox signs show `[Jukebox]` in bold and summarize loop and redstone mode on the third line.
- The sign editor opens in the folder of the sign's current song.
- Players without sign permission see which song a sign plays instead of an error.

### Fixed

- Personal playback stops when a player quits; looping songs previously kept a playback slot.
- The action bar keeps showing the song while playback is paused.
- Left-clicking a configured sign no longer opens the editor or prevents breaking it.
- Players without `customjukebox.sign.place` can no longer change a jukebox sign's text or settings.
- Sign physics checks no longer read a block state for every physics update.
- Configured signs missing from `signs.yml` are registered again when their chunk loads.

### Security

- Custom record identity and song paths are stored in persistent item data instead of trusting display names or disc materials.
