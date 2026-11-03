# R6：裂口可见性剔除与天空裁剪

**状态：候选已实现，待用户实机画面、规则及性能验收；20% FPS 目标尚无法判断。** 本轮按新标准允许天空光照、云及历史结果改变，允许合法计算轨迹改变。计算缓存没有通过完整链收益筛选，生产计算着色器保持原实现。没有减少粒子、模拟频率、碰撞或邻居预算，没有省略持久状态复制。

## 基线与结果边界

本轮从 R5 诊断构建冻结基线：`build/performance-attribution-20261103/r6-baseline`，包括源码、资源、Gradle 设置及逐文件摘要；其构建身份与 R5 归档相同：`4067018795b3f53040c613b8ab8cad7da4fd0afb42f3d3849e6a003a06d26ec9`。基线整包仍保存在 `build/performance-attribution-20261103/r5`。候选整包、源码快照、构建身份和验证摘要见 `build/performance-attribution-20261103/r6/delivery.json`，不使用当前 HEAD 代替构建身份。

没有收到比 R5 更晚的客户端窗口。此前三窗及逐帧归因保持冻结，见 [R5 报告](particle-frame-attribution-r5-2026-11-03.md)。有风暴 46.4996／46.0033 FPS，达到 20% 仍需节省 3.5843／3.6229 ms；约 1.34 ms 是旧窗口 `beginTranslucents` 的同帧未解释余量，尚不是独立实测的目标天空成本。不能把关闭风暴、不同朝向或不同构建诊断窗口的 FPS 差异算成本轮成果。

## 配置与生效能力

客户端模组配置界面的 `allayStorm` 分组新增中英文标签及说明；也可在客户端退出后编辑 `run/config/createmanaindustry-client.toml`：

```toml
[allayStorm]
    riftSkyClipping = true
    riftCulling = "FRUSTUM_DEPTH"
```

| 配置 | 取值／默认 | 行为 |
| --- | --- | --- |
| riftSkyClipping | true | 默认裁剪目标天空；关闭恢复完整目标天空 |
| riftCulling | NONE | 不做整体可见性剔除；天空裁剪独立生效 |
| riftCulling | FRUSTUM | 主视锥剔除 |
| riftCulling | FRUSTUM_DEPTH，默认 | 主视锥及当帧不透明深度剔除；契约不可靠时回退主视锥 |

配置在主视图帧边界快照。旧 `-PcmiNativeSkyMask`／`cmi.nativeSkyMask` 资格探测开关已移除；R5 文档中的该开关仅描述历史构建。

| 路径 | 天空裁剪 | 整体剔除 |
| --- | --- | --- |
| Complementary | 同帧 Julia 掩码裁剪末级片元，要求原尺寸及已核对的 viewport／资源契约 | 主视锥；认证深度、独立 mipmap 资源及格式满足时同帧深度条件执行 |
| 无光影 | 保守屏幕包围矩形 scissor，包含像素保护圈；目标云继续使用原有 Julia 命中判断 | 主视锥及认证主场景深度 |
| 其他原生适配光影包 | 保留完整目标天空，记录暂不支持裁剪 | 主视锥；深度档暂回退主视锥 |

无光影首轮裁剪范围是包围矩形，保留其中多余天空像素，并未宣称逐像素裁掉全部无用片元。所有路径首次初始化、尺寸变化或不确定边界均优先保留可见；初始化完成后才允许深度条件执行。无光影、切包和资源重载的真实客户端行为仍待用户验收。

## 实现与正确性约束

`RiftVisibility` 按主帧、裂口身份、管线代次、尺寸和配置维护共享结果。包围体从 Julia 原始分段边界得出，包含生长缩放、弱透视仿射变形及原 shader 的远平面钳制。近裁面穿越、相机进入包围体、非有限投影等情况保留可见；不把远平面之外但被原 shader 钳回的形状误剔除。

整体投影和目标回放移到不透明场景完成后、主场景首次消费前：Iris `beginTranslucents` 入口，无光影 `AFTER_BLOCK_ENTITIES`。验证当前深度附件身份、viewport、深度映射及查询兼容性；不可靠时记录原因并回退。代理是保守屏幕矩形及最近可能深度，额外保护两像素。代理禁用颜色输出、scissor、stencil 和会缩小覆盖率的采样状态，恢复 MRT draw buffers、独立颜色写掩码及相关状态。

