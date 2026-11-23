# Expressive Cutout Xtreme — prototype v1

Built on the project `dev` branch, preserving the original Face Unlock animation.

## New capabilities
- `Gestures > Normal > Tap > Notification inbox` expands the island to display the listener's active notifications.
- `Gestures > Normal AND Expanded > Swipe left/right > Next/Previous notification` browses them without dismissing notifications.
- Tap an inbox row to send that notification's content intent to Android.
- A camera-clear top band keeps inbox text below the selfie camera rather than across the lens.
- Inbox is disabled while the keyguard is locked. It displays app name + notification title only (no message body), stays in memory, and reads no screen content.
- The original gestures and UI remain selectable.

## Testing
1. **Build with GitHub Actions** (`./gradlew assembleDebug`). A local Android SDK is not provided here, so the source is not yet compilation-verified.
2. The debug build uses `com.ekoehler.expressivecutout.xtreme`, so it installs alongside the original. Disable the original overlay before granting the Xtreme overlay access to avoid two islands at once. New GitHub-hosted debug builds may be signed with different temporary keys; uninstall the previous Xtreme test build before installing a newer debug APK if Android reports a signature mismatch.
3. Enable the accessibility and notification listener permissions as usual.
4. Change the Normal tap action to Notification inbox; change left/right swipe actions to Next/Previous notification.
5. To tap the island even when idle, enable `Shows when empty`, turn off `Show SWE cutout` for an invisible touch target, and select `On click > Notification inbox`. The island cannot open when there is no active overlay/touch target.
6. Test with multiple ordinary notifications and with screen locked/unlocked. Face Unlock triggers should continue using the original code.

## Boundaries
- This is an experimental patch, not a verified release APK. Test on your Pixel 8 Pro before trusting it with notifications.
- The inbox lists up to 40 surfaced notifications. Ongoing/system notifications and messages snoozed out of the shade may be absent, matching the project's existing listener filtering.
- This prototype does not change Shizuku start-on-boot restrictions or guarantee persistent notification-icon hiding.
