# UU Terminal NerdFont

把**网易 UU 远程**（`com.netease.uuremote`）内置终端里的 Nerd Font 图标从"方块"修复为正常图标。

- 形式：**LSPosed 模块**（APK，**自包含字体** —— 无需改系统字体、无需 Magisk 字体模块）
- 适用：UU 远程 Android 版（4.42 实测；其他 4.x 理论上通用）
- 前提：任意 LSPosed 系框架（LSPosed / Vector / KernelSU + Zygisk Next 等），Android 9+（API 26+）

## 下载

- 最新 APK：[`dist/uu-terminal-nerdfont-v1.0.0.apk`](dist/uu-terminal-nerdfont-v1.0.0.apk)
- 打包下载（APK + 本说明）：[`dist/uu-terminal-nerdfont-v1.0.0.zip`](dist/uu-terminal-nerdfont-v1.0.0.zip)
- 或见仓库 Releases 页

## 现象

UU 远程 → 设备 → **终端** → 进入会话后，所有 Nerd Font 图标（tmux 状态栏的自定义按钮、oh-my-zsh / starship / p10k 主题图标等）显示为**彩色小方块** □。

根因：UU 终端用 `Typeface.Builder` / `Typeface.CustomFallbackBuilder` 加载**随包自带的字体** `res/ak/a.ttf`（经验证为 Courier New Bold 派生，**不含 Nerd 字形**）。它既不跟随系统字体变化，也不走 `createFromAsset`，所以换系统字体/换输入法都没用。

## 原理

模块挂钩 UU 进程内的字体加载路径，并把返回值替换为**随模块 APK 分发的 JetBrainsMono Nerd Font Mono**（首次使用时复制到目标进程缓存再加载）：

| 挂钩点 | 说明 |
|---|---|
| `Typeface.Builder.build()` | 实测主路径（v3 日志计数 780+ 次）|
| `Typeface$CustomFallbackBuilder.build()` | AndroidX TypefaceCompat 的 API 29+ 路径 |
| `Typeface.create(String, int)` | 家族名含 `courier` 时替换 |
| `Typeface.createFromAsset` / `createFromFile` / `Resources.getFont` | 兜底 |

加载优先级：模块内置字体 → `/system/fonts/DroidSansMono.ttf`（若系统已被 Nerd 字体模块覆盖）→ `monospace` 兜底。

## 安装

1. 安装 APK：`adb install uu-terminal-nerdfont-v1.0.0.apk`（或在设备上直接点开安装）
2. 打开 LSPosed / Vector 管理器 → 模块 → **启用「UU Terminal NerdFont」**
3. **作用域勾选**：`UU远程`（`com.netease.uuremote`）
4. **重启设备**（至少重启 UU 远程进程）

## 验证

打开 UU 远程 → 设备 → 终端 → 任意会话，Nerd 图标应正常显示。

日志（管理器"日志"页，或 `/data/adb/lspd/log/modules_*.log`）：

```
[UUFontFix] 注入 com.netease.uuremote
[UUFontFix] Nerd 字体就绪(bundled): true
[UUFontFix] 命中 Builder.build()
```

命令行验证：

```bash
su -c 'grep -h -i uufontfix /data/adb/lspd/log/modules_*.log | tail -10'
```

## 停用 / 卸载

- 管理器里关闭模块 + 重启；或直接卸载 APK。
- 卸载后 UU 终端字体会恢复为自带字体（图标重新变方块），无残留。

## 从源码构建

```bash
./build.sh    # 需要 Android SDK：build-tools 30.0.3 的 dx + 34.0.0 的 aapt/zipalign/apksigner
```

`api-82.jar`（Xposed API stub）已随目录附带（取自 Aliyun Maven 镜像 `de.robv.android.xposed:api:82`）。
`assets/fonts/JetBrainsMonoNerdFontMono-Regular.ttf` 为随包分发的 Nerd 字体。

构建产物：`uufontfix.apk`。

## 许可

- 模块代码：MIT
- 字体：JetBrains Mono（SIL OFL 1.1，见 `assets/OFL.txt`）+ Nerd Fonts 补丁（MIT）
- 本项目为第三方社区模块，与网易无关；"UU远程"相关权利归其各自权利人所有。
