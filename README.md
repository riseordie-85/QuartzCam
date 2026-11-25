# Quartz Cam

A clean, private replacement for ad-filled dash cam apps like Viidure. Connect to your dash cam's Wi-Fi and Quartz Cam
lets you browse, preview and save your footage — with a free timeline view.

Part of the Quartz family (Quartz Gallery, Quartz Notes, Quartz Editor…), with the same crystal backgrounds and glass bars.

## Features

- **Timeline** — each day as a 24-hour bar with every drive and SOS clip on it; each drive as a strip of thumbnails.
  Tap anywhere to start playing from that moment.
- **Seamless playback** — a whole drive plays as one video straight off the camera, clip after clip, with the real
  clock time on screen.
- **Save a moment** — mark a start and end while watching and save just the clips around it, or save a clip or a whole drive.
  Downloads continue in the background and land in `Movies/Quartz Cam`.
- **Clips** — every clip on the card, grouped by day, with SOS clips marked. Long-press to select several.
- **Live view** — what the camera sees right now (RTSP).
- **Private** — talks only to the camera over its own Wi-Fi. No ads, no accounts, no tracking.

## Supported cameras

Built for XMSilicon-based dash cams that use the Viidure-style `/app/` API at `192.168.169.1`
(the address can be changed in Settings). Many budget dash cams use this chipset.

| Endpoint | What it does |
| --- | --- |
| `/app/getproductinfo` | Camera model / chipset |
| `/app/getsdinfo` | Card space (MB) |
| `/app/getfilelist?folder=loop\|emr\|event&start=0&end=49` | Files, oldest first (sizes in KB) |
| `/app/getthumbnail?file=<path>` | Thumbnail for a clip |
| `<path>` | The clip itself |
| `/app/getmediainfo` | Live view RTSP address |

## Building

Open in Android Studio and run. Android 13+ (minSdk 33).

## How the Wi-Fi works

The camera's Wi-Fi has no internet, so Android normally keeps sending traffic over mobile data. Quartz Cam binds itself
(only itself) to the camera's network, so every other app keeps using mobile data as usual.
