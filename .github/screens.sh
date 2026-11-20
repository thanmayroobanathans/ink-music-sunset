#!/usr/bin/env bash
# Screenshots of the built app on an emulator: install it, give it a song, tap through the welcome by the words on
# screen, then the home page, the places menu, the tracks, the player and settings. Run inside the emulator step.
# From the workflow: PKG (the app), HOME_WORD (its first place's name), SETTINGS_WORD (its name for settings),
# RADIO_WORD (its name for radio).
set -u
PKG=${PKG:-app.ink.music.sunset}
HOME_WORD=${HOME_WORD:-cruise}
SETTINGS_WORD=${SETTINGS_WORD:-safehouse}
RADIO_WORD=${RADIO_WORD:-airwaves}
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
# a swipe up the screen's middle, from three quarters down to a quarter, whatever the screen's size
swipe_up() {
  local size w h
  size=$(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+'); w=${size%x*}; h=${size#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 400
}
tap() { local at; at=$(find_text "$@") || { echo "not on screen: $*"; return 1; }; echo "tap $1 at $at"; adb shell input tap $at; }
# the emulator's own pop-ups over the app (a launcher not responding, the taskbar's tip): out of the way first
clear_pops() {
  local i
  for i in 1 2 3; do
    find_text "isn't responding" "Got it" > /dev/null || return 0
    tap Wait "Got it" || adb shell input keyevent KEYCODE_BACK
    sleep 2
  done
}

adb install -r -g apk/*.apk
adb shell mkdir -p /sdcard/Music
adb push music/night-drive.mp3 /sdcard/Music/night-drive.mp3
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/night-drive.mp3 > /dev/null 2>&1 || true
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1 || true
sleep 5

# a tablet: held sideways (rotation locked to landscape)
if [ "${ROTATE:-}" = "1" ]; then
  adb shell wm set-ignore-orientation-request true   # what Android 16 does on big screens by itself
  adb shell settings put system accelerometer_rotation 0
  # sideways = landscape: a tablet that's landscape as it stands (the Pixel Tablet) stays at 0, a tall one turns
  size=$(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+')
  w=${size%x*}; h=${size#*x}
  if [ "${w:-0}" -gt "${h:-0}" ]; then turn=0; else turn=1; fi
  echo "screen ${size}: rotation $turn"
  adb shell settings put system user_rotation $turn
  adb shell wm user-rotation lock $turn > /dev/null 2>&1 || true
  sleep 3
fi
adb shell am start -n "$PKG/app.ink.music.Main"
shot 01-welcome 10
# the welcome, step by step, till the places are there
for i in $(seq 1 10); do
  clear_pops
  if find_text "$HOME_WORD" > /dev/null; then break; fi
  tap BEGIN NEXT skip DONE Allow "While using the app" || adb shell input keyevent KEYCODE_BACK
  shot "02-welcome-step$i" 4
done
shot 03-home 8
clear_pops
# find: a song typed, its answer photographed as it comes, and how long YouTube Music took (the app's own log)
adb logcat -c
if tap find; then
  sleep 2; adb shell input text "blinding%slights"
  shot 03b-find-2s 2; shot 03c-find-6s 4
  adb logcat -d -s ink-youtube:D ink-spotify:I | tail -40 > "$OUT/find-log.txt"
  adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2
fi
clear_pops
# the places menu (the big words), from the place's word at the top
clear_pops; tap "$HOME_WORD" && shot 04-places-menu 3
tap tracks && shot 05-tracks 5
clear_pops; tap "night drive" && shot 06-playing 5
# the player: from the mini player, the lowest "night drive" on screen
clear_pops; PICK=last tap "night drive" && shot 07-player 6
swipe_up && shot 08-player-more 3
adb shell input keyevent KEYCODE_BACK; sleep 3
clear_pops; shot 09-back 1
# the radio: the place's word at the top opens the menu
tap tracks "$HOME_WORD" && shot 10-menu-again 3
tap "$RADIO_WORD" && shot 11-radio 10
swipe_up && shot 12-radio-more 4
# settings, at the menu's foot; its sound, and the equalizer with its headphones
tap "$RADIO_WORD" "$HOME_WORD" tracks && sleep 3
tap "$SETTINGS_WORD" && shot 13-settings 5
tap sound && shot 14-sound 4
tap "open it" && shot 15-equalizer 5
swipe_up && shot 16-equalizer-more 3
ls -l "$OUT"
