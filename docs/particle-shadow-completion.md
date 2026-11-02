# 七档阴影补全：实现与验收矩阵

**补全计划尚未全部完成。** 已落地的代码、独立内核证据和未完成的生产链路分别列在下面。未知包或契约失效始终保留原生合并与接收，免接收失败不转自绘。没有启动 Minecraft；客户端画面与整帧验收属于用户。

## 生产行为

- 保留已有七档、Iris 基础规则、主体整体剔除和阴影顶点上下文。
- `StormShadowSnapshot` 在更新输入阶段冻结圆柱及候选代次；索引生成使用该快照，不再读取更新后的实时风暴边界。FULL／OFF／无光影不创建快照。
- BSL 10.1.1 固定源版本增加独立的原生前向免接收程序，覆盖共用程序中的 MODEL、持剑、羽毛和 LOD 分支。FULL 保留原程序；低档程序与原程序一起准备，切档不首次编译。免接收编译失败仅撤销这组程序。
- 已接入当前 `ShadowRenderTargets.getDepthTexture()` 的入口来源记录，时机在不透明地形之后、原生实体批次提交之前。没有读取之后才复制的 opaque-only 快照。
- `beginHand()` 接入观察边界，**尚未接通实际历史深度捕获和可信静态接收面标记**。生产深度上下文仍为空，三个深度档继续几何回退，无捕获／构建开销。
- 生产 `update.comp` 布局不变。真实链接边界验证活跃 SSBO、全部活跃 sampler/image 的类型与单元；详细诊断显示同一验证报告。正常提交不新增这些同步查询。

## 八包免接收契约

`ShadowReceiverContract` 固定匹配 `ShaderPackInventory` 的完整源哈希，随后检查有效预处理片元结构。修改后的包不沿用旧契约。不占用包内材质位，不向磁盘光影包写入补丁，不用发光或全亮替代免接收。

| 本地版本 | 已实现／证据 | 未完成项 |
|---|---|---|
| BSL 10.1.1 | 私有合并程序中替换 `GetShadow`、`GetSubsurfaceShadow`、`GetCloudShadow` 的可见性函数；保留 NoL 与原生混合、雾、编码。默认及六组阴影／云影／高级材质选项均执行原生片元输出测试：原生输出对场景深度／云噪声扰动有变化见证，候选保持免阴影，匹配无遮挡原生输出；改变法线仍保留方向响应。 | 实际生产各段、遮挡、deferred 后处理、更多选项组合及必要成本验收仍需补齐；片元隔离测试不等于整帧画面验证。 |
| Bliss 2.1.2 | 固定源识别、原生与合并程序链接 | composite1 的 deferred receiver 独立覆盖／深度通道与透明原生通道 |
| Complementary Unbound 5.9.3 | 固定源识别、原生与合并程序链接 | `mainLighting` 中方向项与可见性项的完整分离、相关选项／Voxy 分支 |
| iterationT 3.2.0 | 固定源识别、原生与合并程序链接 | deferred 及透明 receiver 原生适配 |
| Photon 1.4 | 固定源识别、原生与合并程序链接 | `d4_deferred_shading` 的场景、SSRT、云影与镜面项，以及独立 CMI 标记／透明通道 |
| Sildur Vibrant 2.02 Extreme | 固定源识别、原生与合并程序链接 | `funShadows` 内角度项与阴影可见性的分离、透明段验证 |
| Solas 3.8 | 固定源识别、原生与合并程序链接 | forward fake/cloud shadow 与 deferred1 屏幕接触阴影的配套通道；保留原生副作用 |
| Sundial Lite 1.2.0 | 固定源识别、原生与合并程序链接 | 原生 Gbuffer/deferred receiver 与透明段适配 |

后七包仍保留接收阴影，诊断报告各自缺口。**这些回退不算八包适配完成。** 尚未实现 deferred CMI 独立标记或透明贡献合成，也未用全屏遮罩绕开该要求。

