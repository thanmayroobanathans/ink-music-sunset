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
# the emulator's own launcher froze again and again ("isn't responding" over every screen, nothing readable under it):
# put away for the test; the app is opened by name, and Android's fallback home stands in
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher > /dev/null 2>&1 || true
adb shell settings put global anr_show_background 0 > /dev/null 2>&1 || true
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

# Exportify's files opened with the app: one playlist's CSV, then "export all"'s zip of two. Opened as a browser's
# downloads open them: VIEW, the downloads' own address for the file (Android's media store, read leave given), Main
# named (no chooser on the emulator). As root, the leave being root's to give.
media_id() {  # a file's id in the downloads (else all files) the media store keeps, by its name
  local where
  for where in downloads file; do
    adb shell content query --uri "content://media/external/$where" --projection _id:_display_name 2>/dev/null \
      | grep -F "_display_name=$1" | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | sed "s|^|$where/|" | grep . && return 0
  done
  return 1
}
exportify_test() {
  adb root > /dev/null 2>&1; sleep 4; adb wait-for-device
  # the shell's leave to read a download can't be handed on here: the app given all files instead, for the test only
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
  printf '%s\n' '"Track URI","Track Name","Artist URI(s)","Artist Name(s)","Album Name"' \
    '"spotify:track:0VjIjW4GlUZAMYd2vXMi3b","Blinding Lights","spotify:artist:1Xyo4u8uXC1ZmMpatF05PJ","The Weeknd","After Hours"' \
    '"spotify:track:463CkQjx2Zk1yXoBuierM9","Levitating (feat. DaBaby)","spotify:artist:6M2wZ9GZgrQXHCFfjv46we,spotify:artist:4r63FhuTkUYltbVAg5TQnk","Dua Lipa,DaBaby","Future Nostalgia"' \
    '"spotify:track:7qiZfU4dY1lWllzX7mPBI3","Shape of You","spotify:artist:6eUKZXaKkcviH0Ku9w2n3V","Ed Sheeran","Divide"' > night_drive_mix.csv
  cp night_drive_mix.csv road_trip.csv
  python3 -c "import zipfile; z = zipfile.ZipFile('spotify_playlists.zip', 'w'); [z.write(f) for f in ('night_drive_mix.csv', 'road_trip.csv')]; z.close()"
  adb push night_drive_mix.csv /sdcard/Download/night_drive_mix.csv > /dev/null
  adb push spotify_playlists.zip /sdcard/Download/spotify_playlists.zip > /dev/null
  adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1; sleep 4
  local csv zip
  csv=$(media_id night_drive_mix.csv) || { echo "csv not in the media store"; return 1; }
  zip=$(media_id spotify_playlists.zip) || { echo "zip not in the media store"; return 1; }
  echo "csv content://media/external/$csv, zip content://media/external/$zip"
  echo "who opens a csv:"; adb shell cmd package query-activities --brief -a android.intent.action.VIEW -t text/csv -d "content://media/external/$csv" | grep -i "$PKG" || echo "  not $PKG"
  echo "who opens a zip:"; adb shell cmd package query-activities --brief -a android.intent.action.VIEW -t application/zip -d "content://media/external/$zip" | grep -i "$PKG" || echo "  not $PKG"
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -t text/csv --grant-read-uri-permission -d "content://media/external/$csv" -n "$PKG/app.ink.music.Main"
  sleep 20; clear_pops; shot 03d-exportify-csv 2
  adb shell am start -a android.intent.action.VIEW -t application/zip --grant-read-uri-permission -d "content://media/external/$zip" -n "$PKG/app.ink.music.Main"
  sleep 6; clear_pops; shot 03e-exportify-zip 2
  clear_pops; tap "road trip" && shot 03f-exportify-zip-picked 15
  adb logcat -d -s ink-import:* ink-youtube:I | tail -40 > "$OUT/exportify-log.txt"
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE default
  adb shell input keyevent KEYCODE_BACK; sleep 2; adb shell input keyevent KEYCODE_BACK; sleep 2
}
# find: a song typed, its answer photographed as it comes, and how long YouTube Music took (the app's own log)
adb logcat -c
if tap find; then
  sleep 2; adb shell input text "blinding%slights"
  shot 03b-find-2s 2; shot 03c-find-6s 4
  adb logcat -d -s ink-youtube:D ink-spotify:I | tail -40 > "$OUT/find-log.txt"
  # the song kept as it plays (StreamCache): played, then played again from its start, the second read from the phone
  if true; then
    adb logcat -c
    adb shell input keyevent KEYCODE_BACK; sleep 1   # the keyboard away
    x=300; y=520   # the first song's row on a Pixel 7's screen, if its artist can't be read off it
    at=$(find_text "the weeknd") && { x=${at% *}; y=$(( ${at#* } - 30 )); }
    echo "play at $x $y"; adb shell input tap $x $y
    sleep 30; shot 03d-song-playing 1
    # another song (the next row down), then the first again from its start: read from the phone this time
    adb shell input tap $x $((y + 168)); sleep 10
    adb shell input tap $x $y
    sleep 12; shot 03e-song-again 1
    adb logcat -d -s ink-cache:* ink-youtube:D | grep -v "^-" | tail -80 > "$OUT/cache-log.txt"
    [ -s "$OUT/cache-log.txt" ] || echo "(nothing logged)" > "$OUT/cache-log.txt"
    grep -c "played from the phone" "$OUT/cache-log.txt" | sed 's/^/reads from the phone: /'
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2
fi
clear_pops
exportify_test || echo "exportify test stopped"
adb shell am start -n "$PKG/app.ink.music.Main" > /dev/null; sleep 3
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

# spotify's sign-in: settings' FROM SPOTIFY, SIGN IN, then its page; "continue with google" tapped, and an email typed
# (example.com: no one's) to see the page that comes next. What each page was, in the app's own log.
spotify_test() {
  local i
  for i in 1 2 3 4; do adb shell input keyevent KEYCODE_BACK; sleep 2; find_text "from spotify" > /dev/null && break
    find_text "$SETTINGS_WORD" > /dev/null && { tap "$SETTINGS_WORD"; sleep 4; }; done
  for i in 1 2 3 4 5; do find_text "from spotify" > /dev/null && break; swipe_up; sleep 2; done
  adb logcat -c
  tap "from spotify" || return 1
  shot 17-spotify-import 4
  tap "sign in" || return 1
  shot 18-spotify-page 15
  ui; cp ui.xml "$OUT/spotify-ui.txt"
  if tap "continue with google" "sign up with google" "log in with google"; then
    shot 19-spotify-google 12
    ui; cp ui.xml "$OUT/spotify-google-ui.txt"
    adb shell input keyevent KEYCODE_BACK; sleep 6
  fi
  shot 20-spotify-back 2
  if tap "email or username" "email address" "email"; then
    sleep 1; adb shell input text "someone@example.com"; sleep 1
    tap "continue" "log in" "next" && shot 21-spotify-after-email 12
  fi
  adb logcat -d -s ink-spotify:* chromium:* cr_*:* | tail -120 > "$OUT/spotify-log.txt"
}
spotify_test || echo "spotify test stopped"

# Spotify's own page (open.spotify.com) in the app's WebView, signed out: what it shows a phone's WebView, and which
# of its asks the app sees (its log). The sign-in screen isn't exported: started as root (a google_apis emulator).
spotify_player_test() {
  adb root > /dev/null 2>&1; sleep 4; adb wait-for-device
  adb logcat -c
  adb shell am start -n "$PKG/app.ink.music.SpotifySignIn" --es start "https://open.spotify.com/" || return 1
  shot 22-spotify-player 20
  adb logcat -d -s ink-spotify:* | tail -80 > "$OUT/spotify-player-log.txt"
  adb shell input keyevent KEYCODE_BACK
}
spotify_player_test || echo "spotify player test stopped"
ls -l "$OUT"
