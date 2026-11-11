#!/bin/bash
# 无线 adb 连接（不需要记住 IP：靠 mDNS 发现 _adb-tls-connect，IP 变了也能连）
# 用法：bash tools/adb_wireless.sh            → 发现并连接
#       bash tools/adb_wireless.sh --status   → 只看当前连接
set -e
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

if [ "$1" = "--status" ]; then
  adb devices -l
  exit 0
fi

# mDNS 里能直接拿到 "adb-<serial>  _adb-tls-connect._tcp  <ip>:<port>"
line=$(adb mdns services 2>/dev/null | grep "_adb-tls-connect._tcp" | head -1 || true)
if [ -z "$line" ]; then
  echo "没发现无线调试设备。检查：手机 WIFI 与电脑同网段 + 开发者选项里「无线调试」开着。" >&2
  exit 1
fi
target=$(echo "$line" | awk '{print $3}')
echo "发现：$target"
adb connect "$target"
sleep 1
adb devices -l
