# Ahmed Reaction Studio

Clean Kotlin Android project rebuilt from the recovered product idea in `New Kotlin.zip`.

## What this project is

This repository now contains a normal Android/Kotlin project structure:

- `settings.gradle.kts`
- root `build.gradle.kts`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- Kotlin source under `app/src/main/java/com/rehman/ahmedreactionstudio`

The decompiled dump was used only as an audit/reference and is intentionally not committed.

## Product direction resolved

The app is a local-first reaction/PiP studio with a clear adaptive editor layout:

- Portrait: top bar, large canvas, bottom tabbed control panel, transport bar.
- Landscape: visible labeled tool rail, central canvas, fixed-width right inspector, transport bar.
- Panels are named explicitly: Sources, Mixer, Properties, Effects, Export.
- The confusing `X` tab from the recovered dump is removed.
- Settings opens project settings; diagnostics is a separate explicit option.

## Source model

Layers now include visual and audio-only sources:

- Local Video
- Camera
- Screen Recording
- Image
- Text
- Background Music
- External Mic

This resolves the previous mismatch between the source/mixer mockups and the recovered `LayerType` model.

## Current implementation status

Implemented:

- Splash screen
- Project list/create/rename/delete
- Local JSON project storage
- Adaptive editor shell
- Canvas preview with draggable visual layers
- Sources panel
- Mixer panel with mute/solo/volume
- Properties panel
- Effects panel with basic transforms
- Diagnostics screen
- SAF media picker that copies video, image and audio into app storage
- Camera2 live PiP preview, flip, and take recording
- MediaProjection foreground service for screen capture
- External mic takes
- Native MediaPlayer preview clock with mixer mute/solo/volume
- H.264/AAC composition export at 720p or 1080p
- MediaStore publishing to `Movies/AhmedReactionStudio`

## Capture / export flow

1. Import a main video (and optional image, text, music).
2. Keep the camera PiP live, or add a screen/mic source.
3. Press **Record** to capture live sources. Imported media plays during the take so you can react.
4. Press **Play** to preview the composition with mixer levels.
5. Export 720p or 1080p — layers are composited, audio is mixed, and the MP4 is published to the gallery.

## Next milestone

- GPU compositor for faster export
- Timeline scrubber and keyframed transforms
- Waveforms in the mixer

## Build

Open in Android Studio, let Gradle sync, then run the `app` configuration.

The project uses:

- Android Gradle Plugin 8.7.3
- Kotlin Android plugin 2.0.21
- compileSdk 35
- minSdk 26
- targetSdk 35
- Framework APIs only (no AndroidX)
