#!/usr/bin/env bash
set -euo pipefail

RUNTIME_URL="https://github.com/brunodev85/winlator/releases/download/v11.1.0/Winlator_11.1.apk"
RUNTIME_SHA256="80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970"

curl -fL --retry 5 --retry-delay 3 -o Winlator_11.1.apk "$RUNTIME_URL"
echo "$RUNTIME_SHA256  Winlator_11.1.apk" | sha256sum -c -

rm -rf apkroot
mkdir apkroot
(cd apkroot && unzip -q ../Winlator_11.1.apk)
rm -rf apkroot/META-INF
mkdir -p apkroot/assets/inputcontrols/profiles
cp starscape/controls-starscape.icp apkroot/assets/inputcontrols/profiles/controls-91.icp

python3 - <<'PY'
import json
p='apkroot/assets/inputcontrols/profiles/controls-91.icp'
d=json.load(open(p,encoding='utf-8'))
assert d['name']=='Starscape'
required=['KEY_UP','KEY_DOWN','KEY_LEFT','KEY_RIGHT','KEY_Q','KEY_W','KEY_E','KEY_R','KEY_ENTER','KEY_ESC']
got=[e['bindings'][0] for e in d['elements']]
assert got==required, (got, required)
print('Starscape profile OK:', got)
PY

BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
test -x "$BUILD_TOOLS/zipalign"
test -x "$BUILD_TOOLS/apksigner"
(cd apkroot && zip -q -r -9 ../StarscapeRuntime-unsigned.apk .)
"$BUILD_TOOLS/zipalign" -f -p 4 StarscapeRuntime-unsigned.apk StarscapeRuntime-aligned.apk
PASS="$(openssl rand -hex 12)"
keytool -genkeypair -keystore starscape-build.keystore -storepass "$PASS" -keypass "$PASS" -alias starscape -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Starscape Android Runtime, OU=Local Build, O=Runtime Build, L=Local, ST=NA, C=US'
"$BUILD_TOOLS/apksigner" sign --ks starscape-build.keystore --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --out Starscape-Android-Runtime-v1.apk StarscapeRuntime-aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs Starscape-Android-Runtime-v1.apk
unzip -l Starscape-Android-Runtime-v1.apk | grep 'assets/inputcontrols/profiles/controls-91.icp'
sha256sum Starscape-Android-Runtime-v1.apk > Starscape-Android-Runtime-v1.apk.sha256
