package com.xt.vip;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 醒图 VIP 与导出限制的实际 Hook 安装器。
 *
 * <p>全部 Hook 均以「类名 + 方法名」反射定位，逐条独立 try/catch：
 * 单条失败只记日志，不影响其余 Hook，也不会让目标进程崩溃。
 *
 * <p>Hook 目标来自对 醒图 15.3.0（versionCode 153080）的静态分析：
 * <ul>
 *   <li>{@code com.lemon.lv.clipmonetize.data.UserDetailInfo} —— VIP 身份数据模型，
 *       {@code isVipUser()} 直接返回私有字段 {@code flag}</li>
 *   <li>{@code com.xt.retouch.subscribe.impl.SubscribeApiImpl} —— {@code SubscribeApi}
 *       接口实现，导出/素材校验统一走这里</li>
 *   <li>{@code com.xt.retouch.subscribe.impl.SubscribeSdkHelper} —— 旧版同步判定入口</li>
 *   <li>{@code com.xt.retouch.export.impl.*} —— 导出页订阅拦截与弹窗</li>
 * </ul>
 */
final class HookInstaller {

    private static final AtomicInteger sOk = new AtomicInteger();
    private static final AtomicInteger sFail = new AtomicInteger();

    /** 醒图把到期时间按「秒」与 System.currentTimeMillis()/1000 比较。 */
    private static final long FAR_FUTURE_SECONDS = 4102444800L; // 2100-01-01

    private HookInstaller() {
    }

    static void install(XposedInterface api, ClassLoader cl) {
        if (api == null || cl == null) {
            XLog.e("install: null api/classloader", null);
            return;
        }
        long t0 = System.currentTimeMillis();

        // ── A. VIP 身份：UserDetailInfo 数据模型 ────────────────────────────
        installUserDetailInfo(api, cl);

        // ── B. SubscribeApi 实现层：所有 VIP 判定的汇聚点 ───────────────────
        installSubscribeApiImpl(api, cl);

        // ── C. 旧版同步入口：SubscribeSdkHelper ────────────────────────────
        installSubscribeSdkHelper(api, cl);

        // ── D. 导出拦截 / 订阅弹窗：直接掐断 ───────────────────────────────
        installExportGates(api, cl);

        // ── E. 素材权益判定：VIP 素材不再拦保存（核心）────────────────────
        installBenefitResourceExt(api, cl);

        XLog.i("install done: ok=" + sOk.get() + " fail=" + sFail.get()
                + " in " + (System.currentTimeMillis() - t0) + "ms");
    }

    // =====================================================================
    // A. UserDetailInfo
    // =====================================================================

    private static void installUserDetailInfo(XposedInterface api, ClassLoader cl) {
        Class<?> c = loadClass(cl, "com.lemon.lv.clipmonetize.data.UserDetailInfo");
        if (c == null) {
            return;
        }
        // 布尔型 VIP 标记全家桶 → 一律 true
        for (String name : new String[]{
                "isVipUser", "isVipWithRewardAd", "isGiftVip",
                "isAutoSubscribeVip", "isUnAutoSubscribeVip"}) {
            hookReturn(api, c, name, Boolean.TRUE);
        }
        // isAdReward / isDidVipUser / isGuest 保持「非特殊来源」，避免走广告或抖音来源分支
        for (String name : new String[]{"isAdReward", "isDidVipUser", "isGuest"}) {
            hookReturn(api, c, name, Boolean.FALSE);
        }
        // VIP 等级 → svip；到期时间 → 远期
        hookReturn(api, c, "getCurVipLevel", "svip");
        hookReturn(api, c, "getExpiredVipLevel", "svip");
        hookReturnLong(api, c, "getEndTime", FAR_FUTURE_SECONDS);
        hookReturnLong(api, c, "getVipRealEnd", FAR_FUTURE_SECONDS);
        hookReturnLong(api, c, "getGroupVipEndTime", FAR_FUTURE_SECONDS);
        hookReturnLong(api, c, "getBenefitEndAt", FAR_FUTURE_SECONDS);
        hookReturn(api, c, "getSubscribeType", "auto");
        hookReturn(api, c, "getVipSource", "uid");
    }

    // =====================================================================
    // B. SubscribeApiImpl
    // =====================================================================

