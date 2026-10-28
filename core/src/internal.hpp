#ifndef VANTAGE_INTERNAL_H
#define VANTAGE_INTERNAL_H

#include "vantage_core.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace vt {

constexpr double kPi = 3.14159265358979323846;
constexpr double kTwoPi = 6.28318530717958647692;
constexpr double kDeg = kPi / 180.0;
constexpr double kEarthRadius = 6371008.8;      // mean radius, metres
constexpr double kRefractionK = 0.13;           // standard terrestrial refraction
constexpr double kEffRadius = kEarthRadius / (1.0 - kRefractionK);
constexpr double kMinEyeHeight = 0.2;
constexpr double kVisibilityBias = 0.03 * kDeg;

inline double clampd(double v, double lo, double hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}
inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}
inline double wrap_pi(double a) {
    while (a > kPi) a -= kTwoPi;
    while (a < -kPi) a += kTwoPi;
    return a;
}
inline double wrap_two_pi(double a) {
    a = std::fmod(a, kTwoPi);
    if (a < 0) a += kTwoPi;
    return a;
}

struct Grid {
    VTGridInfo info{};
    std::vector<int16_t> data;
    bool valid = false;
    double max_height = -10000.0;

    bool contains(double lat, double lon) const {
        if (!valid) return false;
        const double south = info.lat_north - info.height * info.dlat;
        const double east = info.lon_west + info.width * info.dlon;
        return lat <= info.lat_north + 1e-12 && lat >= south - 1e-12 &&
               lon >= info.lon_west - 1e-12 && lon <= east + 1e-12;
    }

    /* Bilinear sample; NaN outside. */
    double sample(double lat, double lon) const {
        if (!valid) return NAN;
        double row = (info.lat_north - lat) / info.dlat - 0.5;
        double col = (lon - info.lon_west) / info.dlon - 0.5;
        if (row < -0.5 || col < -0.5 || row > info.height - 0.5 ||
            col > info.width - 0.5) {
            return NAN;
        }
        row = clampd(row, 0.0, info.height - 1.0);
        col = clampd(col, 0.0, info.width - 1.0);
        const int r0 = static_cast<int>(row);
        const int c0 = static_cast<int>(col);
        const int r1 = r0 + 1 < info.height ? r0 + 1 : r0;
        const int c1 = c0 + 1 < info.width ? c0 + 1 : c0;
        const double fr = row - r0;
        const double fc = col - c0;
        const double h00 = data[static_cast<size_t>(r0) * info.width + c0];
        const double h01 = data[static_cast<size_t>(r0) * info.width + c1];
        const double h10 = data[static_cast<size_t>(r1) * info.width + c0];
        const double h11 = data[static_cast<size_t>(r1) * info.width + c1];
        const double a = h00 + (h01 - h00) * fc;
        const double b = h10 + (h11 - h10) * fc;
        return a + (b - a) * fr;
    }
};

inline double metres_per_degree_lat(double lat) {
    const double p = lat * kDeg;
    return 111132.92 - 559.82 * std::cos(2 * p) + 1.175 * std::cos(4 * p) -
           0.0023 * std::cos(6 * p);
}

inline double metres_per_degree_lon(double lat) {
    const double p = lat * kDeg;
    return 111412.84 * std::cos(p) - 93.5 * std::cos(3 * p) +
           0.118 * std::cos(5 * p);
}

} // namespace vt

struct VantageTerrain {
    vt::Grid fine;
    vt::Grid coarse;

    const vt::Grid *pick(double lat, double lon) const {
        if (fine.valid && fine.contains(lat, lon)) return &fine;
        if (coarse.valid && coarse.contains(lat, lon)) return &coarse;
        return nullptr;
    }

    double height(double lat, double lon) const {
        const vt::Grid *g = pick(lat, lon);
        return g ? g->sample(lat, lon) : NAN;
    }

    double max_height() const {
        double m = -10000.0;
        if (fine.valid) m = std::fmax(m, fine.max_height);
        if (coarse.valid) m = std::fmax(m, coarse.max_height);
        return m;
    }
};

struct VantageSilhouette {
    int bins = VT_DEFAULT_BINS;
    std::vector<float> elev;
    std::vector<float> dist;
};

struct VantagePlaces {
    std::vector<VTPlace> places;
    std::vector<std::string> names;
    std::vector<std::string> regions;
    std::vector<std::string> wikis;
};

#endif
