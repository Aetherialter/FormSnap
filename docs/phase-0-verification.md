# Phase 0 验证记录

日期：2026-09-08。范围仅为 Android 工程初始化与基础架构。

## 自动验证

最后一次功能代码变更后执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，耗时 1m 6s。运行 JDK 为 Temurin 17.0.20.1，Gradle 9.5.0，SDK 36。
以下结果来自 Gradle 的 JUnit XML 与 Lint 报告，没有跳过测试或建立 Lint baseline。

| 分类 | 数量 | 结果 | 主要证据 |
| --- | ---: | --- | --- |
| 领域模型 | 8 | 全通过 | 三层值互不替代、确认空值语义、字段稳定关联、行归属、来源区域 |
| Room / Repository | 6 | 全通过 | 创建草稿、时间精度、真实文件库关闭重开、同名不同 ID、非法输入、冲突不覆盖、排序 |
| ViewModel | 6 | 全通过 | 空白/超长输入、写入失败与重试、连续提交防重、名称恢复、读取失败区别于空数据 |
| Robolectric 应用流程 | 1 | 通过 | 真实 Activity + Compose Navigation + Room：启动、创建、详情、返回、Activity 重建后重新打开 |
| 设备 Smoke Test | 1 | 已编译，未运行 | 无连接设备或已配置 AVD |

21 项本地自动测试全部通过，0 failures / 0 errors / 0 skipped。
Robolectric 使用 API 35 模拟 Android 环境；没有据此宣称真实设备的 API 26–36 兼容性均已通过。

Lint：0 errors、6 warnings，均为 target/compile SDK 或 Gradle、Compose 编译插件、Coroutines 的更新建议。
当前固定可构建的版本组合，未为消除更新提醒而追逐全部最新版本，也未抑制这些检查。
初次构建的 `libandroidx.graphics.path.so` 无法剥离符号提示不影响 Debug APK 生成。

产物（不进入 Git）：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

已用 SDK 的 aapt2 核对 Debug APK：`com.formsnap.app`，版本 `0.1.0`，最低 API 26，目标 API 36，
启动 Activity 为 `com.formsnap.app.MainActivity`。
本阶段不包含发布签名、上架或 Release 验证。

## 本机 Java 回环连接问题

直接启动 Gradle 时观察到：

```text
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
at sun.nio.ch.UnixDomainSockets.connect0
```

JDK 17 与 Android Studio 的 JBR 25 均可用独立 `Selector.open()` 小程序复现。
更换 IPv4 设置或临时目录的长路径未解决；让 Unix-domain socket 绑定不可用后，JDK 自身回退 TCP 的路径成功。
这是本机运行环境限制，没有修改应用代码、全局配置、防火墙或 JDK 安装。

本轮仅为构建命令临时设置 `JAVA_TOOL_OPTIONS`，将 `jdk.net.unixdomain.tmpdir` 指向一个**不存在的目录**。
此参数不是项目依赖或默认配置。若本机再次遇到完全相同的问题，可在仓库根目录、已选择 JDK 的 PowerShell 中使用：

```powershell
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$taskSocketFallback = Join-Path (Get-Location) '.gradle/diagnostics/no-unix-sockets'
try {
    if (Test-Path -LiteralPath $taskSocketFallback) {
        throw '请选用另一个不存在的临时路径；不要删除已有目录。'
    }
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions " + '-Djdk.net.unixdomain.tmpdir="' + $taskSocketFallback + '"'
    .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
```

没有此环境问题的机器直接使用普通 Wrapper 命令。SDK 本机路径位于忽略的 `local.properties`。

## 尚待真实设备验收

本机 `adb devices -l` 无连接设备，`emulator -list-avds` 无结果，没有安装系统镜像或创建模拟器。
下列检查尚未执行，不能将文件数据库重开或 Activity 重建等同于它们：

1. 在 API 26+ 设备安装、冷启动，确认首页排版、系统栏、软键盘和系统返回键正常。
2. 输入中文名称创建任务，回到首页后再次进入详情；空白/超长名称不能提交。
3. 从系统强行停止应用后重新进入，核对任务名称、草稿状态和列表仍存在。
4. 旋转、深浅色、大字体和窄屏下，名称、创建按钮与详情信息均可读可操作。
5. 详情明确显示空来源、空记录与“尚未检查”，没有伪造数据检查结果或未实现功能按钮。

清除应用数据或卸载会删除任务；本阶段没有备份恢复能力。
