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
- Export/validation placeholder
- Diagnostics screen

Next milestone:

- Attach real media picker/import paths
- Camera and screen capture services
- Native preview playback and export pipeline
- MediaStore publishing

## Build

Open in Android Studio, let Gradle sync, then run the `app` configuration.

The project uses:

- Android Gradle Plugin 8.7.3
- Kotlin Android plugin 2.0.21
- compileSdk 35
- minSdk 26
- targetSdk 35
