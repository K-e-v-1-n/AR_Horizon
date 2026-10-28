#!/usr/bin/env bash
# Builds the JNI core for the Android ABIs used by the APK.
# Intended for Linux/macOS CI. Windows uses android/build_native.ps1.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK}" && -n "${SDK}" ]]; then
  if [[ -d "${SDK}/ndk/27.2.12479018" ]]; then
    NDK="${SDK}/ndk/27.2.12479018"
  elif [[ -d "${SDK}/ndk-bundle" ]]; then
    NDK="${SDK}/ndk-bundle"
  fi
fi
if [[ -z "${NDK}" || ! -d "${NDK}" ]]; then
  echo "error: Android NDK not found. Set ANDROID_NDK_HOME." >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  Linux*)  HOST_TAG="linux-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "error: NDK toolchain not found at ${TOOLCHAIN}" >&2
  exit 1
fi

OUT="${ROOT}/android/app/src/main/jniLibs"
SOURCES=("${ROOT}/core/src/core.cpp" "${ROOT}/core/jni/vantage_jni.cpp")
INCLUDES=("-I" "${ROOT}/core/include" "-I" "${ROOT}/core/src")
FLAGS=(-shared -fPIC -O3 -std=c++17 -Wl,--gc-sections -Wl,-z,max-page-size=16384
       -static-libstdc++ -o)

build_abi() {
  local compiler="$1" abi="$2"
  mkdir -p "${OUT}/${abi}"
  local out="${OUT}/${abi}/libvantage_core.so"
  echo "building ${abi}"
  "${TOOLCHAIN}/${compiler}" "${FLAGS[@]}" "${out}" "${INCLUDES[@]}" "${SOURCES[@]}" -llog
  echo "  ${out}  $(du -h "${out}" | cut -f1)"
}

build_abi "aarch64-linux-android28-clang++" "arm64-v8a"
build_abi "x86_64-linux-android28-clang++" "x86_64"

echo "native libraries ready"
