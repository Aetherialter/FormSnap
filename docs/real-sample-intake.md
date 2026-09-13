# Real Sample 原始测试数据

2026-09-13 收到的四张屏摄原图作为新的真实原始测试数据，编号为 `REAL_SAMPLE_02` 至 `REAL_SAMPLE_05`。它们保存在本机的 `real-world-table-fixtures/raw-real-samples/`，文件按原始 JPEG 字节复制，目录被 Git 忽略，不进入提交、测试 APK 或 synthetic TRAIN/VALIDATION/HOLDOUT 评分集。

| 样本 | 文件 | 尺寸 | 字节数 | SHA-256 | 当前角色 | synthetic split |
|---|---|---:|---:|---|---|---|
| `REAL_SAMPLE_02` | `real-sample-02.jpg` | 1279×2275 | 414511 | `BE6E1DCB83CAD6E202B8B1821B3CF749A159FD24E2008F617F4235350E94F94C` | `REAL_RAW` | `UNASSIGNED` |
| `REAL_SAMPLE_03` | `real-sample-03.jpg` | 1279×2275 | 422697 | `A629896A2F6DA8F9447D48D4C72D6F3BB168EAD2289F4CD648617BD9675A60A0` | `REAL_RAW` | `UNASSIGNED` |
| `REAL_SAMPLE_04` | `real-sample-04.jpg` | 1279×2275 | 378838 | `AC44DC6120B5353275DCFE1A8C9C8043D3DB4CC4BF6B0E900E9D260615A1DB72` | `REAL_RAW` | `UNASSIGNED` |
| `REAL_SAMPLE_05` | `real-sample-05.jpg` | 1279×2275 | 325031 | `7BF436F17B28ECD075C7B0A989480976066568F37740B99B141BBBAC0048AFD0` | `REAL_RAW` | `UNASSIGNED` |

这些样本用于真实结构恢复、OCR 运行和回归诊断。当前只登记为 `REAL_RAW`，不把它们强行分配到 synthetic split，也不据此宣称泛化能力。后续发现的问题必须抽象为通用规则或匿名 synthetic regression case，不允许按图片坐标、尺寸、文件名或具体文字特判。

## 当前结构诊断

使用生产链路相同的 1/2 图像采样，在本地 JVM 的 `GrayImage/GridDetector` 上运行；本次只检查表格结构，不运行 ML Kit OCR，也不写入任务数据库。

| 样本 | 检测状态 | 候选行数 | 候选列数 | 诊断含义 |
|---|---|---:|---:|---|
| `REAL_SAMPLE_02` | `STRUCTURE_REVIEW_REQUIRED` | 31 | 7 | 透视、背景干扰、局部断线和合并区域均需人工核对 |
| `REAL_SAMPLE_03` | `STRUCTURE_REVIEW_REQUIRED` | 29 | 5 | 透视、背景干扰和局部断线需人工核对，并发现合并候选 |
| `REAL_SAMPLE_04` | `STRUCTURE_REVIEW_REQUIRED` | 23 | 4 | 透视、背景干扰和局部断线需人工核对 |
| `REAL_SAMPLE_05` | `STRUCTURE_REVIEW_REQUIRED` | 37 | 5 | 透视、背景干扰、局部断线和合并区域均需人工核对 |

这些行列数是检测候选，不是独立标注后的正确答案。四个样本均未进入 `AUTO_ACCEPTED`，也没有把结构不确定性静默放行。

此前的 `Real Sample #1` 规则保持不变：它是既有的 TRAIN + regression 样本；本批四张图是新增原始样本，尚未指定 Validation 或 Holdout 身份。

## 本轮真实样本回归

四张原图分别有独立的 `raw-annotations/real-sample-02.json` 至 `real-sample-05.json`。它们都保持 `annotationStatus: PROPOSED`，只表达基于视觉的候选边界、叶子列数、表头深度和可见行范围；当前没有 `VERIFIED` Ground Truth，因此不参与真实 precision、recall 或 cell accuracy 计算。

`RealSampleRegressionTest` 使用当前 baseline policy `32,35,205`，从本地原图目录读取 JPEG，在本地 JVM 运行结构检测，输出：

- `app/build/reports/structure/real-sample-regression.tsv`：预测行列、上一 Best Policy 的变化、候选线诊断、处理时间、timeout、同输入重复稳定性；
- `real-world-table-fixtures/debug-overlays/`：表格边界、行列边界、合并候选和表头区域的本地 PNG Overlay。该目录被 Git 忽略。

`falseLineCount` 和 `crossBoundaryBoxes` 在没有独立真值或 OCR box 输入时写为 `NA`，不能当作 0。候选线总量达到 800 以上时报告为 `CANDIDATE_EXPLOSION_REVIEW`，这是候选规模的安全代理，不等于已确认的伪线数量。真实样本回归只检查安全退化、候选线规模、超时和结构重复稳定性。

当前一次回归记录为：02=`36×8`、03=`34×7`、04=`32×7`、05=`50×4`，四张均为 `STRUCTURE_REVIEW_REQUIRED`、无 timeout、重复输入结果稳定；四张的候选线规模均触发 `CANDIDATE_EXPLOSION_REVIEW` 代理，需要后续继续降低屏摄背景候选线。相对于登记时的上一组 prediction，行列数变化只作算法变化记录，不能解释为准确率变化。

运行：

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Projects/FormSnap/.gradle/diagnostics/no-unix-sockets'
./gradlew.bat :app:testDebugUnitTest --tests '*RealSampleRegressionTest' --no-daemon --console=plain
```

## 本轮匿名 synthetic benchmark

`StructureRobustnessBenchmarkTest` 独立生成窄列、混合列宽、低对比度竖线、屏摄噪声、二至四级合并表头以及左右裁切/部分裁切表格。HOLDOUT 仍由 `StructureDatasetHarnessTest` 单独维护，不读取真实图片。

输出 `app/build/reports/structure/robustness-benchmark.tsv`，其中稳定性变体覆盖亮度、对比度、JPEG 质量、轻微旋转和轻微裁切，并记录 `structureStability`、`columnCountVariance`、`rowCountVariance`。本轮匿名样本在 20、24、32、40 列范围均保持列行误差不超过 1，稳定性为 `1.000`。

裁切 benchmark 保持 `STRUCTURE_REVIEW_REQUIRED` 且没有无条件补出额外列，但部分裁切案例当前只留下透视/复核提示，报告为 `NO_EXPLICIT_SIGNAL`。这说明“需要复核”已经安全生效，但明确区分裁切边界与完整可见列漏检仍是后续算法工作。
