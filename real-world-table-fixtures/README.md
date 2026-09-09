# 等价纸质表格验收样本

此目录只有完全虚构、人工生成的业务表格，不包含真实用户、学生、机构数据。PNG 是中文字体实际渲染后的图像，包含浅灰边线、背景条纹、顶部元信息和大量空格；不是实拍照片，也不是模型识别结果。

| 样本 | 行数（含表头） | 列数 | 表头深度 | 合并区域 | 预期 |
|---|---:|---:|---:|---:|---|
| printed-regular | 7 | 6 | 1 | 0 | AUTO_ACCEPTED |
| printed-36-column-header | 10 | 36 | 3 | 18（第一层6、第二层12） | STRUCTURE_REVIEW_REQUIRED |
| printed-gaps-variable-width | 8 | 6 | 1 | 0，含一处局部断线 | STRUCTURE_REVIEW_REQUIRED |

每张图片旁的 JSON 记录原图大小、表格像素区域、行列数、表头行数、零基合并区域、预期状态和独立文字框标注。文字框含原始行列归属，测试逐项核对，不能拿它们代替真实 OCR 输出。

`PrintedStructureTest` 使用 Android 原生 BitmapFactory（Robolectric NATIVE）读取 PNG，分别按 2400×1500 和 1200×750 解码，经实际 GrayImage/GridDetector/Assembler 检查边界、行列、全部合并区域、Header深度、文字唯一归属及状态。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PrintedStructureTest' --tests '*ComplexStructureTest'
```

结果在 `app/build/reports/structure/printed-metrics.tsv` 和 `metrics.tsv`。前者6次解码不是6张独立照片，后者是A–K的11个确定性几何/文字框样本。空白负例另在 GridRecognitionTest，不能把正例集合0个SOURCE_UNUSABLE解读为任意图片都可用。

如需重新制作，在 Windows PowerShell 执行 `./real-world-table-fixtures/generate.ps1`（System.Drawing 与 Microsoft YaHei）。生成脚本不是项目运行依赖；日常测试直接读取已提交PNG，不依赖Windows字体，重新生成后应检查图像差异。

## 待设备验收

1. 将这些不含私人信息的PNG放入开发设备，通过系统文件选择器导入同任务。
2. 单独测试每种结构。36列样本应进入结构确认，核对原图覆盖、三级表头与18个合并区域。
3. 修改表头/分割、保存草稿，退出重进；确认后检查字段路径、记录及异常。重复字段名称是有意使用的测试文字，应在字段设置中区分显示名称。
4. 同模板使用第二张缩放/轻透视照片，首张确认后后页应匹配；换另一种列结构应要求核对，不能静默强套。
5. 完成值复核及字段配置后导出，检查最终确认值和来源定位。另用实际拍摄的脱敏材料核验反光、文字挤线和真实OCR误差。

自动测试中的边框、文字框匹配通过不代表这些设备步骤已通过。
