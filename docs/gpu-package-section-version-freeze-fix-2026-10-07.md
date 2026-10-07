# 无操作场景中的静态 section 冻结

## 本轮日志与测试条件

新 `debug.log` 截至 19:26:46。只有 `SECTION_MISSING`：16 条采样时仍冻结，1667 条已恢复。19:24:57 的汇总为 4150 个冻结包裹，全部涉及 section `(-1,4,0)`、step 1675；下一秒汇总恢复为 0，后续大量输出是限流诊断队列中的历史事件。

用户确认没有手动操作，场景持续运行。本轮没有旋转扫掠、移动几何或环境背压事件。旧日志未保存要求的静态几何 revision，因此不能仅凭它确认是淘汰、捕获等待还是被覆盖的上传。以下缺陷已分别复现；原存档仍需复测。

## 实时占用保护与历史表版本

上一轮的历史保留实现用同一个 serial 管理实时和历史表。仅改变历史引用也会让实时占用扫描的表版本过期，`beginPackageUsage` 拒收已经完成的扫描。实时碰撞数据与包裹位置没有变化时，这也可能丢失 section 的防淘汰保护。

修复分开实时数据和历史表的版本号。历史引用、旧版上传与诊断元数据变化只更新历史版本；实际实时上传、移除或替换仍更新实时版本。实时占用扫描继续严格验证它使用的实时表，历史视图同时验证两类版本。

恢复旧的 serial 规则的参考运行失败于 `history references invalidated an unchanged live occupancy scan`。修复后历史引用增加和消费不再使实时扫描失效；夹具将已占用 section 置为 LRU 最老，再加入新 section，验证它仍被保护，未占用 section 被淘汰。

## 尚未上传完成的历史版本

原实现保留了完整上传的旧 slot，但新捕获或失效通知会直接丢弃正在上传的旧版本。多个客户端 tick 可以先于下一次 GL 边界运行，旧输入引用也可能尚未登记到 GPU atlas；直接取消上传会让该历史版本永久缺失。

新增有界的旧上传队列，暂存完整 CPU snapshot、已复制进度及 staging slot。GL 边界登记输入引用后，丢弃不需要的任务，并在原来的时间/字节预算内先完成仍被引用的旧任务。旧数据仅进入按 revision 精确选择的历史表，不替代实时版本。队列最多保存 atlas capacity 个旧任务；GPU 仍使用原有 `capacity × 2` 个 slot，容量不足继续等待。

部分上传夹具依次提供空气、固体、空气三个版本，第二版本只复制 4096 字节后到来第三版本，再登记输入引用。旧实现稳定冻结；修复后第二版本的固体继续阻挡历史包裹，第三版本的空气允许实时下落。直接新捕获和先发失效通知两条路径均通过，上传字节统计确认已复制部分没有重复复制。

## 静态捕获与边界通知

已完成的静态 worker 结果原来只在有剩余捕获预算时领取。现在零预算也领取已完成的不可变结果，不查询世界、不等待 worker。完成后已经被新编辑覆盖的 coherent snapshot 通过独立历史回调交给 GPU，仅供仍需要它的历史输入；它不能发布成当前 CPU snapshot。

边界通知原来一旦发现一个单元变化，就撤销整个邻近 section 范围。现在仍检查一格邻域的已捕获输入，但只撤销实际形状、摩擦或标志变化所属的 section。邻居自身发生变化仍会撤销；未知且尚未捕获的单元继续由队列读取最新状态，不反复重启捕获。

单元回归验证：一个角点变化只撤销其所属 section，其他七个保持 revision；另一个 section 的邻接形状变化单独撤销该邻居；零预算发布不访问世界；过期完成结果不成为当前 snapshot。钩子契约检查同步迁移到共享的 owned-cell 比较路径。

## 可区分的版本诊断

历史表为已知 resident 但缺少指定版本的 section 保留 metadata-only 行。这些行没有碰撞数据 slot，不能作为空气或实时版本的替代。碰撞查询报告 `SECTION_VERSION`、`requiredRevision` 和 `worldWait`，阶段为 `capture`、`upload` 或 `superseded-or-unavailable`。真正缺少 section 的情况仍为 `SECTION_MISSING`。

完整 64 位 revision 使用原有诊断缓冲的原始整数位存储；Body、网络、存档以及诊断缓冲大小不变。GPU 回归验证高位 revision、低位为 NaN 浮点位模式的 revision、预测和恢复阶段，以及两个 metadata-only 行的 hash 冲突探测。metadata-only 行仍然冻结，不能误读为碰撞几何。

## 最终验证

```powershell
.\gradlew.bat validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

519 项单元测试（103 个套件，无失败、错误或跳过）、14,308,660 项完整 GPU 断言、无 Sable 桥接检查和正式构建通过。日志为 `build/package-section-version-final.log`，旧行为复现日志为 `build/superseded-world-upload-before.log`、`build/static-publication-before.log`、`build/live-usage-reference-before.log`。

发布包：`build/libs/createmanaindustry-0.2.6.jar`。未在原存档复测，也未测量整帧耗时；真实数据尚未捕获、上传或容量不足时仍保留必要的等待保护。
