# Sony Bridge

Android app (`dev.ajithv.sonybridge`) that gets photos/videos from a **Sony A7 IV**
(or any Sony camera with FTP transfer) into **immich** automatically — with **no
Creators' App and no immich mobile app** in the loop. Shoot, and the photo shows up
on the immich server (and in the phone gallery) within seconds.

---

## How it works

```
A7 IV  --native FTP auto-transfer (per shot)-->  [ PHONE ]
                                                    ├─ Wi-Fi hotspot (the link the camera joins)
                                                    ├─ embedded FTP server  (0.0.0.0:2121)
                                                    ├─ uploader  --(cellular / Tailscale)-->  immich
                                                    └─ keeps a gallery copy in DCIM/SonyBridge
```

- The **phone hosts a Wi-Fi hotspot**; the **camera is the FTP client**. The camera's
  built-in *FTP Transfer → Auto FTP Transfer / Auto Trans When Shot* pushes every shot
  to the phone the instant the shutter fires (native, reliable, retries on reconnect).
- The app runs a foreground service with an **embedded FTP server** (Apache
  `ftpserver-core`, the same stack `primitive ftpd` uses). A file is uploaded only once
  **complete** (FTP STOR-complete hook + size-stable backstop — no half-written uploads).
- Each completed file is **uploaded to immich** into a **date-named album**
  (`YYYY-MM-DD`), then a **gallery-visible copy is written to `DCIM/SonyBridge`** via
  MediaStore (sibling of the Creators' App `DCIM/CA_IMAGES`). The working copy is removed
  after it's safely in immich + the gallery — originals are never deleted.
- Because uploads are the *phone's own* traffic, they work over **cellular → Tailscale**
  to a self-hosted immich while the hotspot/Wi-Fi radio is busy serving the camera.

## Features

- Camera FTP → immich (date album) + `DCIM/SonyBridge` gallery copy.
- Live **thumbnail** of the current photo + **camera-connection** status in the app
  (tap the thumbnail → opens that photo in the **immich app**).
- **Per-photo upload notification**: large (un-rounded) `BigPicture` preview; tap →
  opens the exact photo in the immich **web viewer** (`/photos/<id>`).
- **Loud red alert** if the hotspot IP the camera uploads to changes.
- Defaults: **JPEG/HEIC + video** upload, **RAW (.ARW) optional** (Settings toggle),
  **files never deleted**.
- Survives reboot (persisted enabled-flag + `BOOT_COMPLETED`), keeps running under
  OnePlus's app-killer when battery-optimization is disabled (prompted on Start).

## Build

- Toolchain: **AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 27, arm64**, JDK **17**.
- Create a **gitignored** `app/secrets.properties` (required — the build bakes no
  default URL/key; both can also be set later in the app's Settings screen):
  ```
  immich_url=http://<immich-host>:2283
  immich_key=<immich API key>
  ```
  The API key needs these scopes: `asset.upload`, `asset.view`, `album.read`,
  `album.create`, `albumAsset.create`.
- Build:
  ```
  JAVA_HOME=<jdk17> gradle :app:assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  (No gradle wrapper is committed; use a local Gradle 8.7+.)

## Setup

**Phone**
1. Turn on the phone's **Wi-Fi hotspot** (set a stable SSID/password once).
2. Open Sony Bridge → **Start** → grant the **notification** permission (and the
   battery-optimization exemption so the service isn't frozen).
3. The screen shows `Server: <ip>` — the address to put in the camera.

**Camera (A7 IV): MENU → Network → FTP Transfer**
- Server: Host = the `Server` IP shown in the app, Port = **2121**, Directory = `/`,
  User = **sony**, Pass = **sony**, Secure (FTPS) = **OFF**, **Passive = ON**.
- Join the **camera to the phone's hotspot SSID**.
- Enable **Auto FTP Transfer** + **Auto Trans When Shot**.

## Known limitations / notes

- **Hotspot IP:** on some phones (e.g. OnePlus/ColorOS) the hotspot is a `10.x` SoftAP
  (not a fixed `192.168.x.1`) and the subnet can rotate when the hotspot is stopped/
  started or on reboot. Keep the hotspot running for a stable IP; the app raises the
  IP-change alert otherwise. A DHCP reservation on an upstream router (if bridged) pins it.
- **Notification tap uses the immich *web* viewer**, not the app: immich's per-asset
  app deep link (`immich://my.immich.app/photos/<id>`) only lands on a photo already
  **synced** into the app's local timeline — a just-uploaded photo isn't, so it opens
  the grid. The web viewer loads any asset by id immediately. (The in-app thumbnail,
  viewed later once synced, uses the app deep link.)
- **immich web + `?apiKey=`:** immich accepts the key as a query param, but a browser's
  existing immich **session cookie is checked first** — a stale cookie yields
  `Invalid user token`. The web viewer needs a valid immich login (re-login once if stale).
- **Storage:** uses MediaStore (`RELATIVE_PATH=DCIM/SonyBridge`) so **no storage
  permission** is needed on Android 10+. (`MANAGE_EXTERNAL_STORAGE` was avoided — it
  can't even be granted via adb on some OnePlus builds.)

## Project layout

- `app/src/main/java/dev/ajithv/sonybridge/`
  - `BridgeService.kt` — foreground service, FTP server, camera-connection tracking, IP watcher.
  - `Uploader.kt` — stable-file detection, immich upload + date album, MediaStore keep-copy.
  - `ImmichClient.kt` — immich REST (upload, ensure/append album).
  - `Notifier.kt` — upload + IP-change notifications.
  - `Net.kt` — hotspot/Wi-Fi IP detection. `Prefs.kt` — encrypted settings.
  - `MainActivity.kt` / `SettingsActivity.kt` — UI.
