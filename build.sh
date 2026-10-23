#!/bin/sh
# Builds out/tvlite.apk with the Android SDK build-tools only (no Gradle).
#   sh build.sh          ordinary app (step 1: test it without touching Home)
#   WITH_HOME=1 sh build.sh   also answers the HOME intent (step 2)
# Needs JDK 17+ and, under $ANDROID_HOME (default %LOCALAPPDATA%\Android\Sdk),
# platforms;android-34, platforms;android-22 and build-tools;34.0.0.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# javac compiles against the TV's own API level, so a call newer than Android 5.1 fails here, not on the TV.
JAR22="$SDK/platforms/android-22/android.jar"
EXE=""; [ -f "$BT/aapt2.exe" ] && EXE=".exe"
BAT=""; [ -f "$BT/d8.bat" ] && BAT=".bat"

rm -rf out && mkdir -p out/gen out/classes out/dex

if [ "${WITH_HOME:-0}" = "1" ]; then
  sed 's#<!--HOME_FILTER-->#<intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.HOME" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>#' \
    AndroidManifest.xml > out/AndroidManifest.xml
  echo "build: with HOME filter"
else
  cp AndroidManifest.xml out/AndroidManifest.xml
  echo "build: ordinary app (no HOME filter)"
fi

"$BT/aapt2$EXE" compile --dir res -o out/res.zip
"$BT/aapt2$EXE" link -I "$JAR" --manifest out/AndroidManifest.xml \
  --min-sdk-version 22 --target-sdk-version 28 \
  --java out/gen -o out/unsigned.apk out/res.zip

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$JAR22" -classpath "$BT/core-lambda-stubs.jar" \
  -d out/classes $(find src out/gen -name '*.java')
jar cf out/classes.jar -C out/classes .
"$BT/d8$BAT" --release --min-api 22 --lib "$JAR" --output out/dex out/classes.jar

python -c "import zipfile,sys; z=zipfile.ZipFile('out/unsigned.apk','a'); z.write('out/dex/classes.dex','classes.dex'); z.close()"
"$BT/zipalign$EXE" -f -p 4 out/unsigned.apk out/aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias tvlite \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Lite" > /dev/null
fi
"$BT/apksigner$BAT" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out out/tvlite.apk out/aligned.apk
ls -l out/tvlite.apk
