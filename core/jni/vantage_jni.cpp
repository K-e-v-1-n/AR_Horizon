#include "vantage_core.h"
#include "internal.hpp"

#include <jni.h>

#include <cstring>
#include <vector>

namespace {

constexpr double kPiJ = 3.14159265358979323846;

jstring new_string(JNIEnv *env, const char *s) {
    return env->NewStringUTF(s ? s : "");
}

std::string jstr(JNIEnv *env, jobjectArray arr, jsize i) {
    auto o = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
    if (!o) return {};
    const char *c = env->GetStringUTFChars(o, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(o, c);
    env->DeleteLocalRef(o);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_vantage_core_NativeCore_nativeCreateTerrain(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(vt_terrain_create());
}

JNIEXPORT void JNICALL
Java_app_vantage_core_NativeCore_nativeDestroyTerrain(JNIEnv *, jclass,
                                                      jlong handle) {
    if (handle) vt_terrain_destroy(reinterpret_cast<VantageTerrain *>(handle));
}

JNIEXPORT jint JNICALL
Java_app_vantage_core_NativeCore_nativeSetGrid(JNIEnv *env, jclass,
                                               jlong handle, jint level,
                                               jshortArray data, jdouble latN,
                                               jdouble lonW, jdouble dlat,
                                               jdouble dlon, jint w, jint h) {
    if (!handle || !data) return -1;
    const jsize n = env->GetArrayLength(data);
    if (n < w * h) return -2;
    jshort *p = env->GetShortArrayElements(data, nullptr);
    if (!p) return -3;
    VTGridInfo info{};
    info.lat_north = latN;
    info.lon_west = lonW;
    info.dlat = dlat;
    info.dlon = dlon;
    info.width = w;
    info.height = h;
    const int rc = vt_terrain_set_grid(
            reinterpret_cast<VantageTerrain *>(handle), level,
            reinterpret_cast<const int16_t *>(p), info);
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_app_vantage_core_NativeCore_nativeHasGrid(JNIEnv *, jclass, jlong handle,
                                               jint level) {
    return handle ? vt_terrain_has_grid(
                            reinterpret_cast<VantageTerrain *>(handle),
                            level)
                  : 0;
}

JNIEXPORT jdouble JNICALL
Java_app_vantage_core_NativeCore_nativeTerrainHeight(JNIEnv *, jclass,
                                                     jlong handle, jdouble lat,
                                                     jdouble lon) {
    if (!handle) return NAN;
    return vt_terrain_height(reinterpret_cast<VantageTerrain *>(handle), lat,
                             lon);
}

JNIEXPORT jlong JNICALL
Java_app_vantage_core_NativeCore_nativeCreateSilhouette(JNIEnv *, jclass,
                                                        jint bins) {
    return reinterpret_cast<jlong>(vt_silhouette_create(bins));
}

JNIEXPORT void JNICALL
Java_app_vantage_core_NativeCore_nativeDestroySilhouette(JNIEnv *, jclass,
                                                         jlong handle) {
    if (handle)
        vt_silhouette_destroy(reinterpret_cast<VantageSilhouette *>(handle));
}

JNIEXPORT jint JNICALL
Java_app_vantage_core_NativeCore_nativeComputeSilhouette(
        JNIEnv *, jclass, jlong terrain, jlong sil, jdouble lat, jdouble lon,
        jdouble eye) {
    if (!terrain || !sil) return -1;
    return vt_silhouette_compute(reinterpret_cast<VantageSilhouette *>(sil),
                                 reinterpret_cast<VantageTerrain *>(terrain),
                                 lat, lon, eye);
}

JNIEXPORT jfloatArray JNICALL
Java_app_vantage_core_NativeCore_nativeSilhouetteElev(JNIEnv *env, jclass,
                                                      jlong sil) {
    auto *s = reinterpret_cast<VantageSilhouette *>(sil);
    if (!s) return nullptr;
    jfloatArray out = env->NewFloatArray(s->bins);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, s->bins, s->elev.data());
    return out;
}

JNIEXPORT jlong JNICALL
Java_app_vantage_core_NativeCore_nativeCreatePlaces(
        JNIEnv *env, jclass, jintArray kinds, jdoubleArray lat,
        jdoubleArray lon, jfloatArray ele, jlongArray pop, jfloatArray rank,
        jobjectArray names, jobjectArray regions, jobjectArray wikis) {
    if (!kinds || !lat || !lon || !names) return 0;
    const jsize n = env->GetArrayLength(kinds);
    if (n <= 0) return 0;

    if (env->GetArrayLength(lat) < n || env->GetArrayLength(lon) < n ||
        env->GetArrayLength(names) < n ||
        (regions && env->GetArrayLength(regions) < n) ||
        (wikis && env->GetArrayLength(wikis) < n) ||
        (ele && env->GetArrayLength(ele) < n) ||
        (pop && env->GetArrayLength(pop) < n) ||
        (rank && env->GetArrayLength(rank) < n)) {
        return 0;
    }

    std::vector<jint> k(static_cast<size_t>(n));
    std::vector<jdouble> la(static_cast<size_t>(n)), lo(static_cast<size_t>(n));
    std::vector<jfloat> el(static_cast<size_t>(n)), ra(static_cast<size_t>(n));
    std::vector<jlong> po(static_cast<size_t>(n));
    env->GetIntArrayRegion(kinds, 0, n, k.data());
    env->GetDoubleArrayRegion(lat, 0, n, la.data());
    env->GetDoubleArrayRegion(lon, 0, n, lo.data());
    if (ele) env->GetFloatArrayRegion(ele, 0, n, el.data());
    if (pop) env->GetLongArrayRegion(pop, 0, n, po.data());
    if (rank) env->GetFloatArrayRegion(rank, 0, n, ra.data());

    std::vector<std::string> name_store(static_cast<size_t>(n));
    std::vector<std::string> region_store(static_cast<size_t>(n));
    std::vector<std::string> wiki_store(static_cast<size_t>(n));
    std::vector<VTPlace> places(static_cast<size_t>(n));

    for (jsize i = 0; i < n; ++i) {
        name_store[static_cast<size_t>(i)] = jstr(env, names, i);
        if (regions) region_store[static_cast<size_t>(i)] = jstr(env, regions, i);
        if (wikis) wiki_store[static_cast<size_t>(i)] = jstr(env, wikis, i);
        VTPlace &p = places[static_cast<size_t>(i)];
        p.id = i;
        p.kind = k[static_cast<size_t>(i)];
        p.lat = la[static_cast<size_t>(i)];
        p.lon = lo[static_cast<size_t>(i)];
        p.ele_m = ele ? el[static_cast<size_t>(i)] : -10000.0f;
        p.population = pop ? po[static_cast<size_t>(i)] : 0;
        p.rank = rank ? ra[static_cast<size_t>(i)] : 0.0f;
        p.name = name_store[static_cast<size_t>(i)].c_str();
        p.region = region_store[static_cast<size_t>(i)].c_str();
        p.wiki = wiki_store[static_cast<size_t>(i)].c_str();
    }

    VantagePlaces *created = vt_places_create(places.data(), n);
    if (created) {
        // vt_places_create copies the strings into its own storage.
    }
    return reinterpret_cast<jlong>(created);
}

JNIEXPORT void JNICALL
Java_app_vantage_core_NativeCore_nativeDestroyPlaces(JNIEnv *, jclass,
                                                     jlong handle) {
    if (handle) vt_places_destroy(reinterpret_cast<VantagePlaces *>(handle));
}

JNIEXPORT jfloatArray JNICALL
Java_app_vantage_core_NativeCore_nativeSolveLabels(
        JNIEnv *env, jclass, jlong terrain, jlong sil, jlong places,
        jdouble lat, jdouble lon, jdouble eye, jdouble yawDeg, jdouble pitchDeg,
        jdouble rollDeg, jdouble hfovDeg, jint width, jint height,
        jfloatArray nameWidths, jint maxLabels) {
    // Packed result: element 0 is the label count, then 12 floats per label.
    if (!terrain || !sil || !places) return env->NewFloatArray(0);
    VTCamera cam{};
    cam.yaw_rad = yawDeg * kPiJ / 180.0;
    cam.pitch_rad = pitchDeg * kPiJ / 180.0;
    cam.roll_rad = rollDeg * kPiJ / 180.0;
    cam.hfov_rad = hfovDeg * kPiJ / 180.0;
    cam.width = width;
    cam.height = height;

    std::vector<float> widths;
    const float *wp = nullptr;
    if (nameWidths) {
        const jsize n = env->GetArrayLength(nameWidths);
        if (n == vt_places_count(reinterpret_cast<VantagePlaces *>(places))) {
            widths.resize(static_cast<size_t>(n));
            env->GetFloatArrayRegion(nameWidths, 0, n, widths.data());
            wp = widths.data();
        }
    }
    VTLabel labels[VT_MAX_LABELS];
    const int n = vt_solve(
            reinterpret_cast<VantageTerrain *>(terrain),
            reinterpret_cast<VantageSilhouette *>(sil), lat, lon, eye,
            reinterpret_cast<VantagePlaces *>(places), &cam, wp, maxLabels,
            labels);
    const int stride = 12;
    std::vector<float> flat(static_cast<size_t>(n + 1) * stride, 0.0f);
    flat[0] = static_cast<float>(n);
    for (int i = 0; i < n; ++i) {
        const VTLabel &L = labels[i];
        float *v = &flat[static_cast<size_t>(i + 1) * stride];
        v[0] = static_cast<float>(L.place_index);
        v[1] = static_cast<float>(L.az_rad * 180.0 / kPiJ);
        v[2] = static_cast<float>(L.elev_rad * 180.0 / kPiJ);
        v[3] = static_cast<float>(L.dist_m);
        v[4] = L.x;
        v[5] = L.y;
        v[6] = static_cast<float>(L.visible);
        v[7] = static_cast<float>(L.has_terrain);
        v[8] = L.score;
        v[9] = L.box_w;
        v[10] = L.box_h;
        v[11] = static_cast<float>(L.target_ele_m);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(flat.size()),
                             flat.data());
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_app_vantage_core_NativeCore_nativeFitSkyline(
        JNIEnv *env, jclass, jfloatArray synthetic, jfloatArray obsAz,
        jfloatArray obsElev, jfloatArray obsWeight, jfloat maxOffsetDeg) {
    if (!synthetic || !obsAz || !obsElev) {
        return env->NewFloatArray(0);
    }
    const jsize bins = env->GetArrayLength(synthetic);
    const jsize count = env->GetArrayLength(obsAz);
    if (count != env->GetArrayLength(obsElev)) return env->NewFloatArray(0);

    std::vector<float> syn(static_cast<size_t>(bins)), az(static_cast<size_t>(count)),
            el(static_cast<size_t>(count)), wt(static_cast<size_t>(count), 1.0f);
    env->GetFloatArrayRegion(synthetic, 0, bins, syn.data());
    env->GetFloatArrayRegion(obsAz, 0, count, az.data());
    env->GetFloatArrayRegion(obsElev, 0, count, el.data());
    if (obsWeight && env->GetArrayLength(obsWeight) == count) {
        env->GetFloatArrayRegion(obsWeight, 0, count, wt.data());
    }

    double off = 0, score = 0, vertical = 0;
    const int rc = vt_fit_skyline(syn.data(), bins, az.data(), el.data(),
                                  wt.data(), count, maxOffsetDeg * kPiJ / 180.0,
                                  0.05 * kPiJ / 180.0, &off, &score, &vertical);
    jfloatArray out = env->NewFloatArray(3);
    if (!out) return nullptr;
    const float vals[3] = {
            rc == 0 ? static_cast<float>(off * 180.0 / kPiJ) : 0.0f,
            rc == 0 ? static_cast<float>(score) : 0.0f,
            rc == 0 ? static_cast<float>(vertical * 180.0 / kPiJ) : 0.0f};
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

JNIEXPORT jstring JNICALL
Java_app_vantage_core_NativeCore_nativeVersion(JNIEnv *env, jclass) {
    return new_string(env, vt_version_string());
}

} // extern "C"
