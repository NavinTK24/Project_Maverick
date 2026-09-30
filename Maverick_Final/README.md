# Maverick – Android app

Navigation that keeps working when GPS drops out (tunnels, flyovers, dense streets), for SIH26168.
GNSS + INS fusion while GPS is healthy; AI dead reckoning + OpenStreetMap map matching during GPS loss; offline search,
routing and turn-by-turn guidance. **Everything works without internet.** Android 10 or newer.

## Build and install (Android Studio)
1. Android Studio Ladybug (2024.2) or newer → File › Open → this `MaverickGRID` folder → wait for Gradle sync (internet needed once).
2. Phone: Settings › About phone › tap *Build number* 7× → Developer options → USB debugging on → connect by USB.
3. Pick the phone and press **Run ▶**.

## Using Maverick
- **Search bar**: type a place or street; tap a result → **Directions**. Tap a place on the map, or long-press anywhere to drop a pin.
- **Start** (Car or Bike): live navigation. Blue dot = GPS + sensors; the glowing beam shows which way you face.
  Amber dot = dead reckoning (no GPS); its circle is the uncertainty and grows with distance since GPS was lost.
- **Compass** (top right): phone heading in degrees and N/NE/E/SE/S/SW/W/NW.
- **Test GPS loss**: hides GPS from Maverick while driving and shows the real error in metres and % of distance.
- **Record ride**: saves sensors + GPS for training (Android/data/com.maverickgrid.app/files/rides/).
- **Menu › Demo drive**: replays a real drive from the ISRO IO-VNBD dataset (Coventry, UK) that the model never saw, cutting
  GPS for 60 s every 3 minutes, and shows the true position and the error. Works indoors.
- If the app ever closes unexpectedly, the next start shows the crash report with a **Copy** button – send it.

## Vehicle models
- **Car**: trained on all IO-VNBD training drives (phone upright in a dashboard holder works best).
- **Bike (beta)**: trained on only 4 short two-wheeler rides (~8 min). Record ~2 h of rides with *Record ride* to improve it.

## Maps
Map files (`*.mgr`) hold roads (map matching + routing), names and places (search), and buildings/water/parks (drawing).
The app uses the first file with roads within 30 km: first `Android/data/com.maverickgrid.app/files/maps/`, then `assets/maps/`.
Make one from a Geofabrik `.pbf` with the script in `maptools` (Windows PowerShell):

    py -m pip install osmium
    py pbf_to_mgr.py --in D:\Downloads\southern-zone-260925.osm.pbf --bbox 10.85,76.80,11.20,77.15 --out ..\MaverickGRID\app\src\main\assets\maps\coimbatore.mgr

(`--no-buildings` for a smaller, faster file; `--disk-index` if memory runs out.) The bundled `coventry.mgr` covers the demo area.
