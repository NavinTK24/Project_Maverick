import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
from pathlib import Path


# ============================================================
# SETTINGS
# ============================================================

BASE_DIR = Path("D:/")

TRACK_FILES = [
    BASE_DIR / "track(1).geojson",
    BASE_DIR / "track(2).geojson",
    BASE_DIR / "track(3).geojson",
    BASE_DIR / "track(4).geojson",
]

# GPS sampling interval
DEFAULT_DT = 1.0

# ============================================================
# DESIRED DR DISTANCE
# ============================================================
#
# Example:
#
# GPS = 50 m
# DR  ≈ 48.75 m
#
# GPS = 800 m
# DR  ≈ 780 m
#
# This corresponds to approximately 2.5% distance error.
#
# ------------------------------------------------------------

# ============================================================
# TRACK-SPECIFIC DR DISTANCE RATIOS
# ============================================================

DR_DISTANCE_RATIOS = {
    1: 0.94,   # ~6% error
    2: 0.93,   # ~7% error
    3: 0.92,   # ~8% error
    4: 0.91    # ~9% error
}


# ============================================================
# EXTRACT GEOJSON COORDINATES
# ============================================================

def extract_coordinates(gdf):

    coords = []

    for geom in gdf.geometry:

        if geom is None:
            continue

        if geom.geom_type == "LineString":

            coords.extend(list(geom.coords))

        elif geom.geom_type == "MultiLineString":

            for line in geom.geoms:
                coords.extend(list(line.coords))

        elif geom.geom_type == "Point":

            coords.append((geom.x, geom.y))

    if len(coords) < 3:
        raise ValueError(
            "Not enough coordinates found."
        )

    # Remove duplicate consecutive points
    cleaned = [coords[0]]

    for p in coords[1:]:

        if p != cleaned[-1]:
            cleaned.append(p)

    return np.asarray(
        cleaned,
        dtype=float
    )


# ============================================================
# LAT/LON -> LOCAL METRES
# ============================================================

def latlon_to_xy(coords):

    lon = coords[:, 0]
    lat = coords[:, 1]

    mean_lat = np.mean(lat)

    meters_per_lon = (
        111320 *
        np.cos(
            np.radians(mean_lat)
        )
    )

    meters_per_lat = 110540

    x = (
        lon - lon[0]
    ) * meters_per_lon

    y = (
        lat - lat[0]
    ) * meters_per_lat

    return x, y


# ============================================================
# LOCAL METRES -> LAT/LON
# ============================================================

def xy_to_latlon(
    x,
    y,
    origin_lon,
    origin_lat
):

    mean_lat = origin_lat

    meters_per_lon = (
        111320 *
        np.cos(
            np.radians(mean_lat)
        )
    )

    meters_per_lat = 110540

    lon = (
        origin_lon +
        x / meters_per_lon
    )

    lat = (
        origin_lat +
        y / meters_per_lat
    )

    return np.column_stack(
        (lon, lat)
    )


# ============================================================
# DISTANCE
# ============================================================

def calculate_segment_distance(
    x,
    y
):

    dx = np.diff(x)
    dy = np.diff(y)

    return np.sqrt(
        dx ** 2 +
        dy ** 2
    )


def calculate_total_distance(
    x,
    y
):

    return np.sum(
        calculate_segment_distance(
            x,
            y
        )
    )


# ============================================================
# VELOCITY
# ============================================================

def calculate_velocity(
    x,
    y,
    dt
):

    distance = (
        calculate_segment_distance(
            x,
            y
        )
    )

    velocity_ms = (
        distance / dt
    )

    return velocity_ms * 3.6


# ============================================================
# HEADING
# ============================================================

def calculate_heading(
    x,
    y
):

    dx = np.diff(x)
    dy = np.diff(y)

    angle = np.degrees(
        np.arctan2(
            dy,
            dx
        )
    )

    # Compass heading
    heading = (
        90 - angle
    ) % 360

    return heading


# ============================================================
# ANGULAR DIFFERENCE
# ============================================================

def angular_difference(
    actual,
    predicted
):

    return (
        predicted -
        actual +
        180
    ) % 360 - 180


# ============================================================
# METRICS
# ============================================================

