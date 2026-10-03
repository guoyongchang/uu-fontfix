package io.github.guoyongchang.uufont;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * UU Terminal NerdFont —— 修复网易 UU 远程（com.netease.uuremote）内置终端
 * 的 Nerd Font 图标显示为方块的问题。
 *
 * 原理：UU 终端用 Typeface.Builder / CustomFallbackBuilder 加载随包自带的
 * 字体（res/ak/a.ttf，无 Nerd 字形且不随系统字体变化）。本模块挂钩其字体
 * 加载路径，把返回值替换为随模块 APK 分发的 JetBrainsMono Nerd Font Mono
 * （assets/fonts/...，自包含，不依赖系统字体改动）。
 *
 * 加载优先级：① 模块内置字体 → ② /system/fonts/DroidSansMono.ttf（若用户
 * 已用 Magisk 字体模块覆盖为 Nerd 字体）→ ③ Typeface.create("monospace")。
 */
public class UUFontFix implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.netease.uuremote";
    private static final String MODULE_PKG = "io.github.guoyongchang.uufont";
    private static final String BUNDLED_FONT = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf";
    private static final String SYSTEM_FONT = "/system/fonts/DroidSansMono.ttf";
    private static final String TAG = "[UUFontFix] ";

    private static Typeface sNerd;
    private static String sSource = "none";
    private static final ThreadLocal<Boolean> IN_LOAD = new ThreadLocal<Boolean>();

    private static synchronized Typeface nerd() {
        if (sNerd == null) {
            IN_LOAD.set(Boolean.TRUE);
            try {
                // ① 模块自带字体（多策略：Android 11+ 包可见性限制需要绕行）
                try {
                    Context app = AndroidAppHelper.currentApplication();
                    if (app != null) {
                        byte[] data = null;
                        // ①a 模块上下文（依赖 android:forceQueryable="true"）
                        try {
                            Context mod = app.createPackageContext(MODULE_PKG,
                                    Context.CONTEXT_IGNORE_SECURITY);
                            data = readAll(mod.getAssets().open(BUNDLED_FONT));
                            sSource = "bundled(①a)";
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "①a 模块上下文不可用: " + t);
                        }
                        // ①b 类加载器资源
                        if (data == null) {
                            try {
                                InputStream in = UUFontFix.class.getResourceAsStream("/assets/" + BUNDLED_FONT);
                                if (in != null) {
                                    data = readAll(in);
                                    sSource = "bundled(①b)";
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "①b 失败: " + t);
                            }
                        }
                        // ①c /proc/self/maps 找模块 APK 路径，直接读 zip 里的 assets
                        if (data == null) {
                            try {
                                String apk = findModuleApkInMaps();
                                if (apk != null) {
                                    java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk);
                                    java.util.zip.ZipEntry e = zf.getEntry("assets/" + BUNDLED_FONT);
                                    if (e != null) {
                                        data = readAll(zf.getInputStream(e));
                                        sSource = "bundled(①c:" + apk + ")";
                                    }
                                    zf.close();
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "①c 失败: " + t);
                            }
                        }
                        if (data != null) {
                            File dst = new File(app.getCacheDir(), "uuterm_nerd_mono.ttf");
                            FileOutputStream out = new FileOutputStream(dst);
                            out.write(data);
                            out.close();
                            sNerd = Typeface.createFromFile(dst);
                        }
                    } else {
                        XposedBridge.log(TAG + "① 当前无 Application 上下文，跳过内置字体");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "内置字体加载失败: " + t);
                }
                // ② 系统 monospace（用户可能已用字体模块替换为 Nerd 字体）
                if (sNerd == null) {
                    try {
                        sNerd = Typeface.createFromFile(SYSTEM_FONT);
                        if (sNerd != null) sSource = "system";
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "系统字体加载失败: " + t);
                    }
                }
                // ③ 兜底
                if (sNerd == null) {
                    try {
                        sNerd = Typeface.create("monospace", Typeface.NORMAL);
                        if (sNerd != null) sSource = "monospace";
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "monospace 兜底失败: " + t);
                    }
                }
                XposedBridge.log(TAG + "Nerd 字体就绪(" + sSource + "): " + (sNerd != null));
            } finally {
                IN_LOAD.remove();
            }
        }
        return sNerd;
    }

    /** 自己的加载调用不处理（防递归） */
    private static boolean isLoading() {
        return Boolean.TRUE.equals(IN_LOAD.get());
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(2621440);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** 从 /proc/self/maps 找本模块 APK 路径（绕开 Android 11+ 包可见性限制） */
    private static String findModuleApkInMaps() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("/data/app/");
                if (i < 0) continue;
                String p = line.substring(i);
                int sp = p.indexOf(' ');
                if (sp > 0) p = p.substring(0, sp);
                if (p.endsWith(".apk") && p.contains(MODULE_PKG)) {
                    r.close();
                    return p;
                }
            }
            r.close();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "maps 扫描失败: " + t);
        }
        return null;
    }

    private static String fmt(Object[] args) {
        if (args == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            sb.append(a == null ? "null" : (a instanceof String ? "\"" + a + "\"" : String.valueOf(a)));
        }
        return sb.toString();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) return;
        XposedBridge.log(TAG + "注入 " + lp.processName);

        XC_MethodHook swap = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (isLoading()) return;
                    XposedBridge.log(TAG + "命中 " + p.method.getDeclaringClass().getSimpleName() + "."
                            + p.method.getName() + "(" + fmt(p.args) + ")");
                    if (p.getResult() instanceof Typeface) {
                        Typeface nf = nerd();
                        if (nf != null) p.setResult(nf);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "回调异常: " + t);
                }
            }
        };

        // —— 字体加载路径（v3 实测：Builder.build / CustomFallbackBuilder.build 为主）——
        h(Typeface.class, lp, swap, "createFromAsset", AssetManager.class, String.class);
        h(Typeface.class, lp, swap, "createFromFile", String.class);
        h(Typeface.class, lp, swap, "createFromFile", File.class);
        h("android.graphics.Typeface$Builder", lp, swap, "build");
        h("android.graphics.Typeface$CustomFallbackBuilder", lp, swap, "build");
        h("android.content.res.Resources", lp, swap, "getFont", int.class);

        // 家族名创建（含 courier 时替换）
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "create", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (isLoading()) return;
                                String fam = (String) p.args[0];
                                XposedBridge.log(TAG + "命中 Typeface.create(\"" + fam + "\", "
                                        + p.args[1] + ")");
                                if (fam != null && fam.toLowerCase().contains("courier")) {
                                    Typeface nf = nerd();
                                    if (nf != null) p.setResult(nf);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "create(String,int) 异常: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "已挂钩 Typeface.create(String,int)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "挂钩 create(String,int) 失败: " + t);
        }

        // 观察：终端最终用到自定义字体时记一笔（降噪：只见 custom）
        XC_MethodHook watch = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (isLoading()) return;
                    Typeface tf = null;
                    if (p.args != null && p.args.length > 0 && p.args[0] instanceof Typeface) {
                        tf = (Typeface) p.args[0];
                    }
                    if (tf == null || tf == Typeface.DEFAULT || tf == Typeface.DEFAULT_BOLD
                            || tf == Typeface.SANS_SERIF || tf == Typeface.SERIF
                            || tf == Typeface.MONOSPACE || tf == sNerd) {
                        return;
                    }
                    XposedBridge.log(TAG + "观察 " + p.method.getDeclaringClass().getSimpleName() + "."
                            + p.method.getName() + " tf=custom@"
                            + Integer.toHexString(System.identityHashCode(tf)));
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Paint.class, "setTypeface", Typeface.class, watch);
        } catch (Throwable ignored) {
        }
    }

    private static void h(Class<?> cls, XC_LoadPackage.LoadPackageParam lp, XC_MethodHook cb,
                          String method, Object... paramTypes) {
        try {
            XposedHelpers.findAndHookMethod(cls, method, appendTypes(paramTypes, cb));
            XposedBridge.log(TAG + "已挂钩 " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "挂钩 " + cls.getSimpleName() + "." + method + " 失败: " + t);
        }
    }

    private static void h(String className, XC_LoadPackage.LoadPackageParam lp, XC_MethodHook cb,
                          String method, Object... paramTypes) {
        try {
            XposedHelpers.findAndHookMethod(className, lp.classLoader, method, appendTypes(paramTypes, cb));
            XposedBridge.log(TAG + "已挂钩 " + className + "." + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "挂钩 " + className + "." + method + " 失败: " + t);
        }
    }

    private static Object[] appendTypes(Object[] types, XC_MethodHook cb) {
        Object[] out = new Object[types.length + 1];
        System.arraycopy(types, 0, out, 0, types.length);
        out[types.length] = cb;
        return out;
    }
}
