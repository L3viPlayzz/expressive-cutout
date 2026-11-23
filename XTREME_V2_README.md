# Expressive Cutout Xtreme v2.0 — Hybrid

This is an incremental update to the existing Android app, not a replacement project.

## Included in this revision
- Hybrid blue/violet animated five-bar indicator in the expanded music island. The bars are decorative and react to playback state; they are not a microphone-based spectrum analyzer.
- Music playback progress is enabled by default for new music settings. Existing user choices are respected.
- Clear setup instructions and Hybrid styling on the Music settings screen.
- All original music infrastructure remains: Android media sessions, album art, track and artist, previous/play-pause/next, optional album background, mini player, music visibility settings.
- Xtreme v1 notification inbox and gestures remain intact.
- Original Face Unlock and other existing app features are unchanged.

## To test
1. Build the `dev` branch and install its debug APK.
2. Grant the app Notification Access and enable the app's accessibility service.
3. Check that the Music tile is enabled in Dynamic Tiles settings.
4. Start Spotify or YouTube Music, then expand the island.
5. Check playback controls, album artwork, progress, and the blue/violet animated bars.
6. Verify Face Unlock and notification inbox behavior.

## Notes
- Some players do not expose track progress or all transport actions through Android MediaSession.
- Existing settings stored in DataStore override new defaults.
- This is the first v2 Hybrid iteration, not a claim that every requested feature (volume, brightness, timer and charging redesign) is complete.
