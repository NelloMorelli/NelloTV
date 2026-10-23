#!/bin/zsh
# Build and install Nello TV on a Fire TV over the network, then turn on its background service.
#   ./install.sh 192.168.1.32
set -e
cd "$(dirname "$0")"
IP=${1:?indirizzo IP della Fire TV}
adb connect "$IP:5555"
export ANDROID_SERIAL="$IP:5555"
./gradlew -q assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
# location: Wi-Fi network name; storage: local wallpapers
adb shell pm grant com.nello.tvhome android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant com.nello.tvhome android.permission.READ_EXTERNAL_STORAGE || true
# lets the launcher switch its background service back on after updates
adb shell pm grant com.nello.tvhome android.permission.WRITE_SECURE_SETTINGS
# lets it install its own updates
adb shell appops set com.nello.tvhome REQUEST_INSTALL_PACKAGES allow
adb shell appops set com.nello.tvhome WRITE_SETTINGS allow
adb shell pm grant com.nello.tvhome com.wireguard.android.permission.CONTROL_TUNNELS || true
adb shell cmd deviceidle whitelist +com.wireguard.android || true
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in
  *com.nello.tvhome*) ;;
  null|"") adb shell settings put secure enabled_accessibility_services com.nello.tvhome/com.nello.tvhome.WakeService ;;
  *) adb shell "settings put secure enabled_accessibility_services '$CUR:com.nello.tvhome/com.nello.tvhome.WakeService'" ;;
esac
adb shell settings put secure accessibility_enabled 1
echo "Nello TV installato. Scegli Nello TV come home in Home on Fire."