深度档用当帧 `ANY_SAMPLES_PASSED` 与 `GL_QUERY_WAIT` GPU 条件执行，不把历史遮挡结果用于隐藏当前帧，也不进行 CPU 查询结果读回。共享 R32F 标志由同一条件写入，主场景消费者先读该标志，避免跳过回放后采样旧天空。被剔除时 GPU 绘制、dispatch、清理和目标深度／历史／mipmap blit 均不执行；CPU 仍提交条件区间命令并维护资源和帧状态。这不暂停风暴模拟、伤害、传送或服务端状态。

只有条件执行时才使用 `RiftTargetTransfer` 的 framebuffer blit；其他路径保留原深度／历史复制及原生 mipmap。条件 mipmap 在独立、非整数目标资源上逐层 LINEAR blit，必要分配在使用前完成。GPU 条件执行覆盖 Draw、Clear、Blit、Dispatch，不能依赖其跳过 CopyTexSubImage 或 GenerateMipmap，参见 [OpenGL 4.6 规范](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)。剔除帧不推进 GPU 目标历史，再次可见时生成当帧输出；消费者同时受同帧资源就绪和 GPU 标志约束。

Complementary 末级包装保留当前命中区域的双线性邻域和完整导数 quad，同时保护 `colortex5` 末像素及相邻保护区域。消费目标天空时固定基础层四点 `texelFetch` 双线性过滤，避免 mipmap／各向异性扩大采样足迹。目标附件双 bank 初始化并保持与主场景隔离；裁剪外历史允许较旧，当前消费像素执行当帧计算。上游天空、原分辨率和云质量保留。

## 计算候选筛选

原实现原子压缩与网格链顺序差异继续保存在 R5 跟踪证据，未当作 fixture 错误强行修正。R6 的 `efficiencyRules` 模式不再要求逐位／压缩槽相同，先以稳定身份、有限状态、类型／材质、成员字段、fixture 死亡及反馈去重做规则冒烟，再测完整更新链。**这不足以批准完整战斗规则资格**：冒烟输出有独立标记，汇总器拒绝将其当作生产替换资格。

原始目录：`build/benchmarks/rift-r6-20261103`。65536 粒子、纯 MODEL 与混合 mode1/2，七种场景 starfall、helix、dome_gather、dome_attack、corpse、crowded、recovery；每单元预热 16、交错计时 32 样本，包含原准备、更新、网格、接触、脱嵌、导航、复制、屏障和发布。

| 候选 | 原始结果 | 决策 |
| --- | --- | --- |
| MODEL 调用内只读攻击上下文缓存 | compute-update 对 compute-aa；多数单元中位数退化约 0.4–1.4%，未证明稳定净收益 | 撤回 |
| 接触重复 owner 查询缓存 | compute-contacts、-2、-3，与三轮 compute-aa 独立进程匹配；14 单元没有任何一个连续三轮都超过相应 A/A 噪声，部分轮次退化 | 撤回 |
| 组合 | 两个独立候选均未通过收益筛选 | 不进入组合；不修改生产计算 shader |

`compute-screen-summary.json` 和 `owner-repeats.json` 保留配对差值、A/A 及尾部统计；数微秒波动不换算客户端 FPS。由于候选已因性能撤回，没有宣称它们通过多帧完整玩法、失败帧／同代重试等生产资格，也不发布规则尚未完整验收的计算改动。

## 自动验证与未覆盖范围

Gradle、Java、离屏 GPU 任务串行执行。最终构建及 1032 项 Java 测试通过；帧归因与更新效率 Python 共 19 项通过。新增测试覆盖等价偏航、未知数量、同帧父子计时不重复计算，以及同构建配置变化不能混入 A/A／A/B。

| 验证 | 结果／证据 |
| --- | --- |
| 生产可见性及传输 GPU | 71,575 项通过，final-proxy/rift-visibility.txt；完全遮挡、反复露出、局部矩形遮挡、左右一像素细缝、绘制和计算执行计数、深度24/32F、浮点／打包颜色、条件历史复制及完整 mipmap、MRT 隔离和状态恢复、深度不匹配等回退 |
| 裁剪包装 | 同一任务包含空／满／中间／边缘／细对角掩码、31/32/63 尺寸、关闭恢复、双线性邻域、quad 和全局历史像素检查 |
| 无光影主消费 GPU | final-consumer，34 项；GPU 标志抑制旧天空、关闭 guard、三纹理 sampler 状态恢复 |
| 天空与光影回放 GPU | final-regression，16,346＋53,397 项通过；包含 Sundial 非 Complementary 回放及状态恢复 |
| 实际 Complementary 源码 | actual-terminal/r6-actual-compile.txt；捕获的 402 项选项、哈希 2f845d3b8761，真实隔离末级 shader 编译／链接通过 |

