# GPU 光照覆盖反馈与请求合并（2026-09-30）

本轮接通缺失光照的 GPU 反馈、分区预取和已确认亮度保留；完整 Create 包裹计划仍未完成。之前的 [采样报告](package-light-sampling-2026-09-30.md) 描述本轮以前的组件状态。

## 实现

`light_sample.comp` 每候选读取已提交 pool/admission。有效采样在私有 uint 的 bit 31 标记已确认；光照分区失效或尚未完成时保留上一份确认亮度，没有确认记录的候选使用自己的 metadata 初始亮度。隐形/无效 admission 清除标记。世界/source 更换、pool reset 和完整 metadata 替换清除旧记录；append 或同一身份的可见性变更不清除其他候选的记录。未修改 64B 粒子、20 vec4 header 或 shaderpack 的 pool 数据协议。

缺区候选记录纯数字 section 坐标。工作组内先按反馈桶合并，再用 uint atomicMin 选全局代表；每桶只有一个候选进入 `light_requests.comp`。不同 section 的哈希碰撞只推迟其中一项：收到代表后，所属线程立即在 atlas 登记 pending key，即使其原生光照尚不可用，它也退出下次请求竞争，后续代表继续重试。输出超过 256 section 时仍保留真实计数，未输出项没有被确认，下一次重新参加竞争。没有浮点原子、厂商 subgroup 扩展或线程自旋锁。

反馈开启时所有 64 个 invocation（包括尾部、无效/隐藏候选）进入 shared barrier；关闭反馈的分支是整个工作组一致的 uniform。算法不会因部分线程提前返回而漏过 barrier。

`PackageLightFeedbackGpu` 使用独立四槽 `PackageReadbackRing`，每快照 4112B。只消费完成 fence，轮询 timeout 为 0；满槽跳过新复制，未登记分区仍由下一次 shader 提交重试。清空或 source 更换删除旧 ring，不等待旧 GPU copy 完成。结果只有 section，不包含可用于伤害、拾取、库存或网络操作的裸池索引。

`PackageLightRequests` 验证完整 header/count/坐标/保留字段后才调用 owner；其临时数组复用。`PackageWorldRuntime.prepare` 在 GL 边界内消费完成反馈，`PackageCollisionRuntime.requestLightSection` 只登记数字身份，不在渲染阶段查询世界或等待 worker。真正的复制和上传仍属于之前的共享采集/上传预算。分区容量耗尽走已有 authority 关闭/恢复 Create 路径，不假装已覆盖；这个全局回退策略不是容量外活动包裹的性能验收。

`PackagePoolGpu` 全部 9 个 shader program 成功编译后才替换旧程序。新增缺区 gather 同属 draw 使用的已提交 pool/admission，未依赖 CPU 滞后粒子 census。`PackageWorldRuntime.status` 输出反馈字节、在途槽、跳过、溢出和最后读回延迟。

## 验证

- **250 Java 测试**（50 suite，0 failure/error）；新增解码测试覆盖整包拒绝、负/高 Y、固定前缀和真实超量计数、长度/保留字段及 callback 失败后重试。
- **886,165 GPU 光照断言**，默认工作组版本和保留的 global atomicMin 基准版本均通过同一套件。覆盖 1/63/64/65/131072 同分区候选、隐藏/无效/尾部混合、零 count、确认/失效/再确认、1024 不同缺区的哈希碰撞和 >256 输出连续重试、四份不同坐标不可变快照、第五次拒绝及恢复。
- 完整包裹 GPU 套件 **13,341,136 断言**通过。实际 indirect draw 像素验证了光照失效后的确认值保留、完整 metadata 替换的新身份不继承旧亮度、source 替换不继承旧 ring/亮度，以及程序重载失败后的保留。
- Sable 缺席独立 JVM 检查和构建通过。仍使用指定 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`，Sable 桥接仍直接调用 API。

这些验证使用真实 OpenGL 与数值 fixture，不能代替用户的 Minecraft 客户端、Mixin、Iris、物流和视觉验证。

## 同机算法前后比较

RTX 4070 Laptop GPU，NVIDIA 581.15，OpenGL 4.5。固定数据，10000/65536/131072 候选，每组至少预热 30 次且 1 秒；三轮，每轮 120 个样本。两版本只改变请求合并：原始逐候选 global atomicMin 与默认工作组合并。两次进程依次运行；这里不是同时交错测量的整帧基准。

计时包括清理反馈、采样、1024 桶 gather、4112B GPU copy 与 fence 提交；不包括 CPU 解码、分区采集/上传、物理、实际 draw 或服务器。测量程序等待 timer/fence 后在计时区间外消费快照，不以满 ring 跳过复制代替正常提交。

`known` 为已覆盖；`dense_missing` 为全体缺失同一 section；`distributed_missing` 按 27 个不同缺区交错排列。缺区基准故意不登记收到的 section，持续施加请求竞争；实际路径通常在下一次 atlas header 发布后退出竞争。这是组件压力，不是活动包裹整帧场景。

| 131072 候选负载 | 原始 GPU p50 / 三轮 p95（ms） | 默认 GPU p50 / 三轮 p95（ms） |
| --- | --- | --- |
| 已覆盖 | .030720 / .034816, .034816, .034816 | .030720 / .034816, .034816, .033792 |
| 密集同一缺区 | .036864 / .040960, .040960, .040960 | .036864 / .040960, .041984, .040960 |
| 交错分散缺区 | .048128 / .053248, .053248, .053248 | .038912 / .043008, .044032, .044032 |

分散缺区三轮 p50 均减少 .009216ms，p95 减少约 17–19%；已覆盖与密集缺区差异接近 1µs 计时粒度，因此不宣称这两类提速。65536 分散缺区 p95 相差约 1µs，未认为有收益。默认版本的 131072 提交 CPU p95 为 .0050–.0060ms，仍只是此 fixture 的缓存 uniform/绑定、dispatch/copy/fence 开销。

[原始汇总](package-light-feedback-global-before-2026-09-30.csv)、[原始 3240 样本](package-light-feedback-global-before-samples-2026-09-30.csv.gz)、[默认汇总](package-light-feedback-grouped-2026-09-30.csv)、[默认 3240 样本](package-light-feedback-grouped-samples-2026-09-30.csv.gz)。原始版本在 shader 中保留为编译期基准分支，生产不启用；没有运行时逐粒子 Java 行为。

131072 配置增加 2MiB missing sidecar、4KiB winners、4112B 输出、约 16KiB staging ring 与 4112B CPU 读回暂存。保留之前的 .5MiB 光照结果缓冲及四个 atlas。新增反馈最大每成功提交 4112B，实际持续提交频率/显存/主线程采集 p95 还需在游戏中测量。

复现默认：`validatePackageLightGpu -PpackageLightFeedbackBenchmark`；原始分支另加 `-PpackageLightFeedbackGlobal`。均配合 `--offline --no-configuration-cache -I scripts/particles/validation.init.gradle`。

## 继续工作

反馈当前在包裹采样位置缺区后发起请求，未完成按速度与整条链路的提前预取。首次无确认亮度、长期不可用数据、容量外区域、缓存回收、moving parent/Sable 的光照空间与远坐标仍须继续处理；本轮不承诺它们全部视觉对齐。紧急关闭最新姿态、观察客户端增量、Iris/阴影和完整性能验收也仍未完成，CHAIN_READY 继续关闭。
