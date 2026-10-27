#!/usr/bin/env bash
set -euo pipefail

# Reproducible host-side build for the app_process probe.  All generated files
# stay below this directory; no Android device or NDK is needed.

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_HOME_VALUE="${JAVA_HOME:-}"
ANDROID_HOME_VALUE="${ANDROID_HOME:-}"
ANDROID_PLATFORM_VALUE="${ANDROID_PLATFORM:-android-35}"
ANDROID_BUILD_TOOLS_VALUE="${ANDROID_BUILD_TOOLS:-35.0.0}"

if [ -z "$JAVA_HOME_VALUE" ]; then
    echo "ERROR: JAVA_HOME is not set" >&2
    exit 2
fi
if [ -z "$ANDROID_HOME_VALUE" ]; then
    echo "ERROR: ANDROID_HOME is not set" >&2
    exit 2
fi

JAVAC="$JAVA_HOME_VALUE/bin/javac"
JAR="$JAVA_HOME_VALUE/bin/jar"
ANDROID_JAR="$ANDROID_HOME_VALUE/platforms/$ANDROID_PLATFORM_VALUE/android.jar"
D8="$ANDROID_HOME_VALUE/build-tools/$ANDROID_BUILD_TOOLS_VALUE/d8"

for required_path in "$JAVAC" "$JAR" "$ANDROID_JAR" "$D8"; do
    if [ ! -f "$required_path" ] || [ ! -x "$required_path" ] && [ "$required_path" != "$ANDROID_JAR" ]; then
        echo "ERROR: required tool or file not found: $required_path" >&2
        exit 3
    fi
done

BUILD_ROOT="$SCRIPT_DIR/build"
mkdir -p "$BUILD_ROOT"
BUILD_DIR="$(mktemp -d "$BUILD_ROOT/run-XXXXXXXX")"
CLASSES_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
COMPILED_JAR="$BUILD_DIR/location-probe-classes.jar"
RUNTIME_JAR="$BUILD_DIR/location-probe.dex.jar"
RUNTIME_DEX="$BUILD_DIR/classes.dex"

mkdir -p "$CLASSES_DIR" "$DEX_DIR"

SOURCE_FILE="$SCRIPT_DIR/src/moe/fuqiuluo/locationprobe/LocationProbe.java"

"$JAVAC" \
    -encoding UTF-8 \
    -source 8 \
    -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES_DIR" \
    "$SOURCE_FILE"

"$JAR" --create --file "$COMPILED_JAR" -C "$CLASSES_DIR" .

"$D8" \
    --min-api 33 \
    --lib "$ANDROID_JAR" \
    --output "$DEX_DIR" \
    "$COMPILED_JAR"

cp "$DEX_DIR/classes.dex" "$RUNTIME_DEX"
"$JAR" --create --file "$RUNTIME_JAR" -C "$DEX_DIR" classes.dex

echo "Built: $RUNTIME_DEX"
echo "Built: $RUNTIME_JAR"
echo "Entry: moe.fuqiuluo.locationprobe.LocationProbe"
