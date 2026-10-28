#include "internal.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace vt {

double distance_m(double lat1, double lon1, double lat2, double lon2) {
    const double p1 = lat1 * kDeg;
    const double p2 = lat2 * kDeg;
    const double dp = (lat2 - lat1) * kDeg;
    const double dl = (lon2 - lon1) * kDeg;
    const double sp = std::sin(dp * 0.5);
    const double sl = std::sin(dl * 0.5);
    const double a = sp * sp + std::cos(p1) * std::cos(p2) * sl * sl;
    return 2.0 * kEarthRadius * std::asin(std::fmin(1.0, std::sqrt(a)));
}

double bearing_rad(double lat1, double lon1, double lat2, double lon2) {
    const double p1 = lat1 * kDeg;
    const double p2 = lat2 * kDeg;
    const double dl = (lon2 - lon1) * kDeg;
    const double y = std::sin(dl) * std::cos(p2);
    const double x = std::cos(p1) * std::sin(p2) -
                     std::sin(p1) * std::cos(p2) * std::cos(dl);
    return wrap_two_pi(std::atan2(y, x));
}

double curvature_drop_m(double d) {
    return d * d / (2.0 * kEffRadius);
}

} // namespace vt

extern "C" double vt_distance_m(double lat1, double lon1, double lat2,
                                double lon2) {
    return vt::distance_m(lat1, lon1, lat2, lon2);
}

extern "C" double vt_bearing_rad(double lat1, double lon1, double lat2,
                                 double lon2) {
    return vt::bearing_rad(lat1, lon1, lat2, lon2);
}

extern "C" double vt_curvature_drop_m(double dist_m) {
    return vt::curvature_drop_m(dist_m);
}

VantageTerrain *vt_terrain_create(void) { return new VantageTerrain(); }

void vt_terrain_destroy(VantageTerrain *t) { delete t; }

int vt_terrain_set_grid(VantageTerrain *t, int level, const int16_t *data,
                        VTGridInfo info) {
    if (!t || !data || info.width <= 1 || info.height <= 1 || info.dlat <= 0 ||
        info.dlon <= 0) {
        return -1;
    }
    vt::Grid g;
    g.info = info;
    const size_t n = static_cast<size_t>(info.width) * info.height;
    g.data.assign(data, data + n);
    g.valid = true;
    int16_t maxv = std::numeric_limits<int16_t>::min();
    for (size_t i = 0; i < n; ++i) {
        if (g.data[i] > maxv) maxv = g.data[i];
    }
    g.max_height = maxv;
    if (level == 0) {
        t->fine = std::move(g);
    } else if (level == 1) {
        t->coarse = std::move(g);
    } else {
        return -2;
    }
    return 0;
}

int vt_terrain_has_grid(const VantageTerrain *t, int level) {
    if (!t) return 0;
    if (level == 0) return t->fine.valid ? 1 : 0;
    if (level == 1) return t->coarse.valid ? 1 : 0;
    return 0;
}

double vt_terrain_height(const VantageTerrain *t, double lat, double lon) {
    if (!t || lat < -90 || lat > 90) return NAN;
    return t->height(lat, lon);
}

double vt_terrain_global_max(const VantageTerrain *t) {
    return t ? t->max_height() : NAN;
}

double vt_terrain_range_m(const VantageTerrain *t, double lat, double lon) {
    if (!t) return 0;
    const vt::Grid *g = t->coarse.valid ? &t->coarse : &t->fine;
    if (!g || !g->valid) return 0;
    const double south = g->info.lat_north - g->info.height * g->info.dlat;
    const double east = g->info.lon_west + g->info.width * g->info.dlon;
    if (lat < south || lat > g->info.lat_north || lon < g->info.lon_west ||
        lon > east) {
        return 0;
    }
    const double mlat = vt::metres_per_degree_lat(lat);
    const double mlon = vt::metres_per_degree_lon(lat);
    const double north_m = (g->info.lat_north - lat) * mlat;
    const double south_m = (lat - south) * mlat;
    const double west_m = (lon - g->info.lon_west) * mlon;
    const double east_m = (east - lon) * mlon;
    return std::fmax(0.0, std::fmin(std::fmin(north_m, south_m),
                                   std::fmin(west_m, east_m)));
}

