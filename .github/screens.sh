#!/usr/bin/env bash
# Screenshots of the built app on an emulator: install it, give it a song, tap through the welcome by the words on
# screen, then the home page, the places menu, the tracks, the player and settings. Run inside the emulator step.
# From the workflow: PKG (the app), HOME_WORD (its first place's name), SETTINGS_WORD (its name for settings).
set -u
PKG=${PKG:-app.ink.music.sunset}
HOME_WORD=${HOME_WORD:-cruise}
SETTINGS_WORD=${SETTINGS_WORD:-safehouse}
OUT=shots
mkdir -p "$OUT"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png" && echo "shot $1"; }
ui() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml > /dev/null 2>&1; }
# where the first of the words is on screen (exact, any case; else the start of a word): "x y", or nothing
find_text() {
  ui
  python3 - "$@" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    nodes = list(ET.parse('ui.xml').iter('node'))
except Exception:
    sys.exit(1)
def centre(n):
    a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds', '[0,0][0,0]')))
    return (a + c) // 2, (b + d) // 2
import os
last = os.environ.get('PICK') == 'last'
for want in sys.argv[1:]:
    w = want.lower()
    for exact in (True, False):
        hits = []
        for n in nodes:
            for t in (n.get('text') or '', n.get('content-desc') or ''):
                t = t.strip().lower()
                if t and (t == w if exact else t.startswith(w)):
                    x, y = centre(n)
                    if x > 0 and y > 0:
                        hits.append((x, y))
        if hits:
            x, y = max(hits, key=lambda h: h[1]) if last else hits[0]
            print(x, y); sys.exit(0)
sys.exit(1)
PY
}
tap() { local at; at=$(find_text "$@") || { echo "not on screen: $*"; return 1; }; echo "tap $1 at $at"; adb shell input tap $at; }

adb install -r -g apk/*.apk
adb shell mkdir -p /sdcard/Music
adb push music/night-drive.mp3 /sdcard/Music/night-drive.mp3
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/night-drive.mp3 > /dev/null 2>&1 || true
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
sleep 5

# a tablet: held sideways (rotation locked to landscape)
if [ "${ROTATE:-}" = "1" ]; then
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1
  adb shell wm user-rotation lock 1 > /dev/null 2>&1 || true
  sleep 3
fi
adb shell am start -n "$PKG/app.ink.music.Main"
shot 01-welcome 10
# the welcome, step by step, till the places are there
for i in $(seq 1 10); do
  if find_text "$HOME_WORD" > /dev/null; then break; fi
  tap BEGIN NEXT skip DONE Allow "While using the app" || adb shell input keyevent KEYCODE_BACK
  shot "02-welcome-step$i" 4
done
shot 03-home 8
# the places menu (the big words), from the place's word at the top
tap "$HOME_WORD" && shot 04-places-menu 3
tap tracks && shot 05-tracks 5
tap "night drive" && shot 06-playing 5
# the player: from the mini player, the lowest "night drive" on screen
PICK=last tap "night drive" && shot 07-player 6
adb shell input swipe 160 480 160 160 300 && shot 08-player-more 3
adb shell input keyevent KEYCODE_BACK; sleep 3
shot 09-back 1
# settings: the place's word at the top opens the menu; settings is at its foot
tap tracks "$HOME_WORD" && shot 10-menu-again 3
tap "$SETTINGS_WORD" && shot 11-settings 5
adb shell input swipe 160 520 160 120 400 && shot 12-settings-more 3
ls -l "$OUT"
