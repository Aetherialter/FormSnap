# FormSnap（表录）

**纸质表单，可靠变成可用数据。**

面向批量纸质业务表单的结构化录入、数据质量检查与异常复核工具。
当前实现到 Phase 1：支持任务管理、通过系统文档选择器多选来源图片、移除页面与检查来源访问状态。
只保存来源引用，不复制原图；尚未提供识别或数据质量检查。真实设备来源选择与重启恢复仍待验收。

## 开发环境

- Android Studio，支持 Android Gradle Plugin 9.3 的版本。
- Gradle Wrapper 9.5.0；Gradle 运行 JDK 17 或更高的兼容版本，Java/Kotlin 字节码目标为 17。
- Android SDK Platform 36、Build Tools 36.0.0；最低运行版本 Android 8.0（API 26）。
- 依赖版本固定在 `gradle/libs.versions.toml`，AGP 9 使用内置 Kotlin，不额外应用 Kotlin Android 插件。

首次打开后同步 Gradle。将本机 SDK 路径写入未跟踪的 `local.properties`（例如 `sdk.dir=D\:/Android/Sdk`），
或设置 `ANDROID_HOME`。通过当前终端的 `JAVA_HOME` 或 Android Studio 的 Gradle JDK 选择 JDK；不要将绝对 JDK 路径写入项目配置。

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

macOS / Linux 使用 `./gradlew`。首次运行需要从 Google Maven、Maven Central、Gradle 官方分发站下载依赖；
Robolectric 还需下载其 Android 测试运行时。

在 Android Studio 中选择 `app` 和 API 26+ 的设备后运行，或连接设备后执行：

```powershell
.\gradlew.bat :app:installDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

设备测试应使用开发用设备或模拟器。没有设备时可独立执行 JVM、Room 和 Robolectric UI 测试，
但这些不能替代真实 Android 设备验收。安装后由桌面“表录”图标启动应用。

## 工程边界

单个 `app` 模块，手动构造依赖；没有 DI 框架、联网权限或后端。
任务与来源表落地 Room v2，包含保留旧任务的 v1→v2 迁移；Schema JSON 进入版本控制，禁止破坏性重建。

- `domain/model`：任务、来源页、动态字段与表结构、行、三层单元格值及来源引用；不依赖 Android。
- `domain/repository`：任务与来源的操作、观察契约。
- `data/local`：Room entity、DAO、数据库。
- `data`：任务及来源仓库、事务、来源授权与访问边界。
- `ui`：Compose 导航、页面、ViewModel 与主题。
- `src/test`：领域、Room 文件重开与迁移、授权生命周期、失败/取消路径、Robolectric 应用与选择器回调测试。
- `src/androidTest`：设备启动与导航 Smoke Test。

`com.formsnap.app` 为当前本地应用标识；公开分发前需要确认最终包名与签名归属。
本阶段不提供发布签名或发布配置。卸载或清除应用数据会删除本地任务；当前不提供备份与恢复。

当前设计、产品文档差异与验收状态见 [Phase 1 设计](docs/phase-1-design.md) 和 [Phase 1 验证记录](docs/phase-1-verification.md)。
初始设计保留在 [Phase 0 基础架构说明](docs/architecture.md)，其中的阶段现状描述属于历史记录。
实际验证结果、本机 Java 回环问题的命令级绕行方式及待验收项见 [Phase 0 验证记录](docs/phase-0-verification.md)。
本文件是开发入口，不替代产品用户手册。