    private static void installSubscribeApiImpl(XposedInterface api, ClassLoader cl) {
        Class<?> c = loadClass(cl, "com.xt.retouch.subscribe.impl.SubscribeApiImpl");
        if (c == null) {
            return;
        }
        // 无反参的布尔判定：c/w/V/m/I/Y/i0/k0/r/j 全部为「当前用户有权益」
        for (String name : new String[]{
                "c", "w", "V", "m", "I", "Y", "i0", "k0", "r", "j"}) {
            hookReturnByName(api, c, name, Boolean.TRUE);
        }
        // 挂起型判定（返回 Object，语义为 Boolean）：同步给出 true，避免走网络校验
        for (String name : new String[]{"W", "M", "A"}) {
            hookReturnByName(api, c, name, Boolean.TRUE);
        }
        hookReturn(api, c, "getUserType", "vip");
    }

    // =====================================================================
    // C. SubscribeSdkHelper
    // =====================================================================

    private static void installSubscribeSdkHelper(XposedInterface api, ClassLoader cl) {
        Class<?> c = loadClass(cl, "com.xt.retouch.subscribe.impl.SubscribeSdkHelper");
        if (c == null) {
            return;
        }
        for (String name : new String[]{"J", "L", "N", "O", "I", "H", "F"}) {
            hookReturnByName(api, c, name, Boolean.TRUE);
        }
        for (String name : new String[]{"P", "K"}) {
            hookReturnByName(api, c, name, Boolean.TRUE);
        }
        hookReturn(api, c, "n", "svip");
        hookReturn(api, c, "v", "vip");
    }

    // =====================================================================
    // D. 导出拦截 / 订阅弹窗
    // =====================================================================

    private static void installExportGates(XposedInterface api, ClassLoader cl) {
        // 导出页「开通会员」拦截弹窗：调用点整体置空，不阻断导出流程本身
        noOpAll(api, cl, "com.xt.retouch.export.impl.component.ExportComponent2",
                "tryShowExportSubscribePopup");
        noOpAll(api, cl, "com.xt.retouch.export.impl.component.ExportComponent3",
                "tryShowExportSubscribePopup");
        noOpAll(api, cl, "com.xt.retouch.export.impl.component.newframe.NewExportFrameComponent",
                "tryShowExportSubscribePopup");

        // 画质增强导出：非 VIP 分支会跳订阅页，这里掐断跳转
        noOpAll(api, cl, "com.xt.retouch.export.impl.enhance.ExportImageEnhanceViewModel", "rO");

        // 「限时免费次数已用完」之类的 toast 不再触发
        noOpAll(api, cl, "com.xt.retouch.subscribe.impl.SubscribeApiImpl", "showLimitFreeToast");
    }

    // =====================================================================
    // E. 素材权益判定 —— 「用了 VIP 功能就不给保存」的正面解法
    // =====================================================================

    /**
     * 目标：{@code com.xt.retouch.subscribe.api.ext.BenefitResourceExtKt}
     * （被 R8 重命名为 {@code la8.c}）。
     *
     * <p>该类是 {@code BenefitResource} 的 Kotlin 扩展函数集合，导出流程用它判断
     * 「这个素材/功能属不属于会员权益」，判定为会员专属就会拦下保存并跳订阅页：
     * <pre>
     *   if (w() || (V() && !la8.c.k(r))) { 直接导出 }
     *   if (la8.c.j(r))                  { 直接导出 }   // 限时免费
     *   rO();                                            // 否则跳订阅
     * </pre>
     *
     * <p>这里把「是不是 VIP/SVIP 专属」全部改判为否，把「限时免费」改判为是，
     * 于是导出流程走第一条分支，普通用户也不会被拦。
     */
    private static void installBenefitResourceExt(XposedInterface api, ClassLoader cl) {
        Class<?> c = loadClass(cl, "la8.c");
        if (c == null) {
            XLog.i("BenefitResourceExtKt(la8.c) 未命中，该类可能已被 R8 重新分配短名");
            return;
        }
        // m = isVipBenefit   → 该资源不再需要 VIP
        hookStaticByName(api, c, "m", Boolean.FALSE);
        // k = isSVipBenefit  → 该资源不再是 SVIP 专属
        hookStaticByName(api, c, "k", Boolean.FALSE);
        // j = isLimitTime(限时免费) → 视作限免可用，导出直接放行
        hookStaticByName(api, c, "j", Boolean.TRUE);
        // i = hasEffectivePayStrategy → 视作已有可用付费策略
        hookStaticByName(api, c, "i", Boolean.TRUE);
    }

    // =====================================================================
    // 基础设施
    // =====================================================================

    private static Class<?> loadClass(ClassLoader cl, String name) {
        try {
            Class<?> c = Class.forName(name, false, cl);
            XLog.i("class ok: " + name);
            return c;
        } catch (Throwable t) {
            XLog.i("class miss: " + name + " -> " + t);
            return null;
        }
    }

    /** Hook 一个无参方法，返回固定值。 */
    private static void hookReturn(XposedInterface api, Class<?> owner, String name,
                                   final Object value) {
        Method m = findNoArg(owner, name);
        if (m == null) {
            return;
        }
        hookFixed(api, m, value);
    }

