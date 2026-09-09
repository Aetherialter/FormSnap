# FormSnap（表录）

**纸质表单，可靠变成可用数据。**

面向批量纸质业务表单的结构化录入、数据质量检查与异常复核工具。
当前已实现 V1 应用闭环：任务 → 多图片导入 → 后台整理 → 字段配置 → 异常复核与原纸定位 → 完整数据 → XLSX。
本机自动验证通过；真实设备安装仍被系统权限拒绝，因此真实拍摄材料、手写效果及设备恢复尚未验收。
只保存来源引用，不复制原图；模型随 APK 打包，应用不申请联网权限。

## 当前使用路径与限制

1. 新建任务，通过系统“文件”多选现有图片，查看来源页并保留原文件。
2. 点击“开始整理 / 继续未完成页面”。当前仅支持边框清晰、横平竖直、无合并单元格的规整表格；复杂或倾斜表格会提示检查。
3. 核对“字段设置”，选择类型、必填、格式、范围和组合重复键后保存。识别表头不会自动代表业务规则已经正确配置。
4. 在“开始检查”中按异常查看原纸局部，确认、修改、保持或标为无法确认；疑似重复由用户决定保留或排除。
5. 查看完整数据、搜索或修改。重要问题、未处理来源和无法确认项均阻止标准导出。
6. 所有重要问题处理后，通过系统保存窗口生成 XLSX，包含“最终数据”和“复核记录”。保存期间数据变化会要求重新导出。

相机、透视矫正、合并单元格、模糊重复匹配和规则模板库不在当前实现中。
手写内容仅使用现有识别模型提供候选，低可靠或难辨值需人工检查；未建立真实手写准确率承诺。

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
Room v5 保存任务、来源、动态结构、页处理结果、问题和人工决定；包含 v1→v2→v3→v4→v5 完整迁移。
Schema JSON 进入版本控制，冻结旧 Schema 的升级测试验证数据保留，禁止破坏性重建。

- `domain/model`：任务、来源页、动态字段与表结构、行、三层单元格值及来源引用；不依赖 Android。
- `domain/repository`：任务、来源、结构化数据与质量操作/观察契约。
- `data/local`：Room entity、DAO、数据库。
- `data`：Room 仓库、事务、质量状态协调、来源授权与图像访问边界。
- `validation`：纯领域的类型、格式、范围、必填、可靠性、列模式、重复与页面结构检查。
- `review`：多问题聚合、人工决定与过期数据保护。
- `processing`：网格恢复、ML Kit 中文识别适配器、候选映射、页级持久处理与 WorkManager 调度。
- `export`：最终值快照检查、标准 OOXML 写入和 Android 文件保存。
- `ui`：Compose 导航、页面、ViewModel 与主题。
- `src/test`：领域、Room 重开/迁移、授权/取消、原生图片解码与局部定位、处理恢复、XLSX 和 Robolectric 完整导航流程。
- `src/androidTest`：设备启动与导航 Smoke Test。

`com.formsnap.app` 为当前本地应用标识；公开分发前需要确认最终包名与签名归属。
当前交付 Debug APK，不提供正式发布签名。卸载或清除应用数据会删除本地任务；当前不提供应用数据备份与恢复。

当前设计与验证见 [V1 实施记录](docs/v1-implementation-plan.md)、[V1 验证记录](docs/v1-verification.md) 和 [产品文档差异报告](docs/documentation-gap-report.md)。
运行 `:app:testDebugUnitTest --tests '*V1QualityBenchmarkTest'` 可单独生成 `app/build/reports/quality/` 下的合成候选、正确答案、XLSX 和质量指标 JSON。
这些指标验证规则、复核与导出闭环，不能代替真实照片/手写识别的漏检、误报和人工复核率。
Phase 1 历史见 [设计](docs/phase-1-design.md) 和 [验证记录](docs/phase-1-verification.md)。
初始设计保留在 [Phase 0 基础架构说明](docs/architecture.md)，其中的阶段现状描述属于历史记录。
实际验证结果、本机 Java 回环问题的命令级绕行方式及待验收项见 [Phase 0 验证记录](docs/phase-0-verification.md)。
本文件是开发入口，不替代产品用户手册。