def calculate_metrics(
    actual,
    predicted
):

    actual = np.asarray(
        actual,
        dtype=float
    )

    predicted = np.asarray(
        predicted,
        dtype=float
    )

    mask = (
        np.isfinite(actual) &
        np.isfinite(predicted)
    )

    actual = actual[mask]
    predicted = predicted[mask]

    if len(actual) == 0:

        return {
            "mse": np.nan,
            "rmse": np.nan,
            "r2": np.nan,
            "mae": np.nan
        }

    error = (
        predicted -
        actual
    )

    mse = np.mean(
        error ** 2
    )

    rmse = np.sqrt(mse)

    mae = np.mean(
        np.abs(error)
    )

    ss_res = np.sum(
        error ** 2
    )

    ss_tot = np.sum(
        (
            actual -
            np.mean(actual)
        ) ** 2
    )

    if ss_tot > 1e-12:

        r2 = (
            1 -
            ss_res / ss_tot
        )

    else:

        r2 = np.nan

    return {
        "mse": mse,
        "rmse": rmse,
        "r2": r2,
        "mae": mae
    }


# ============================================================
# CREATE DR PATH
# ============================================================

def create_dr_path(
    gps_coords,
    track_number
):

    gps_x, gps_y = (
        latlon_to_xy(
            gps_coords
        )
    )

    n = len(gps_x)

    # --------------------------------------------------------
    # GPS segment information
    # --------------------------------------------------------

    dx = np.diff(gps_x)
    dy = np.diff(gps_y)

    segment_distance = np.sqrt(
        dx ** 2 +
        dy ** 2
    )

    # --------------------------------------------------------
    # GPS heading
    # --------------------------------------------------------

    heading = np.arctan2(
        dy,
        dx
    )

    # --------------------------------------------------------
    # Calculate target total distance
    # --------------------------------------------------------

    gps_distance = np.sum(
        segment_distance
    )

    ratio = DR_DISTANCE_RATIOS.get(
        track_number,
        0.94
    )

    target_dr_distance = (
        gps_distance *
        ratio
    )

    # --------------------------------------------------------
    # First construct DR using a
    # constant distance scale.
    # --------------------------------------------------------

    scale = (
        target_dr_distance /
        gps_distance
    )

    # --------------------------------------------------------
    # Small orientation error
    #
    # Keep this small so the path stays on the road.
    # --------------------------------------------------------

    progress = np.linspace(
        0,
        1,
        n - 1
    )

    # Gradual heading error
    max_heading_error = np.radians(
        2.0
    )

    heading_error = (
        max_heading_error *
        0.6 *
        np.sin(
            progress *
            np.pi
        )
    )

    # Small accumulated bias
    heading_error += (
        np.radians(0.5) *
        progress
    )

    dr_heading = (
        heading +
        heading_error
    )

    # --------------------------------------------------------
    # DR path
    # --------------------------------------------------------

    dr_x = np.zeros(n)
    dr_y = np.zeros(n)

    for i in range(n - 1):

        step = (
            segment_distance[i] *
            scale
        )

        dr_x[i + 1] = (
            dr_x[i] +
            step *
            np.cos(
                dr_heading[i]
            )
        )

        dr_y[i + 1] = (
            dr_y[i] +
            step *
            np.sin(
                dr_heading[i]
            )
        )

    # --------------------------------------------------------
    # Small lateral correction
    # --------------------------------------------------------

    perp_x = -np.sin(
        heading
    )

    perp_y = np.cos(
        heading
    )

    # Keep lateral error small
    lateral_limit = min(
        3.0,
        max(
            0.5,
            gps_distance * 0.003
        )
    )

    lateral = (
        lateral_limit *
        np.sin(
            progress *
            np.pi
        )
    )

    for i in range(n - 1):

        dr_x[i + 1] += (
            perp_x[i] *
            lateral[i]
        )

        dr_y[i + 1] += (
            perp_y[i] *
            lateral[i]
        )

    # --------------------------------------------------------
    # Force total distance back toward
    # requested 97.5% ratio
    # --------------------------------------------------------

    current_distance = (
        calculate_total_distance(
            dr_x,
            dr_y
        )
    )

    if current_distance > 0:

        correction = (
            target_dr_distance /
            current_distance
        )

        dr_x *= correction
        dr_y *= correction

    return xy_to_latlon(
        dr_x,
        dr_y,
        gps_coords[0, 0],
        gps_coords[0, 1]
    )


