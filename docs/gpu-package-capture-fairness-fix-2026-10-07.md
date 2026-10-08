# 捕获预算竞争引起的 section 版本等待

## 本轮日志

`debug.log` 截至 2026-10-07 23:27:28。1024 条单体记录全部为 `SECTION_VERSION`、`worldWait=capture`、`stillFrozen=false`。没有仍冻结的单体或冻结汇总，也没有再次出现上轮的链表缺号异常。

| step | section | requiredRevision | 记录数 |
| --- | --- | --- | --- |
| 1006 | (-1,4,0) | 316 | 160 |
| 1006 | (-1,4,1) | 315 | 128 |
| 1376 | (-1,4,0) | 317 | 376 |
| 1376 | (-1,4,1) | 318 | 360 |

这些是延迟、限流输出的已恢复事件，不代表包裹一直冻结到日志输出时。两侧 section 的旧版本已上传，也不能替代物理输入要求的精确 revision。日志未记录该版本失效的原始通知或各类 CPU 捕获实际用时；本轮修复的是回归中确认的捕获调度缺陷，不能逐条证明原存档发生了哪一种竞争。

## 三类捕获共享时间

旧调度让排在前面的光照或移动几何使用全部剩余时间，随后静态世界捕获可能得到零预算。新增回归使用实际静态缓存和可耗尽预算的前置工作，覆盖 moving/light 的四种优先顺序；修复前失败于静态 section 没有获得捕获时间。

现在先完成移动来源发现，再把光照失效通知合并限制在剩余时间的四分之一内。三类捕获各先获得剩余时间三分之一以内的机会，然后按原优先顺序借用闲置时间。总截止时间与原配置相同；单次不可抢占读取及必须采样的移动姿态仍可能超出软预算，超时后不会开始额外的非零捕获。

移动几何的第二次机会只继续已有 cursor，不重新读取姿态、不增加 pose frame。回归检查同一 tick 只读取一对 previous/current 姿态，空闲 world/light 的时间能够返还移动几何，历史帧仍保存原来的一对姿态。零预算时继续发布已完成静态结果并执行必要姿态采样。

## 活跃 section 优先捕获

旧静态缓存对所有 pending section 按 32 单元轮询。一个真正被包裹使用的 section 和几十个后台需求分摊时间，要等很多轮才能完整捕获。新增回归让一个活跃 section 排在 32 个后台任务之后，修复前在两个总预算调用后仍不能完成。

现在复用已完成 GPU 占用扫描提供的 packageUsage 集合。存在活跃和后台任务时，先给活跃队列最多四分之三时间，再让后台使用剩余时间；后台阻塞或结束后把闲置时间返还活跃队列。各队列内部仍按 32 单元轮询。预算、worker 上限和完整快照发布规则不变。

回归中活跃 section 在两次 4096 时间单位的捕获后完成，后台获得非零进度且仍未全部完成，总时间为 8192。真实 GPU 夹具从异步占用扫描建立保护集合，验证需要新版本的包裹暂停、其他 section 的包裹继续运动；优先捕获和精确版本上传后，暂停包裹恢复，另一个包裹继续推进。

## 新诊断字段

单体 `SECTION_VERSION` 日志现在追加精确版本的 `captureCause`：`INITIAL`、`BLOCK`、`CHUNK_LOAD`、`CHUNK_UNLOAD`、`CHUNK` 或 `EXPLICIT`，以及对应的 `causeBlock`、`causeChunk` 或 `causeSection` 坐标和合并通知数 `notifications`。

`observedTargetRevision`、`observedCapture`、`observedCells` 明确表示**日志输出时**的当前进度，不是事件发生时的进度。来源按事件的 `requiredRevision` 查询，即使该版本已完成或被替换，也不会用新版本原因替代。进度状态包括 `capture`、`worker`、`obsolete-worker`、`complete` 和 `evicted`。

最多保留 4096 条纯数值来源记录；超出保留范围标为 `captureCause=unretained`。不保留世界或方块实体，也不增加 GPU 诊断缓冲或采样频率。回归覆盖历史原因与当前进度分离、坐标和 section 匹配、通知合并、容量上限以及清理。

## 验证

```powershell
.\gradlew.bat test --tests '*PackageCollisionCaptureScheduleTest' --tests '*PackageCollisionCacheTest' --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

参考失败日志：`build/capture-fairness-before.log`。修复后针对性日志：`build/capture-fairness-targeted.log`、`build/capture-priority-gpu.log`（110 项真实 GPU 断言通过）。原存档尚未复测，日志也没有证明本轮曾发生永久冻结。

最终日志为 `build/package-capture-fairness-final.log`。541 项单元测试（106 个套件，无失败、错误或跳过）、14,308,725 项完整 GPU 断言、无 Sable 桥接检查和正式构建通过。

发布包：`build/libs/createmanaindustry-0.2.6.jar`。内嵌主 JAR 中涉及本轮及上轮修改的 39 个编译类及内部类已逐项核对一致。发布包 SHA-256：`82F20C71F3F7BA36D20E5D44A64284358030F6E0A52724C1E42C0CCED7C592C1`。
