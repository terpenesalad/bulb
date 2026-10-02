# Halo

A dark, premium-feeling Android app for controlling a Mirabella Genio (Tuya) RGB bulb directly over your home Wi-Fi.

## Install

1. Open the [latest release](../../releases/latest) on your phone.
2. Download `Halo-x.y.z.apk` and open it. Android will ask you to allow installs from your browser the first time.
3. New versions install over the top and keep your settings.

Every push to `main` builds a new APK automatically (see the Actions tab).

## First-time setup

Halo talks to the bulb locally, which needs the bulb's secret **local key**. You get it once:

1. **Move the bulb to Smart Life.** Remove it from the Genio app and add it in the *Smart Life* app. (Genio is a rebranded Tuya app that can't be linked to Tuya's developer site; Smart Life can.)
2. **Create a free Tuya developer account** at [platform.tuya.com](https://platform.tuya.com) → *Cloud → Development → Create Cloud Project*. Pick "Smart Home" and tick every data centre.
3. In the project: **Devices → Link App Account → Add App Account**, and scan the QR code with Smart Life (*Me* tab → scan icon).
4. Copy the **Access ID** and **Access Secret** from the project's *Overview* page.
5. In Halo, paste them into step 2 and tap **Fetch my lights**. Halo finds the bulb on your Wi-Fi, fills everything in and connects.

The secret isn't stored. After setup nothing goes through the cloud. If you ever re-pair the bulb, its key changes: fetch it again from *Settings → Lights*.

## What's in it

- **Control:** power, brightness, warmth (2,700–6,500K), full colour wheel with saturation, saved colour swatches
- **Presets and scenes:** save your own; built-in moods; animated "living scenes" that run on the bulb itself
- **Effects:** breathe, candle, spectrum, tide, party, storm, and music sync (uses the mic)
- **Schedules:** sunrise wake-up fades, bedtime wind-down, on/off at a time or at sunrise/sunset with an offset, any days
- **Sleep timer** that runs on the bulb, even with your phone away
- **Home-screen widget** and **Quick Settings tile**
- **Themes:** AMOLED black, Midnight, Graphite; accent colours or "match my light"; glow, animation and haptics controls
- Works with protocol 3.3, 3.4 and 3.5 bulbs, old and new data layouts
- **Diagnostics** log in Settings if anything misbehaves

## Development

- Android app: Kotlin + Jetpack Compose (`app/`)
- Tuya LAN protocol: `app/src/main/java/app/halo/tuya/` (no dependencies; unit-tested)
- `tools/fake_bulb.py` simulates a bulb for testing without hardware (`pip install tinytuya`)
- `tools/gen_vectors.py` regenerates protocol test vectors from tinytuya

Build locally with `./gradlew assembleRelease`.
