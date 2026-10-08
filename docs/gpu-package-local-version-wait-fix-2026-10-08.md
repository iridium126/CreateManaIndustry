# 局部版本等待：立即发布完整快照与连续通知上传证明

## 本轮日志与工作范围

debug.log 截至 2026-10-08 15:15:04。按用户后续要求，本轮重点修复局部版本等待。

1904 条 SECTION_VERSION 全部为 worldWait=capture、stillFrozen=false。事件发生时 GPU 缺少对应 CPU 捕获；限流输出时 CPU 和 GPU 都已完成。不能把延迟输出当作包裹仍在冻结。

| step | section | requiredRevision | 记录数 |
| --- | --- | --- | --- |
| 1767 | (-1,4,0) | 329 | 108 |
| 1767 | (-1,4,1) | 330 | 54 |
| 1961 | (-1,4,0) | 331 | 1019 |
| 1961 | (-1,4,1) | 332 | 723 |

来源仍是 BLOCK，坐标 (-5,77,14) 与 (-5,77,19)，分别合并 2 次、7 次通知。日志没有保存具体方块状态或每个变化单元的输入，无法逐事件重放存档。

另外有两条 SECTION_MISSING；15:11:49 汇总为 1，下一秒恢复为 0。这是另一条初始覆盖等待，本轮按用户要求聚焦局部版本，不更改未知 section 的碰撞保护。

## 小范围数值快照立即完成

原局部通知保留完整基线、重新捕获变化单元，但仍遍历全部单元并等待 worker 重打包，期间撤销整个 section 的当前快照。这会让远离实际变化位置的包裹一起等待 requiredRevision。

现在通知比较得到的已知单元可直接构造完整不可变快照，不再重复查询世界，也不等待 worker。未变化单元复用基线的数值数据；几何不变时复用 CPU 形状数组和 GPU 字典。几何变化时重建完整 CPU offsets/boxes，保持局部浮点坐标精度；GPU 字典保留原前缀，只追加必要形状，其他单元的索引不被重新编号。

快速路径需要已有完整基线和全部已知输入，最多 27 个单元、64 个变化形状、新 CPU 总形状不超过 8192、GPU 字典不超过 1024；形状继续遵守既有 [-1,2] 单元覆盖限制。数值处理共享每个缓存捕获周期 1 ms 的软时间额度，超过额度在发布前回退。单次分配或读取仍不可抢占，不宣称硬时间上限。

未知单元、查询异常、复杂形状、字典容量、完整区块上下文变化、正在进行的捕获或预算耗尽继续使用原增量捕获及 worker 流程。查询失败不能只发布同一邻域中其他已知单元的变化，避免把未知数据错误保留为旧碰撞。

## 连续通知保留正确 GPU 基线

立即发布会产生多个完整 CPU 版本，而 GPU 可能仍显示这一批通知之前的版本。只保存上一份 CPU 快照的上传证明，会导致第 2 次及后续通知的基线与 GPU 不匹配。

现在每份快照最多保留 8 份纯数值证明，包含基线 revision、唯一快照身份、原字典长度及沿途变化单元的并集。没有前一快照对象链。每次都保持字典前缀，合并证明因此仍可验证未改变的 GPU 数据。GPU 选择与当前可见快照身份及 revision 同时匹配的证明，而非只取最近 CPU 版本。

证明超过保留范围、字典被完整重捕获压缩、字节额度不足、历史版本仍被引用或 GPU 仍在读取时，保留现有完整 staging 上传规则。没有跳过需要保留的旧物理输入，没有把缺失版本当作空气。

## 回归证据

修复前，小范围更新立即查询当前快照会得到 null；失败日志为 build/local-version-before.log。修复后，同一测试无需新的 worker 或世界读取即可得到完整新快照。

真实 GPU 夹具使用日志中的两个 section 和通知坐标，分别连续改变 7 次、2 次。所有 CPU 版本都立即完成，GPU 仍可匹配批次开始前的可见版本；两侧在一次 512 字节额度中仅使用 320 字节上传完成。两侧远离变化位置的包裹都保持运动，没有局部版本冻结。

单元回归覆盖浮点精度、形状增删与索引稳定、邻接 section、连续通知、不可读邻域、证明范围、时间预算回退，以及复杂形状继续走 worker。原完整上传、读取 lease、历史版本和不同缓存同 revision 的身份保护回归继续通过。

诊断追加 observedCaptureMode=local/incremental 和 observedLocalFallback，后者区分 time-budget、input-unavailable、changed-shape-limit、dictionary-limit、cpu-shape-limit、shape-bounds、capture-in-progress 等。仍为输出时的观察，不反推旧事件发生时的状态。

## 最终验证

```powershell
.\gradlew.bat test validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

build/local-version-regression.log：182 项针对性世界版本 GPU 断言通过。
build/package-local-version-final.log：547 项单元测试（106 个套件，无失败、错误或跳过）、14,308,797 项完整 GPU 断言、无 Sable 桥接检查及正式构建通过。

发布包 build/libs/createmanaindustry-0.2.6.jar 内的 48 个相关类及内部类已逐项核对编译输出。SHA-256：1AB5ABDCDBA2DA7D185D642A6CC6D8A3C9AB92D5871C115E9B58E87D23BE9194。

原存档尚未复测；无法从旧日志证明每次通知都会满足快速路径上限。真正未知的输入、超出预算或尚未解除的 GPU/历史保护仍可能产生等待。
