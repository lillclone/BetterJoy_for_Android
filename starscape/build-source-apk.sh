#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
SRC="$ROOT/winlator-starscape"
RUNTIME_APK="$ROOT/Winlator_11.1.apk"
WINLATOR_APP_COMMIT="c03f6ab558c6f94cbac6ec0c791b12f3428fbdf6"
RUNTIME_SHA256="80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970"

rm -rf "$SRC" runtime-unpacked signing-tools
git clone --quiet https://github.com/brunodev85/winlator-app.git "$SRC"
git -C "$SRC" checkout --quiet "$WINLATOR_APP_COMMIT"

curl -fL --retry 5 --retry-delay 3 \
  -o "$RUNTIME_APK" \
  https://github.com/brunodev85/winlator/releases/download/v11.1.0/Winlator_11.1.apk
echo "$RUNTIME_SHA256  $RUNTIME_APK" | sha256sum -c -

mkdir runtime-unpacked
unzip -q "$RUNTIME_APK" -d runtime-unpacked
mkdir -p "$SRC/app/src/main/assets"
rsync -a --exclude 'dexopt/' runtime-unpacked/assets/ "$SRC/app/src/main/assets/"
mkdir -p "$SRC/app/src/main/assets/inputcontrols/profiles"
cp "$ROOT/starscape/controls-starscape.icp" "$SRC/app/src/main/assets/inputcontrols/profiles/controls-91.icp"
cp "$ROOT/starscape/StarscapeActivity.java" "$SRC/app/src/main/java/com/winlator/StarscapeActivity.java"

python3 - "$SRC" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1])

main=src/'app/src/main/java/com/winlator/MainActivity.java'
s=main.read_text()
needle='if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this);'
assert needle in s, 'MainActivity rootfs install call changed upstream'
s=s.replace(needle, 'RootFSInstaller.installIfNeeded(this);', 1)
main.write_text(s)

manifest=src/'app/src/main/AndroidManifest.xml'
s=manifest.read_text()
launcher='''            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n'''
assert s.count(launcher)==1, 'Unexpected launcher intent count'
s=s.replace(launcher, '', 1)
anchor='''        <activity android:name="com.winlator.XServerDisplayActivity"'''
assert anchor in s
starscape='''        <activity android:name="com.winlator.StarscapeActivity"\n            android:theme="@style/AppThemeDark"\n            android:exported="true"\n            android:screenOrientation="sensorLandscape"\n            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN"/>\n                <category android:name="android.intent.category.LAUNCHER"/>\n            </intent-filter>\n        </activity>\n\n'''
s=s.replace(anchor, starscape+anchor, 1)
manifest.write_text(s)

strings=src/'app/src/main/res/values/strings.xml'
s=strings.read_text()
old='<string name="app_name">Winlator</string>'
if old in s:
    s=s.replace(old, '<string name="app_name">Starscape Android</string>', 1)
strings.write_text(s)

# Ensure the custom profile and launcher source are present before Gradle runs.
import json
p=src/'app/src/main/assets/inputcontrols/profiles/controls-91.icp'
d=json.loads(p.read_text())
assert d['name']=='Starscape' and d['id']==91
assert (src/'app/src/main/java/com/winlator/StarscapeActivity.java').is_file()
print('Source patch validation OK')
PY

# Install exact Android packages required by the pinned Winlator source.
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-35' \
  'build-tools;35.0.0' \
  'ndk;24.0.8215888' \
  'cmake;3.22.1'

cd "$SRC"
chmod +x gradlew
./gradlew --no-daemon clean assembleDebug
cd "$ROOT"

APK="$(find "$SRC/app/build/outputs/apk/debug" -name '*.apk' -type f | head -1)"
test -f "$APK"
cp "$APK" Starscape-Android-Bootstrap-v2.apk

BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
"$BUILD_TOOLS/apksigner" verify --verbose Starscape-Android-Bootstrap-v2.apk
unzip -l Starscape-Android-Bootstrap-v2.apk | grep 'assets/inputcontrols/profiles/controls-91.icp'
unzip -l Starscape-Android-Bootstrap-v2.apk | grep 'classes.dex'
sha256sum Starscape-Android-Bootstrap-v2.apk > Starscape-Android-Bootstrap-v2.apk.sha256

mkdir -p signing-tools/lib signing-tools/lib64
cp "$BUILD_TOOLS/zipalign" signing-tools/
cp "$BUILD_TOOLS/apksigner" signing-tools/
cp "$BUILD_TOOLS/lib/apksigner.jar" signing-tools/lib/
LIBCXX="$(find "$BUILD_TOOLS" -name 'libc++.so' -type f | head -1)"
test -n "$LIBCXX"
cp "$LIBCXX" signing-tools/lib64/
chmod +x signing-tools/zipalign signing-tools/apksigner
