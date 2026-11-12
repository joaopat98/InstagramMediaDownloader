#!/usr/bin/env bash
set -euo pipefail
kind=$1
link=$2
activity=com.joaopat98.instagrammediadownloader/.MainActivity
directory=/sdcard/Download/InstagramMediaDownloader
shortcode=$(printf '%s' "$link" | sed -E 's@[?#].*@@; s@/$@@; s@.*/@@')
[[ $shortcode =~ ^[A-Za-z0-9_-]+$ ]]
adb shell am force-stop com.joaopat98.instagrammediadownloader
adb shell am start -W -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$link" -n "$activity" >/dev/null 2>&1
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
for _ in {1..60}; do
    saved=$(adb shell "find '$directory' -type f -name 'Instagram_${shortcode}_*' -print -quit 2>/dev/null" || true)
    if [[ -n $saved ]] && adb pull "$saved" "$scratch/media" >/dev/null 2>&1; then
        mime=$(file -b --mime-type "$scratch/media")
        if [[ $mime == video/* || ( $kind != reel && $mime == image/* ) ]]; then
            printf 'Android saved the %s as %s (%s bytes).\n' "$kind" "$mime" "$(wc -c < "$scratch/media")"
            exit 0
        fi
    fi
    sleep 1
done
echo "Android did not save valid media for the $kind link."
adb shell uiautomator dump /sdcard/imd-test-window.xml >/dev/null 2>&1 || true
adb shell cat /sdcard/imd-test-window.xml 2>/dev/null |
    grep -Eo 'Instagram didn’t return a downloadable file|Instagram is rate limiting requests|Couldn’t reach Instagram|Couldn’t start the download|files? queued in Downloads' |
    sort -u || true
exit 1
