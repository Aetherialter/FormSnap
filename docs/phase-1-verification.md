# Phase 1 验证记录

日期：2026-09-08。范围：来源页面可靠接入。

## Git 与边界

开始时为 main，工作区干净，HEAD 为 `6cb01b7`，前序为 `751ced8`。
保留两个 Phase 0 提交，不 amend，不修改 Git 历史，不改写 Phase 0 验证记录。
本阶段未改动外部《用户手册》《操作指南》《最初测试方案》V0.1，文件 SHA-256 与读取时一致。
对应功能差异见 [Phase 1 设计](phase-1-design.md) 的文档对照表。

## 自动验证

最终功能代码执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

结果为 `BUILD SUCCESSFUL`。使用现有 JDK 17、Gradle 9.5.0、SDK 36 与 Phase 0 记录的命令级 Java socket 绕行参数。
没有安装新库、修改全局配置、跳过测试、削弱断言或建立 Lint baseline。

| 分类 | 数量 | 结果 |
| --- | ---: | --- |
| Phase 0 原有领域 / Task Repository / ViewModel / 应用流程 | 21 | 全部通过 |
| 来源 Room Repository | 14 | 全部通过 |
| 从冻结 v1 Schema 创建文件库并执行 v1→v2 迁移 | 1 | 通过 |
| Android 来源访问适配器（Robolectric 提供者） | 4 | 全部通过 |
| 来源 ViewModel | 6 | 全部通过 |
| 选择器回调与完整来源 UI 流程（Robolectric） | 1 | 通过 |
| 合计 | 47 | 0 failures / 0 errors / 0 skipped |

覆盖证据：

- 来源与任务外键、跨任务隔离、同 URI 跨任务共享授权、最后引用移除后释放。
- 多选追加、重复回调、并发重复添加、删除中间页后的顺序、再追加、状态随来源集合变化。
- 文件数据库关闭重开后，来源 ID、URI、顺序、名称、状态和时间不变。
- 使用 Phase 0 的 `1.json` 创建真实旧库，保留任务全部字段，Room 校验迁移后的结构，并能新增来源。
- 插入失败整批回滚；删除失败保留记录和权限；取消导入不留下部分记录或临时授权。
- 授权回收后不虚报长期可读；已存在页面不会因失权消失；清理失败可在下次操作重试。
- 仅申请读取授权；测试原文件字节不变；非图片/非 content URI、读取失败和超时可处理。
- UI 中取消、重复选择、选择器打开期间 Activity 重建后返回结果、真实来源计数、移除确认及不可用提示。
- 读取失败与“0 页”分开；前台刷新期间的选择器回调不会丢弃。

Robolectric 中的权限与系统选择器结果是测试模拟，未当作真实 Android 系统持久权限证据。
Room 测试使用真实 SQLite 文件，但“数据库重开”不等于“手机进程强停或设备重启”。

报告及 APK 均保留在忽略的构建目录：

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

移除来源数量旧标签这一未使用资源后，又运行对应 APK 构建及 Lint。最终 Lint 为 0 errors、7 warnings：
6 条已有版本更新建议，1 条建议使用 KTX `toUri` 的风格提示。保留直接使用 Android Uri API，未为提示新增依赖。

## 真机验收：仍受阻

本轮只读检查 `adb devices -l`，无连接设备。
Phase 0 的 `INSTALL_FAILED_ABORTED: User rejected permissions` 阻塞继续保留；本轮没有反复重试安装或修改手机安全设置。
设备 Smoke Test APK 可以构建，但没有执行 `connectedDebugAndroidTest`，没有完成真实选择器导入或重启恢复测试。

因此本阶段状态是：**代码与自动验证完成；真实设备来源选择及重启恢复验收未完成，不宣称全部验收通过。**

待用户连接并授权安装后，至少完成下列工程验收（不替代产品测试方案）：

1. 在保留 Phase 0 应用数据的前提下升级安装，不卸载、不清除数据，确认旧任务仍存在。
2. 在一个测试任务中从真实系统文档选择器选择至少两张本机图片，检查顺序、文件名、真实页数与访问状态。
3. 取消选择及重复选择，不产生空页或重复页；同一来源加入第二任务，两任务互不串联。
4. 从系统强行停止 FormSnap 后重新打开，进入同一任务，确认来源与长期读取权限恢复。
5. 如条件允许，再进行设备重启后的读取验证；未执行前不标为通过。
6. 从一个任务移除来源，确认另一任务仍能访问、原图仍在；最后引用移除后不删除原图。
7. 由用户对测试图片撤权或移除原文件，再次进入任务，保留页面并显示不可用；恢复原文件后重查或移除后重新添加。
8. 检查大字体、系统返回、旋转及长文件名时，添加/移除入口可操作，无原图缓存副本。

## 已知限制

- 没有相机、整页预览、图像缩略图、拖拽排序、识别、Validation、Review、XLSX 或 WorkManager。
- 来源条目用文件名、页序与访问状态提供摘要，不检查图像清晰度或表结构。
- 相同图片的不同 URI 不作内容去重；旧 URI 失效通过恢复文件或移除重选处理。
- 外部文件提供者和系统授权额度可能影响长期访问，云盘图片不保证离线可读。
- 提供者不响应取消时，底层调用可能稍后结束，界面已超时返回；授权清理在后续来源操作时再次核对。
- 尚未完成的导入不保证在进程被杀后继续；已提交的来源会保存，重新选择通过唯一约束去重。
