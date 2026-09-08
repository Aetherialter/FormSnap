# Phase 0 基础架构

## 产品约束

FormSnap 的核心是结构化数据正确性、质量检查、异常复核与来源追溯。
识别只产生候选数据，导出交付确认数据。关键错误漏检优先于降低人工复核率。
核心模型不认识具体学校、考试、业务审批或固定模板。

## 已实现的依赖方向

```text
MainActivity / FormSnapApplication（依赖装配）
  ├─ Compose Navigation → TaskViewModel → TaskRepository
  └─ RoomTaskRepository → TaskDao → FormSnapDatabase
                  └─ domain/model
```

`domain` 不引用 UI、Room、Android URI。Repository 用 suspend 写入、Flow 观察；写入成功后才返回任务。
应用级数据库实例使用 applicationContext，避免 Activity 泄漏。UI 按生命周期收集 Flow，
ViewModel 使用 viewModelScope 管理异步工作，取消异常继续传播。存储错误独立于空列表，提供重试。
任务名去除首尾空白，要求 1–120 个 UTF-16 code unit，允许同名任务；UUID 区分身份。
时间采用 UTC Instant，以毫秒精度持久化。插入冲突失败，不覆盖已有记录。

## 模型与稳定关联

| 模型 | 当前职责 / 关系 |
| --- | --- |
| DigitizationTask | id、名称、明确状态和创建/更新时间；唯一持久化业务实体 |
| SourceDocument | taskId、来源 URI 文本、从 0 开始的页序、可用状态；仅模型 |
| TableSchema | taskId 与有序动态字段，字段 ID 唯一，显示名可重复 |
| FieldDefinition | 稳定 id、显示名和通用类型，无具体业务字段 |
| TableRow | taskId、schemaId、Cell 集合；检查行归属与一字段一 Cell |
| Cell | 稳定 id、rowId、fieldId、raw / normalized / confirmed，独立复核语义与可选来源 |
| CellSource | 来源页 id、原行列索引、原图归一化区域 |

`confirmedValue = null` 表示没有确认值，`""` 表示明确的空字符串；不会回退使用 raw 或 normalized。
模型是不可变属性的数据类，修改通过 copy 表达，调用者必须保留 raw；当前尚无生产数据编辑接口或修订历史。
已确认的 Cell 必须提供 confirmedValue。模型级检查不等于完整领域校验：
跨 Schema 的 Field 引用、跨 Task 的来源归属、输入集合防御性复制等在结构化数据落地阶段收紧。

Cell 不包含单个 Warning 字段。未来 `ValidationIssue` 通过 cellId 等目标引用形成一对多关系，
Review 再聚合问题并保存决策。本阶段不建立 Issue 表、规则接口、空实现或伪造检查结果。
来源区域相对于原始页面，后续若引入旋转/透视校正，必须明确变换到原图的映射。
URI 字符串本身不等于已持久获得读取权限；来源导入阶段需要真实验证权限、重启恢复与文件不可用状态。

## 当前状态与 UI

任务状态预留 DRAFT / CAPTURING / PROCESSING / REVIEW_REQUIRED / READY_TO_EXPORT / EXPORTED / FAILED。
唯一生产创建路径只写 DRAFT，当前没有状态迁移器、识别完成路径或导出入口。
因此尚未实现“未解决问题阻止导出”的完整业务门禁，不能把预留枚举视为该能力已经完成。

首页 → 输入名称 → Room 插入成功 → 详情 → 返回最近任务。
详情中的来源与记录数量为 0：本版本无法添加来源或记录，所有任务确实为空。
待确认数量显示“尚未检查”，不以 0 假装检查完成；后续落地对应数据后必须改为真实聚合。
未实现的导入等操作不显示可点击的假按钮。
详情通过 observeTask 独立订阅任务，读取中、失败和记录不存在有不同状态，不依赖首页列表刷新。
创建过程中若用户已返回首页，保存完成后不会强行跳转；任务仍由列表流呈现。

## 持久化范围

Room 仅一张 `digitization_tasks` 表。Schema 导出到 `app/schemas`。
不启用 destructive migration，不启用云备份；尚无来源持久化、表结构持久化、审计日志或数据库升级迁移。
关闭再重开真实文件数据库测试覆盖任务内容一致性，但不等于真实设备强杀恢复验收。

## 阶段边界与后续顺序

未引入 WorkManager：当前仅数据库短操作，无应交付后台执行的工作。
没有 processing、validation、review、export 空包；在各阶段有真实实现时再建立逻辑目录。

建议 Phase 1 只完成来源页面的可靠接入与任务归属：

1. 以系统文档选择器加入多张图片，维护明确页序与任务关联；不做相机或识别。
2. 落地 SourceDocument，提供从 Room v1 到 v2 的保留任务迁移。
3. 持久 URI 权限、重启可访问性、撤权/文件丢失提示；不读写原始图片内容。
4. 详情改为真实来源数量和页面列表；任务状态的变化仅覆盖本阶段需要的语义。
5. 自动验证关联、排序、迁移不丢任务、持久化与错误传播；设备验收选择、退出重启及失效来源。

随后再建立动态结构化数据持久化与输入契约、Validation/Review 闭环及来源区域查看。
具体顺序待 Phase 1 结果复核，不在本阶段预先实现。
