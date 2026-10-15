#!/usr/bin/env bash
# Usage: emu.sh <api> <target> <device-profile|-> <script>
# Boots an emulator with full logging into devtest-out/, then runs <script>.
set -x
API=$1; TARGET=$2; PROFILE=$3; SCRIPT=$4
OUT=devtest-out; mkdir -p $OUT
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
SM=$(ls $SDK/cmdline-tools/*/bin/sdkmanager | head -1); AM=$(dirname $SM)/avdmanager
IMG="system-images;android-$API;$TARGET;x86_64"
yes | $SM --install "emulator" "platform-tools" "$IMG" > $OUT/sdk_$TARGET.txt 2>&1
echo "sdkmanager exit $?" >> $OUT/sdk_$TARGET.txt
DEV=""; [ "$PROFILE" != "-" ] && DEV="-d $PROFILE"
export ANDROID_AVD_HOME=$HOME/.android/avd; mkdir -p $ANDROID_AVD_HOME
echo no | $AM create avd -n t -k "$IMG" $DEV --force -p $ANDROID_AVD_HOME/t.avd > $OUT/avd_$TARGET.txt 2>&1
ls -la $ANDROID_AVD_HOME >> $OUT/avd_$TARGET.txt 2>&1
# runners have little free disk: smaller data partition, more RAM-backed speed
sed -i '/disk.dataPartition.size/d' $ANDROID_AVD_HOME/t.avd/config.ini
echo "disk.dataPartition.size=3G" >> $ANDROID_AVD_HOME/t.avd/config.ini
df -h / >> $OUT/avd_$TARGET.txt
export PATH=$SDK/platform-tools:$PATH
nohup $SDK/emulator/emulator -avd t -no-window -gpu swiftshader_indirect -no-boot-anim -no-snapshot \
  -no-audio -camera-back none -memory 3072 -accel on > $OUT/emulator_$TARGET.txt 2>&1 &
sleep 20
if ! pgrep -f "qemu-system" >/dev/null; then echo "EMULATOR DID NOT START" >> $OUT/emulator_$TARGET.txt; exit 1; fi
timeout 900 adb wait-for-device || { echo "NO DEVICE" >> $OUT/emulator_$TARGET.txt; exit 1; }
start=$(date +%s)
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 10
  if [ $(( $(date +%s) - start )) -gt 1800 ]; then echo "BOOT TIMEOUT" >> $OUT/emulator_$TARGET.txt; exit 1; fi
done
echo "booted in $(( $(date +%s) - start ))s" | tee $OUT/boot_$TARGET.txt
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
sleep 20
bash $SCRIPT
