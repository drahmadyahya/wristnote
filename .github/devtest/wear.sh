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
adb shell input tap 225 225
shot watch_2_saved 1
sleep 3
adb shell am start -n com.ahmad.wristnote/.AnswerActivity \
  --es title "فاضل ٣ في المشتريات" --es body "$(printf '• لبن\n• عيش\n• جبنة')" --es kind answer
shot watch_3_answer 3
adb shell am start -n com.ahmad.wristnote/.AnswerActivity \
  --es title "صباح الخير" --es body "$(printf 'الخميس ١٥ أكتوبر\nالنهارده عندك حاجتين:\n• ٧:٠٠ م  معاد الدكتور\n• ٩:٠٠ م  تذكير: أكلم أحمد\nفاضل ٥ في نوتك: المشتريات (٥)\nامبارح سجلت ٣ أفكار.')" --es kind brief
shot watch_4_brief 3
adb shell input swipe 225 350 225 120 300; shot watch_5_brief_scrolled
adb shell input keyevent KEYCODE_BACK
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile \
  --ecn component com.ahmad.wristnote/com.ahmad.wristnote.RecordTileService | tee $OUT/tile.txt
IDX=$(grep -o 'Index=\[[0-9]*\]' $OUT/tile.txt | grep -o '[0-9]*' | head -1)
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation show-tile --ei index "${IDX:-0}"
shot watch_6_tile 4
adb shell cmd package query-services -a android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST > $OUT/complications.txt
adb logcat -d -s Nerdly AndroidRuntime:E > $OUT/logcat.txt
exit 0
