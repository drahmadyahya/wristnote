#!/usr/bin/env bash
# Runs inside the Wear OS emulator: recorder, answer screen, tile, complication.
set -x
OUT=devtest-out; mkdir -p $OUT
adb root; sleep 3; adb wait-for-device
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb install -r -g wear/build/outputs/apk/release/wear-release.apk
shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; }
adb logcat -c
adb shell am start -n com.ahmad.wristnote/.RecordActivity
shot watch_1_recording 3
adb shell input tap 227 227
shot watch_2_saved 0.6
sleep 3
NL=$'\n'
adb shell "am start -n com.ahmad.wristnote/.AnswerActivity --es kind answer --es title 'فاضل ٣ في المشتريات' --es body '• لبن${NL}• عيش${NL}• جبنة'"
shot watch_3_answer 3
adb shell "am start -n com.ahmad.wristnote/.AnswerActivity --es kind brief --es title 'صباح الخير' --es body 'الخميس ١٥ أكتوبر${NL}النهارده عندك حاجتين:${NL}• ٧:٠٠ م  معاد الدكتور${NL}• ٩:٠٠ م  تذكير: أكلم أحمد${NL}فاضل ٥ في نوتك: المشتريات (٥)${NL}امبارح سجلت ٣ أفكار.'"
shot watch_4_brief 3
adb shell input swipe 225 380 225 100 300; shot watch_5_brief_scrolled
adb shell am start -n com.ahmad.wristnote/.TasksActivity
shot watch_6a_tasks 4
adb shell am force-stop com.ahmad.wristnote
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile \
  --ecn component com.ahmad.wristnote/com.ahmad.wristnote.RecordTileService | tee $OUT/tile.txt
IDX=$(grep -o 'Index=\[[0-9]*\]' $OUT/tile.txt | grep -o '[0-9]*' | head -1)
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation show-tile --ei index "${IDX:-0}"
shot watch_6_tile 5
# fallback: tiles are to the left of the watch face
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation list-tiles | tee -a $OUT/tile.txt
adb shell input keyevent KEYCODE_HOME; sleep 2
for k in 1 2; do adb shell input swipe 420 227 30 227 250; shot watch_7_tile_swipe_$k 3; done
adb shell cmd package query-services -a android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST > $OUT/complications.txt
adb logcat -d -s Nerdly AndroidRuntime:E > $OUT/logcat.txt
exit 0