double vt_eye_altitude_m(const VantageTerrain *t, double lat, double lon,
                         double eye_height_m) {
    const double ground = vt_terrain_height(t, lat, lon);
    if (!std::isfinite(ground)) return NAN;
    if (eye_height_m < vt::kMinEyeHeight) eye_height_m = vt::kMinEyeHeight;
    return ground + eye_height_m;
}

VantageSilhouette *vt_silhouette_create(int bins) {
    if (bins < 36) bins = 36;
    if (bins > 36000) bins = 36000;
    VantageSilhouette *s = new VantageSilhouette();
    s->bins = bins;
    s->elev.assign(static_cast<size_t>(bins), NAN);
    s->dist.assign(static_cast<size_t>(bins), NAN);
    return s;
}

void vt_silhouette_destroy(VantageSilhouette *s) { delete s; }

int vt_silhouette_bins(const VantageSilhouette *s) { return s ? s->bins : 0; }

namespace {

int bin_index(const VantageSilhouette *s, double az) {
    if (!s || s->bins <= 0) return -1;
    const double t = vt::wrap_two_pi(az) / vt::kTwoPi * s->bins;
    int b = static_cast<int>(t);
    if (b >= s->bins) b = s->bins - 1;
    return b;
}

// Distance schedule: dense close in, coarse far away. ~273 samples per bin.
std::vector<double> distance_schedule(double max_range) {
    std::vector<double> ds;
    ds.reserve(360);
    double d = 25.0;
    while (d < max_range) {
        ds.push_back(d);
        double step;
        if (d < 500) {
            step = 15;
        } else if (d < 2000) {
            step = 60;
        } else if (d < 8000) {
            step = 150;
        } else if (d < 25000) {
            step = 300;
        } else if (d < 60000) {
            step = 600;
        } else {
            step = 1000;
        }
        d += step;
    }
    if (ds.empty() || ds.back() < max_range) ds.push_back(max_range);
    return ds;
}

} // namespace

int vt_silhouette_compute(VantageSilhouette *s, const VantageTerrain *t,
                          double lat, double lon, double eye_height_m) {
    if (!s || !t || s->bins < 36) return -1;
    if (!t->fine.valid && !t->coarse.valid) return -2;
    if (!std::isfinite(vt_terrain_height(t, lat, lon))) return -3;

    const double eye = vt_eye_altitude_m(t, lat, lon, eye_height_m);
    if (!std::isfinite(eye)) return -3;

    const double max_range = vt_terrain_range_m(t, lat, lon);
    if (max_range < 100.0) return -4;

    const std::vector<double> ds = distance_schedule(max_range);
    const double max_h = t->max_height();
    const double inv2r = 1.0 / (2.0 * vt::kEffRadius);
    const double lat_m = vt::metres_per_degree_lat(lat);
    const double lon_m = vt::metres_per_degree_lon(lat);
    const double mlat = 1.0 / lat_m;
    const double mlon = 1.0 / std::fmax(1.0, lon_m);

    const int bins = s->bins;
    const double az_step = vt::kTwoPi / bins;

    for (int b = 0; b < bins; ++b) {
        const double az = (b + 0.5) * az_step;
        const double cos_az = std::cos(az);
        const double sin_az = std::sin(az);

        double best_slope = -std::numeric_limits<double>::infinity();
        double best_d = NAN;
        bool any = false;

        const size_t ds_n = ds.size();
        for (size_t i = 0; i < ds_n; ++i) {
            const double d = ds[i];
            const double drop = d * d * inv2r;
            // Early exit: even the highest terrain in the pack at this range
            // cannot beat the current horizon.
            if (any && (max_h - eye - drop) / d <= best_slope) {
                // Check whether it could still beat us further out (drop grows,
                // maximum height is fixed), so once it fails it fails forever.
                break;
            }
            const double glat = lat + d * cos_az * mlat;
            const double glon = lon + d * sin_az * mlon;
            const double h = t->height(glat, glon);
            if (!std::isfinite(h)) continue;
            const double slope = (h - eye - drop) / d;
            if (slope > best_slope) {
                best_slope = slope;
                best_d = d;
                any = true;
            }
            if (best_slope > 12.0) break; // ~85 degrees, no point continuing
        }

        if (!any) {
            s->elev[b] = NAN;
            s->dist[b] = NAN;
        } else {
            s->elev[b] = static_cast<float>(std::atan(best_slope));
            s->dist[b] = static_cast<float>(best_d);
        }
    }
    return 0;
}

