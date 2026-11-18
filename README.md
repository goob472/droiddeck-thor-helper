# DroidDeck Thor Helper

An Android helper for the **AYN Thor** (Snapdragon 8 Gen 2, Adreno 740). It applies tuned
[DroidDeck](https://github.com/Droid-Deck/DroidDeck) settings to one game at a time. It needs root.

## What it changes

It writes the same files DroidDeck's own settings screens write, scoped to the selected game's Steam app ID:

| What | File (under `/data/data/com.droiddeck.launcher/files`) |
| --- | --- |
| FEX preset and environment fixes | `game-environment.json` → `games["<appid>"]` (DroidDeck → Games → Game environment) |
| Steam launch options | `linuxfs/root/.local/share/Steam/userdata/<id>/config/localconfig.vdf` |
| Game tweaks (intro movies, `Engine.ini`) | the game's install folder and its Proton prefix |

Before writing, it force-closes DroidDeck so a running session cannot overwrite the changes. It
backs up every file to its own data folder and records each previous value, so **Revert** restores
exactly what you had. DroidDeck picks the changes up the next time you launch the game.

## The game list

Each game is a card: cover art (Steam's 600x900 library capsule from its public CDN, cached on the
device, with DroidDeck's local Steam cache as the offline fallback) and title on
the left, two buttons on the right:

* **Apply best settings / Revert settings**: the compatibility and performance profile below.
* **Apply UI mod / Revert UI mod**: makes the game's UI larger and easier to read on the Thor's
  6-inch screen. It is applied and reverted separately from the settings.

Tap a game's title for the full list of changes, notes, and what DroidDeck currently has for it.

| Game | UI mod |
| --- | --- |
| Maneater | `ApplicationScale=1.25` under `[/Script/Engine.UserInterfaceSettings]` in `Engine.ini` (Unreal Engine's own UI scale) |
| HITMAN WoA | `SteamDeck=1` for the game only. This turns on HITMAN's built-in small-display mode: interaction prompts at twice the default size, larger HUD and menu text, prompts and objectives scaled ×1.2–1.5 |
| Monster Hunter: World | Not possible from outside the game. Its text and HUD sizes are stored in the encrypted save, so the button explains the in-game options instead |

## Games

Games and their profiles live in [`app/src/main/assets/games.json`](app/src/main/assets/games.json).
To add a game, add an entry to that file. No code changes are needed.

### Maneater (629820)

Unreal Engine 4.25, Direct3D 11, **Denuvo**. It hangs on the spinning-shark loading screen.

* All profiles set `FEX_SMCCHECKS=full` and `FEX_HIDEHYPERVISORBIT=1`, the two settings DroidDeck's Denuvo preset uses.
  Denuvo relies on self-modifying code, and FEX's faster presets skip the checks that catch it.
* `OPENSSL_ia32cap=:~0x20000000` stops the game's old bundled OpenSSL from using the CPU's SHA
  extensions. That is the Steam community fix for Maneater freezing or crashing at startup.
* FEX: main TSO on (UE4's loading threads can deadlock without it), with vector and memcpy TSO off and
  multiblock on for speed. This combines DroidDeck's Denuvo preset with its Performance + TSO preset.
* Also skips the intro MP4s (renamed, not deleted), applies handheld `Engine.ini` tweaks (no motion blur,
  depth of field, chromatic aberration or lens flares, and lighter volumetric fog), and adds
  `-USEALLAVAILABLECORES` to the Steam launch options.


### Monster Hunter: World (582010)

MT Framework, no Denuvo (removed in 2021). Very CPU-heavy.

* FEX: DroidDeck's Performance + TSO preset (main TSO keeps MT Framework's threads from deadlocking).
* `graphics_option.ini`: `DirectX12Enable=Off` (DXVK is faster than DX12 on Proton), 1280x720,
  30 fps cap, and the heavy effects off. The game creates this file on first launch, so launch once
  before applying.

### HITMAN World of Assassination (1659040)

Glacier 2, Direct3D 12 only (VKD3D-Proton), no Denuvo.

* Launch options `%command% -skip_launcher`.
* `VKD3D_CONFIG=nodxr` hides ray tracing, the main cause of freezes under Proton. `VKD3D_FRAME_RATE=30`
  caps the frame rate for stability.
* FEX: Performance + TSO.
* Render scale is set to 75% through the `SuperSampling` registry value in the prefix's `user.reg`.
  Only values the game already wrote are changed, keeping their type.

## Install and update with Obtainium

The repository is private, so Obtainium needs a GitHub token:

1. On GitHub: **Settings → Developer settings → Fine-grained tokens → Generate new token**.
   Under repository access, pick only this repository. Under permissions, set **Contents: Read-only**.
2. In Obtainium: **Settings → GitHub → Personal access token**, and paste the token.
3. **Add app** with the URL `https://github.com/goob472/droiddeck-thor-helper`.

Every push to `main` builds a signed APK and publishes it as a GitHub Release (`v1.0.<build>`).

## Building locally

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Without a `signing.properties` file, the build is signed with the debug key.
