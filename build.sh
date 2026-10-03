#!/bin/bash
# 构建「UU 终端字体修复」Xposed 模块（com.guo.uufont）
#
# 作用：把网易 UU 远程（com.netease.uuremote）进程内加载的任何 ttf 字体
#       替换为系统 monospace（/system/fonts/DroidSansMono.ttf —— 已由
#       nerd_monospace 模块覆盖为 JetBrainsMono Nerd Font Mono），
#       修复 UU 内置终端里 Nerd 图标（tmux 状态栏等）显示为方块的问题。
#
# 依赖：
#   - Android SDK：build-tools 34.0.0 的 aapt/zipalign/apksigner，
#     build-tools 30.0.3 的 dx（本机上 d8 会抛 NPE，改用 dx）
#   - api-82.jar（Xposed API stub，已随目录提供；
#     来源 maven.aliyun.com 镜像 de.robv.android.xposed:api:82）
#
# 安装：adb install -r uu-terminal-nerdfont.apk
# 启用：su -c 'sh /data/adb/modules/zygisk_vector/cli modules enable com.guo.uufont'
# 作用域：su -c 'sh /data/adb/modules/zygisk_vector/cli scope set com.guo.uufont com.netease.uuremote/0'
# 重启生效。
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
DX="$SDK/build-tools/30.0.3/dx"
AJ="$SDK/platforms/android-33/android.jar"

rm -rf build
mkdir -p build/classes build/dex

echo "[1/5] javac"
javac -source 8 -target 8 -nowarn -cp "$AJ:api-82.jar" -d build/classes src/com/guo/uufont/UUFontFix.java

echo "[2/5] dx -> classes.dex"
"$DX" --dex --output=build/dex/classes.dex build/classes

echo "[3/5] 打包"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$AJ" -F build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zip -qr build/unsigned.apk assets

echo "[4/5] 对齐 + 签名"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f ks.jks ]; then
  keytool -genkeypair -keystore ks.jks -storepass android -keypass android -alias m \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=omp"
fi
"$BT/apksigner" sign --ks ks.jks --ks-pass pass:android --key-pass pass:android \
  --out uu-terminal-nerdfont.apk build/aligned.apk

echo "[5/5] 验证"
"$BT/apksigner" verify uu-terminal-nerdfont.apk
echo "OK -> $(pwd)/uu-terminal-nerdfont.apk"