## 原生深度内核

`ShadowDepthPyramid` 从原生深度直接做首级归约，不做全尺寸复制；使用独立非比较 sampler，不改变源纹理的比较参数。支持正向 max 和反向 min 深度，非有限源值变成不可拒绝的远深度。NPOT 构建和查询采用互逆整数分区，覆盖末行、末列和完整矩形。

`shadow_depth_test.glsl` 支持 identity、quartic、euclidean 的保守区间，避免仅投影畸变后八角点；加入滤波范围和深度裕量，越界、非有限、近面穿越保留候选。先阴影图、后历史图的查询顺序位于实例收集之前。深度内核通过显式候选构造启用，FULL 的收集内核编译时排除这些分支。

`ShadowHistoryGpu` 实现候选双 bank、GPU 重投影与可靠性过滤。必须输入可信静态接收面附件；当前／历史置信边缘、天空、深度不匹配和孔洞变成远深度。首帧只 bootstrap，不提供拒绝证据。`ShadowDepthHistory` 检查 session、选项代次、维度、尺寸、连续帧、投影，以及 8 格／15° 镜头阈值。失败候选不发布。历史允许短暂时序误差。

这些是实际可执行内核，但还缺以下生产资格：

1. 八包有效程序的深度方向、偏移上界、过滤范围和可观察副作用证明；识别畸变不等于完整契约。Log、Sundial、iterationT 等其余映射也未完成区间实现。
2. 生产静态接收面标记、实际 pre-hand 捕获与同代发布。隐藏测试使用合成可信标记，不能替代生产数据。
3. 当前图与历史图分别及组合的八包完整阴影绘制链测量。已有测量只包含层级构建和生产收集，不包含包内实际 raster、标记、透明通道与整帧收益。

因此深度阶段是**资格未完成**，没有用通用原型或独立收集成本将原生完整链路判为失败，也没有宣称已启用。

## 输入与布局独立候选

`UpdateFrameInputs` 实现 CPU 直传和对齐 buffer range，不做 GPU-copy。调用方的 ByteBuffer 位置、范围与位模式保持不变。`UpdateStorageLayout` 从同一描述生成四种更新域候选：合并输入 14 块、加成员 SSBO 15 块、加分配器 SSBO 15 块、完整布局 16 块。

候选验证包括按稳定身份比较更新位模式、真实并发分配／退役／失败恢复、成员列表和动画槽、风暴列表溢出与尾部哨兵。分配器转 SSBO 时单独切换 `memoryBarrierBuffer()`，保留动画 image 屏障；跨视图发布使用 storage/image/texture/buffer-update/command 屏障。接口描述保留动画读 TBO、碰撞纹理及单元 15 的离线导航 TBO。

输入候选目前只在隐藏验证所有者中直传 CPU 数据，**未接入生产 BakeMeta／玩家／伤害队列所有者和 damage_prepare 的 range 生命周期**。生产恢复、硬件后端、反馈与整链对照尚未全部接通。不能把两个更新域的结果当作完整更新链资格。

## 数据与逐候选决定

基线：`.local/particle-baselines/reference/shadow-completion-20261102`，2799 文件，包含 HEAD、原索引补丁、工作树补丁及 SHA-256；原索引未改动。上一轮 `build/shadow-quality` 原始证据保留。

新实验目录为 `build/shadow-completion/`。0–9 轮为探索和修正记录，失败记录保留；10–12 轮补入普通 MODEL 与普通 MODEL／风暴混合，最终汇总使用 `summary.json` 中记录的三次独立进程。每格分别有 64 对 A/A 和 64 对 A/B，CPU 提交与 GPU 查询分开记录。正常生产没有诊断读回或等待。