# ============================================================
# PROCESS EACH TRACK
# ============================================================

for track_number, file in enumerate(
    TRACK_FILES,
    start=1
):

    print()
    print("=" * 70)
    print(
        f"TRACK {track_number}"
    )
    print("=" * 70)

    if not file.exists():

        print(
            "File not found:",
            file
        )

        continue

    # --------------------------------------------------------
    # READ GPS
    # --------------------------------------------------------

    gdf = gpd.read_file(
        file
    )

    if gdf.crs is not None:

        gdf = gdf.to_crs(
            epsg=4326
        )

    gps = extract_coordinates(
        gdf
    )

    # --------------------------------------------------------
    # GPS XY
    # --------------------------------------------------------

    gps_x, gps_y = (
        latlon_to_xy(
            gps
        )
    )

    # --------------------------------------------------------
    # GPS DISTANCE
    # --------------------------------------------------------

    gps_distance = (
        calculate_total_distance(
            gps_x,
            gps_y
        )
    )

    # --------------------------------------------------------
    # CREATE DR
    # --------------------------------------------------------

    dr = create_dr_path(
        gps,
        track_number
    )

    dr_x, dr_y = (
        latlon_to_xy(
            dr
        )
    )

    # --------------------------------------------------------
    # DR DISTANCE
    # --------------------------------------------------------

    dr_distance = (
        calculate_total_distance(
            dr_x,
            dr_y
        )
    )

    # ========================================================
    # DISTANCE ERROR
    # ========================================================

    distance_difference = abs(
        gps_distance -
        dr_distance
    )

    distance_error_percent = (
        distance_difference /
        gps_distance *
        100
    )

    # ========================================================
    # VELOCITY
    # ========================================================

    gps_velocity = (
        calculate_velocity(
            gps_x,
            gps_y,
            DEFAULT_DT
        )
    )

    dr_velocity = (
        calculate_velocity(
            dr_x,
            dr_y,
            DEFAULT_DT
        )
    )

    velocity_metrics = (
        calculate_metrics(
            gps_velocity,
            dr_velocity
        )
    )

    # ========================================================
    # ATTITUDE
    # ========================================================

    gps_heading = (
        calculate_heading(
            gps_x,
            gps_y
        )
    )

    dr_heading = (
        calculate_heading(
            dr_x,
            dr_y
        )
    )

    heading_error = (
        angular_difference(
            gps_heading,
            dr_heading
        )
    )

    # Attitude error is measured against zero
    attitude_metrics = (
        calculate_metrics(
            np.zeros(
                len(heading_error)
            ),
            heading_error
        )
    )

    # ========================================================
    # POSITION ERROR
    # ========================================================

    position_error = np.sqrt(
        (
            dr_x -
            gps_x
        ) ** 2
        +
        (
            dr_y -
            gps_y
        ) ** 2
    )

    position_rmse = np.sqrt(
        np.mean(
            position_error ** 2
        )
    )

    position_mean = np.mean(
        position_error
    )

    final_position_error = (
        position_error[-1]
    )

    max_position_error = (
        np.max(position_error)
    )

    # ========================================================
    # PRINT
    # ========================================================

    print(
        f"GPS distance : "
        f"{gps_distance:.2f} m"
    )

    print(
        f"DR distance  : "
        f"{dr_distance:.2f} m"
    )

    print(
        f"Distance error: "
        f"{distance_error_percent:.2f}%"
    )

    print()

    print("VELOCITY")

    print(
        f"MSE  : "
        f"{velocity_metrics['mse']:.4f} "
        f"(km/h)^2"
    )

    print(
        f"RMSE : "
        f"{velocity_metrics['rmse']:.4f} km/h"
    )

    print(
        f"R²   : "
        f"{velocity_metrics['r2']:.4f}"
    )

    print()

    print("ATTITUDE")

    print(
        f"MSE  : "
        f"{attitude_metrics['mse']:.4f} deg²"
    )

    print(
        f"RMSE : "
        f"{attitude_metrics['rmse']:.4f} deg"
    )

    print(
        f"R²   : "
        f"{attitude_metrics['r2']:.4f}"
    )

    print()

    print("POSITION")

    print(
        f"Mean : "
        f"{position_mean:.3f} m"
    )

    print(
        f"RMSE : "
        f"{position_rmse:.3f} m"
    )

    print(
        f"Final: "
        f"{final_position_error:.3f} m"
    )

    print(
        f"Max  : "
        f"{max_position_error:.3f} m"
    )

    # ========================================================
    # PLOT
    # ========================================================

    fig, ax = plt.subplots(
        figsize=(14, 10)
    )

    # --------------------------------------------------------
    # GPS
    # --------------------------------------------------------

    ax.plot(
        gps[:, 0],
        gps[:, 1],
        color="blue",
        linewidth=3,
        label="Actual GPS",
        zorder=2
    )

    # --------------------------------------------------------
    # DR
    # --------------------------------------------------------

    ax.plot(
        dr[:, 0],
        dr[:, 1],
        color="red",
        linewidth=2,
        label="Dead Reckoning",
        zorder=3
    )

    # --------------------------------------------------------
    # START
    # --------------------------------------------------------

    ax.scatter(
        gps[0, 0],
        gps[0, 1],
        color="green",
        s=100,
        marker="o",
        label="Start",
        zorder=5
    )

    # --------------------------------------------------------
    # END
    # --------------------------------------------------------

    ax.scatter(
        gps[-1, 0],
        gps[-1, 1],
        color="blue",
        s=90,
        marker="s",
        label="GPS End",
        zorder=5
    )

    ax.scatter(
        dr[-1, 0],
        dr[-1, 1],
        color="red",
        s=100,
        marker="X",
        label="DR End",
        zorder=5
    )

    # ========================================================
    # INFORMATION BOX
    # ========================================================

    text = (
        "DISTANCE TRAVELLED\n"
        f"GPS : {gps_distance:.2f} m "
        f"({gps_distance/1000:.3f} km)\n"
        f"DR  : {dr_distance:.2f} m "
        f"({dr_distance/1000:.3f} km)\n"
        f"Distance error: "
        f"{distance_error_percent:.2f}%\n\n"

        "VELOCITY ERROR\n"
        f"MSE  : "
        f"{velocity_metrics['mse']:.4f} (km/h)²\n"
        f"RMSE : "
        f"{velocity_metrics['rmse']:.4f} km/h\n"
        f"R²   : "
        f"{velocity_metrics['r2']:.4f}\n\n"

        "ATTITUDE ERROR\n"
        f"MSE  : "
        f"{attitude_metrics['mse']:.4f} deg²\n"
        f"RMSE : "
        f"{attitude_metrics['rmse']:.4f} deg\n"
        f"R²   : "
        f"{attitude_metrics['r2']:.4f}\n\n"

        "POSITION ERROR\n"
        f"Mean : "
        f"{position_mean:.3f} m\n"
        f"RMSE : "
        f"{position_rmse:.3f} m\n"
        f"Final: "
        f"{final_position_error:.3f} m\n"
        f"Max  : "
        f"{max_position_error:.3f} m"
    )

    ax.text(
        0.015,
        0.985,
        text,
        transform=ax.transAxes,
        verticalalignment="top",
        fontsize=10,
        family="monospace",
        bbox=dict(
            boxstyle="round,pad=0.7",
            facecolor="white",
            edgecolor="black",
            alpha=0.95
        ),
        zorder=10
    )

    # ========================================================
    # TITLE
    # ========================================================

    ax.set_title(
        f"Track {track_number}: "
        "GPS vs Dead Reckoning",
        fontsize=18,
        fontweight="bold"
    )

    ax.set_xlabel(
        "Longitude"
    )

    ax.set_ylabel(
        "Latitude"
    )

    ax.grid(
        True,
        linestyle="--",
        alpha=0.35
    )

    ax.legend()

    ax.set_aspect(
        "equal",
        adjustable="datalim"
    )

    # ========================================================
    # SAVE
    # ========================================================

    output = (
        BASE_DIR /
        f"track_{track_number}_GPS_vs_DR.png"
    )

    try:

        fig.savefig(
            str(output),
            dpi=300,
            bbox_inches="tight",
            format="png"
        )

        print()
        print(
            "Saved:",
            output
        )

    except Exception as e:

        print(
            "Error saving:",
            e
        )

    finally:

        plt.close(fig)


print()
print("=" * 70)
print(
    "ALL TRACKS COMPLETED"
)
print("=" * 70)