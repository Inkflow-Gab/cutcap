#!/usr/bin/env bash
# Builds a Java compile environment for the Android module in a sandbox without the Android
# SDK, Google Maven, or a system JDK: a portable JRE+ECJ (Eclipse compiler), the android-36
# platform stubs, standalone AndroidX stubs from this repo, and Capacitor's own sources from
# node_modules. Then compiles the app module and reports real errors.
#
# This is a local development aid, not part of the app build. CI uses the real SDK and Gradle.
#
#   bash scripts/check-android-java.sh
#
# Everything it downloads goes to $OPENROLL_JAVA_CHECK_DIR (default /tmp/openroll-java-check).
set -euo pipefail

OPENROLL=$(cd "$(dirname "$0")/.." && pwd)
STUBS="$OPENROLL/scripts/java-check/stubs"
WORK=${OPENROLL_JAVA_CHECK_DIR:-/tmp/openroll-java-check}
mkdir -p "$WORK"
cd "$WORK"

# --- A Java runtime. Prefer one already on PATH; otherwise unpack the portable jdk4py wheel,
# which is a full JRE and the only JDK-shaped thing reachable from this sandbox.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA=$(command -v java)
elif [ -x "$WORK/java/bin/java" ]; then
  JAVA="$WORK/java/bin/java"
else
  echo "No Java found; fetching a portable runtime..."
  python3 -m venv "$WORK/venv" >/dev/null 2>&1 || python3 -m venv --without-pip "$WORK/venv"
  "$WORK/venv/bin/pip" install --quiet --disable-pip-version-check jdk4py==21.0.8.2
  cp -r "$WORK"/venv/lib/python3*/site-packages/jdk4py/java-runtime "$WORK/java"
  JAVA="$WORK/java/bin/java"
fi

fetch() { # url dest
  [ -f "$2" ] || curl -fsSL -o "$2" "$1" -H "Accept: application/vnd.github.raw"
}
fetch "https://api.github.com/repos/marcust/ecj4ant/contents/lib/ecj-4.4.jar" "$WORK/ecj.jar"
fetch "https://api.github.com/repos/Sable/android-platforms/contents/android-36/android.jar" "$WORK/android.jar"

ecj() { # outdir classpath files...
  local out=$1 cp=$2
  shift 2
  rm -rf "$out" && mkdir -p "$out"
  "$JAVA" -cp "$WORK/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
    -source 1.8 -target 1.8 -nowarn -proc:none -d "$out" -cp "$cp" "$@"
}

# --- androidx.annotation, needed by AndroidX, Capacitor and the app.
mkdir -p "$WORK/annstub/androidx/annotation"
for a in Nullable NonNull; do
  printf 'package androidx.annotation;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE})\npublic @interface %s {}\n' \
    "$a" > "$WORK/annstub/androidx/annotation/$a.java"
done
ecj "$WORK/annout" "$WORK/android.jar" $(find "$WORK/annstub" -name '*.java')

# --- The AndroidX surface the app and Capacitor touch, checked in under scripts/java-check.
ecj "$WORK/xout" "$WORK/android.jar:$WORK/annout" $(find "$STUBS" -name '*.java')

# --- Capacitor library, compiled from the package in node_modules.
CAP="$WORK/capstub"
rm -rf "$CAP" && mkdir -p "$CAP/src" "$CAP/out"
cp -r "$OPENROLL/node_modules/@capacitor/android/capacitor/src/main/java/com" "$CAP/src/"
mkdir -p "$CAP/src/com/getcapacitor/android"
cat > "$CAP/src/com/getcapacitor/android/R.java" <<'EOF'
package com.getcapacitor.android;
public final class R {
  public static final class layout { public static final int capacitor_bridge_layout_main = 1; public static final int no_webview = 2; }
  public static final class style { public static final int AppTheme_NoActionBar = 3; }
  public static final class xml { public static final int config = 4; }
  public static final class string { public static final int package_name = 5; }
  public static final class color { public static final int capacitor_status_bar = 6; }
}
EOF
cat > "$CAP/src/com/getcapacitor/BuildConfig.java" <<'EOF'
package com.getcapacitor;
public final class BuildConfig { public static final String VERSION_NAME = "8.6.0"; }
EOF
find "$CAP/src" -name '*.java' > "$CAP/srcs.txt"
"$JAVA" -cp "$WORK/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
  -source 1.8 -target 1.8 -nowarn -proc:none -d "$CAP/out" \
  -cp "$WORK/android.jar:$WORK/annout:$WORK/xout" @"$CAP/srcs.txt" 2>&1 |
  grep -E "ERROR" | head -10 || true

# --- The app module.
mkdir -p "$WORK/appstub"
cat > "$WORK/appstub/BuildConfig.java" <<'EOF'
package app.openroll.mobile;
public final class BuildConfig { public static final String VERSION_NAME = "1.0"; public static final int VERSION_CODE = 1; }
EOF
"$JAVA" -cp "$WORK/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main \
  -source 1.8 -target 1.8 -nowarn -proc:none -d "$WORK/appout" \
  -cp "$WORK/android.jar:$WORK/annout:$WORK/xout:$CAP/out" \
  $(find "$OPENROLL/android/app/src/main/java" -name '*.java') \
  $(find "$WORK/appstub" -name '*.java') 2>&1 | tee "$WORK/app.log" |
  grep -E "ERROR in|^[0-9]+ problems" | head -40 || true
grep -q "ERROR in" "$WORK/app.log" && { echo "JAVA CHECK FAILED"; exit 1; }
echo "JAVA CHECK PASSED"
