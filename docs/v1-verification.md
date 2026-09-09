# FormSnap V1 验证记录

日期：2026-09-09。代码闭环和本机自动验证完成；外部真机验收仍受安装权限阻塞。
这是开发验证记录，不替代主对话维护的产品用户手册和操作指南。

## 工程与流程

单 app module，Kotlin / Compose Material 3 / Navigation / Coroutines Flow / Room v5；通过应用级手动构造依赖。
依赖从内到外为：domain 模型与契约 → validation/review 规则及决定 → data 事务与状态 → processing/export 平台适配 → ui。
识别、复核和导出共享同一份动态结构与来源关系，没有第二套平行数据模型。

已连接首页、新建任务、多选文件、来源管理、后台整理、字段配置、聚合复核、原纸局部/整行/整页、重复决策、完整数据搜索编辑和系统保存 XLSX。
候选起于 UNVALIDATED；Validation 才能设 AUTO_CONFIRMED；人工操作设 MANUAL_CONFIRMED 或 UNREADABLE，原始值独立保留。
页处理完成不代表可导出：缺失来源、未完成页、未确认规则配置、重要问题与无法确认均阻止标准导出。
数据修改使已导出状态失效；写文件时发生数据变化不会将新数据标成已经导出。

Room v1→v2 来源；v2→v3 动态表结构与三层值；v3→v4 页结果、问题和人工记录；v4→v5 字段配置确认位。
迁移保留旧任务、来源、raw/normalized/confirmed 与历史；未使用 destructive migration。

## 最终自动验证

执行命令（Windows，2026-09-09）：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Projects/FormSnap/.gradle/diagnostics/no-unix-sockets'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

`BUILD SUCCESSFUL`，114 项测试，0 failures / 0 errors / 0 skipped。
JBR 25 与用户已有的未跟踪 JVM criteria 文件匹配。回环套接字绕行参数只在当前命令进程设置，未修改全局环境或提交机器路径。

| 分类（互不重复） | 数量 | 主要证据 |
| --- | ---: | --- |
| 领域模型、Validation 与 Review 聚合 | 20 | StructuredModelsTest、ValidationEngineTest |
| Room 任务/来源/动态结构/质量事务与冻结版本迁移 | 47 | Room*RepositoryTest、*MigrationTest |
| Android URI 访问与原生图像/EXIF/原纸局部 | 8 | AndroidSourceAccessTest、SourceImageLoaderTest |
| 批处理、失败、取消和中断重试 | 6 | ProcessingRunnerTest；隔离真实 ML Kit |
| 像素网格和文字框归属 | 6 | GridRecognitionTest；合成像素 |
| XLSX 标准写入、输出故障、来源失效与保存期间变化 | 8 | XlsxWriterTest、AndroidXlsxExporterTest |
| ViewModel 失败、重试、取消和选择器恢复 | 15 | Task/Source/WorkflowViewModelTest |
| 完整应用导航与选择器回调 | 3 | App/Source/WorkflowFlowTest；Robolectric |
| 800 Cell 基准、模拟复核、数据库重开与独立 XML 值检查 | 1 | V1QualityBenchmarkTest |
| **总计** | **114** | 22 个测试类 |

至少两种不同表结构在 RoomStructuredRepositoryTest 中使用同一核心持久化，资产场景还经过完整 Compose 应用复核/保存流程。
UI 测试覆盖保存窗口期间 Activity 重建；不使用假数据充当真实 Recognition，也不将 Robolectric 当作真实 SAF 权限执行。
设备 LaunchSmokeTest 仅构建成 APK，本轮未运行成功，不计入上述 114 项。

Lint：0 errors / 18 warnings。主要是依赖/SDK 版本更新建议，另有 3 条 Uri KTX 风格建议和 2 个未用字符串资源。
编译还提示旧版 Compose 手势回调重载弃用；JBR 25 有 native-access 提示。未为了告警扩大依赖升级或抑制检查。
Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，58,591,456 bytes（约 55.9 MiB，包含多 ABI 与随包中文模型）。
测试 APK 构建通过；未提供正式分发签名。最终合并 APK 无 INTERNET 权限；WorkManager 带入唤醒/开机恢复等平台权限。

