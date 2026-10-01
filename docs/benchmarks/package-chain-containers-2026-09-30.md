# 锁链包裹容器 CPU 遍历对照（2026-09-30）

本次减少 Create 对已接管包裹的重复遍历。完整列表仍保存原包裹对象、顺序及数量，供容量、保存、拾取和掉落使用；tick、坐标更新和摆动更新使用独立的 Create 子集。此报告只测量容器遍历，不证明真实物流接管、GPU 物理或整帧性能通过。

## 方法

Windows、本机 JDK 21；CPU 标识为 `AMD64 Family 25 Model 97 Stepping 2, AuthenticAMD`。`PackageChainContainerBenchmark` 使用固定进度的简单对象，对普通 `ArrayList` 和 `PackageOwnershipList.createView()` 执行相同的求和遍历，结果写入 volatile 防止死代码消除。GPU owner 的检查点回调在正常遍历中调用即报错，不在该程序执行模拟。

规模为 10000、65536、131072；分别测量全 Create、仅一个 GPU owner、半数 Create、约 1% Create、全 GPU owner。每条路径至少预热一秒且不少于 1000 次遍历，采集 3000 个样本，每场景重复三次。`System.nanoTime` 记录 CPU 时间，HotSpot `ThreadMXBean` 记录当前线程分配量。原路径每次遍历完整列表；新路径只遍历 Create 子集。完整身份及所有权守恒由单元测试另行验证。

初始化、接管、退回、首次子集镜像构建及成员变化后的镜像重建均在计时范围之外。基准未包含实际服务器 tick、库存回调、模型、上传/读回、GL、渲染或网络；下一阶段需要独立测量大量接管/交接造成的重建和分配成本。

## 131072 项的结果

以下为三次重复各自 p95 的最小至最大值，单位 ms。不是把多个重复的样本混合后重新计算。

| 场景 | 原完整列表 p95 | 新路径 p95 | 新路径遍历项数 |
|---|---:|---:|---:|
| 全 Create，最后一个 owner 已退回 | 0.0825–0.0826 | 0.0824–0.0826 | 131072 |
| 仅一个 GPU owner | 0.0826 | 0.0826–0.0827 | 131071 |
| 半数 Create | 0.0825 | 0.0396–0.0399 | 65536 |
| 约 1% Create | 0.0825–0.0826 | 0.0009 | 1311 |
| 全 GPU owner | 0.0820–0.0826 | 0.0001 | 0 |

空子集结果接近计时分辨率，只能说明没有访问包裹，不能用于计算精确提速倍数。131072 项各场景两条路径均分配 32 字节/遍历，即一个原生 `ArrayList` iterator；无每个 GPU owner 的热路径分配。部分 10000 项场景的 iterator 被 JIT 消除，见原始 CSV。

此前链表子集原型在全 Create 的三组测试中 p95 为 0.6023–0.6094ms，同次进程的 `ArrayList` 为 0.1003–0.1045ms，存在明显回退，已弃用。最终路径在子集成员未变时复用连续数组，迭代器和原路径均为原生 `ArrayList.Itr`；最后一个 owner 退回时恢复实际 Create 字段为普通 `ArrayList`。原型和最终版本在不同 JVM 进程测量，不能把两次进程的原列表时间差解释为优化收益。

## 实现与限制

`PackageOwnershipList` 以对象身份维护完整顺序和所有权。Create 子集镜像只在成员变化后由 Create 链表构建，不扫描 GPU owner。原生 iterator 的删除与插入同时修改完整列表，保留 Create 的容器行为。保存、拾取、掉落和移除边界按需物化检查点；批量回调保存原 owner 身份，不得处理回调期间重新接管的新 owner。

`ChainOwnershipContainersMixin` 只替换三个包裹模拟 pass 的列表读取，不取消 conveyor tick。原对象仍参与容量统计和保存；连接移除、读档、清空、销毁及结构变换会退回相关 owner。服务端只能在隐藏 admission 的 PREPARED 后冻结最终基线，客户端必须核对 ACTIVE 和成功的可见提交后才隐藏 Create 渲染。此测量完成后已添加[服务端 lease/节点事务接线](../gpu-chain-authority.md)，客户端 chain acquisition 和渲染接管仍未接通，`CHAIN_READY` 保持关闭。ASM 检查针对实际 Create 6.0.10 的方法描述符和字段访问，不能替代客户端启动后的 Mixin 注入及玩法测试；本次数据也不覆盖后续服务端代码成本。

## 复现与数据

```powershell
.\gradlew.bat benchmarkPackageChainContainers --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

- [最终摘要，90 条测量](package-chain-containers-2026-09-30.csv)
- [最终逐次样本，270000 条，gzip](package-chain-containers-samples-2026-09-30.csv.gz)
- [已弃用链表原型摘要](package-chain-containers-linked-before-2026-09-30.csv)
- [GPU 活动锁链内核对照](package-chain-tracks-2026-09-30.md)

GPU 内核报告测量另一条独立组件路径。两份报告都不包含完整真实物流系统，不能将组件时间相加后宣布达到 131072 活动包裹整帧 p95 ≤16.7ms。
