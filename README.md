# My Video Editor

A small multi-clip video editor for Android — import clips, reorder and trim
them on a timeline, preview the result, and export one stitched MP4. Kotlin +
Jetpack Compose + **Media3**.

This is a clean-room learning project inspired by CapCut's *core loop*, not a
clone of it. Where CapCut ships a huge native C++ engine (an FFmpeg fork, a GPU
render graph, ML models), this app leans on Google's **Media3 Transformer** to do
the heavy lifting — which is the realistic, supported way to build a video editor
on Android.

## What it does

- Add multiple videos via the system photo picker (no storage permission needed)
- A **timeline** of clips you can **reorder** (up/down) and **remove**
- **Trim** each clip with an in/out range slider (non-destructive — the source
  file is never changed)
- **Preview** the stitched, trimmed result with a real player (ExoPlayer)
- **Export** everything into a single MP4, with a live progress percentage
- The export is saved to your **gallery** (Movies/MyVideoEditor, Android 10+)
  and can be **shared** anywhere

## How it maps to a real editor (and to CapCut)

| Concept | CapCut | This app |
|---|---|---|
| Timeline / project model | native C++ edit graph | the `List<Clip>` in `EditorViewModel` |
| Non-destructive trim | engine in/out points | `MediaItem.ClippingConfiguration` per clip |
| Preview playback | TTMPlayer (native) | ExoPlayer with a clipped playlist |
| Stitch + encode | FFmpeg fork + GPU | Media3 `Transformer` (`Composition`) |
| Export target | gallery / share | MediaStore + FileProvider |

## File map

```
app/src/main/java/com/example/videoeditor/
  Clip.kt              one timeline clip (uri, duration, trim in/out, thumbnail)
  EditorViewModel.kt   timeline state: add / reorder / trim / remove / export
  VideoExporter.kt     Media3 Transformer: clip -> EditedMediaItem -> Composition
  Output.kt            save to gallery (MediaStore) + share (FileProvider)
  MainActivity.kt      Compose UI: preview, timeline, trim slider, export
```

## How to build

Open in **Android Studio**, let Gradle sync, Run on a device/emulator running
**Android 8.0 (API 26)+**. Or from a terminal: `./gradlew assembleDebug`.

### Cloud build (no local setup)

`.github/workflows/build.yml` builds a debug APK on GitHub's servers on every
push and attaches it to the repo's **Releases** page ("latest" prerelease).

## Notes & next steps

- Media3 Transformer transcodes as needed to make differently-encoded clips line
  up, so export time scales with total video length.
- The preview reloads when you change trims — fine for now; a polished editor
  would seek instead of reload.
- Natural next features: a background music track, text/caption overlays,
  per-clip filters (Media3 `Effects`), transitions, and drag-to-reorder.
