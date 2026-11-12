#!/usr/bin/env bash
set -euo pipefail
activity=com.joaopat98.instagrammediadownloader/.MainActivity
directory=/sdcard/Download/InstagramMediaDownloader
shortcode=$(printf '%s' "$INSTAGRAM_TEST_LINK" | sed -E 's@[?#].*@@; s@/$@@; s@.*/@@')
[[ $shortcode =~ ^[A-Za-z0-9_-]+$ ]]
adb shell am force-stop com.joaopat98.instagrammediadownloader
adb shell am start -W -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$INSTAGRAM_TEST_LINK" -n "$activity" >/dev/null 2>&1
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
for _ in {1..90}; do
    saved=$(adb shell "find '$directory' -type f -name 'Instagram_${shortcode}_*' -print -quit 2>/dev/null" || true)
    if [[ -n $saved ]] && adb pull "$saved" "$scratch/media" >/dev/null 2>&1; then
        mime=$(file -b --mime-type "$scratch/media")
        if [[ $mime == image/* || $mime == video/* ]]; then
            printf 'Android saved a %s file (%s bytes).\n' "$mime" "$(wc -c < "$scratch/media")"
            exit 0
        fi
    fi
    sleep 1
done
echo "Android did not save a valid media file for the supplied Instagram link."
exit 1
