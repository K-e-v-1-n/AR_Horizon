#include "vantage_core.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(cond)                                                            \
    do {                                                                       \
        ++g_checks;                                                            \
        if (!(cond)) {                                                         \
            ++g_failures;                                                      \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);        \
        }                                                                      \
    } while (0)

#define CHECK_NEAR(a, b, tol)                                                  \
    do {                                                                       \
        ++g_checks;                                                            \
        const double _a = (a);                                                 \
        const double _b = (b);                                                 \
        if (!(std::fabs(_a - _b) <= (tol))) {                                  \
            ++g_failures;                                                      \
            std::printf("FAIL %s:%d  %s = %.6f, expected %.6f ± %.6f\n",       \
                        __FILE__, __LINE__, #a, _a, _b, (double)(tol));        \
        }                                                                      \
    } while (0)

constexpr double kPi = 3.14159265358979323846;
constexpr double kDeg = kPi / 180.0;

struct TestTerrain {
    std::vector<int16_t> data;
    VTGridInfo info{};
    VantageTerrain *terrain = nullptr;

    TestTerrain(double lat_north, double lon_west, int w, int h, int16_t height) {
        info.lat_north = lat_north;
        info.lon_west = lon_west;
        info.dlat = 1.0 / 3600.0;
        info.dlon = 1.0 / 3600.0;
        info.width = w;
        info.height = h;
        data.assign(static_cast<size_t>(w) * h, height);
        terrain = vt_terrain_create();
    }

    void set(double lat, double lon, int16_t height) {
        const int row = static_cast<int>((info.lat_north - lat) / info.dlat);
        const int col = static_cast<int>((lon - info.lon_west) / info.dlon);
        if (row < 0 || col < 0 || row >= info.height || col >= info.width) return;
        data[static_cast<size_t>(row) * info.width + col] = height;
    }

    void commit(int level = 0) {
        vt_terrain_set_grid(terrain, level, data.data(), info);
    }

    ~TestTerrain() { vt_terrain_destroy(terrain); }
};

void test_geodesy() {
    // Gornergrat -> Matterhorn, independently checked against a great-circle
    // computation (WGS84).
    const double lat1 = 45.98331, lon1 = 7.78470;
    const double lat2 = 45.97639, lon2 = 7.65861;
    const double d = vt_distance_m(lat1, lon1, lat2, lon2);
    const double b = vt_bearing_rad(lat1, lon1, lat2, lon2) / kDeg;
    CHECK_NEAR(d / 1000.0, 9.88, 0.12);
    CHECK_NEAR(b, 265.4, 0.6);
    CHECK_NEAR(vt_curvature_drop_m(50000.0), 170.7, 2.0);
    CHECK_NEAR(vt_curvature_drop_m(0.0), 0.0, 1e-9);
}

void test_flat_horizon() {
    TestTerrain t(45.01, 6.99, 256, 256, 500);
    t.commit();
    VantageSilhouette *s = vt_silhouette_create(360);
    const int rc = vt_silhouette_compute(s, t.terrain, 45.005, 7.005, 1.7);
    CHECK(rc == 0);
    const float e = vt_silhouette_elev(s, 0.0);
    CHECK(std::isfinite(e));
    CHECK(e < 0.0f);
    CHECK(e > -0.25f * static_cast<float>(kDeg));
    vt_silhouette_destroy(s);
}

void test_ridge_and_visibility() {
    TestTerrain t(45.06, 6.94, 500, 500, 0);
    t.commit();
    // A plateau ~5 km due east of the observer, 1000 m high.
    for (int r = 0; r < 60; ++r) {
        for (int c = 0; c < 60; ++c) {
            t.set(45.0060 - r * (1.0 / 3600.0), 7.0450 + c * (1.0 / 3600.0), 1000);
        }
    }
    t.commit();

    VantageSilhouette *s = vt_silhouette_create(3600);
    CHECK(vt_silhouette_compute(s, t.terrain, 45.0, 7.0, 1.7) == 0);
    const float east = vt_silhouette_elev(s, 90.0 * kDeg) / static_cast<float>(kDeg);
    CHECK_NEAR(east, 15.9, 1.2);

    VTPlace places[3]{};
    places[0].id = 1;
    places[0].kind = VT_PEAK;
    places[0].lat = 45.0;
    places[0].lon = 7.05;
    places[0].ele_m = 1200;
    places[0].name = "Test Peak";
    places[1].id = 2;
    places[1].kind = VT_VILLAGE;
    places[1].lat = 45.0;
    places[1].lon = 7.09;
    places[1].ele_m = 100;
    places[1].name = "Hidden Town";
    places[2].id = 3;
    places[2].kind = VT_PEAK;
    places[2].lat = 45.004;
    places[2].lon = 7.02;
    places[2].ele_m = 900;
    places[2].name = "Close Hill";

    VantagePlaces *p = vt_places_create(places, 3);
    CHECK(p != nullptr);

    VTCamera cam{};
    cam.yaw_rad = 90 * kDeg;
    cam.pitch_rad = 10 * kDeg;
    cam.hfov_rad = 60 * kDeg;
    cam.width = 800;
    cam.height = 600;

    VTLabel labels[16];
    const int n = vt_solve(t.terrain, s, 45.0, 7.0, 1.7, p, &cam, nullptr, 16,
                           labels);
    CHECK(n >= 2);
    bool found_peak = false, found_hidden = false, found_close = false;
    for (int i = 0; i < n; ++i) {
        const char *name = places[labels[i].place_index].name;
        if (std::strcmp(name, "Test Peak") == 0) {
            found_peak = true;
            CHECK(labels[i].visible == 1);
            CHECK(labels[i].has_terrain == 1);
        } else if (std::strcmp(name, "Hidden Town") == 0) {
            found_hidden = true;
            CHECK(labels[i].visible == 0);
        } else if (std::strcmp(name, "Close Hill") == 0) {
            found_close = true;
        }
    }
    CHECK(found_peak);
    CHECK(found_hidden);
    CHECK(found_close);

    // Declutter: two features at the same place must not produce two labels.
    VTPlace twin[2] = {places[0], places[0]};
    twin[1].name = "Test Peak Twin";
    twin[1].ele_m = 1201;
    VantagePlaces *pt = vt_places_create(twin, 2);
    VTLabel two[8];
    const int m = vt_solve(t.terrain, s, 45.0, 7.0, 1.7, pt, &cam, nullptr, 8, two);
    CHECK(m == 1);
    vt_places_destroy(pt);
    vt_places_destroy(p);
    vt_silhouette_destroy(s);
}

void test_projection() {
    VTCamera cam{};
    cam.yaw_rad = 0;
    cam.pitch_rad = 0;
    cam.roll_rad = 0;
    cam.hfov_rad = 90 * kDeg;
    cam.width = 200;
    cam.height = 100;

    float x = 0, y = 0;
    int on = 0;
    vt_project(&cam, 0, 0, &x, &y, &on);
    CHECK(on == 1);
    CHECK_NEAR(x, 100.0, 0.01);
    CHECK_NEAR(y, 50.0, 0.01);

    vt_project(&cam, 26.565 * kDeg, 0, &x, &y, &on);
    CHECK(on == 1);
    CHECK_NEAR(x, 150.0, 0.05);
    CHECK_NEAR(y, 50.0, 0.01);

    vt_project(&cam, 180 * kDeg, 0, &x, &y, &on);
    CHECK(on == 0);

    // Roll must tilt the horizon without moving its centre.
    cam.roll_rad = 10 * kDeg;
    vt_project(&cam, 0, 0, &x, &y, &on);
    CHECK(on == 1);
    CHECK_NEAR(x, 100.0, 0.01);
    CHECK_NEAR(y, 50.0, 0.01);
    vt_project(&cam, 20 * kDeg, 0, &x, &y, &on);
    CHECK(on == 1);
    CHECK(x > 100.0f);
    CHECK(y < 50.0f);
}

void test_skyline_fit() {
    const int bins = 3600;
    auto profile = [](double az) {
        const double a = 2.5 * std::exp(-std::pow((az - 235.0) / 6.0, 2.0));
        const double b = 5.0 * std::exp(-std::pow((az - 272.0) / 3.0, 2.0));
        const double c = 3.5 * std::exp(-std::pow((az - 307.0) / 8.0, 2.0));
        const double d = 0.25 * std::sin(az * 1.9);
        return 1.5 + a + b + c + d;
    };
    std::vector<float> synthetic(static_cast<size_t>(bins));
    for (int i = 0; i < bins; ++i) {
        const double az = (i + 0.5) * 360.0 / bins;
        synthetic[static_cast<size_t>(i)] = static_cast<float>(profile(az) * kDeg);
    }

    std::vector<float> az, el, w;
    const double shift = 1.5; // degrees
    for (double a = 200.0; a <= 320.0; a += 0.25) {
        const double true_az = a + shift;
        int b = static_cast<int>(true_az / 360.0 * bins) % bins;
        az.push_back(static_cast<float>(a * kDeg));
        el.push_back(synthetic[static_cast<size_t>(b)]);
        w.push_back(1.0f);
    }

    double off = 0, score = 0, vertical = 0;
    const int rc = vt_fit_skyline(synthetic.data(), bins, az.data(), el.data(),
                                  w.data(), static_cast<int>(az.size()),
                                  12 * kDeg, 0.05 * kDeg, &off, &score, &vertical);
    CHECK(rc == 0);
    CHECK_NEAR(off / kDeg, 1.5, 0.08);
    CHECK(score > 0.99);
    CHECK_NEAR(vertical, 0.0, 1e-5);
}

void test_dem_heights() {
    TestTerrain t(45.01, 6.99, 256, 256, 500);
    for (int r = -1; r <= 1; ++r) {
        for (int c = -1; c <= 1; ++c) {
            t.set(45.005 + r / 3600.0, 7.005 + c / 3600.0, 1500);
        }
    }
    t.commit();
    CHECK_NEAR(vt_terrain_height(t.terrain, 45.005, 7.005), 1500.0, 6.0);
    CHECK_NEAR(vt_eye_altitude_m(t.terrain, 45.005, 7.005, 1.7), 1501.7, 6.0);
    CHECK(std::isnan(vt_terrain_height(t.terrain, 40.0, 7.0)));
}

} // namespace

int main() {
    test_geodesy();
    test_dem_heights();
    test_flat_horizon();
    test_ridge_and_visibility();
    test_projection();
    test_skyline_fit();
    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