早期失败尝试保留在 final-mrt* 等目录：修复 core profile 仅返回首项 polygon mode 时的错误回退，以及代理矩形范围问题；未删除失败记录或将其计作成功。主构建仍有既有 Fabric EnvType 和弃用 API 警告，无构建／测试错误。

**离屏没有重放真实世界、全部上游纹理、目标历史及云状态的完整 NativeSkyView 链，因此没有合格的目标天空完整链 A/A、A/B 性能成绩。** 以上只能证明组件行为、采样保护和实际末级源码可编译，不能证明实机无闪烁、黑块或主场景污染，不能把执行计数或简化片元加速当作整帧 FPS。实际快速转向、移动遮挡物、透明物、近裁面／进入包围体、重新可见、切包、重载及分辨率切换均交由用户验证。

## 用户下一轮：定向诊断

本轮先收集具体候选的组件与完整链成本，**尚不安排正式 20% 收益验收**。无需重做 FULL/OFF 实验。启动当前工作区：

```powershell
.\gradlew runClient --no-configuration-cache
```

保留 FULL 阴影、原分辨率、Complementary 捕获选项、风暴数量和攻击阶段，关闭自动节流、VSync 与限帧，确保窗口有焦点。不要向 `run/mods` 加入本项目整包以免重复加载。预热后保持固定相机／FOV，客户端命令：

```text
/cmi particle profile backend production
/cmi particle profile off
/cmi particle profile frames 60
```

每个固定配置至少采两个重复窗口，单窗至少 30 秒、1000 帧；两窗间复位场景、预热并等待捕获完成。分别采以下配置，以隔离裁剪、剔除及组合成本：

| 定向窗口 | riftSkyClipping | riftCulling |
| --- | --- | --- |
| 完整路径对照 | false | NONE |
| 仅裁剪 | true | NONE |
| 仅整体剔除 | false | FRUSTUM_DEPTH |
| 组合／新默认 | true | FRUSTUM_DEPTH |

先在此前固定可见场景采集；再对完全遮挡或视锥外场景做功能和定向采集，各配置必须在各自相同相机／遮挡条件下比较。不同朝向仅用于功能覆盖，不能相减得出优化收益。配置界面必须在窗口前关闭，捕获中改配置会标记无效；可单独用 FRUSTUM 检查深度回退。若出现错误消失、黑块或闪烁，保留日志并先用 false／NONE 复核。

提交捕获目录完整的 `frames-*.json`、逐帧／阶段／相机／数量／提交 CSV、新增 `*-rift-visibility.jsonl`、引用的选项文件及 `debug.log`。JSON 起止元数据和 JSONL 记录请求／应用配置、裂口身份、帧、代次、尺寸和深度路径／回退原因；GPU 遮挡结果明确标为 CPU 未知，CPU 提交次数不冒充实际 GPU 执行次数。

新增 `rift.visibility.prepare` 在主帧边界；`rift.visual.whole` 包含深度检测、Julia 投影和 `rift.target.whole`；后者包含 prepare/sky/depth-copy/deferred/clouds/composite/history-publish/restore。比较同帧互斥子项，不能重复相加父子计时；缺失／查询不完整保持未知。新分析器输出 begin 外余量及 visual 扣除深度和 target 后的余量。

得到完整链数据后才能决定保留／撤回候选并寻找剩余瓶颈。最终正式 FPS 测量与本轮诊断分开，使用冻结基线和候选、匹配测量模式、三轮独立进程的基线 A/A、候选 A/A 与交错 A/B，每窗至少 30 秒及 1000 帧；平均 FPS 每轮至少提升 20%、超过 A/A 波动，p95/p99 无稳定退化，并由用户确认画面及玩法。现阶段实际收益为**待用户验收**，不是 0%，也没有足够证据宣布 20% 不可达。
