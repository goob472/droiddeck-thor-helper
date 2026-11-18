# DroidDeck Thor Helper

An Android helper for the **AYN Thor** (Snapdragon 8 Gen 2, Adreno 740). It applies tuned
[DroidDeck](https://github.com/Droid-Deck/DroidDeck) settings to one game at a time. No root needed: it works through DroidDeck's folder via Android's file picker.

## Access: DroidDeck's folder, through Android's file picker (no root)

DroidDeck exposes its app folder to Android's file picker through its own documents provider. The first
time you open this app, it asks you to pick that folder: in the picker, open the ☰ menu, tap
**DroidDeck**, then **Use this folder** and **Allow**. Android remembers the grant.

* Android only gives this app the folder you picked. Every operation is also checked against that
  folder in [`Scope.kt`](app/src/main/java/com/goob/ddhelper/Scope.kt).
* Files are created and written by DroidDeck's own provider, so their owner and permissions are always
  what DroidDeck expects.
* If DroidDeck has a Steam library on shared storage or an SD card, the app asks for that library folder
  separately (**Grant library folder**).
* No root is used. The app can't close DroidDeck itself, so before applying it asks you to close it,
  with a **Force stop DroidDeck** shortcut to Android's App info page. If Steam is running when you apply,
  it can rewrite its config on exit and undo the change.

Each game's **Details** page shows an install check: every Steam library looked at and why the game was or
wasn't found there (missing appmanifest, missing install folder, or missing executable).

## What it changes

It writes the same files DroidDeck's own settings screens write, for the selected game's Steam app ID only:

| What | File (under DroidDeck's `files` folder) |
| --- | --- |
| FEX preset and environment fixes | `game-environment.json` → `games["<appid>"]` (DroidDeck → Games → Game environment) |
| Steam launch options | `linuxfs/root/.local/share/Steam/userdata/<id>/config/localconfig.vdf` |
| Game tweaks (intro movies, `Engine.ini`, `graphics_option.ini`, registry) | the game's install folder and its Proton prefix |

It backs up every file to its own data folder and records each previous value, so **Revert** restores
exactly what you had. DroidDeck picks the changes up the next time you launch the game.

A game's buttons are greyed out until its install is found inside DroidDeck: Steam's
`appmanifest_<appid>.acf`, the folder under `steamapps/common`, and the game's executable in it.
Revert stays available if a game is uninstalled after applying.

## The game list

Each game is a card: cover art (Steam's 600x900 library capsule from its public CDN, cached on the
device, with DroidDeck's local Steam cache as the offline fallback) and title on
the left, two buttons on the right:

* **Apply best settings / Revert settings**: the compatibility and performance profile below.
* **Apply UI mod / Revert UI mod**: makes the game's UI larger and easier to read on the Thor's
  6-inch screen. It is applied and reverted separately from the settings.
* **Apply controller fix / Revert controller fix** (Dead Rising games): installs a community fix mod
  from its author's official GitHub release, after checking its SHA-256.

Tap a game's title for the full list of changes, notes, and what DroidDeck currently has for it.

| Game | UI mod |
| --- | --- |
| Maneater | `ApplicationScale=1.25` under `[/Script/Engine.UserInterfaceSettings]` in `Engine.ini` (Unreal Engine's own UI scale) |
| HITMAN WoA | `SteamDeck=1` for the game only. This turns on HITMAN's built-in small-display mode: interaction prompts at twice the default size, larger HUD and menu text, prompts and objectives scaled ×1.2–1.5 |
| Monster Hunter: World | Not possible from outside the game. Its text and HUD sizes are stored in the encrypted save, so the button explains the in-game options instead |
| Dead Rising 2 / Off the Record | No UI scale exists in these games, so the button explains what helps in-game |

## Games

Games and their profiles live in [`app/src/main/assets/games.json`](app/src/main/assets/games.json).
To add a game, add an entry to that file. No code changes are needed.

### Maneater (629820)

Unreal Engine 4.25, Direct3D 11, **Denuvo**. It hangs on the spinning-shark loading screen. The v2 profile
(after v1 still hung) targets that hang three ways:

* **Audio:** PulseAudio with 100 ms latency (`WINE_AUDIO_DRIVER=pulse`, `PULSE_LATENCY_MSEC=100`) instead of
  DroidDeck's DirectAudio. A ProtonDB report of this exact hang was fixed with `PULSE_LATENCY_MSEC=100`.
* **Threads:** all of FEX's memory-ordering emulation (TSO) on, plus full x87 precision.
* **Loading:** Unreal's async loading thread off (`s.AsyncLoadingThreadEnabled=False` in `Engine.ini`).
* Kept from v1: `FEX_SMCCHECKS=full` and `FEX_HIDEHYPERVISORBIT=1` for Denuvo, `OPENSSL_ia32cap=:~0x20000000`,
  skipped intro MP4s, handheld `Engine.ini` tweaks, and `-USEALLAVAILABLECORES`.

When a newer profile is bundled than the one applied, the game's button reads **Update settings**, which
reverts the old profile and applies the new one.

### Monster Hunter: World (582010)

MT Framework, no Denuvo (removed in 2021). Very CPU-heavy.

* FEX: DroidDeck's Performance + TSO preset (main TSO keeps MT Framework's threads from deadlocking).
* `graphics_option.ini`: `DirectX12Enable=Off` (DXVK is faster than DX12 on Proton), 1280x720,
  30 fps cap, and the heavy effects off. The game creates this file on first launch, so launch once
  before applying.

### HITMAN World of Assassination (1659040)

Glacier 2, Direct3D 12 only (VKD3D-Proton), no Denuvo.

* Launch options `%command% -skip_launcher`.
* `VKD3D_CONFIG=nodxr` with `VKD3D_FEATURE_LEVEL=12_1` hides ray tracing, the main cause of freezes and
  a startup crash under Proton (DroidDeck #1293). `VKD3D_FRAME_RATE=30`
  caps the frame rate for stability.
* FEX: Performance + TSO.
* Render scale is set to 75% through the `SuperSampling` registry value in the prefix's `user.reg`.
  Only values the game already wrote are changed, keeping their type.

### Dead Rising 2 (45740) and Dead Rising 2: Off the Record (45770)

Forge engine, 32-bit Direct3D 9 through DXVK.

* FEX: Performance + TSO.
* Audio crackling: DirectAudio gets a bigger buffer for these games only (`AUDIO_DIRECT_MS=100`,
  `AUDIO_DIRECT_PERIOD_MS=25`, up from 60/20 on the Thor). If it still crackles, DroidDeck's documented
  fallback is `WINE_AUDIO_DRIVER=pulse` + `PULSE_LATENCY_MSEC=60` in its Game environment. No older DXVK is
  pinned: nothing ties it to this crackling, and ClownDR2Fix needs a recent DXVK.
* `DXVK_CONFIG=d3d9.maxFrameRate = 60`: some gameplay runs too fast above 60 FPS.
* Off the Record: `UseMultiCore = false` in `rendersettings.ini` fixes the endless loading screen at Case 7-4.
  Only an existing key is changed, keeping the file's own spacing.
* **Controller fix**: downloads [ClownDR2Fix](https://github.com/Clownacy/ClownDR2Fix) v1.5.0.1 from its
  GitHub release, checks its SHA-256, and copies its 4 files into the game folder, with
  `WINEDLLOVERRIDES=dinput8=n,b` for that game. Without it, the games only detect an official Xbox 360 pad.
  Nothing is bundled with this app, and revert removes exactly the files it added.

## Install and update with Obtainium

In Obtainium, tap **Add app** and enter:

```
https://github.com/goob472/droiddeck-thor-helper
```

No account or token is needed. Obtainium picks up each new release as an update.

Every push to `main` builds a signed APK and publishes it as a GitHub Release (`v1.0.<build>`).

## Testing on an emulator

`fakedroiddeck` is a test-only app that installs as `com.droiddeck.launcher` and exposes a seeded,
fake DroidDeck folder through a documents provider, the same way DroidDeck does. With it, the folder picker,
game detection, apply and revert can all be tested on an emulator. It is never released.

```bash
./gradlew :app:assembleDebug :fakedroiddeck:assembleDebug
```

## Building locally

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Without a `signing.properties` file, the build is signed with the debug key.
