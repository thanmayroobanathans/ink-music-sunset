#!/usr/bin/env bash
# The iPhone app, built on GitHub's Mac: the Kotlin screens (ios/, Kotlin/Native) as a framework, the Xcode project
# made from ios/app/project.yml (xcodegen), then
#   1. an unsigned .ipa for a real iPhone: a sideloading tool (AltStore, SideStore, Sideloadly) signs it with the
#      person's own Apple ID as it installs it (a free one's install lasts 7 days, then it's done again);
#   2. the same app for the iPhone simulator, installed there, opened and photographed.
# Run from the workflow's ios job, in ink-music/. EDITION, EDITION_NAME from the job. Leaves ../out/<edition>.ipa and
# ../shots/.
set -euo pipefail
mkdir -p ../out ../shots
./gradlew -Pios -PdesktopOnly -PphoneOnly :ios:linkReleaseFrameworkIosArm64 :ios:linkDebugFrameworkIosSimulatorArm64 --stacktrace
cd ios/app
xcodegen generate
xcodebuild -project InkMusic.xcodeproj -scheme InkMusic -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build | tail -25
app="build/Build/Products/Release-iphoneos/$EDITION_NAME.app"
ls -d "$app"
rm -rf Payload && mkdir Payload && cp -R "$app" Payload/
zip -qry "../../../out/$EDITION.ipa" Payload
ls -l "../../../out/$EDITION.ipa"

# the simulator: its own framework (debug: built faster, and the simulator's arm64)
sed -i '' 's|iosArm64/releaseFramework|iosSimulatorArm64/debugFramework|g' project.yml
xcodegen generate
xcodebuild -project InkMusic.xcodeproj -scheme InkMusic -configuration Release -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build-sim CODE_SIGNING_ALLOWED=NO build | tail -15
sim="build-sim/Build/Products/Release-iphonesimulator/$EDITION_NAME.app"
ls -d "$sim"
bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$sim/Info.plist")
udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
d = json.load(sys.stdin)["devices"]
phones = [x for rt in sorted(d, reverse=True) if "iOS" in rt for x in d[rt] if x["name"].startswith("iPhone")]
print(phones[0]["udid"] if phones else "")')
[ -n "$udid" ] || { echo "no iPhone simulator"; exit 1; }
xcrun simctl boot "$udid" || true
xcrun simctl bootstatus "$udid" -b
xcrun simctl install "$udid" "$sim"
xcrun simctl launch "$udid" "$bundle"
sleep 45
xcrun simctl io "$udid" screenshot "../../../shots/ios-01-open.png"
sleep 30
xcrun simctl io "$udid" screenshot "../../../shots/ios-02-later.png"
xcrun simctl spawn "$udid" log show --last 3m --predicate "process == \"$EDITION_NAME\"" --style compact 2>/dev/null | tail -60 > ../../../shots/ios-log.txt || true
ls -l ../../../shots
