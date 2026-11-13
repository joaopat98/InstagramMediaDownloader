#!/usr/bin/env bash
set -euo pipefail
package=com.joaopat98.instagrammediadownloader
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result=$(adb shell am instrument -w "$package.test/$package.GalleryFixture")
printf '%s\n' "$result"
grep -Fq 'fixture=saved' <<< "$result"
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$package/.MainActivity"

check_gallery() {
    local name=${1##*/}
    local xml=/sdcard/souvenir-gallery.xml
    for _ in {1..8}; do
        adb shell uiautomator dump "$xml" >/dev/null
        adb pull "$xml" "$scratch/gallery.xml" >/dev/null
        if python3 - "$scratch/gallery.xml" "$name" >"$scratch/tap" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1])
for node in root.iter("node"):
    if node.get("clickable") == "true" and node.get("content-desc", "").endswith(" " + sys.argv[2]):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        print((left + right) // 2, (top + bottom) // 2)
        sys.exit(0)
sys.exit(1)
PY
        then
            read -r x y <"$scratch/tap"
            adb logcat -c
            adb shell input tap "$x" "$y"
            for _ in {1..10}; do
                if adb shell dumpsys activity activities |
                    grep -Ei 'topResumedActivity.*(chooser|resolver)' >/dev/null; then
                    echo 'Saved media appeared in Souvenir and opened the Android app chooser.'
                    return 0
                fi
                adb shell uiautomator dump "$xml" >/dev/null
                if adb shell cat "$xml" | grep -Ei 'package="[^"]*(intentresolver|chooser)[^"]*"' >/dev/null; then
                    echo 'Saved media appeared in Souvenir and opened the Android app chooser.'
                    return 0
                fi
                sleep 1
            done
            adb shell dumpsys activity activities | grep -Ei 'resumedactivity|android.intent.action.CHOOSER' || true
            adb logcat -d -s ActivityTaskManager:I AndroidRuntime:E | tail -n 30
            echo 'Tapping the saved media did not open the chooser.'
            return 1
        fi
        adb shell input swipe 250 750 250 400 250
    done
    echo 'The downloaded file did not appear in the in-app gallery.'
    return 1
}
check_gallery souvenir-gallery-fixture.png
