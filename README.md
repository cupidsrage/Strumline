# Strumline — ukulele chord sheets for Android

Reads chord sheets the way Ultimate Guitar does: chords above the lyrics, scrolling by itself while you play.

- **Listen mode**: tap Listen and play. The app hears which chord you strum, highlights it, and keeps
  your place on screen as you move through the song. Tap any chord to jump there; adjust Sensitivity if it
  moves on too early or too late.
- **Auto-scroll** with 15 speed levels. Tap the sheet to start or pause; you can still drag to scroll by hand.
- **Transpose** up or down; every chord name and diagram updates.
- **Ukulele chord diagrams** (G C E A) for every chord in the song along the bottom; tap any chord in the sheet for a big diagram.
- **Text size** control, light and dark mode, and the screen stays on.
- **Find songs on any site**: search opens Strumline's built-in browser. Pick any chord site, open a song,
  and tap **Add to Strumline** to bring it into the player. Links and Share → Strumline open there too.
- **Or paste the chords**: copy a sheet from Ultimate Guitar or any site and paste it in. Chords-over-lyrics, [Verse]/[Chorus] labels,
  tab blocks and ChordPro (`[C]words [G]like this`) are all understood. Songs are saved on the phone.

## Get the APK

**Option A, no install needed (GitHub Actions):** push this folder to a GitHub repo. The
`Build APK` workflow runs automatically; open the run under the Actions tab and download
`strumline-apk`. Unzip it and open `app-debug.apk` on your phone (allow "Install unknown apps"
when asked).

**Option B, Android Studio:** File → Open → this folder, let Gradle sync, then Run ▶ with your
phone plugged in (USB debugging on), or Build → Build APK(s).

**Option C, command line** (Android SDK installed, `ANDROID_HOME` set):
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

## Changing the app

The whole app is one file: `app/src/main/assets/index.html`. Edit it and rebuild.

## Updates

Every push to `main` builds a signed release (tagged `build-N`). When the app opens, it checks
for a newer release and offers to install it. This needs two repository secrets
(Settings → Secrets and variables → Actions): `STRUMLINE_KEYSTORE` (the signing key, base64) and
`STRUMLINE_KS_PASS` (its password). Keep a copy of both: without that exact key, Android won't
accept updates over the installed app.