    private static void hookReturnLong(XposedInterface api, Class<?> owner, String name,
                                       final long value) {
        Method m = findNoArg(owner, name);
        if (m == null) {
            return;
        }
        hookFixed(api, m, Long.valueOf(value));
    }

    /**
     * 按名字模糊匹配（不限定参数），hook 该名字下「无参」或「单 Continuation 参」的判定方法。
     * 醒图的布尔判定方法签名被 R8 打散，这里对同名全部候选做类型检查后逐个安装。
     */
    private static void hookReturnByName(XposedInterface api, Class<?> owner, String name,
                                         final Object value) {
        Method[] all;
        try {
            all = owner.getDeclaredMethods();
        } catch (Throwable t) {
            XLog.e("getDeclaredMethods fail on " + owner.getName(), t);
            return;
        }
        boolean any = false;
        for (Method m : all) {
            if (!name.equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 0) {
                hookFixed(api, m, value);
                any = true;
            } else if (ps.length == 1 && "kotlin.coroutines.Continuation".equals(ps[0].getName())) {
                hookFixedSuspend(api, m, value);
                any = true;
            }
        }
        if (!any) {
            XLog.i("method miss: " + owner.getSimpleName() + "#" + name);
        }
    }

    /**
     * Hook 一个静态方法（按名字匹配，不限定参数表），返回固定值。
     * 用于 Kotlin 顶层扩展函数被 R8 打散后的类。
     */
    private static void hookStaticByName(XposedInterface api, Class<?> owner, String name,
                                         final Object value) {
        Method[] all;
        try {
            all = owner.getDeclaredMethods();
        } catch (Throwable t) {
            XLog.e("getDeclaredMethods fail on " + owner.getName(), t);
            return;
        }
        boolean any = false;
        for (Method m : all) {
            if (!name.equals(m.getName()) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() != boolean.class) {
                continue;
            }
            hookFixed(api, m, value);
            any = true;
        }
        if (!any) {
            XLog.i("static method miss: " + owner.getName() + "#" + name);
        }
    }

    private static void hookFixed(XposedInterface api, final Method m, final Object value) {
        try {
            m.setAccessible(true);
            api.hook(m).intercept(chain -> {
                XLog.d("hit " + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                        + " -> " + value);
                return value;
            });
            sOk.incrementAndGet();
            XLog.i("hook ok: " + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                    + "() = " + value);
        } catch (Throwable t) {
            sFail.incrementAndGet();
            XLog.e("hook fail: " + m, t);
        }
    }

    /** suspend 函数：直接返回装箱值即可被状态机当作正常返回（不挂起）。 */
    private static void hookFixedSuspend(XposedInterface api, final Method m,
                                         final Object value) {
        hookFixed(api, m, value);
    }

    /** 把某个类下所有同名方法变成空实现（void→null，boolean→false）。 */
    private static void noOpAll(XposedInterface api, ClassLoader cl, String className,
                                String methodName) {
        Class<?> c = loadClass(cl, className);
        if (c == null) {
            return;
        }
        Method[] all;
        try {
            all = c.getDeclaredMethods();
        } catch (Throwable t) {
            XLog.e("getDeclaredMethods fail on " + className, t);
            return;
        }
        boolean any = false;
        for (final Method m : all) {
            if (!methodName.equals(m.getName())) {
                continue;
            }
            final Object empty = m.getReturnType() == boolean.class ? Boolean.FALSE : null;
            try {
                m.setAccessible(true);
                api.hook(m).intercept(chain -> {
                    XLog.d("blocked " + m.getDeclaringClass().getSimpleName()
                            + "#" + m.getName());
                    return empty;
                });
                sOk.incrementAndGet();
                any = true;
                XLog.i("noop ok: " + className + "#" + m.getName());
            } catch (Throwable t) {
                sFail.incrementAndGet();
                XLog.e("noop fail: " + m, t);
            }
        }
        if (!any) {
            XLog.i("noop miss: " + className + "#" + methodName);
        }
    }

    private static Method findNoArg(Class<?> owner, String name) {
        try {
            Method m = owner.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // 回退：按名字扫描，取零参版本（getter 可能带 @JvmStatic 或被搬运）
            try {
                for (Method m : owner.getDeclaredMethods()) {
                    if (name.equals(m.getName()) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
            XLog.i("method miss: " + owner.getSimpleName() + "#" + name + "()");
            return null;
        } catch (Throwable t) {
            XLog.e("find fail: " + owner.getSimpleName() + "#" + name, t);
            return null;
        }
    }
}
