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
# seed the watch's copy of the phone's notebooks (there is no phone on the emulator)
python3 - <<'PY'
import json, html
snap = {"books": [
  {"id": 1, "title": "المشتريات", "blocks": [
    {"id": 2, "h": False, "t": "لبن", "d": True}, {"id": 3, "h": False, "t": "عيش", "d": False},
    {"id": 4, "h": False, "t": "جبنة", "d": False}]},
  {"id": 5, "title": "الشغل", "blocks": [
    {"id": 6, "h": True, "t": "المهام", "d": False}, {"id": 7, "h": False, "t": "نراجع العقد", "d": False},
    {"id": 8, "h": False, "t": "نكلم المحامي", "d": False}]}],
  "reminders": [{"at": 0, "when": "النهارده الساعة ٩:٠٠ م", "text": "أكلم أحمد"}]}
x = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="json">' + \
    html.escape(json.dumps(snap, ensure_ascii=False)) + '</string>\n</map>\n'
open("/tmp/snapshot.xml", "w", encoding="utf-8").write(x)
PY
adb shell am force-stop com.ahmad.wristnote
adb push /tmp/snapshot.xml /data/local/tmp/snapshot.xml
APPUID=$(adb shell stat -c %u /data/data/com.ahmad.wristnote | tr -d '\r')
adb shell "mkdir -p /data/data/com.ahmad.wristnote/shared_prefs && cp /data/local/tmp/snapshot.xml /data/data/com.ahmad.wristnote/shared_prefs/snapshot.xml && chown $APPUID:$APPUID /data/data/com.ahmad.wristnote/shared_prefs /data/data/com.ahmad.wristnote/shared_prefs/snapshot.xml && chmod 660 /data/data/com.ahmad.wristnote/shared_prefs/snapshot.xml"
wtap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  read -r X Y < <(python3 .github/devtest/tap.py /tmp/ui.xml "$1") && adb shell input tap "$X" "$Y"
}
adb shell am start -n com.ahmad.wristnote/.TasksActivity
shot watch_6a_tasks 5
wtap "المشتريات"; shot watch_6b_notebook 3
wtap "عيش"; shot watch_6c_ticked 2
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input swipe 225 380 225 100 300; shot watch_6d_tasks_scrolled 2
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
