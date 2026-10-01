# 包裹移动光照：组件接线与测量（2026-09-30）

本轮修正 GPU 包裹一直沿用接管亮度的问题，提供分区光照快照、非阻塞 atlas 与 GPU 插值位置采样。没有宣布完整 Create 包裹计划或 131072 活动包裹整帧目标达成。

## 实现和参考

- 对照 `.refs/Create/.../ChainConveyorRenderer.java` / `ChainConveyorVisual.java`：链上箱体和吊具都在 `prevPos.lerp(pos, partialTicks)` 的摆动位置采样；不是吊点或 mesh 顶点。
- 对照 `.refs/neoforge-21.1.227/.../EntityRenderer.java` / `Entity.java` / `EntityDimensions.java`：普通包裹在插值脚底位置加 `height * .85` 的眼高采样。着火包裹仍不具备现有 free authority 接管资格。
- `PackageLightCache` 每 section 保留两层原生 nibble 数据，共 4096B；`PackageWorldLightSource` 只在客户端线程复制 `DataLayer.copy()`。无昂贵几何整理需要，所以不增加 worker 或后台世界访问。缺失天空光层使用预算内原生查询，保留其从更高 section 继承非均匀亮度的语义；不能把缺失层直接当 0 或 15。
- 每次 fallback 捕获至多 32 个 cell 后轮转，预算剩余时继续续采。未加载或异常来源当 tick 不重复查询。完成全部 4096 cell 后才发布当前版本。
- `ClientChunkCache.onLightUpdate` 只记录已请求列的数字标识，并合并重复通知。所属线程在原有 250µs 预算内处理；列中不同高度的已请求快照一起失效，以覆盖天空光继承。区块更换和换世界也失效。
- 与静态/动态碰撞共享采集 250µs 和上传 256KiB/250µs 软预算，轮换光照优先级。单次 native 复制/列失效或 GL 调用仍可能超出软预算，不能据此承诺实际游戏 p95；命令统计光照采集 p50/p95、超限、来源失败、上传字节和满 ring 次数。
- 四个独立、持久映射且 coherent 的 atlas bank，只使用零 timeout fence 检查可写资格；view 保留期间不能写。没有变化复用原 bank，满槽跳过更新。上传预算包含 hash header、完整 section 与 ready word。已提交 view 保留自己的旧数据，不被新版本覆盖。
- draw 前 GPU 每候选采样一次，箱体与吊具读取同一个 uint 结果。采样读取已提交 admission、pool 和 attachment；不改写这些缓冲。draw 不逐包裹查询世界，也不下载整个粒子池。资源重建先编译全部 8 个程序再替换，light shader 失败保留原程序。
- 粒子仍为 64B，header 仍为 20 vec4；80B package metadata 的 `nudge.w`（offset 76）保存普通包裹 probe 高度，附件复用其值。增加 4B/candidate 的 draw 光照缓冲，不增加通用粒子槽位。
- Sable 继续使用 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"` 和直接 API，无 Sable 反射。缺席分支继续在独立 JVM 验证。

## 正确性

Java 光照测试覆盖快照不可变、原生 nibble 顺序、软预算续采、失效丢弃部分捕获、列/高度失效、队列与容量、未加载、错误线程、来源异常。

独立真实 GPU 光照套件通过 **620,522** 项断言：0/1/63/64/65/10000/65536/131072 候选，负坐标和 section 边界，0/.5/1 插值，ground probe 与 chain 摆动位置，隐藏/空/越容量 admission，未知光照使用初始亮度，输出尾哨兵，输入 pool/admission 不变，部分上传，四 bank 内容不可覆盖，槽满/恢复，过期版本和 hash 碰撞（含失效的原点 section）。

原有完整包裹 GPU 套件通过 **13,341,133** 项断言（含本轮增加的 8 项实际绘制断言）：间接绘制像素随已发布光照由 3→12 改变，mesh/instance 映射正确，light shader 重载失败保留可用程序，draw 不修改 pool generation。

最终 **246 项 Java 测试**（49 个 suite，0 failure/error）、Sable 缺席验证和 `build` 均通过。这些测试不运行真实 Minecraft 客户端、Mixin 生命周期、多人生存库存或动态结构游戏场景。

## 采样 pass 测量

RTX 4070 Laptop GPU，NVIDIA 581.15，OpenGL 4.5。固定输入与 27 个非均匀光照 section，每组至少预热 30 次且 1 秒；三轮，每轮 120 个计时样本。插值不断变化，候选混合 ground/chain、隐藏/越界/未知光照；不以结果下载或 CPU 回退代替 GPU 计算。

| 候选数 | GPU p50（ms） | 三轮 GPU p95（ms） | 三轮 CPU 提交 p95（ms） |
| --- | --- | --- | --- |
| 10000 | 0.009216 | 0.010240 / 0.010240 / 0.010240 | 0.0014 / 0.0010 / 0.0009 |
| 65536 | 0.013312 | 0.015360 / 0.015360 / 0.014336 | 0.0009 / 0.0009 / 0.0009 |
| 131072 | 0.018432 | 0.020480 / 0.020480 / 0.020480 | 0.0010 / 0.0011 / 0.0009 |

[汇总 CSV](package-light-sampling-2026-09-30.csv)，[1080 个原始样本](package-light-sampling-samples-2026-09-30.csv)。CPU 数值是 standalone fixture 的缓存 uniform/绑定/dispatch 提交，不包含游戏 GL 边界、采集、atlas 更新、物理或 draw。GPU 计时只覆盖 light sample，计时查询等待只发生在显式验证程序中。

没有用初始冻结亮度作为“等价更快”基线：冻结亮度本身不正确。本次给出新增 pass 的成本，未测实际游戏 native 逐包裹光照或每顶点光照的同场景对照，因此不声称 CPU/GPU 加速比例。1024-section 配置的四个 GPU atlas 约 16.125MiB，131072 个 draw 光照 uint 另占 .5MiB；这是缓冲分配量，不是显存分析器实测。

## 尚未完成

光照未覆盖时保留 metadata 初始亮度；本轮没有完成按 GPU 未覆盖光照结果批量预取/交还或为临时失效保留上一份逐候选确认亮度。普通包裹复用碰撞请求的 section 范围，链上接管时请求摆动位置邻区；整条长链后续运动的光照覆盖、移动 parent/Sable 光照空间映射、远坐标精度和第三方动态光仍需补齐并做视觉测试。

`CHAIN_READY` 继续关闭，free authority 仍为默认关闭的实验配置。紧急回退最新姿态、观察客户端增量、Iris/阴影接线及整帧/服务端/网络验收仍未完成。此报告不能用于认定光照与全部 Create 行为对齐。

复现：`validatePackageLightGpu -PpackageLightBenchmark`，配合 `--offline --no-configuration-cache -I scripts/particles/validation.init.gradle`；原有 GPU 回归使用 `validatePackageGpu`。
