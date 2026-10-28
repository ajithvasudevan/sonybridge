# Sony Bridge

Android app (`dev.ajithv.sonybridge`) that bridges a **Sony A7 IV** to **immich**
with no Creators' App / immich-mobile-app involvement.

The phone runs a Wi-Fi hotspot + an embedded **FTP server**; the camera's native
*FTP auto-transfer during shooting* pushes each shot to the phone. The app then:

- uploads each completed photo/video to immich (into a date-named album), over
  whatever route the phone has (e.g. Tailscale to a self-hosted server);
- keeps a gallery-visible copy in `DCIM/SonyBridge` (via MediaStore);
- shows a live thumbnail + camera-connection status, a per-photo upload
  notification (tap -> immich), and a loud alert if the hotspot IP changes.

Defaults: JPEG/HEIC + video upload, RAW optional, files never deleted.

## Build
- `app/secrets.properties` (gitignored): `immich_url=...` and `immich_key=...`
  (an immich API key with `asset.upload`, `asset.view`, `album.read`,
  `album.create`, `albumAsset.create`).
- `JAVA_HOME=<jdk17> gradle :app:assembleDebug` (AGP 8.5.2, Kotlin 1.9.24,
  compileSdk 34, minSdk 27).
