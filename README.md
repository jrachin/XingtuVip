# XingtuVip

> 醒图（Xingtu / `com.xt.retouch`）本地会员解锁模块 —— 基于 **libxposed API 101** 的 LSPosed 模块。

<br>

<div align="center">

![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20101-6C5CE7?style=flat-square)
![API](https://img.shields.io/badge/libxposed-101.0.1-00B894?style=flat-square)
![Language](https://img.shields.io/badge/Language-Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Target](https://img.shields.io/badge/%E9%86%92%E5%9B%BE-15.3.0%20(153080)-FF4757?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

**本地 VIP 身份 · 解除会员素材导出限制 · 去除订阅拦截弹窗**

</div>

---

## 目录

- [功能](#功能)
- [兼容性](#兼容性)
- [安装](#安装)
- [构建](#构建)
- [工作原理](#工作原理)
- [Hook 清单](#hook-清单)
- [日志与排查](#日志与排查)
- [项目结构](#项目结构)
- [常见问题](#常见问题)
- [免责声明](#免责声明)

---

## 功能

| 功能 | 说明 |
|---|---|
| 🔓 **本地 VIP 身份** | 将当前账号在本地标记为 SVIP（永久），App 全流程认为你是会员 |
| 🖼️ **解除素材导出限制** | 会员滤镜 / 贴纸 / 文字模板 / AI 功能导出时不再被拦，直接落盘 |
| 🚫 **去除订阅拦截弹窗** | 导出页「开通会员」弹窗、画质增强的订阅跳转全部掐断 |
| 💧 **去水印链路放行** | 去水印业务逻辑在会员身份下自然走通 |
| 📋 **可观测** | 每条 Hook 命中都会打日志，`ok=N fail=M` 一目了然 |

> 所有改动仅发生在**目标进程的 Java 内存**中，不修改 APK 文件、不篡改安装包签名、不触碰 native 层。

## 兼容性

| 项目 | 要求 |
|---|---|
| 醒图版本 | **15.3.0**（versionCode `153080`） |
| 包名 | `com.xt.retouch` |
| Android | 8.0+（minSdk 26，由 libxposed API 101 决定） |
| 框架 | LSPosed 支持 libxposed API 101 的版本（1.9.2+） |
| Root | 需要（LSPosed 前置条件） |

> ⚠️ Hook 目标包含 R8 压名后的短名（如 `la8.c`、`SubscribeApiImpl#c()`），**换版本后必然失效**，需要重新对位。详见 [docs/ANALYSIS.md](docs/ANALYSIS.md)。

## 安装

### 方式一：直接安装预编译包

```bash
adb install -r XingTuVip-1.0.apk
```

1. 打开 **LSPosed 管理器** → **模块** → 启用「醒图 本地VIP」
2. **作用域** 勾选 **醒图**（`com.xt.retouch`）
3. 强制停止醒图，重新打开

### 方式二：自己构建

见下节。

## 构建

### 方式 A：手工构建（无需 Gradle，已验证）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

产出：`build/XingTuVip-1.0.apk`

脚本流水线：

```
javac  →  d8  →  aapt2 compile/link  →  组装(+classes.dex +META-INF/xposed)  →  zipalign  →  apksigner
```

脚本会**自动探测**构建环境，无需改代码：

| 依赖 | 探测来源 |
|---|---|
| **JDK 17+** | `JAVA_HOME` → Android Studio 自带 JBR → 常见安装路径（低于 17 的会被跳过） |
| **Android SDK** | `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `local.properties` 的 `sdk.dir` → 常见路径 |
| **build-tools** | 自动选已安装的最高版本（需含 `aapt2` + `lib/d8.jar`） |
| **android.jar** | 自动选最高的 `platforms/android-*` |
| **libxposed API 101** | 优先用 `lib/api-101.0.1.aar` 缓存，缺失时从 Maven Central 自动下载 |

**产物**：

| 路径 | 说明 |
|---|---|
| `build/XingTuVip-1.0.apk` | 已签名，可直接安装 |
| `signing/module.keystore` | 首次构建自动生成（口令 `android`），已被 `.gitignore` 排除 |

> 脚本直接调用 `java -cp lib/d8.jar com.android.tools.r8.D8` 与 `java -jar lib/apksigner.jar`，绕开会读取系统 `JAVA_HOME` 的 `.bat` 包装器。
> 用 JDK 21+ 构建时 apksigner 会打印 `restricted method ... native access` 警告，属正常现象，不影响签名结果。

### 方式 B：Gradle

```bash
./gradlew :app:assembleRelease
```

需要能解析 `io.github.libxposed:api:101.0.1`（Maven Central）。

> Gradle 与 AGP 版本需匹配：AGP 8.7.x 配 Gradle 8.9~8.11；若用 Gradle 9.x 请把 AGP 升到 8.11+。

## 工作原理

醒图的会员体系来自与**剪映同源的商务 SDK** `com.lemon.lv.clipmonetize`，判定链路如下：

```
┌─────────────────────────────────────────────────────────────┐
│  服务端 / 本地缓存                                            │
│      └── UserDetailInfo { flag, curVipLevel, endTime, ... }  │
│              └── isVipUser()  ← 只读字段，无签名无时间校验      │
└───────────────────────────┬─────────────────────────────────┘
                            │
                 ┌──────────▼───────────┐
                 │  SubscribeApiImpl     │   c() / w() / V()
                 │  SubscribeSdkHelper   │   J() / L() / N() / P()
                 └──────────┬───────────┘
                            │
        ┌───────────────────▼────────────────────┐
        │  导出前的素材权益判定（真正的拦截点）      │
        │  la8.c = BenefitResourceExtKt           │
        │    m() isVipBenefit    → 素材是 VIP 权益 │
        │    k() isSVipBenefit   → 素材是 SVIP 专属│
        │    j() isLimitTime     → 限时免费        │
        └───────────────────┬────────────────────┘
                            │
                   ┌────────▼────────┐
                   │ 导出放行 / 跳订阅 │
                   └─────────────────┘
```

导出流程的原始逻辑（反编译自 `ExportImageEnhanceViewModel`）：

```java
BenefitResource r = vm.eO();
if (vm.oO().w() || (vm.oO().V() && !la8.c.k(r))) {   // SVIP，或 可跳过且素材非 SVIP 专属
    vm.bO(vm.j, callback);                            // → 直接导出
    return;
}
if (la8.c.j(r)) {                                     // 限时免费
    vm.vO(activity, callback);                        // → 直接导出
    return;
}
vm.rO(...);                                           // → 否则 "go subscribe page"
```

**模块的解法**：让 VIP 身份恒为真（`isVipUser()` → `true`），同时把素材权益判定改判（`m`/`k` → `false`，`j` → `true`），第一条分支直接成立，普通用户也不再被拦。

完整的逆向分析过程、证据与代码引用见 **[docs/ANALYSIS.md](docs/ANALYSIS.md)**。

## Hook 清单

共 **21 组**，全部按「类名 + 方法名」反射定位，逐条独立 `try/catch` —— 单点失败只记日志，不影响其余 Hook，也不会让目标进程崩溃。

<details>
<summary><b>A. VIP 身份 · <code>com.lemon.lv.clipmonetize.data.UserDetailInfo</code></b></summary>

<br>

| 方法 | 改为 |
|---|---|
| `isVipUser()` `isVipWithRewardAd()` `isGiftVip()` `isAutoSubscribeVip()` `isUnAutoSubscribeVip()` | `true` |
| `isAdReward()` `isDidVipUser()` `isGuest()` | `false` |
| `getCurVipLevel()` `getExpiredVipLevel()` | `"svip"` |
| `getEndTime()` `getVipRealEnd()` `getGroupVipEndTime()` `getBenefitEndAt()` | `4102444800`（2100-01-01） |
| `getSubscribeType()` `getVipSource()` | `"auto"` / `"uid"` |

</details>

<details>
<summary><b>B. 判定汇聚层 · <code>com.xt.retouch.subscribe.impl.SubscribeApiImpl</code></b></summary>

<br>

| 方法 | 改为 |
|---|---|
| `c()` `w()` `V()` `m()` `I()` `Y()` `i0()` `k0()` `r()` `j()` | `true` |
| `W(Continuation)` `M(Continuation)` `A(Continuation)`（suspend） | `true` |
| `getUserType()` | `"vip"` |
| `showLimitFreeToast(...)` | 空实现 |

</details>

<details>
<summary><b>C. 旧同步入口 · <code>com.xt.retouch.subscribe.impl.SubscribeSdkHelper</code></b></summary>

<br>

| 方法 | 改为 |
|---|---|
| `J()` `L()` `N()` `O()` `I()` `H()` `F()` | `true` |
| `P(Continuation)` `K(Continuation)` | `true` |
| `n()` `v()` | `"svip"` / `"vip"` |

</details>

<details open>
<summary><b>D. 素材权益判定 · <code>la8.c</code> = <code>BenefitResourceExtKt</code> ★ 核心</b></summary>

<br>

| 短名 | 原始名 | 改为 |
|---|---|---|
| `m` | `isVipBenefit` | `false` |
| `k` | `isSVipBenefit` | `false` |
| `j` | `isLimitTime` | `true` |
| `i` | `hasEffectivePayStrategy` | `true` |

这一组是「**用了 VIP 功能就不给保存**」的正面解法。

</details>

<details>
<summary><b>E. 导出页弹窗</b></summary>

<br>

| 类 | 方法 | 改为 |
|---|---|---|
| `ExportComponent2` / `ExportComponent3` | `tryShowExportSubscribePopup(...)` | 空实现 |
| `newframe.NewExportFrameComponent` | 同上 | 空实现 |
| `enhance.ExportImageEnhanceViewModel` | `rO(...)` | 空实现（不跳订阅页） |

</details>

## 日志与排查

```bash
adb logcat -s XingTuVip
```

正常启动预期输出：

```
module loaded in process: com.xt.retouch (systemServer=false)
package ready: com.xt.retouch cl=dalvik.system.PathClassLoader[DexPathList[...]]
class ok: com.lemon.lv.clipmonetize.data.UserDetailInfo
hook ok: UserDetailInfo#isVipUser() = true
hook ok: UserDetailInfo#getCurVipLevel() = svip
class ok: com.xt.retouch.subscribe.impl.SubscribeApiImpl
hook ok: SubscribeApiImpl#w() = true
class ok: la8.c
hook ok: c#m = false
hook ok: c#k = false
hook ok: c#j = true
install done: ok=21 fail=0 in 87ms
```

| 现象 | 含义 | 处理 |
|---|---|---|
| `ok=N fail=0` | 全部生效 | — |
| `fail > 0` | 个别方法没找到或已被热修补丁替换 | 看对应 `hook fail:` 日志定位 |
| `class miss: xxx` | 类名对不上（通常是 App 更新换了压名） | 按 [docs/ANALYSIS.md](docs/ANALYSIS.md) 的流程重新对位 |
| `hook ok` 有但功能没变 | 命中了别的分支 | 抓 `hit` 日志看实际调用路径 |

## 项目结构

```
XingtuVip/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xt/vip/
│       │   ├── XingTuVipModule.java     # libxposed API 101 入口
│       │   ├── HookInstaller.java       # 21 组 Hook 实现
│       │   └── XLog.java                # 双通道日志（LSPosed + logcat）
│       ├── res/values/strings.xml
│       └── resources/META-INF/xposed/
│           ├── java_init.list           # 入口类声明
│           ├── module.prop              # minApiVersion / targetApiVersion
│           └── scope.list               # com.xt.retouch
├── docs/
│   └── ANALYSIS.md                      # 完整逆向分析报告
├── build.ps1                            # 手工构建脚本
├── build.gradle / settings.gradle
└── README.md
```

## 常见问题

<details>
<summary><b>Q: 装完模块，LSPosed 里看不到它？</b></summary>

<br>

libxposed API 101 的模块识别依赖 `META-INF/xposed/module.prop`，LSPosed 版本太旧会读不到。升级到支持 API 101 的版本（1.9.2+）。

</details>

<details>
<summary><b>Q: 模块启用了，但醒图里还是显示非会员？</b></summary>

<br>

先看 `adb logcat -s XingTuVip`。若完全没日志，说明模块没注入（作用域没勾选、或进程未被 LSPosed 覆盖）；若有 `class miss:`，说明你装的不是 15.3.0（153080）。

</details>

<details>
<summary><b>Q: 会员素材能导出，但显示"保存失败"？</b></summary>

<br>

那属于存储权限或导出器自身问题，与本模块无关。Android 11+ 需要 `MANAGE_EXTERNAL_STORAGE` 或相册写入授权。

</details>

<details>
<summary><b>Q: 会不会被服务端检测？</b></summary>

<br>

模块只改本地 Java 层的会员判定。服务端的权益校验接口仍会返回「未购买」，因此**云端用量统计、埋点**会与本地状态不一致；但已保存到相册的图片不会被回滚。native 层的签名校验（`libmetasec_ml.so`）未被触碰，醒图当前不依赖它判会员。

</details>

<details>
<summary><b>Q: 支持其他版本吗？</b></summary>

<br>

不支持。`la8.c` 的短名与 `SubscribeApiImpl` 的单字母方法名都是 R8 产物，每个版本都会重新分配。换版本必须重新分析。

</details>

## 免责声明

> 本项目仅供**个人学习与安全研究**使用，用于理解 Android 应用的会员校验与权益分发机制。
>
> - 请勿用于商业用途或二次分发
> - 请在 **24 小时内**删除本地产物
> - 使用本模块产生的一切后果由使用者自行承担
> - 若你依赖这些功能，请购买正版会员支持开发者
>
> 本项目与字节跳动 / 醒图（Xingtu）官方**没有任何关联**，未获得任何形式的授权或认可。所有商标、产品名称归其各自所有者。

## 致谢

- [libxposed/api](https://github.com/libxposed/api) —— Modern Xposed API 101
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [jadx](https://github.com/skylot/jadx) —— DEX 反编译
- [Apktool](https://github.com/iBotPeaches/Apktool) —— 资源与 Manifest 解码

---

<div align="center">

**如果这个项目帮你理解了 Android 会员体系的实现方式，点个 ⭐ 吧**

</div>
