# Flip8 FlexWindow via Shizuku

An experimental Android controller that keeps a custom looping animation on the
Samsung Galaxy Z Flip8 FlexWindow while the phone is unfolded or half-open,
without root, Camera, Accessibility Service, or Notification Access.

![Ame example running on the FlexWindow](docs/demo.gif)

## Tested configuration

- Galaxy Z Flip8 `SM-F776B`
- Android 17 / One UI 9.0
- Shizuku 13.x
- FlexWindow display ID `1`
- Samsung device states `2` (`HALF_OPENED`), `3` (`OPENED`), and `4`
  (`CONCURRENT_INNER_DEFAULT`)

These IDs and behaviors are firmware-specific. The app intentionally targets
the tested Flip8 configuration and should be treated as experimental on other
models or One UI releases.

## What it does

- Requests Samsung's concurrent-display state through a Shizuku UserService.
- launches a fullscreen `TextureView` video activity on display 1 while the
  inner display remains usable.
- Keeps working fully unfolded and around the 90-degree half-open posture.
- Rotates the media in 90-degree steps using the phone's absolute orientation,
  with a short stability delay to prevent jitter.
- Pauses for lock/screen-off, Camera/FlipShot, and Battery Saver, then restores
  the animation when the phone becomes eligible again.
- Provides a manual **Stop and restore** action and restores Samsung's normal
  state after errors or Shizuku loss.

## Requirements

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku).
2. Start Shizuku using Wireless debugging or ADB. Root is not required.
3. Install this app and grant its Shizuku permission.
4. Open **Flip8 FlexWindow** and tap **Start FlexWindow**.

Shizuku must be started again after every phone reboot. After Shizuku is
running, USB is not required.

## Use your own animation

Replace:

```text
app/src/main/res/raw/example_cover_animation.mp4
```

Recommended encoding for the Flip8 FlexWindow:

- 948 x 1048
- H.264 MP4
- `yuv420p`
- silent
- short seamless loop

Keep the filename unchanged, or update `R.raw.example_cover_animation` in
`ProofActivity.java`.

The bundled Ame media is an example and is excluded from the MIT license. See
[MEDIA-NOTICE.md](MEDIA-NOTICE.md).

## Build

Install Android SDK 37 and JDK 17, then run:

```bash
./gradlew assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Recovery

Use **Stop and restore** first. If the controller or display becomes stuck:

```bash
adb shell am force-stop com.ameprobe.flexshizuku
adb shell cmd device_state state 3
adb shell cmd device_state state reset
```

A reboot is the final fallback and returns Samsung's normal display state.

## Current limitations

- No automatic startup after reboot; Shizuku and the controller must be started
  manually.
- The implementation depends on Samsung's current hidden device-state behavior.
- The outer display is visual-only for this use case; interaction is not a goal.
- Battery and OLED wear have not been characterized for long sessions.

## License

The source code is available under the [MIT License](LICENSE). The bundled
example media is excluded; see [MEDIA-NOTICE.md](MEDIA-NOTICE.md).