| 候选 | 当前决定 |
|---|---|
| 圆柱输入快照 | 已接入；增加同代输入约束，不改变人口数量或模拟。生产分组所有者的真实双 bank 验证中止提交、失配提交、关闭写入、过期快照及恢复发布；完整 Runtime 粒子池事务仍未全部覆盖。 |
| BSL 原生免接收 | 适配代码已接入，默认及六组选项的场景／云影输出扰动、方向响应测试通过；完整材质矩阵与必要成本验收未完成。 |
| 原生当前深度与历史深度 | 内核及失效测试通过；生产契约、标记和八包完整链路未完成，不启用。 |
| CPU 合并输入 | 有实际 CPU 直传实现和独立数据；生产所有者／完整链未接通，不发布。 |
| 成员 SSBO | 稳定动画槽、溢出和更新域对照通过；完整链资格未完成，不发布。 |
| 分配器 SSBO／完整布局 | 退化集中于分配器变更，保留失败证据，不替换生产布局。独立数据不冒充完整链路结论。 |
| 批量实例预留／窄资源快照 | 保持上一轮关闭决定；没有重新启用。 |

例如在修正屏障后的 7–9 轮，131,072 风暴成员的分配器 SSBO 更新域中位数约 `0.850 → 1.577 ms`，完整布局约 `0.850 → 1.572 ms`；单独输入／成员变更未出现相近幅度退化。原生层级构建＋收集在全隐藏测试中，1024／2048 图约 `0.034 → 0.091／0.132 ms`。这些都未包含绘制节省，**不作为原生深度整链收益结论**。

复现（顺序运行，GPU 空闲，不启动客户端）：

```powershell
foreach ($completionRound in 100,101,102) {
  .\gradlew.bat -I scripts/particles/validation.init.gradle validateNativeShadowDepthGpu validateUpdateInputsGpu "-PshadowRound=$completionRound" --no-configuration-cache
}
python scripts/particles/summarize_shadow_completion.py 100
.\gradlew.bat test check build -I scripts/particles/validation.init.gradle validateModelMaterialGpu validateModelShadowGpu validateModelShadowIrisGpu validateModelPopulationGpu validateParticleProgramsGpu --no-configuration-cache
python scripts/particles/package_shadow_completion.py
```

复现请使用新轮次目录，保留原数据；汇总脚本参数为首轮编号。安装候选及证据包使用 `shadow-completion-candidate` 名称，不覆盖上一轮交付。

最终本机自动化记录：`test check build` 成功，JUnit 1,027 项、0 失败／错误／跳过；原生深度内核 33,013 项，CPU 直传／布局候选 8,935,990 项，八包材质入口 1,468 项，含生产分组双 bank 的人口索引回归 1,099 项。程序生命周期 GPU 回归 694 项通过，其中失败重载日志来自预期的失败注入。深度及输入性能汇总继续固定使用 10–12 轮；后续 13 轮只复核最终深度边界修正，不替换三轮资格数据。

安装候选为 `build/distributions/createmanaindustry-0.2.7-shadow-completion-candidate-20261102.jar`；同目录 `shadow-completion-candidate-20261102-results.zip` 收集第一方源码、基线状态、逐轮样本、失败记录、逐包契约状态及 JUnit XML。`shadow-completion-candidate-20261102-provenance.json` 给出安装包与源码哈希，并明确标记八包免接收未完成、生产深度未获资格、生产布局未替换、未运行客户端。

## 用户实机清单

当前安装候选可检查 FULL 的实例／动画／持剑、七档切换、圆柱完全隐藏及部分可见、屏幕外投影者、跨界持剑、离群攻击者、尸体和普通 MODEL 混合，以及 BSL 的主体／持剑／羽毛／两级 LOD／透明段、遮挡和雾。未知／修改后光影包应保持原生接收并说明原因。

后七包免接收、原生深度启用、历史显露误差和八包完整性能验收在代码缺口补齐后才能提交实机验收。不要把当前几何回退或链接成功视作这些项目已通过。