float vt_silhouette_elev(const VantageSilhouette *s, double az_rad) {
    const int b = bin_index(s, az_rad);
    if (b < 0) return NAN;
    return s->elev[static_cast<size_t>(b)];
}

float vt_silhouette_dist(const VantageSilhouette *s, double az_rad) {
    const int b = bin_index(s, az_rad);
    if (b < 0) return NAN;
    return s->dist[static_cast<size_t>(b)];
}

const float *vt_silhouette_elev_data(const VantageSilhouette *s) {
    return (s && !s->elev.empty()) ? s->elev.data() : nullptr;
}

VantagePlaces *vt_places_create(const VTPlace *places, int count) {
    if (!places || count < 0) return nullptr;
    VantagePlaces *p = new VantagePlaces();
    p->places.resize(static_cast<size_t>(count));
    p->names.resize(static_cast<size_t>(count));
    p->regions.resize(static_cast<size_t>(count));
    p->wikis.resize(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        p->places[static_cast<size_t>(i)] = places[i];
        if (places[i].name) p->names[static_cast<size_t>(i)] = places[i].name;
        if (places[i].region) p->regions[static_cast<size_t>(i)] = places[i].region;
        if (places[i].wiki) p->wikis[static_cast<size_t>(i)] = places[i].wiki;
    }
    for (int i = 0; i < count; ++i) {
        p->places[static_cast<size_t>(i)].name = p->names[static_cast<size_t>(i)].c_str();
        p->places[static_cast<size_t>(i)].region =
                p->regions[static_cast<size_t>(i)].c_str();
        p->places[static_cast<size_t>(i)].wiki = p->wikis[static_cast<size_t>(i)].c_str();
    }
    return p;
}

void vt_places_destroy(VantagePlaces *p) { delete p; }

int vt_places_count(const VantagePlaces *p) {
    return p ? static_cast<int>(p->places.size()) : 0;
}

void vt_project(const VTCamera *cam, double az, double elev, float *x, float *y,
                int *on_screen) {
    if (!cam || cam->width <= 0 || cam->height <= 0) {
        if (x) *x = 0;
        if (y) *y = 0;
        if (on_screen) *on_screen = 0;
        return;
    }
    const double yaw = cam->yaw_rad;
    const double pitch = cam->pitch_rad;
    const double roll = cam->roll_rad;

    const double ce = std::cos(elev);
    const double vx = std::sin(az) * ce;  // east
    const double vy = std::cos(az) * ce;  // north
    const double vz = std::sin(elev);     // up

    const double fwd_x = std::sin(yaw) * std::cos(pitch);
    const double fwd_y = std::cos(yaw) * std::cos(pitch);
    const double fwd_z = std::sin(pitch);

    const double right0_x = std::cos(yaw);
    const double right0_y = -std::sin(yaw);
    const double right0_z = 0.0;

    const double up0_x = -std::sin(yaw) * std::sin(pitch);
    const double up0_y = -std::cos(yaw) * std::sin(pitch);
    const double up0_z = std::cos(pitch);

    // Positive roll = camera tilted clockwise, so the world appears to rotate
    // counter-clockwise (the right side of the horizon rises).
    const double cr = std::cos(roll);
    const double sr = std::sin(roll);
    const double right_x = right0_x * cr - up0_x * sr;
    const double right_y = right0_y * cr - up0_y * sr;
    const double right_z = right0_z * cr - up0_z * sr;
    const double up_x = up0_x * cr + right0_x * sr;
    const double up_y = up0_y * cr + right0_y * sr;
    const double up_z = up0_z * cr + right0_z * sr;

    const double pz = vx * fwd_x + vy * fwd_y + vz * fwd_z;
    if (pz <= 0.02) {
        if (x) *x = -1e9f;
        if (y) *y = -1e9f;
        if (on_screen) *on_screen = 0;
        return;
    }
    const double f = (cam->width * 0.5) / std::tan(std::fmax(0.05, cam->hfov_rad) * 0.5);
    const double sx = (vx * right_x + vy * right_y + vz * right_z) / pz;
    const double sy = (vx * up_x + vy * up_y + vz * up_z) / pz;
    const double px = cam->width * 0.5 + f * sx;
    const double py = cam->height * 0.5 - f * sy;
    if (x) *x = static_cast<float>(px);
    if (y) *y = static_cast<float>(py);
    if (on_screen) {
        *on_screen = (px >= -8 && px <= cam->width + 8 && py >= -8 &&
                      py <= cam->height + 8)
                             ? 1
                             : 0;
    }
}

