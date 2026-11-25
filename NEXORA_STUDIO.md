# NEXORA Studio 1.2 — Reborn beta

Independent Canvas overlay and native settings UI, built on the Android event and media integrations from Expressive Cutout (GPL-3.0).

## New in Studio
- Category-based settings, real geometry preview, slim preset, configurable motion and durations.
- Three-second default expanded phase; incoming calls bypass collapse and navigation until the platform reports a state change.
- Shared-element album cover morph, five-bar waveform, real media controls and progress.
- Optional event queue, focus mode, notification privacy, per-app exclusions, active notification inbox, non-reply notification actions.
- Live download/upload progress where Android exposes it; optional persistent battery percentage.
- Quick Controls: media volume, Android quick settings, inbox.
- Accessibility and notification access status, optional phone permission, existing Shizuku integration.
- Unit tests for timing, ringing exception, minimum height and motion curves.

## Verification
Use the nexora-reborn branch. Download the NEXORA artifact and extract NEXORA.apk. The build must pass before installation.
Manually check camera alignment, motion, 3-second collapse, music controls, notification taps/actions, progress updates, incoming/answered calls, screen off, app filtering, focus mode, and Shizuku restoration.
120 fps and device compatibility require measurement; Face Unlock displays confirmed device unlock, not private biometric scanning states.
Android notifications are retained; system heads-up replacement is not guaranteed. Legacy advanced settings do not all apply to the new renderer.
