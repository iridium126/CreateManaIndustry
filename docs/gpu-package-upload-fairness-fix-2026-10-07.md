# 上传调度引起的暂时单体冻结

## 本轮日志

新 `debug.log` 截至 21:11:32，1840 条 `MOVING_GEOMETRY` 和 1264 条 `SECTION_VERSION` 全部为 `stillFrozen=false`，没有持续冻结汇总。日志保留的是已经恢复的事件，不能把后续数分钟的限流输出当作包裹仍在冻结。

移动事件集中在结构 5、几何 revision 5、`geometryWait=upload`、step 1291。静态事件集中在 section `(-1,4,0)`、revision 313、`worldWait=upload`、step 1297。它们明确显示完整 CPU 数据已经存在，但对应 GPU 上传尚未完成。

本轮没有丢失 section、旋转扫掠或环境背压事件。下面修复了可复现的上传顺序缺陷；日志没有给出每次上传的字节进度和全部并发任务，不能逐条重放原场景。

## 移动碰撞体轮流分片

原移动 atlas 从第一个 pending 结构开始，尽量把它全部复制完，再处理后面的结构。一个大 BVH 可以消耗整帧额度，让后面的极小碰撞体也无法就绪。

改为每个结构每轮最多复制 16 KiB，游标跨调用保留。仍只在完整上传后发布几何，空几何不需要复制，可在零预算时发布。来源和物理接触的顺序不变，没有增加同步回读或等待。

真实 GPU 夹具有一个 1023 节点的大 BVH 和一个 48 字节的小碰撞体，预算为 32768 字节。旧实现失败于 `small required collider waited behind one large moving upload`。修复后小碰撞体在同一预算内就绪，实际物理管线中的附近包裹不冻结、保持支撑；大 BVH 后续也能完成，字节额度不被突破。

## 活跃静态 section 优先且保留后台进度

静态 atlas 已有分片轮询，但没有区分正在被包裹使用的 section 与其他任务。现在使用已有 GPU 占用扫描的保护集合，把活跃历史任务、活跃当前任务优先处理，均保留精确 revision 规则。

当存在后台任务且额度至少为两个分片时，为后台保留至多 16 KiB、至多四分之一的字节额度。后台剩余不用的额度再返还活跃任务，避免详细活跃碰撞体使新 section 永久拿不到进度。GPU slot 容量与未知数据保护保持原值。

回归让背景 section 先入队，再加入一个活跃 section。五次 16 KiB 上传后，活跃 section 已完成，背景随后继续完成。另一个回归使用 8192 个不同形状的大活跃碰撞体，验证背景碰撞体在大任务尚未完成时也能就绪；两组测试都检查总上传字节数。

## 三类 atlas 共享额度

原帧调度让优先的 lighting、moving 或 world atlas 先使用全部剩余额度，另一个 atlas 可能拿到零。新增调度先给各类 atlas 一次受限的上传机会，再按原来的优先顺序分配剩余额度。

在默认 256 KiB 总额度下，world 和 moving 的首次机会各最多 80 KiB，lighting 最多 16 KiB；三者首次机会的时间也受总时间预算的三分之一限制。实际使用的字节从剩余额度扣除，没有任务的份额可被其他 atlas 使用。仍使用原来的总额度与软时间预算。

四项调度单元回归验证 lighting 优先时碰撞数据仍有机会、闲置份额借用、软时间耗尽后没有后续非零复制，以及零预算与无 lighting 情况。没有改变物理时钟、增加全场景暂停或把未上传数据视为空气。

## 最终验证

```powershell
.\gradlew.bat validatePackageGpu -PpackageUploadFairness --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

523 项单元测试（104 个套件，无失败、错误或跳过）、14,308,711 项完整 GPU 断言、无 Sable 桥接检查和正式构建通过。验证日志为 `build/upload-fairness-before.log`、`build/upload-fairness-targeted.log`、`build/package-upload-fairness-final.log`。

发布包 `build/libs/createmanaindustry-0.2.6.jar` 已核对包含本轮编译输出。未在原存档复测，也未测量整帧耗时；真实上传量超过预算或 slot 尚未释放时仍可能暂时等待。