namespace {

float type_weight(int kind) {
    switch (kind) {
    case VT_PEAK: return 0.34f;
    case VT_CITY: return 0.40f;
    case VT_TOWN: return 0.30f;
    case VT_VILLAGE: return 0.18f;
    case VT_HAMLET: return 0.04f;
    case VT_WATER: return 0.22f;
    case VT_LANDMARK: return 0.16f;
    default: return 0.10f;
    }
}

bool overlaps(float ax, float ay, float aw, float ah, float bx, float by,
              float bw, float bh, float pad) {
    return std::fabs(ax - bx) * 2.0f < (aw + bw + pad * 2.0f) &&
           std::fabs(ay - by) * 2.0f < (ah + bh + pad * 2.0f);
}

} // namespace

int vt_solve(const VantageTerrain *t, const VantageSilhouette *s, double lat,
             double lon, double eye_m, const VantagePlaces *places,
             const VTCamera *cam, const float *name_widths_px, int max_labels,
             VTLabel *out_labels) {
    if (!t || !s || !places || !cam || !out_labels || max_labels <= 0) return 0;
    if (max_labels > VT_MAX_LABELS) max_labels = VT_MAX_LABELS;

    const int n = static_cast<int>(places->places.size());
    std::vector<VTLabel> cand;
    cand.reserve(static_cast<size_t>(n));

    for (int i = 0; i < n; ++i) {
        const VTPlace &p = places->places[static_cast<size_t>(i)];
        const double dist = vt_distance_m(lat, lon, p.lat, p.lon);
        if (dist < 8.0 || dist > 220000.0) continue;

        // Level of detail by distance, mirroring the renderer's density rules.
        const double km = dist / 1000.0;
        if (km > 5.0 && p.kind == VT_HAMLET) continue;
        if (km > 20.0 && (p.kind == VT_VILLAGE || p.kind == VT_HAMLET)) continue;
        if (km > 50.0) {
            // Far field: major cities and peaks only, matching the pack LOD.
            if (p.kind == VT_CITY && p.population < 10000) continue;
            if (p.kind == VT_TOWN) continue;
            if (p.kind == VT_PEAK && p.rank < 0.62f) continue;
            if (p.kind == VT_WATER && p.rank < 0.5f) continue;
            if (p.kind == VT_LANDMARK) continue;
        }

        const double az = vt_bearing_rad(lat, lon, p.lat, p.lon);
        const double drop = vt_curvature_drop_m(dist);

        double h_target = p.ele_m;
        if (!(h_target > -1000.0f)) {
            h_target = vt_terrain_height(t, p.lat, p.lon);
        }
        if (!std::isfinite(h_target)) continue;

        const double elev = std::atan2(h_target - eye_m - drop, dist);

        VTLabel L{};
        L.place_index = i;
        L.az_rad = az;
        L.elev_rad = elev;
        L.dist_m = dist;
        L.target_ele_m = h_target;

        const float sil = vt_silhouette_elev(s, az);
        L.has_terrain = std::isfinite(sil) ? 1 : 0;
        L.visible = (!L.has_terrain || elev > static_cast<double>(sil) + vt::kVisibilityBias) ? 1 : 0;

        int on = 0;
        float px = 0, py = 0;
        vt_project(cam, az, elev, &px, &py, &on);
        L.x = px;
        L.y = py;
        L.on_screen = on;
        if (!on) continue;

        float w = (name_widths_px && name_widths_px[i] > 0)
                          ? name_widths_px[i]
                          : static_cast<float>(8 * places->names[static_cast<size_t>(i)].size() + 28);
        w = vt::clampf(w, 24.0f, 320.0f);
        L.box_w = w;
        L.box_h = 22.0f;

        const double population = std::fmax(0.0, static_cast<double>(p.population));
        const double pop_term = std::log10(population + 1.0) / 7.0;
        const float pop_score = static_cast<float>(vt::clampd(pop_term, 0.0, 1.0));
        const float rank = std::isfinite(p.rank) ? p.rank : 0.0f;
        const float prox = static_cast<float>(vt::clampd(1.0 - dist / 120000.0, 0.0, 1.0));
        L.score = type_weight(p.kind) + pop_score * 0.34f +
                  vt::clampf(rank, 0.0f, 1.0f) * 0.28f + prox * 0.22f +
                  (L.visible ? 0.06f : -0.10f);
        cand.push_back(L);
    }

    std::sort(cand.begin(), cand.end(), [](const VTLabel &a, const VTLabel &b) {
        return a.score > b.score;
    });

    std::vector<VTLabel> placed;
    placed.reserve(static_cast<size_t>(max_labels));
    for (const VTLabel &L : cand) {
        if (static_cast<int>(placed.size()) >= max_labels) break;
        bool hit = false;
        for (const VTLabel &P : placed) {
            if (overlaps(L.x, L.y, L.box_w, L.box_h, P.x, P.y, P.box_w,
                             P.box_h, 7.0f)) {
                hit = true;
                break;
            }
        }
        if (hit) continue;
        // Keep labels fully inside the viewport with a small margin.
        if (L.x - L.box_w * 0.5f < -12.0f ||
            L.x + L.box_w * 0.5f > cam->width + 12.0f || L.y < 18.0f ||
            L.y > cam->height - 8.0f) {
            continue;
        }
        placed.push_back(L);
    }

    for (size_t i = 0; i < placed.size(); ++i) out_labels[i] = placed[i];
    return static_cast<int>(placed.size());
}

