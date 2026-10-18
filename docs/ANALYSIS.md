# 醒图 15.3.0 会员体系逆向分析报告

- **目标**：`醒图_15.3.0.apk`（354.65 MB，36 个 dex）
- **包名**：`com.xt.retouch`
- **versionName / versionCode**：`15.3.0` / `153080`
- **minSdk / targetSdk / compileSdk**：21 / 34 / 35
- **SHA-256**：`C5EF156D7307294755C64F5119EA7911C7C935FE452BE253CF160F010DBF65E1`
- **入口 Activity**：`com.xt.retouch.uilauncher.LaunchActivity`
- **Application**：`com.xt.retouch.MainApplication`

## 0. 加固与混淆判定

`AndroidManifest.xml` 里 `android:name` 直接是 `com.xt.retouch.MainApplication`，**没有壳 Application 接管**；36 个 `classes*.dex` 全部可直接解析出血缘清晰的业务类（`com.xt.retouch.subscribe.*`、`com.xt.retouch.export.*`、`com.xt.edit.watermark.*`）。

结论：**无整体加壳**（无 360/爱加密/梆梆壳）。字节系保护集中在 native 层（`libmetasec_ml.so` 反调试/签名校验、`libnpth_*.so` 运行时监控），与本次 Java 层 VIP 逻辑无耦合，不必触碰。

DEX 内的类名基本保留，只有少量工具类被 R8 压成短名（例如本报告的核心类 `la8.c`）。

## 1. VIP 数据模型

会员状态来自剪映同源商务 SDK `com.lemon.lv.clipmonetize`（醒图与剪映共用该模块）：

```java
// com.lemon.lv.clipmonetize.data.UserDetailInfo
public final boolean isVipUser()          { return this.flag; }
public final boolean isVipWithRewardAd()  { if (isAdReward) return flag && vipRealEnd > now/1000; return flag; }
public final boolean isGiftVip()          { return this.isFrisDistribute; }
public final String  getCurVipLevel()     { return this.curVipLevel; }   // "general"/"vip"/"svip"
public final long    getEndTime()         { return this.endTime; }       // 秒级
```

等级枚举：

```java
// com.lemon.lv.clipmonetize.data.VipState
UNKNOWN, GENERAL, VIP, SVIP          // from(int): 0→GENERAL, 1→VIP, 2→SVIP
label(): GENERAL→"general", SVIP→配置串, VIP→配置串
```

**关键**：`isVipUser()` 只读私有字段 `flag`，不做签名/时间校验。也就是说，只要这个方法的返回值为 `true`，全 App 都认为当前用户是会员 —— 这是最干净的单点。

## 2. 判定汇聚层 `SubscribeApi`

业务代码统一走接口 `com.xt.retouch.subscribe.api.SubscribeApi`，实现类 `SubscribeApiImpl` 全部转发到 `SubscribeSdkHelper`：

| 接口方法 | 实现 | 语义 |
|---|---|---|
| `c()` | `SubscribeSdkHelper.L()` → `isVipWithRewardAd()` | 是否 VIP（含广告奖励） |
| `w()` | `SubscribeSdkHelper.J()` → `isVipUser() && curVipLevel == SVIP.label()` | **是否 SVIP** |
| `V()` | `SubscribeSdkHelper.N()` → `q2.a.D2() \|\| !nf7.a.a.b() \|\| L()` | 是否可跳过权益校验 |
| `W(Continuation)` | `P()` → `isVipUserWithCacheCheck` | 异步 VIP 判定 |
| `M(Continuation)` | `K()` → `isSVipUserWithCacheCheck` | 异步 SVIP 判定 |
| `l()` | — | 取 `UserDetailInfo` |

`SubscribeApiImpl` 里所有方法体都被 `com.bytedance.hotfix.PatchProxy` 包了一层（Tinker 热修代理），未打补丁时走原逻辑。

## 3. 导出 / 保存的 VIP 拦截链路（核心）

### 3.1 画质增强导出

`com.xt.retouch.export.impl.enhance.ExportImageEnhanceViewModel`：

```java
public final void sO(Activity activity, Function1 exportCallback) {   // 导出入口
    ...
    aO(activity, () -> tO(this, exportCallback, activity));           // 先检查登录
}

private static g1 tO(vm, callback, activity) {
    BenefitResource r = vm.eO();
    if (vm.oO().w() || (vm.oO().V() && !la8.c.k(r))) {   // ← SVIP，或 可跳过且资源非 SVIP 专属
        log("login vip account, directly export");
        vm.bO(vm.j, callback);                            // 直接导出
        return g1.a;
    }
    if (la8.c.j(r)) {                                     // ← 限时免费
        log("has free trial, directly export");
        vm.vO(activity, callback);                        // 直接导出
        return g1.a;
    }
    vm.rO(() -> ...);                                     // ← 否则：go subscribe page
}
```

`rO()` 打日志 `"go subscribe page"`，随后 `pO().b("export_image_enhance", ...)` 埋点并弹订阅页 —— **这就是"用了 VIP 功能就不给保存"的实际落点**。

### 3.2 素材权益判定 `la8.c`（真实名 `com.xt.retouch.subscribe.api.ext.BenefitResourceExtKt`）

被 R8 压成 `la8.c`，是 `BenefitResource` 的 Kotlin 扩展函数集合。Kotlin metadata 里的原始函数名与短名对应关系：

