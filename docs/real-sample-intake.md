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
