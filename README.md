# TV Lite

A small Home screen for an Android 5.1 Xiaomi TV (MiTV-ANTL0 / L43M7-EA, firmware 22.10.12.2070) whose
stock PatchWall launcher was slow. Plain Java, no libraries, about 45 KB, about 29 MB of RAM, no
`INTERNET` permission.

- Clock, date and greeting in Vietnamese.
- Pills: **Nguồn vào** (Xiaomi's input-source popup), **Wi-Fi** (with the network name), **Cài đặt**.
- One grid of every app with a TV banner or launcher icon, most used first.
- **Menu** key on an app: pin it to the top, hide it, open its app info, or uninstall it (apps you
  installed yourself only; Android asks to confirm). Menu on the top bar shows hidden apps again, or
  opens the old PatchWall while it is not hidden.

It is not part of [cleanyourtv](https://github.com/Gathin23/cleanyourtv): that launcher needs Android 8.

## Build

Needs JDK 17+ and the Android SDK command-line tools with `platforms;android-34`, `platforms;android-22`
and `build-tools;34.0.0` (default location `%LOCALAPPDATA%\Android\Sdk`, or set `ANDROID_HOME`).

```sh
sh build.sh               # ordinary app, does not answer the Home key
WITH_HOME=1 sh build.sh   # the Home build that is installed on the TV
```

javac compiles against `android-22.jar`, so any call newer than Android 5.1 is a build error, not a crash
on the TV. `debug.keystore` is created on the first build; keep it, or the next build cannot update the
installed app (`pm install -r` refuses a different signature).

## Install or update

From a cleanyourtv checkout next to this folder, with ADB debugging on (Settings → Account & Security):

```sh
export TV_HOST=192.168.1.8 MSYS_NO_PATHCONV=1
tools/venv/Scripts/python -I tools/b64push.py ../tvlite-launcher/out/tvlite.apk /data/local/tmp/tvlite.apk
tools/venv/Scripts/python -I tools/tv.py 'pm install -r /data/local/tmp/tvlite.apk'
```

`tv.py --push` hangs on this TV; `b64push.py` works. On this firmware network ADB closes after every
restart: switch ADB debugging off and on again to reopen it.

## Back to PatchWall

PatchWall (`com.mitv.tvhome`) is hidden with `pm hide`, not removed:

```sh
tools/venv/Scripts/python -I tools/tv.py 'pm unhide com.mitv.tvhome'
```

Then press Home and choose Home (PatchWall) → Always. To remove TV Lite completely, run
`pm uninstall com.tvlite.home` after unhiding PatchWall, never before: without PatchWall and TV Lite the TV
has no Home screen. A factory reset also brings PatchWall back.
