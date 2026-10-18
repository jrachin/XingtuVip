package com.xt.vip;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 醒图（com.xt.retouch）LSPosed 模块入口 —— libxposed API 101。
 *
 * <p>入口约定：assets/xposed_init 中声明的类名，框架为每个进程实例化一次，
 * 再依次回调 {@link #onModuleLoaded} 与 {@link #onPackageReady}。
 */
public final class XingTuVipModule extends XposedModule {

    /** 目标包名（醒图 / Xingtu）。 */
    static final String TARGET_PACKAGE = "com.xt.retouch";

    public XingTuVipModule() {
        // 构造函数里不做任何初始化：此时框架尚未注入。
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XLog.bind(new XLog.Logger() {
            @Override
            public void write(int priority, String tag, String msg, Throwable tr) {
                if (tr == null) {
                    log(priority, tag, msg);
                } else {
                    log(priority, tag, msg, tr);
                }
            }
        });
        XLog.i("module loaded in process: " + param.getProcessName()
                + " (systemServer=" + param.isSystemServer() + ")");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PACKAGE.equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        XLog.i("package ready: " + pkg + " cl=" + cl);
        try {
            HookInstaller.install(this, cl);
        } catch (Throwable t) {
            XLog.e("install failed", t);
        }
    }

    /** 供 HookInstaller 使用的最小抽象，避免直接依赖具体 Xposed 实现类。 */
    static XposedInterface api(XposedModule module) {
        return module;
    }
}