## 合成候选基准结果

独立运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*V1QualityBenchmarkTest'
```

结果位于 `app/build/reports/quality/`：`v1-quality.json`、`expected.tsv`、`candidates.tsv`、`v1-benchmark.xlsx`。
这些均是可再生测试产物，不提交到 Git。可读的真值与异常植入位置保存在测试源码。

| 指标 | 结果 |
| --- | ---: |
| totalCells | 800 |
| realIssues（预置异常目标） | 19 |
| detectedIssues（检出的目标） | 19 |
| detectedRealIssues | 19 |
| missedIssues | 0 |
| falsePositives | 0 |
| reviewItems | 19 |
| reviewRate | 2.375% |
| Issue Recall | 100% |
| 底层 Issue 条数 | 23 |
| finalErrors（模拟复核后与真值逐 Cell 比较） | 0 |

100 行 × 8 字段、4 页同结构及额外 1 页异结构。16 个 Cell 目标包括 3 个 ID、3 个 NUMBER、2 个 RANGE、3 个缺失、3 个难辨/低可靠、2 个列模式异常；另有 2 个重复组和 1 个 Schema mismatch。
多个问题聚合，重复由模拟用户明确保留为不同业务记录；异结构来源由模拟用户移除。复核中途关闭重开数据库，最终 800 个值均与独立答案一致，XML 逐行逐列检查也通过。

**这些数字只描述有意植入异常的合成候选回归集。** 识别接口在本测试中使用 fixture，原纸复核由预置答案模拟；实际照片/手写的 recall、误报、最终错误率和人工用时尚无可信数字。
reviewRate 的分子包含 Cell/重复组/来源目标，不代表等价操作成本，也不包括首次规则设置；不得作为真实用户效率宣传。

## 真机外部阻塞

Goal 开始时无设备；最终检查发现 V2055A / Android API 34 已连接。
执行了一次授权范围内的非清除安装：

```powershell
& 'D:/Android/Sdk/platform-tools/adb.exe' install -r app/build/outputs/apk/debug/app-debug.apk
```

返回 `Failure [INSTALL_FAILED_ABORTED: User rejected permissions]`。
未绕过系统确认、未反复安装、未清除设备应用数据或修改安全设置。连接设备不能算安装或运行已通过。
因此未执行成功：真实 App 启动、系统多图选择、强停恢复、设备重启后的 URI 权限、实际 ML Kit、真机 Review/Provenance、实际 XLSX 保存和 Excel/WPS 打开。
后续应在设备端允许开发安装后，先验证规整印刷表，再验证经过授权的真实首个场景和第二种通用场景；将每项结果追加为新记录。

## 已知产品限制

- 当前识别要求横平竖直、完整边框、无合并单元格，表格占图像主要区域；不承诺任意业务纸张布局或潦草手写。超出支持范围会拒绝或提示复核。
- 可靠性阈值尚未用真实纸张标注集校准，不能保证不存在高置信度错字或浅色墨迹遗漏。
- 初始类型为文本，用户必须核对字段规则；没有自动场景推断、规则模板库或相似重复模型。
- 文件导入和原 URI 保存已实现；相机、缩略图列表、页序拖动、排除记录恢复专页和备份工具未实现。
- 完整数据为记录列表与少量编辑；没有完整 Excel 网格。日期格式为 ISO，规则格式为安全子集。
- XLSX 标准结构和值已验证，但 Excel/WPS 兼容性仍需实际打开核对。
- 真机性能、内存峰值和真实处理时间未量测；有有界图片、串行图片工作、批量事务和 LazyColumn，不能由此宣称实机已经流畅验收。

## Git 与文档

旧基线 `751ced8`、`6cb01b7`、`17db808` 保留；各阶段新增独立提交，没有改写历史或推送。
本 Goal 开始时已有 `gradle/gradle-daemon-jvm.properties`，由用户管理，未提交、删除、忽略或修改。最终不能把该未跟踪状态称为完全干净。
三份外部产品文档 hash 复核一致，具体差异见 [Documentation Gap Report](documentation-gap-report.md)。