int vt_fit_skyline(const float *synthetic_elev, int bins, const float *obs_az,
                   const float *obs_elev, const float *obs_weight, int count,
                   double max_offset_rad, double step_rad, double *out_offset,
                   double *out_score, double *out_vertical) {
    if (!synthetic_elev || bins < 36 || !obs_az || !obs_elev || count < 8) {
        return -1;
    }
    if (step_rad <= 0) step_rad = 0.05 * vt::kDeg;
    if (max_offset_rad <= 0) max_offset_rad = 12.0 * vt::kDeg;

    double best_score = -2.0;
    double best_off = 0.0;
    double best_vertical = 0.0;
    std::vector<double> residuals;
    residuals.reserve(static_cast<size_t>(count));

    const int steps = static_cast<int>(std::ceil(max_offset_rad / step_rad));
    for (int k = -steps; k <= steps; ++k) {
        const double off = k * step_rad;
        double sw = 0, swx = 0, swy = 0, swxx = 0, swyy = 0, swxy = 0;
        residuals.clear();
        int used = 0;
        for (int i = 0; i < count; ++i) {
            const double w = (obs_weight ? obs_weight[i] : 1.0f);
            if (w <= 0) continue;
            const double az = obs_az[i] + off;
            const double t = vt::wrap_two_pi(az) / vt::kTwoPi * bins;
            int b = static_cast<int>(t);
            if (b >= bins) b = bins - 1;
            const float syn = synthetic_elev[b];
            if (!std::isfinite(syn)) continue;
            const double y = obs_elev[i];
            const double x = static_cast<double>(syn);
            sw += w;
            swx += w * x;
            swy += w * y;
            swxx += w * x * x;
            swyy += w * y * y;
            swxy += w * x * y;
            residuals.push_back(y - x);
            ++used;
        }
        if (used < 8 || sw <= 0) continue;
        const double varx = swxx - swx * swx / sw;
        const double vary = swyy - swy * swy / sw;
        if (varx <= 1e-12 || vary <= 1e-12) continue;
        const double cov = swxy - swx * swy / sw;
        const double score = cov / std::sqrt(varx * vary);
        if (score > best_score) {
            best_score = score;
            best_off = off;
            std::sort(residuals.begin(), residuals.end());
            best_vertical = residuals[residuals.size() / 2];
        }
    }

    if (best_score < -1.0) return -2;
    if (out_offset) *out_offset = best_off;
    if (out_score) *out_score = best_score;
    if (out_vertical) *out_vertical = best_vertical;
    return 0;
}

const char *vt_version_string(void) { return "vantage-core/1.0.0"; }
