# Verification

The desktop suite generates local synthetic footage and covers rectangle parsing, automatic static HUD suggestions, unchanged unmasked pixels before encoding, translated neighboring-frame fill, fractional-rate frame seeking, batch output, AAC preservation, original integrity, and reuse of completed chunks after a missing chunk.

Android unit tests cover pause/resume timestamp accounting and static HUD suggestions. The Android CI workflow runs tests, lint, and debug packaging.

## Physical device and accelerator checks

Use owned gameplay and record device, OS, codec, settings, and test conditions. The checks below are intended for hardware validation.

| Area | Verification |
| --- | --- |
| Consent and notification | Cancel projection, start again, revoke projection from Android, deny notification permission, and stop from the app. Exercise single-app and whole-display options on Android 14+. |
| Capture and recovery | On Android 10, 13 and 15, record each supported codec, pause and resume repeatedly, check positive video duration, and inspect a recovery file after an interrupted session. |
| Audio sync | Record an on-screen signal with an associated audio impulse and a second impulse after pausing. Compare AAC and video timestamps with ffprobe. Test game, mic and mixed modes, including games that block playback capture. |
| Device performance | Record active gameplay for an extended session across multiple profiles. Use ffprobe packet timestamps to calculate delivered FPS and gaps. Record memory, thermal changes, dropped frames and battery change against an equivalent session without capture. |
| Storage | Exercise low-storage cutoff, custom bitrate validation, local deletion, and transfer a saved copy. |
| Editor | Import portrait and landscape files and files with rotation metadata. Confirm normalized masks align in exported frames and ordinary exports retain source audio. |
| CUDA and MPS | Run the same test footage on CPU, Apple MPS, one T4, and two T4 GPUs. Capture wall time, maximum per-device memory use, and exported frame counts. Interrupt and resume after copying checkpoints. |
| Reconstruction | Compare to ground truth using clean footage with synthetic overlays. Measure mask precision and recall, masked error, flicker, and visible artifacts, including at chunk boundaries and scene cuts. |
