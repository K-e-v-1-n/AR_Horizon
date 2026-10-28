#include "vantage_core.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct GridFile {
    VTGridInfo info{};
    std::vector<int16_t> data;
};

bool read_grid(const char *path, GridFile &g) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return false;
    char magic[4] = {0};
    int32_t level = 0;
    int32_t w = 0, h = 0;
    if (std::fread(magic, 1, 4, f) != 4 || std::memcmp(magic, "VTG1", 4) != 0) {
        std::fclose(f);
        return false;
    }
    if (std::fread(&level, sizeof(level), 1, f) != 1 ||
        std::fread(&w, sizeof(w), 1, f) != 1 ||
        std::fread(&h, sizeof(h), 1, f) != 1 ||
        std::fread(&g.info.lat_north, sizeof(double), 1, f) != 1 ||
        std::fread(&g.info.lon_west, sizeof(double), 1, f) != 1 ||
        std::fread(&g.info.dlat, sizeof(double), 1, f) != 1 ||
        std::fread(&g.info.dlon, sizeof(double), 1, f) != 1) {
        std::fclose(f);
        return false;
    }
    g.info.width = w;
    g.info.height = h;
    g.data.resize(static_cast<size_t>(w) * h);
    const size_t got = std::fread(g.data.data(), sizeof(int16_t), g.data.size(), f);
    std::fclose(f);
    return got == g.data.size();
}

void usage() {
    std::printf(
            "vantage-cli — offline horizon probe\n"
            "\n"
            "  vantage-cli silhouette --fine <fine.vtgrid> [--coarse <coarse.vtgrid>]\n"
            "                         [--lat <deg>] [--lon <deg>] [--eye <m>]\n"
            "                         [--bins <n>] [--csv <path>]\n"
            "  vantage-cli bench --fine <fine.vtgrid> [--coarse <coarse.vtgrid>]\n"
            "                    [--lat ..] [--lon ..] [--eye ..] [--iters <n>]\n"
            "  vantage-cli version\n");
}

double arg(int argc, char **argv, const char *name, double fallback) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], name) == 0) return std::atof(argv[i + 1]);
    }
    return fallback;
}

const char *argstr(int argc, char **argv, const char *name) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], name) == 0) return argv[i + 1];
    }
    return nullptr;
}

VantageTerrain *load(int argc, char **argv) {
    const char *fine = argstr(argc, argv, "--fine");
    const char *coarse = argstr(argc, argv, "--coarse");
    if (!fine) return nullptr;
    VantageTerrain *t = vt_terrain_create();
    GridFile f;
    if (!read_grid(fine, f) || vt_terrain_set_grid(t, 0, f.data.data(), f.info) != 0) {
        vt_terrain_destroy(t);
        return nullptr;
    }
    if (coarse) {
        GridFile c;
        if (read_grid(coarse, c)) vt_terrain_set_grid(t, 1, c.data.data(), c.info);
    }
    return t;
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        usage();
        return 2;
    }
    const std::string cmd = argv[1];
    if (cmd == "version") {
        std::printf("%s\n", vt_version_string());
        return 0;
    }
    const bool bench = cmd == "bench";
    if (cmd != "silhouette" && !bench) {
        usage();
        return 2;
    }

    VantageTerrain *t = load(argc, argv);
    if (!t) {
        std::fprintf(stderr, "error: could not read terrain grid\n");
        return 1;
    }
    const double lat = arg(argc, argv, "--lat", 45.9833);
    const double lon = arg(argc, argv, "--lon", 7.7847);
    const double eye = arg(argc, argv, "--eye", 1.7);
    const int bins = static_cast<int>(arg(argc, argv, "--bins", 3600));
    const int iters = static_cast<int>(arg(argc, argv, "--iters", bench ? 5 : 1));

    VantageSilhouette *s = vt_silhouette_create(bins);
    const double eye_abs = vt_eye_altitude_m(t, lat, lon, eye);
    std::printf("position %.5f, %.5f  ground %.1f m  eye %.1f m  range %.1f km\n",
                lat, lon,
                std::isfinite(eye_abs) ? eye_abs - eye : NAN, eye_abs,
                vt_terrain_range_m(t, lat, lon) / 1000.0);

    int rc = -1;
    double worst = 0, total = 0;
    for (int i = 0; i < iters; ++i) {
        const auto t0 = std::chrono::high_resolution_clock::now();
        rc = vt_silhouette_compute(s, t, lat, lon, eye);
        const auto t1 = std::chrono::high_resolution_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        total += ms;
        if (ms > worst) worst = ms;
        if (i == 0) {
            std::printf("silhouette %d bins in %.1f ms%s\n", bins, ms,
                        rc == 0 ? "" : " (failed)");
        }
    }
    if (bench) std::printf("bench: %d iters, avg %.1f ms, worst %.1f ms\n",
                           iters, total / iters, worst);
    if (rc != 0) {
        vt_silhouette_destroy(s);
        vt_terrain_destroy(t);
        return 3;
    }

    constexpr double kPi = 3.14159265358979323846;
    const char *csv = argstr(argc, argv, "--csv");
    FILE *out = csv ? std::fopen(csv, "w") : nullptr;
    if (out) std::fprintf(out, "az_deg,elev_deg,dist_m\n");

    double lo = 1e9, hi = -1e9, sum = 0;
    int n = 0;
    for (int b = 0; b < bins; ++b) {
        const double az = (b + 0.5) * kPi * 2.0 / bins;
        const float e = vt_silhouette_elev(s, az);
        const float d = vt_silhouette_dist(s, az);
        if (!std::isfinite(e)) continue;
        lo = std::fmin(lo, e);
        hi = std::fmax(hi, e);
        sum += e;
        ++n;
        if (out) {
            std::fprintf(out, "%.3f,%.4f,%.1f\n", az * 180.0 / kPi,
                         e * 180.0 / kPi, d);
        }
    }
    if (out) std::fclose(out);
    std::printf("horizon: %.2f° .. %.2f°  mean %.2f°  over %d/%d bins\n",
                lo * 180.0 / kPi, hi * 180.0 / kPi,
                (sum / (n ? n : 1)) * 180.0 / kPi, n, bins);

    vt_silhouette_destroy(s);
    vt_terrain_destroy(t);
    return 0;
}
