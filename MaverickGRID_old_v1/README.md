# MaverickGRID – Android app

Intelligent dead reckoning for SIH26168. Runs the MaverickGRID engine on the phone at 10 Hz:
GNSS + INS fusion while GNSS is healthy, AI dead reckoning + OpenStreetMap map matching during GNSS loss,
and a seamless switch both ways. Fully offline. Android 10 or newer.

## Build and install (Android Studio)

1. Android Studio **Ladybug (2024.2) or newer** → *File › Open* → select this `MaverickGRID` folder.
   Let Gradle sync finish (first time downloads Gradle 8.9 and Android Gradle Plugin 8.7.3; needs internet once).
   If Studio offers to upgrade the Android Gradle Plugin, accept.
2. On the phone: *Settings › About phone* → tap *Build number* 7 times → *Developer options* → enable *USB debugging*.
   Connect by USB and allow the PC.
3. In Studio pick the phone in the device list and press **Run ▶**. The app installs and opens.
   (Or *Build › Build App Bundle(s)/APK(s) › Build APK(s)* → `app/build/outputs/apk/debug/app-debug.apk`, copy to the phone and install.)

## Using it

| Button | What it does |
|---|---|
| **Replay IO-VNBD** | Replays held-out drive S1 (Coventry) at 10× speed through the on-phone engine, with a 60 s GNSS outage every 3 min. Shows the true position (black ring), the error at the end of every outage and the running mean drift. Works indoors, no GNSS needed. |
| **Start live** | Live navigation with the phone's sensors and GNSS. Blue arrow = GNSS + INS, orange arrow = dead reckoning. |
| **Vehicle** | Chooses the model folder in `assets/models/` (currently `car`; `bike` after the two-wheeler model is trained). |
| **Simulate GNSS loss** | Live mode: stops feeding GNSS to the engine while still recording it, so the app shows the real error in metres and % of distance. |
| **Record ride** | Records raw sensors (native rate) and every GNSS fix to `Android/data/com.maverickgrid.app/files/rides/ride_<date>/` for training. |

Pinch to zoom, drag to pan, double-tap to follow the vehicle again.

## Maps

The app looks for road files (`*.mgr`) in `Android/data/com.maverickgrid.app/files/maps/` first, then in `assets/maps/`,
and uses the first one with roads within 20 km of the first GNSS fix. Make one from any OSM extract with
`python -m idr.osm_pack` (see the Python package), e.g. for Coimbatore from the Geofabrik southern-zone file:

    pip install osmium
    python -m idr.osm_pack --in southern-zone-latest.osm.pbf --bbox 10.85,76.80,11.20,77.15 --out coimbatore.mgr

Copy `coimbatore.mgr` to the phone folder above (USB) or into `app/src/main/assets/maps/` before building.

## Phone mounting (car model)

The car model was trained on IO-VNBD, where the phone sat **upright (portrait) in a dashboard holder**.
Mount it the same way for best results. Heading uses rotation about gravity, so it works in any orientation,
but the speed model's vibration features depend on the mount.

## What is inside

- `app/src/main/java/com/maverickgrid/engine/` – the engine (pure Java, same code as the edge engine and the tests)
- `app/src/main/java/com/maverickgrid/app/` – service (sensors, GNSS, 10 Hz loop, logger), map view, main screen
- `app/src/main/assets/models/car` – speed model + stop detector trained on all IO-VNBD training drives
- `app/src/main/assets/models/car_heldout_S1` + `assets/replay/S1` – model trained without drive S1, and drive S1 itself, for the honest replay demo
- `app/src/main/assets/maps/coventry.mgr` – OSM roads for the IO-VNBD test area
