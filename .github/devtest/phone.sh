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
adb shell am instrument -w -e class com.ahmad.wristnote.DevicePipelineTest,com.ahmad.wristnote.VoiceActivityTest,com.ahmad.wristnote.UndoTest \
  com.ahmad.wristnote.test/androidx.test.runner.AndroidJUnitRunner | tee $OUT/instrument.txt
adb pull /sdcard/Android/data/com.ahmad.wristnote/files/report.txt $OUT/report.txt || \
  adb shell run-as com.ahmad.wristnote cat files/report.txt > $OUT/report.txt || true
for f in undo_report.txt voice_report.txt voice_1_recording.png voice_2_result.png voice_idea_1_recording.png voice_idea_2_result.png; do
  adb pull /sdcard/Android/data/com.ahmad.wristnote/files/$f $OUT/$f || true
done
adb logcat -d -s NerdlyTest Nerdly NerdlyWhisper AndroidRuntime:E > $OUT/logcat.txt
# the Clock app's own alarm list (enabled=0 means switched off)
db() { adb shell "find /data -name alarms.db 2>/dev/null | head -1"; }
ADB_DB=$(db | tr -d '\r')
adb shell "sqlite3 $ADB_DB 'select _id,hour,minutes,enabled,label from alarm_templates'" > $OUT/clock_alarms.txt 2>&1 || true
evq() { adb shell "content query --uri content://com.android.calendar/events --projection title:dtstart:deleted" 2>&1; }

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; }
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  read -r X Y < <(python3 .github/devtest/tap.py /tmp/ui.xml "$1") && adb shell input tap "$X" "$Y"
}
adb shell am force-stop com.ahmad.wristnote
adb shell cmd uimode night yes
adb shell am start -W -n com.ahmad.wristnote/.MainActivity
shot phone_1_ideas 8
# the record button -> phone recorder with the real (emulator) microphone
tap "سجّل فكرة"; shot phone_r1_recording 3
tap "حفظ"; shot phone_r2_after_stop 4
tap "تمام"; sleep 2
adb shell am start -W -n com.ahmad.wristnote/.VoiceActivity; shot phone_r3_direct 3
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -W -n com.ahmad.wristnote/.MainActivity; sleep 2
tap "النوت"; shot phone_2_notebooks
tap "المشتريات"; shot phone_3_notebook
longpress() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  read -r X Y < <(python3 .github/devtest/tap.py /tmp/ui.xml "$1") && adb shell input swipe "$X" "$Y" "$X" "$Y" 900
}
longpress "لبن"; shot phone_3b_select 3
tap "عيش"; shot phone_3c_select_two 3
tap "نقل"; shot phone_3d_move 3
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "الأفكار"; sleep 2
tap "فكرة جديدة"; shot phone_4_idea_sheet
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "ملخص"; shot phone_5_answer_sheet
adb shell input keyevent KEYCODE_BACK; sleep 2
# delete an appointment from the app -> asks, then removes it from the calendar too
evq > $OUT/calendar_before_delete.txt
adb shell input swipe 540 900 540 1900 300; sleep 1
tap "احفظ معاد زيارة"; sleep 2
adb shell input swipe 540 2000 540 800 400; sleep 1
tap "حذف"; shot phone_d1_confirm 2
tap "امسح الفكرة و"; shot phone_d2_deleted 2
evq > $OUT/calendar_after_delete.txt
adb shell input swipe 540 1900 540 700 400; shot phone_6_ideas_scrolled
tap "الإعدادات"; shot phone_7_settings
adb shell input swipe 540 1500 540 500 600; shot phone_8_settings_more
adb shell input keyevent KEYCODE_BACK
adb shell cmd uimode night no
adb shell am force-stop com.ahmad.wristnote
adb shell am start -W -n com.ahmad.wristnote/.MainActivity
shot phone_9_light 8
adb logcat -d -s Nerdly AndroidRuntime:E > $OUT/logcat_ui.txt
adb logcat -d -b crash > $OUT/crashes.txt
adb logcat -d ActivityTaskManager:I ActivityManager:I *:S | tail -200 > $OUT/activities.txt
exit 0
