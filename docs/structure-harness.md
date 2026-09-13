# Autonomous Structure Harness

该 harness 是 PC JVM 测试，不依赖 Android UI、手机或网络，也不读取 Real Sample #1。它为每次运行创建 48 个确定性匿名表格：`TRAIN` 24 个、`VALIDATION` 12 个、`HOLDOUT` 12 个。每个 case 同时在内存中保留表格边界、行列数和噪声条件，评分器在检测完成后才读取 ground truth。

生成器覆盖 4–37 列、5–34 行、不同列宽、灰底亮度变化、屏幕纹理近似、局部断线和界面边缘干扰。HOLDOUT 使用不同 seed 区间，只用于最终指标报告；任何策略调整都必须重新生成 HOLDOUT，测试不会用 HOLDOUT 结果选择参数。

测试输出：

- `app/build/reports/structure/dataset-manifest.tsv`：split、case id、seed；
- `app/build/reports/structure/dataset-splits.tsv`：检测状态、行列边界召回、预期/实际结构状态及已知干扰线数量。

`StructureDatasetHarnessTest` 当前是基线评分闭环：它验证分片不重叠、HOLDOUT 不发生灾难性行列丢失，并留下可比较的边界指标。它不会通过删除候选线来降低 crossing，也不会改变 Validation 安全门禁。真实图片只作为本地 TRAIN + regression 观察，不复制进 fixture、测试 APK 或 HOLDOUT。

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*StructureDatasetHarnessTest'
```

现阶段的参数选择仍需围绕报告中的失败 case 演进；后续可在不改变评分器和 HOLDOUT 的前提下增加候选策略搜索。真实照片的泛化结论仍需要新的 Real Validation / Real Holdout 图片。
