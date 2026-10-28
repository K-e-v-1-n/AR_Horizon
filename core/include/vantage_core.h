/*
 * Vantage portable core — public C ABI.
 *
 * Owns terrain sampling, horizon silhouettes, visibility, projection and the
 * label solver. No I/O, no platform types: the Android shell feeds it two
 * regular lat/lon int16 grids (fine + coarse) assembled from a pack, and gets
 * back labels in screen pixels.
 *
 * Grid convention: row 0 is the NORTH edge, column 0 the WEST edge. Values are
 * metres above the DEM datum (Copernicus GLO-30, EGM2008 orthometric).
 */
#ifndef VANTAGE_CORE_H
#define VANTAGE_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VT_VERSION 1
#define VT_ABI 1
#define VT_MAX_LABELS 64
#define VT_DEFAULT_BINS 3600

typedef enum {
    VT_PEAK = 0,
    VT_CITY = 1,
    VT_TOWN = 2,
    VT_VILLAGE = 3,
    VT_HAMLET = 4,
    VT_WATER = 5,
    VT_LANDMARK = 6
} VTPlaceKind;

typedef struct {
    double lat_north; /* latitude of the north edge of row 0 */
    double lon_west;  /* longitude of the west edge of column 0 */
    double dlat;      /* degrees per row, positive */
    double dlon;      /* degrees per column, positive */
    int32_t width;
    int32_t height;
} VTGridInfo;

typedef struct VantageTerrain VantageTerrain;
typedef struct VantageSilhouette VantageSilhouette;
typedef struct VantagePlaces VantagePlaces;

/* ---- terrain ------------------------------------------------------------ */

VantageTerrain *vt_terrain_create(void);
void vt_terrain_destroy(VantageTerrain *t);

/* level 0 = fine (1"), level 1 = coarse (4"). data is row-major, north to
 * south, int16 little-endian in memory. Copied by the callee. */
int vt_terrain_set_grid(VantageTerrain *t, int level, const int16_t *data,
                        VTGridInfo info);

int vt_terrain_has_grid(const VantageTerrain *t, int level);
double vt_terrain_height(const VantageTerrain *t, double lat, double lon);
double vt_terrain_global_max(const VantageTerrain *t);

/* Conservative radius of usable terrain around a point, metres. */
double vt_terrain_range_m(const VantageTerrain *t, double lat, double lon);

/* ---- silhouette --------------------------------------------------------- */

VantageSilhouette *vt_silhouette_create(int bins);
void vt_silhouette_destroy(VantageSilhouette *s);

/* Computes the apparent horizon (radians) and distance (metres) per azimuth
 * bin. Includes Earth curvature and standard refraction. Returns 0 on
 * success. */
int vt_silhouette_compute(VantageSilhouette *s, const VantageTerrain *t,
                          double lat, double lon, double eye_height_m);

int vt_silhouette_bins(const VantageSilhouette *s);
float vt_silhouette_elev(const VantageSilhouette *s, double az_rad);
float vt_silhouette_dist(const VantageSilhouette *s, double az_rad);
const float *vt_silhouette_elev_data(const VantageSilhouette *s);

/* ---- places and labels -------------------------------------------------- */

typedef struct {
    int32_t id;
    int32_t kind; /* VTPlaceKind */
    double lat;
    double lon;
    float ele_m;      /* summit/ground height, -10000 when unknown */
    int64_t population;
    float rank;       /* pack precomputed 0..1 bias */
    const char *name;
    const char *region;
    const char *wiki;
} VTPlace;

VantagePlaces *vt_places_create(const VTPlace *places, int count);
void vt_places_destroy(VantagePlaces *p);
int vt_places_count(const VantagePlaces *p);

typedef struct {
    double yaw_rad;   /* camera heading, clockwise from north */
    double pitch_rad; /* + up */
    double roll_rad;  /* + clockwise tilt (horizon slopes down to the right) */
    double hfov_rad;
    int width;  /* px */
    int height; /* px */
} VTCamera;

typedef struct {
    int place_index;
    double az_rad;
    double elev_rad;
    double dist_m;
    double target_ele_m;
    float x, y; /* label anchor in screen px */
    int on_screen;
    int visible;     /* target clears the horizon (or no terrain) */
    int has_terrain; /* a terrain silhouette covered this bearing */
    float score;
    float box_w, box_h;
} VTLabel;

/* Projects a direction into screen pixels. on_screen is set to 0 when the
 * direction is behind the camera or outside the viewport. */
void vt_project(const VTCamera *cam, double az_rad, double elev_rad, float *x,
                float *y, int *on_screen);

/* Ranks, culls and declutters. Fills out_labels (at most max_labels) sorted by
 * score. name_widths_px may be NULL; otherwise it gives the measured width of
 * each place label for collision testing. Returns the label count. */
int vt_solve(const VantageTerrain *t, const VantageSilhouette *s, double lat,
             double lon, double eye_m, const VantagePlaces *places,
             const VTCamera *cam, const float *name_widths_px, int max_labels,
             VTLabel *out_labels);

/* ---- skyline lock ------------------------------------------------------- */

/* Correlates observed sky/terrain boundary samples against the synthetic
 * horizon. Returns the best *positive* yaw correction in radians that should
 * be added to the camera heading, the normalised correlation score (-1..1)
 * and the median vertical residual in radians. */
int vt_fit_skyline(const float *synthetic_elev, int bins, const float *obs_az,
                   const float *obs_elev, const float *obs_weight, int count,
                   double max_offset_rad, double step_rad, double *out_offset,
                   double *out_score, double *out_vertical);

/* ---- geodesy helpers ---------------------------------------------------- */

double vt_distance_m(double lat1, double lon1, double lat2, double lon2);
double vt_bearing_rad(double lat1, double lon1, double lat2, double lon2);
double vt_curvature_drop_m(double dist_m);
double vt_eye_altitude_m(const VantageTerrain *t, double lat, double lon,
                         double eye_height_m);

const char *vt_version_string(void);

#ifdef __cplusplus
}
#endif

#endif /* VANTAGE_CORE_H */
