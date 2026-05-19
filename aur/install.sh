#!/bin/bash
set -e

APP_DIR="/opt/sher-music"

echo "=== Sher Music 桌面版安装 ==="

if [ "$EUID" -ne 0 ]; then
  echo "请用 sudo 运行: sudo bash aur/install.sh"
  exit 1
fi

# Check electron
if ! command -v electron &>/dev/null; then
  echo "[0/4] 安装 Electron..."
  npm install -g electron 2>/dev/null || {
    echo "尝试用 pacman 安装..."
    pacman -S --noconfirm electron 2>/dev/null || {
      echo "请手动安装: npm install -g electron"
      exit 1
    }
  }
fi

echo "[1/4] 停止旧进程..."
pkill -f "electron.*sher-music" 2>/dev/null || true
pkill -f "node.*$APP_DIR" 2>/dev/null || true
sleep 1

echo "[2/4] 安装文件到 $APP_DIR ..."
rm -rf "$APP_DIR"
mkdir -p "$APP_DIR"

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cp -r "$SCRIPT_DIR/app.js" \
      "$SCRIPT_DIR/index.js" \
      "$SCRIPT_DIR/server.js" \
      "$SCRIPT_DIR/package.json" \
      "$APP_DIR/"
[ -d "$SCRIPT_DIR/module" ]  && cp -r "$SCRIPT_DIR/module"  "$APP_DIR/"
[ -d "$SCRIPT_DIR/util" ]    && cp -r "$SCRIPT_DIR/util"    "$APP_DIR/"
[ -d "$SCRIPT_DIR/public" ]  && cp -r "$SCRIPT_DIR/public"  "$APP_DIR/"
[ -d "$SCRIPT_DIR/electron" ] && cp -r "$SCRIPT_DIR/electron" "$APP_DIR/"

echo "[3/4] 安装依赖..."
cd "$APP_DIR"
npm install --omit=dev --no-optional --ignore-scripts 2>/dev/null

echo "[4/4] 创建桌面快捷方式..."
ELECTRON_BIN=$(command -v electron)

cat > /usr/share/applications/sher-music.desktop << EOF
[Desktop Entry]
Name=Sher Music
Comment=Sher 音乐播放器
Exec=${ELECTRON_BIN} ${APP_DIR}
Icon=audio-x-generic
Terminal=false
Type=Application
Categories=AudioVideo;Audio;Music;
StartupWMClass=sher-music
EOF

echo ""
echo "=== 安装完成 ==="
echo "在应用菜单找到 'Sher Music' 启动"
echo "或命令行运行: electron $APP_DIR"
echo ""
echo "卸载: sudo bash aur/uninstall.sh"
