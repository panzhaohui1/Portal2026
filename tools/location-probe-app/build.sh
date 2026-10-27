#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
USER_HOME="${HOME:-}"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
JDK17_HOME="${JAVA_HOME_17:-${JAVA_HOME:-}}"

if [[ -z "$SDK_ROOT" ]]; then
    echo "Set ANDROID_SDK_ROOT to the Android SDK containing platform android-35." >&2
    exit 2
fi
if [[ -z "$JDK17_HOME" ]]; then
    echo "Set JAVA_HOME_17 or JAVA_HOME to a JDK 17 installation." >&2
    exit 2
fi

PLATFORM_JAR="${ANDROID_PLATFORM_JAR:-$SDK_ROOT/platforms/android-35/android.jar}"
BUILD_TOOLS_DIR="${ANDROID_BUILD_TOOLS_DIR:-$SDK_ROOT/build-tools/35.0.0}"
AAPT2_BIN="${AAPT2:-$BUILD_TOOLS_DIR/aapt2}"
D8_BIN="${D8:-$BUILD_TOOLS_DIR/d8}"
APKSIGNER_BIN="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"
JAVAC_BIN="${JAVAC:-$JDK17_HOME/bin/javac}"
DEBUG_KEYSTORE="${DEBUG_KEYSTORE:-$USER_HOME/.android/debug.keystore}"
DEBUG_KEY_ALIAS="${DEBUG_KEY_ALIAS:-androiddebugkey}"
DEBUG_KEYSTORE_PASS="${DEBUG_KEYSTORE_PASS:-android}"
DEBUG_KEY_PASS="${DEBUG_KEY_PASS:-android}"

for required in "$PLATFORM_JAR" "$AAPT2_BIN" "$D8_BIN" "$APKSIGNER_BIN" "$JAVAC_BIN" "$DEBUG_KEYSTORE"; do
    if [[ ! -f "$required" && ! -x "$required" ]]; then
        echo "Required build input is missing: $required" >&2
        exit 2
    fi
done

JAVAC_VERSION="$("$JAVAC_BIN" -version 2>&1)"
if [[ "$JAVAC_VERSION" != "javac 17" && "$JAVAC_VERSION" != "javac 17."* ]]; then
    echo "JAVAC_BIN must be JDK 17: $JAVAC_BIN" >&2
    exit 2
fi

export JAVA_HOME="$JDK17_HOME"
export PATH="$JDK17_HOME/bin:$PATH"

BUILD_ROOT="$PROJECT_DIR/build"
mkdir -p "$BUILD_ROOT"
RUN_DIR="$(mktemp -d "$BUILD_ROOT/run-XXXXXXXX")"
mkdir -p "$RUN_DIR/classes" "$RUN_DIR/dex"

"$AAPT2_BIN" link \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --min-sdk-version 33 \
    --target-sdk-version 35 \
    -I "$PLATFORM_JAR" \
    -o "$RUN_DIR/resources.ap_"

JAVA_SOURCES=()
while IFS= read -r -d '' source_file; do
    JAVA_SOURCES+=("$source_file")
done < <(find "$PROJECT_DIR/src" -type f -name '*.java' -print0)
if [[ "${#JAVA_SOURCES[@]}" -eq 0 ]]; then
    echo "No Java sources found." >&2
    exit 4
fi

"$JAVAC_BIN" \
    -source 17 \
    -target 17 \
    -encoding UTF-8 \
    -classpath "$PLATFORM_JAR" \
    -d "$RUN_DIR/classes" \
    "${JAVA_SOURCES[@]}"

CLASS_FILES=()
while IFS= read -r -d '' class_file; do
    CLASS_FILES+=("$class_file")
done < <(find "$RUN_DIR/classes" -type f -name '*.class' -print0)
if [[ "${#CLASS_FILES[@]}" -eq 0 ]]; then
    echo "javac produced no class files." >&2
    exit 5
fi

"$D8_BIN" \
    --lib "$PLATFORM_JAR" \
    --min-api 33 \
    --output "$RUN_DIR/dex" \
    "${CLASS_FILES[@]}"

UNSIGNED_APK="$RUN_DIR/location-probe-unsigned.apk"
SIGNED_APK="$RUN_DIR/location-probe.apk"
cp "$RUN_DIR/resources.ap_" "$UNSIGNED_APK"
zip -q -j "$UNSIGNED_APK" "$RUN_DIR/dex/classes.dex"

"$APKSIGNER_BIN" sign \
    --ks "$DEBUG_KEYSTORE" \
    --ks-key-alias "$DEBUG_KEY_ALIAS" \
    --ks-pass "pass:$DEBUG_KEYSTORE_PASS" \
    --key-pass "pass:$DEBUG_KEY_PASS" \
    --out "$SIGNED_APK" \
    "$UNSIGNED_APK"

"$APKSIGNER_BIN" verify --verbose "$SIGNED_APK" > "$RUN_DIR/apksigner-verify.txt"
printf 'APK=%s\nRUN_DIR=%s\n' "$SIGNED_APK" "$RUN_DIR"
