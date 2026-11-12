#!/usr/bin/env bash
set -euo pipefail

apk=$1
package=com.joaopat98.instagrammediadownloader
activity="$package/.MainActivity"

adb install -r "$apk"
adb logcat -c
trap 'adb logcat -d -b crash' ERR

check_home() {
    local state
    for _ in {1..10}; do
        state=$(adb shell dumpsys activity activities)
        if grep -E '(topResumedActivity|mResumedActivity)' <<< "$state" | grep -Fq "$activity"; then
            return 0
        fi
        sleep 1
    done
    echo "MainActivity did not reach the foreground."
    return 1
}

adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$activity"
check_home

adb shell am force-stop "$package"
adb shell am start -W -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT 'invalid-link-for-smoke-test' -n "$activity"
check_home
