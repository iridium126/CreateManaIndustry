# 重复结构通知和环境回读名额引起的暂时冻结

## 本轮日志

新 `debug.log` 截至 15:47:18。49 条 `MOVING_GEOMETRY` 记录中 16 条采样时仍冻结；32 条 `MOVING_SWEEP` 记录全部已恢复；还有一条仍冻结的 `ENV_BACKPRESSURE`。这些是限流事件样本，不是独立包裹数量。

15:45:57 的汇总显示 105 个包裹在等待结构 4 的几何，样本均为 step 2444；下一秒汇总恢复为 0。15:46:19 的包裹 32708 环境队列为 `pending=20 written=20 acknowledged=0 health=5`，下一秒也恢复。日志没有证明它们永久冻结，也没有记录导致本次结构 revision 改变的原始方块通知。下面修复的是代码中已确认的重复失效和回读竞争问题，原存档仍需复测。

## 相同几何的重复通知

Sable 的 `LevelChunkMixin` 在 `setBlockState` 返回时调用 `LevelPlot.onBlockChange`，即使设置相同状态；本模组的 plot hook 和普通 chunk hook 均可能转入移动碰撞通知。方块实体数据包也会经过碰撞通知。原实现无条件递增来源版本并撤销完整 snapshot，使相同的碰撞形状也要重新捕获、构建和上传。

新增有界的数值签名缓存，保留已捕获的碰撞盒、摩擦、标志及空单元信息。通知到达时，在来源边界内比较该单元及一格邻域；全部已知且数值相同时保留版本。形状、材料或标志变化、邻域变化、未知单元、查询失败或容量溢出仍失效。块读取直接来自 Sable plot 的 chunk holder，形状上下文仍用 embedded 坐标。

来源含自定义 Sable 碰撞形状或声明为动态形状的方块时，保留原来的整体失效规则，避免局部比较遗漏远处上下文。来源边界、plot 实例、初始完成状态或 chunk 改变继续撤销签名。缓存最多 1024 个 section，碰撞盒总数不超过已有捕获上限；溢出后保守失效。坐标使用三个 int，避免 `BlockPos.asLong` 截断超大 plot Y。

单元回归覆盖 100 次相同通知、真实与邻域变化、摩擦和标志变化、形状变化、复制隔离、Y=2147483500、section 边界、未知单元及容量溢出恢复。

## 环境事件优先回读

原 `environment_capture.comp` 把所有未 ACK 的日志一起争抢每次 512 个回读名额。已发送且没有新增后缀的日志会重复占用名额，推迟其他包裹首次发送，导致其 20 条可靠队列积满。

修复将未发送的新后缀先放入队列，再用剩余名额重发。增加每体 4 字节的捕获批次标记，防止同一个包裹在两个阶段重复入选；重置、上传和退役时清除标记。仍保留重发、累积 ACK、不可变样本、严格生命周期校验和满队列保护；不扩大 20 条历史，也不覆盖未确认事件。两阶段都是 GPU 扫描，不新增同步读取。

4096 包裹夹具中，3584 个日志已发送等待 ACK，尾部 512 个尚未发送。恢复原入选条件的参考运行只捕获到 **0 / 512** 个新日志。修复后新日志全部入选，随后还验证了无 ACK 时重发、槽位复用，以及新记录与重发同批时不会重复。

针对性环境 GPU 回归共通过 25,964 项断言，包括旧的火焰、水、机器事件、20/200 TPS 低帧率压缩与延迟 ACK 检查。此改动改善回读竞争，不能消除真实网络延迟或超出固定发送能力的持续事件压力所需的背压。

## 后续诊断

`MOVING_GEOMETRY` 增加精确的 64 位 `geometryRevision` 和 `geometryWait`：`capture`、`worker`、`upload`、`historical-version`、`unsupported`。沿用原来的冻结诊断缓冲和采样频率。GPU 回归验证上传未完成时能够输出正确版本和 `upload` 阶段；单元回归验证高低 32 位解码。

```powershell
.\gradlew.bat validatePackageGpu -PpackageJournalFairness --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

参考失败日志：`build/journal-fairness-no-priority.log`。修复日志：`build/journal-fairness-targeted.log`、`build/package-repeated-freeze-validation.log`。未在原存档复测，也未测量整帧耗时。

最终版本验证日志：`build/package-repeated-freeze-final.log`。512 项单元测试（102 个套件，无失败、错误或跳过）、14,307,454 项完整 GPU 断言、无 Sable 桥接检查及正式构建通过。发布包为 `build/libs/createmanaindustry-0.2.6.jar`；内嵌主 JAR 的改动着色器、主要类及 Sable Source / cursor / Base 内部类已核对匹配最终源文件和编译输出。几何缓存仅记录与来源边界相交的 section，避免 plot 内无关空 section 消耗签名容量。
