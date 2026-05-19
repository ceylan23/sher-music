#!/bin/bash
set -e

echo "=== Sher Music 卸载 ==="

if [ "$EUID" -ne 0 ]; then
  echo "请用 sudo 运行: sudo bash aur/uninstall.sh"
  exit 1
fi

echo "[1/2] 停止进程..."
pkill -f "electron.*sher-music" 2>/dev/null || true
pkill -f "node.*/opt/sher-music" 2>/dev/null || true
sleep 1

echo "[2/2] 删除文件..."
rm -rf /opt/sher-music
rm -f /usr/share/applications/sher-music.desktop

echo "Sher Music 已卸载"
