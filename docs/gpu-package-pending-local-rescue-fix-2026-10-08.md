# 已在捕获中的局部版本恢复

## 最新日志

debug.log 截至 2026-10-08 16:30:55。1744 条 SECTION_VERSION 全部为 worldWait=capture、stillFrozen=false，没有持续冻结汇总。另有一条已恢复的 SECTION_MISSING。

| step | section | requiredRevision | 样本数 |
| --- | --- | --- | --- |
| 1041 | (-1,4,0) | 321 | 950 |
| 1041 | (0,4,0) | 320 | 121 |
| 1456 | (0,4,0) | 329 | 673 |

来源是 BLOCK，记录中的最后坐标为 (-2,77,14) 与 (2,77,14)。早期输出 localPublications=0，回退为 capture-in-progress；后期已有 7 次局部发布。上轮快速路径已经生效，但在首次失败后进入增量捕获的 section 仍无法直接恢复。

回退字符串此前会被后续通知的 capture-in-progress 覆盖，日志没有保留第一笔失败的具体原因。本轮不能证明第一笔必然是时间预算失败；该机制由明确失败的回归验证。

## 捕获阶段重试

此前 publishLocal 要求当前完整快照存在且没有 worker。第一笔局部尝试失败并撤销当前快照后，后续通知都只能回退；即使完整基线和少量脏单元仍保留，也没有重试机会。

现在在已有的捕获预算内重试局部替换，每个 section 每轮最多一次。重读基线以来的所有脏单元，而不是沿用首个通知的旧输入；仍遵守 27 单元、64 个变化形状、CPU/GPU 数据容量以及 1 ms 局部软额度。成功后完成已有的 requiredRevision，不制造新版本，不查询无关世界单元。

本轮重读的单元按已捕获输入参与变化检查；若源查询期间再次失效，放弃本次替换。失败后普通捕获可复用本轮已经核对的数据，避免重复读取；本轮结束时清除这些样本。零捕获预算不进行重试或世界读取。未知单元、字典及形状限制继续保守回退。

## 旧 worker 不阻塞当前快照

旧 worker 的 immutable input 仍可完成。它不再阻止新版本的局部恢复，也不阻止恢复后的下一笔局部通知。

恢复后的 Work 保留必要的完成轮询。旧结果只通过 historical 回调供精确旧输入使用；与已发布当前版本重复的结果被忽略，不能用另一个 snapshot 身份替换当前数据。保留原全局 worker 计数，不取消计数、不增加 executor 洪峰、不等待未完成任务。

## 诊断与回归

后续 capture-in-progress 不再覆盖已知的首次具体回退原因，使 time-budget、shape/dictionary 限制等能够继续被看到。

修复前 build/local-rescue-before.log 中，通知预算失败后，即使下一轮预算足够，当前快照仍为空并重新提交 worker。修复后只重新读取一个脏单元，无新 worker 即完成同一版本。

真实 GPU 夹具复现首次失败后又发生局部通知：捕获阶段采用最新变化完成原 revision，以 48 字节上传后包裹继续运动。针对性世界版本 GPU 验证通过 188 项断言。

其他回归覆盖旧 worker 仍运行时恢复、恢复后继续通知、过期结果不覆盖当前快照、旧版本历史回调、零预算、未知数据与复杂形状回退。对复杂形状每轮只尝试一次，不会每个 32 单元分片重复重试。

## 验证

```powershell
.\gradlew.bat test validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

针对性日志：build/local-rescue-regression.log。最终日志：build/package-local-rescue-final.log。

原存档尚未复测。本轮修复的是已知局部数据的恢复入口及 worker 阻塞；真正未知的数据、完整上下文失效、超出预算/容量或仍需要旧 GPU 版本时仍可能暂时等待。

最终验证通过 550 项单元测试（106 个套件，无失败、错误或跳过）、14,308,803 项完整 GPU 断言、无 Sable 桥接检查及正式构建。

发布包：build/libs/createmanaindustry-0.2.6.jar。内嵌主 JAR 中 48 个相关类及内部类与最终编译输出一致。SHA-256：C20489A1C3F0AB5D7CBEA9547044F457F900C2428D8C844190BF2B4CA992B753。
