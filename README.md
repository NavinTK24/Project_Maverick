# Maverick

**Offline-first Android navigation designed to keep a useful position estimate when GNSS is unavailable.**

Maverick combines satellite positioning with phone motion sensors, dead reckoning, and OpenStreetMap road data. The Android app includes offline maps, place and street search, route guidance, ride recording, and a demo mode for exploring GPS-loss behavior.

> **Project status:** Maverick is an actively developed prototype. Dead-reckoning performance depends on the vehicle, phone placement, motion, and available map data. Demo/replay results are not a guarantee of real-world accuracy.

## Screenshots

<p align="center">
	<img src="images/testing.jpeg" width="30%" alt="Turn guidance during demo dead reckoning without GPS">
	<img src="images/ride.jpeg" width="30%" alt="Maverick showing on-route dead reckoning during a ride">
	<img src="images/reached.jpeg" width="30%" alt="Navigation map continuing to show the route while GPS is unavailable">
</p>

## What it does

- **Continues through GPS loss:** uses the accelerometer and gyroscope to estimate motion while satellite fixes are unavailable.
- **Keeps navigation context:** combines the position estimate with local road geometry and map matching.
- **Works offline:** search, maps, and routing use bundled map data; no network is needed for the included offline map experience.
- **Supports car and bike profiles:** the car model is the primary profile; bike support is experimental and benefits from additional ride data.
- **Lets you inspect GPS-loss behavior:** use the in-app demo/replay or GPS-loss test controls to observe the mode change and its reported uncertainty.
- **Records rides:** captures sensor and location data for analysis and model improvement.

## Get started

### Requirements

- Android Studio (recent stable release)
- JDK 17
- Android SDK Platform 35
- Android 10 (API 29) or newer for device installation

The first Gradle sync may need internet access to download the Android Gradle Plugin and SDK components. Runtime navigation with the bundled maps does not require internet access.

### Open and build

Open `Maverick_Final` in Android Studio and allow Gradle sync to finish. Or, from the repository root on Windows, run:

```powershell
cd Maverick_Final
./gradlew.bat assembleDebug
```

The debug APK is written to:

```text
Maverick_Final/app/build/outputs/apk/debug/app-debug.apk
```

To install it on a connected device with USB debugging enabled:

```powershell
./gradlew.bat installDebug
```

The app needs location permission and a device with a gyroscope, accelerometer, and GNSS hardware. Grant location permission when prompted and mount the phone securely in the orientation appropriate to the selected vehicle profile.

## Maps and demo data

The Android app and its bundled data are in [`Maverick_Final`](Maverick_Final):

- `app/src/main/assets/maps/` contains the Coimbatore and Coventry offline maps.
- `app/src/main/assets/models/` contains the packaged vehicle profiles.
- `app/src/main/assets/replay/` contains replay data used by the demo.

Map files use Maverick's `.mgr` format. The repository also includes map packaging utilities under [`maptools`](maptools) and [`tools`](tools). For map generation options, see the utility's local help before processing new source data:

```powershell
py maptools/osm_pack.py --help
```

## Repository layout

| Path | Contents |
| --- | --- |
| [`Maverick_Final`](Maverick_Final) | Current Android app and its Gradle project. |
| [`Maverick`](Maverick) | Research, engineering notes, evaluation material, and an earlier project implementation. |
| [`maptools`](maptools) | OpenStreetMap and map packaging utilities. |
| [`tools`](tools) | Supporting data and export scripts. |
| `MaverickGRID_old_v1`, `MaverickGRID_old_v2` | Archived Android project iterations. |
| `maverick-edge`, `maverick-edge_old_v1` | Edge-model experiments. |

Start with [`Maverick_Final/README.md`](Maverick_Final/README.md) for app controls, vehicle profiles, and map details. Research and evaluation notes are collected in [`Maverick/docs`](Maverick/docs).

## Important limitations

- Dead reckoning accumulates uncertainty over time; it does not replace GNSS indefinitely.
- Accuracy varies with phone mounting, vehicle dynamics, sensor quality, and the route/map match.
- Bike mode is experimental. Treat its estimates as a prototype feature, not a validated navigation guarantee.
- The demo/replay is useful for repeatable inspection, but simulated or replayed GPS loss should not be represented as a live field test.
- Use the app as an experimental aid and continue to follow road signs and local traffic laws.

## Contributing

Bug reports and focused improvements are welcome. Include the device model, Android version, vehicle profile, and whether the issue occurred during a live ride or a replay. Do not publish ride logs without removing location traces and other identifying information.

## License

See [`Maverick/LICENSE`](Maverick/LICENSE) for the license associated with the research project. Check the individual subproject and dataset terms before redistributing bundled third-party data or assets.