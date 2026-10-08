# 链表重建异常与静态捕获等待

## 日志结论

本轮 `debug.log` 截至 2026-10-07 22:10:13。1885 条 `SECTION_VERSION`、3 条 `MOVING_GEOMETRY` 都是 `stillFrozen=false`；没有仍冻结的单体记录。这些是限流输出的已恢复事件，不代表 1888 个包裹持续冻结。

静态等待集中在：

| section | requiredRevision | worldWait | 记录数 |
| --- | --- | --- | --- |
| (-1,4,0) | 309 | capture | 994 |
| (0,4,0) | 308 | capture | 702 |
| (-1,4,0) | 317 | capture | 128 |
| (-1,4,0) | 316 | upload | 61 |

两侧 section 上传过旧版本，不能保证物理步骤所需的精确新版本已就绪。1824 条静态事件明确等待 CPU 捕获，而不是 GPU 上传。三个移动事件等待结构 7、revision 9 的 worker。

另有确定的运行时异常：22:06:55 链权限关闭后，22:07:01 重建收到表头 2，但新表需要从 0 开始，抛出 `Chain table index order: received=2, expected=0`，释放整个 GPU 包裹运行时。日志没有保存每个 TRACK 包的收包过程；以下机制由代码和回归确认，不能逐包重放原存档。

## 表头跨 GPU 资源重建保留

原收包路径在 GPU 运行时不可用时丢弃 TRACK，服务器仍按会话发送后续增量编号。现在在客户端传输边界保留不可变表头，按连接、维度和 epoch 隔离。重建先从 0 重放已收到的完整表，再处理增量 TRACK 或 OFFER。没有后续 TRACK、仅收到 OFFER 时也能初始化。

CLOSE 撤销缓存 epoch，旧 epoch 不能重新打开；旧 CLOSE 不清除新 epoch。重复表头幂等，真正的缺号、冲突及容量超限仍明确报错，不构造缺失轨道。缓存最多 131072 个表头，沿用协议容量。服务端关闭链权限时客户端同时撤回 readiness，避免 GPU 已退出但服务器仍认为其就绪。

真实 GPU 链获取夹具先确认单独收到表头 2 会被拒绝，再重放 0、1、2 并确认增量 2 幂等；验证自由包裹的 GPU 状态保持原值，继续执行链获取、可见性、事件和退役流程。针对性链 GPU 验证通过 158585 项断言。

## 局部变化复用完整碰撞快照

局部通知仍比较一格邻域的形状、摩擦与标志，变化立即撤销当前覆盖。已有完整快照时，仅重新查询发生变化的单元，其他单元复用不可变快照；没有完整基线、GPU 编码不支持、区块重新加载及完整失效继续重捕获。

复用的局部浮点坐标来自原 GPU 形状字典，避免从已平移的坐标相减造成精度变化。连续变化保留待重读单元的并集；捕获尚未开始时仍比较保留的基线。完整失效先丢弃基线，再执行失效合并。查询暂时不可用的变化单元撤销覆盖并等待恢复，不使用旧数据或空气替代。

回归覆盖仅查询变化单元、未变化小数形状精度、worker 期间连续修改、捕获开始前连续修改、区块完整失效覆盖局部失效，以及暂时不可读单元恢复。仍遵守原捕获预算、worker 上限、精确历史版本和完整上传后发布规则。

## 验证

```powershell
.\gradlew.bat test validatePackageGpu -PpackageChainOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

最终验证通过 535 项单元测试（106 个套件，无失败、错误或跳过）、14,308,714 项完整 GPU 断言、无 Sable 桥接检查及正式构建。最终日志为 `build/package-chain-capture-final.log`，针对性 GPU 日志为 `build/chain-bootstrap-gpu.log`。

发布包为 `build/libs/createmanaindustry-0.2.6.jar`，内嵌主 JAR 的 7 个修改类（包括碰撞快照及 Work 内部类）已与编译输出逐项核对 SHA-256。发布包 SHA-256：`2F499E523FD1C3D7B0754C02F2DD65E99E17961E5490ECB9FFF9627B1C9B6853`。

原存档尚未复测。局部捕获减少世界查询，仍需处理整个不可变数值快照；真正的数据不可用、网络关闭和预算不足仍可能产生等待。日志没有给出单次等待的持续时间，本轮不声称原场景已无冻结。