| 短名 | 原始名 | 实现 | 语义 |
|---|---|---|---|
| `m` | `isVipBenefit` | `role == VipState.VIP.label()` | 该素材属 VIP 权益 |
| `k` | `isSVipBenefit` | `role == VipState.SVIP.label()` | 该素材属 SVIP 专属 |
| `e` | `exist` | `BusinessManager.c.a().t(r) != null` | 存在付费策略 |
| `i` | `hasEffectivePayStrategy` | 策略有效性 | 有可用付费策略 |
| `g` | `getFreeLimitType` | — | 免费额度类型 |
| `j` | `isLimitTime` | `BusinessManager.m(...AssetType.LimitFree...)` | **是否限时免费** |
| `f` | `getFreeCount` | — | 免费次数 |
| `l` | `isUseMode` | `useMode == "use"` | 使用态 |

导出流程用 `m`/`k`/`j` 三个判定决定放行还是弹订阅页。**把 `isVipBenefit`/`isSVipBenefit` 改判为否、`isLimitTime` 改判为是，导出就直接走第一条分支，普通用户也不会被拦。**

### 3.3 导出页订阅弹窗

`com.xt.retouch.export.impl.component.ExportComponent2`（及 `ExportComponent3`、`NewExportFrameComponent`）里的 `tryShowExportSubscribePopup(...)`：
- 埋点 `ExportPageComponent2` → 检查 `RtPopupStrategyManager.a.m("VIPPromotionPopup")` 频控 → `ExportSubscribeConfigManager.a.c()` 拉广告位 → 弹订阅。

这条是**营销弹窗**，与"能不能保存"无因果关系，但一并掐掉体验更干净。

### 3.4 水印

`com.xt.retouch.effect.watermark.WatermarkProviderImpl` 只管水印贴纸素材的加载/远端同步（`panel=watermark`），不含会员判定；去水印入口是 `com.xt.retouch.subscribe.api.IRemoveWaterMarkBusinessLogic.a(...)`（导出时是否展示"去水印"提醒条）。VIP 身份为真后该分支自然走通，无需单独 hook。

## 4. 关键结论

1. VIP 判定无签名/无服务端强校验兜底，`UserDetailInfo.isVipUser()` 一条就能把全局身份改成会员。
2. "用了 VIP 素材不给保存"不是导出器拦的，而是**导出前的权益判定 `la8.c` + 订阅页跳转**拦的。改判 `la8.c` 三个布尔函数即可放行。
3. 导出后若有服务端权益回执（`checkBenefitUsage`）也只会影响埋点与工作量统计，不会回滚已落盘的图片。

## 5. Hook 清单（已实现于模块）

| # | 类 | 方法 | 改判 |
|---|---|---|---|
| 1 | `com.lemon.lv.clipmonetize.data.UserDetailInfo` | `isVipUser()` | `true` |
| 2 | 同上 | `isVipWithRewardAd()` | `true` |
| 3 | 同上 | `isGiftVip()` / `isAutoSubscribeVip()` / `isUnAutoSubscribeVip()` | `true` |
| 4 | 同上 | `isAdReward()` / `isDidVipUser()` / `isGuest()` | `false` |
| 5 | 同上 | `getCurVipLevel()` / `getExpiredVipLevel()` | `"svip"` |
| 6 | 同上 | `getEndTime()` / `getVipRealEnd()` / `getGroupVipEndTime()` / `getBenefitEndAt()` | `4102444800`（2100-01-01） |
| 7 | 同上 | `getSubscribeType()` / `getVipSource()` | `"auto"` / `"uid"` |
| 8 | `com.xt.retouch.subscribe.impl.SubscribeApiImpl` | `c/w/V/m/I/Y/i0/k0/r/j()` | `true` |
| 9 | 同上 | `W/M/A(Continuation)`（suspend） | `true` |
| 10 | 同上 | `getUserType()` | `"vip"` |
| 11 | 同上 | `showLimitFreeToast(...)` | 空实现 |
| 12 | `com.xt.retouch.subscribe.impl.SubscribeSdkHelper` | `J/L/N/O/I/H/F()` | `true` |
| 13 | 同上 | `P/K(Continuation)` | `true` |
| 14 | 同上 | `n()` / `v()` | `"svip"` / `"vip"` |
| 15 | **`la8.c`** | `m(BenefitResource)` = isVipBenefit | **`false`** |
| 16 | **`la8.c`** | `k(BenefitResource)` = isSVipBenefit | **`false`** |
| 17 | **`la8.c`** | `j(BenefitResource)` = isLimitTime | **`true`** |
| 18 | **`la8.c`** | `i(BenefitResource)` = hasEffectivePayStrategy | `true` |
| 19 | `com.xt.retouch.export.impl.component.ExportComponent2/3` | `tryShowExportSubscribePopup(...)` | 空实现 |
| 20 | `com.xt.retouch.export.impl.component.newframe.NewExportFrameComponent` | 同上 | 空实现 |
| 21 | `com.xt.retouch.export.impl.enhance.ExportImageEnhanceViewModel` | `rO(...)` | 空实现（不跳订阅页） |

## 6. 分析产物位置

| 内容 | 路径 |
|---|---|
| 原始 APK | `XingTu/APK/醒图_15.3.0.apk` |
| ASCII 工副本 | `XingTu/work/xingtu_15.3.0.apk` |
| apktool 解包（manifest + res + lib） | `XingTu/work/apktool/` |
| 关键词扫描结果（9209 命中） | `XingTu/work/dex_hits.json` |
| VIP 类排名 | `XingTu/work/vip_classes.json` |
| 类 → dex 映射 | `XingTu/work/dex_priority.json` |
| 精准正则扫描 | `XingTu/work/deep_scan.json` |
| jadx 反编译（subscribe/export/watermark，16295 类） | `XingTu/work/jadx_focus/sources/` |
| jadx 反编译（clipmonetize/UserDetailInfo） | `XingTu/work/jadx_focus2/sources/` |
| libxposed API 101 源码参考 | `XingTu/work/libxposed/src/` |
