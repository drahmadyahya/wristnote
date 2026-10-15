#!/usr/bin/env bash
# Runs inside the phone emulator: installs the app, runs the pipeline test, takes screenshots.
set -x
OUT=devtest-out; mkdir -p $OUT
adb root; sleep 3; adb wait-for-device
adb shell settings put global auto_time_zone 0
adb shell setprop persist.sys.timezone Africa/Cairo
adb shell cmd time_zone_detector suggest_manual_time_zone --zone_id Africa/Cairo || true
# S24 Ultra default screen
adb shell wm size 1080x2340; adb shell wm density 450
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true

adb install -r -g mobile/build/outputs/apk/release/mobile-release.apk
adb install -r -g mobile/build/outputs/apk/androidTest/release/mobile-release-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.ahmad.wristnote.DevicePipelineTest \
  com.ahmad.wristnote.test/androidx.test.runner.AndroidJUnitRunner | tee $OUT/instrument.txt
adb pull /sdcard/Android/data/com.ahmad.wristnote/files/report.txt $OUT/report.txt || \
  adb shell run-as com.ahmad.wristnote cat files/report.txt > $OUT/report.txt || true
adb logcat -d -s NerdlyTest Nerdly NerdlyWhisper AndroidRuntime:E > $OUT/logcat.txt

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; }
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  read -r X Y < <(python3 .github/devtest/tap.py /tmp/ui.xml "$1") && adb shell input tap "$X" "$Y"
}
adb shell am force-stop com.ahmad.wristnote
adb shell cmd uimode night yes
adb shell am start -W -n com.ahmad.wristnote/.MainActivity
shot phone_1_ideas 8
tap "النوت"; shot phone_2_notebooks
tap "المشتريات"; shot phone_3_notebook
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "الأفكار"; sleep 2
tap "فكرة جديدة"; shot phone_4_idea_sheet
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "ملخص"; shot phone_5_answer_sheet
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input swipe 540 1900 540 700 400; shot phone_6_ideas_scrolled
tap "الإعدادات"; shot phone_7_settings
adb shell input swipe 540 1900 540 600 400; shot phone_8_settings_more
adb shell input keyevent KEYCODE_BACK
adb shell cmd uimode night no
adb shell am force-stop com.ahmad.wristnote
adb shell am start -W -n com.ahmad.wristnote/.MainActivity
shot phone_9_light 8
adb logcat -d -s Nerdly AndroidRuntime:E > $OUT/logcat_ui.txt
exit 0
