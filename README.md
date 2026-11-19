# School Prayer (Android)

A simple app for school assembly: keep a list of prayer/anthem audio files, search them, and tap to play.

## Features
- **School name:** the first time the app opens it asks for the school name, which is then shown at the top of the home screen. Tap the name (or ⋮ → Change school name) to edit it.
- **Favourites:** tap the ☆ next to a prayer to make it a favourite; favourites always stay at the top of the list.
- **Built-in tracks:** Jan Gan Man, Vande Mataram, Mujhe is Duniya me laaya and Subah Savere lete tera naam ship inside the app and appear on first launch.
- **Add prayer files:** tap **+** to pick one or more audio files (MP3, M4A, WAV and similar) from the phone. They stay in the list after the app restarts.
- **Searchable list:** type in the search box to filter by name.
- **Player:** tap a prayer to play it; tap it again to pause or resume. The bottom bar has play/pause, stop and a seek bar.
- **Manage:** use the ⋮ button (or long-press) to **Rename** or **Remove** an entry. Removing only takes it off the list; it doesn't delete the file.
- The screen stays on while a prayer is playing.
- **Silent mode while playing:** switch it on from the home screen. When a prayer starts, the phone goes into Do Not Disturb (calls, notifications, alarms and system sounds silenced; the prayer audio still plays). When the prayer finishes or is stopped, the phone's previous setting is put back. The first time, Android asks you to allow School Prayer under Do Not Disturb access. If the phone was already in Do Not Disturb, the app leaves it alone.
- **Bluetooth speaker:** tap the Bluetooth icon at the top to turn Bluetooth on, see paired devices, **Search** for nearby speakers, and tap one to pair and connect. The connected speaker shows in a "Playing through …" card, and as a yellow chip on the main screen.
  - Android has no official way for apps to connect an already-paired speaker. The app tries anyway (works on many phones); if the phone blocks it, it offers a one-tap shortcut to Bluetooth settings.
  - On Android 11 and older, Location must be switched on to search for nearby devices.

Requires Android 8.0 or newer.

## Build
**Android Studio:** File → Open → select this folder → wait for Gradle sync → Run ▶ (or Build → Build APK(s)).

**GitHub (no Android Studio needed):** push this folder to a GitHub repo. The workflow in `.github/workflows/build-apk.yml` builds a debug APK on every push; download it from the run's **Artifacts** section.

## Adding more built-in tracks
Put the MP3 in `app/src/main/res/raw/` (use lowercase letters, digits and underscores only, e.g. `morning_prayer.mp3`), then add a line to `builtIns()` in `TrackStore.kt`:
```kotlin
Track(rawUri(R.raw.morning_prayer), "Morning Prayer"),
```
Built-ins load on first launch only. To see a new one on a phone that already has the app, clear the app's data or reinstall.
