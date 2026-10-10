# GPU Hex 环绕图案接管范围检查（2026-10-10）

> 本文描述实施前的审计快照。后续已实现世界、阴影、玩家预览与施法 UI 接管，当前行为和验收边界见 [实现与验证报告](gpu-hex-shared-takeover-2026-10-10.md)。

结论：当前并未完全接管。GPU 引擎只接管符合本帧准入条件的世界玩家环绕施法栈；普通第一人称自身的图案阴影和正常 UI 玩家模型环绕图案仍走 Hex 原生绘制。检查还发现：世界中已被接管玩家的原生图案阴影会被取消，而 GPU 端没有 Hex 阴影 pass。

本次为源码/依赖字节码检查，没有修改渲染行为，也没有进行游戏内视觉验证。已核对 `.refs` 与项目实际使用的 Hexcasting 1.21.1-0.12.0-devel-pre-53 JAR；实际 ClientRenderHelper 和 MixinPlayerRenderer 的签名、调用与本项目重定向相符。

## 路由

| 场景 | 当前是否由本项目 GPU 粒子引擎绘制 | 原因 |
| --- | --- | --- |
| 世界中的其他玩家环绕图案 | 条件满足时是 | 从 level.players 收集，成功接纳整个施法栈后以 UUID 记录 redirected，在 AFTER_LEVEL 统一绘制 |
| 第三人称自身环绕图案 | 条件满足时是 | 相机 detached 时不执行第一人称玩家排除；使用世界实体锚点 |
| 普通第一人称自身的光影阴影图案 | 否，保留 Hex 原生入口 | 非 detached、非睡眠的相机玩家在 prepare 中明确跳过，因而其 redirected 判定为 false。Iris 阴影渲染玩家时，PlayerRenderer 的 Hex mixin 仍调用原生 renderCastingStack |
| 正常背包/类似 Screen 的玩家模型环绕图案 | 否，保留 Hex 原生入口 | GUI 使用自己的 PoseStack/投影调用 EntityRenderDispatcher → PlayerRenderer。正常 GUI 在世界 AFTER_LEVEL 后绘制，此时 frameArmed=false，取消条件不成立 |
| 世界中已接管玩家的光影阴影图案 | 接管不完整，会缺失投影 | 通用取消入口没有判断 shadow pass，提前 compute 后 frameArmed=true、UUID 已被接管，原生阴影图案被取消；现有 shadow hook 仅绘制包裹/MODEL |
| 石板、卷轴、工具提示、绘图 UI 等其他 Hex 图案 | 否 | 当前取消点仅为玩家 renderCastingStack，未替换各自的 PatternRenderer/WorldlyPatternRenderHelpers |

第一人称自身一行针对正常本地相机、非睡眠、非 detached 情况；观战、更换相机实体和睡眠可能改变准入。原生路径是否实际投影，仍受 Iris 的实体/玩家阴影开关、剔除及 Hex 原生 shader 行为影响；这里确认的是接管入口归属。

## 关键证据

- `HexPatternRuntime.prepare`：只遍历 ClientLevel.players；排除第一人称相机玩家；容量/图案顶点上限采用整个玩家施法栈 fallback；只在 fits 后将 UUID 加入 redirected。
- `CMIParticleEngine.redirectsHexPatterns`：判断 particleEnabled、hexPatternRedirect、frameArmed、hexReady，以及 player.level/UUID。没有 PoseStack、shadow 或 GUI 上下文参数。
- `HexPatternRedirectMixin`：在所有 renderCastingStack 调用的 HEAD 按上述判断取消，而不是只取消世界主 pass。
- `DrawPipeline.drawHexPatterns`：在 AFTER_LEVEL 使用世界 Camera、view/projection、当前 pool 和 Hex buffers；没有 UI PoseStack 绘制入口。
- `model_shadow_collect.comp`：只接受 material∈[1.5,2.5)，HEX_PATTERN 的 material=4 不会被选中。两个 Iris shadow mixin 分别调用包裹和 MODEL，没有 Hex shadow draw。
- `.refs/HexMod/.../MixinPlayerRenderer.java` 与实际 JAR：PlayerRenderer.render HEAD 调用 ClientRenderHelper.renderCastingStack。
- `.refs/HexMod/.../ClientRenderHelper.kt` 与实际 JAR：原生入口用传入 PoseStack，在 CPU 生成 makeZappy/线段并提交；UI 和阴影共用这个入口。
- `.refs/Iris/.../ShadowRenderer.java`：实体或仅玩家阴影路径通过 invokeRenderEntity 渲染玩家，因而触发同一个 PlayerRenderer/Hex 入口。
- `.refs/neoforge-21.1.227/.../InventoryScreen.java`：UI 调用 EntityRenderDispatcher.render，并传入 GUI 的 pose；GameRenderer 的正常 Screen 阶段发生在世界 renderLevel 结束之后。

## 缺口

1. **已接管玩家漏投影。** 在 shaderpack 集成启用、当前提交成功、该玩家栈已准入且 Iris 将该玩家纳入实体/玩家阴影 pass 时，原生 Hex 调用会被取消，GPU 又没有对应阴影绘制。常见对象是其他玩家和第三人称自身；player-only 阴影开关也必须单独考虑。
2. **取消依据只有 UUID 与帧就绪。** 标准 GUI 因时序回到原生而正常，但如果别的 mod 在世界提交已就绪、尚未 AFTER_LEVEL 收尾时渲染同一玩家的 UI/离屏预览，入口也可能误取消，且世界锚点 GPU 绘制不能替代该预览 PoseStack。这是代码结构上的风险，尚未在具体 mod 中复现。
3. **“接管”仍有显式 fallback。** 配置关闭、未准备、shader 未就绪、容量或几何预算不足都保留整个玩家栈的原生绘制，不应描述为所有情况完全 GPU 接管。

要完整覆盖指定场景，需要明确 world/shadow/UI 的 pass 归属，加入独立 Hex 阴影列表/命令/矩阵与 UI PoseStack 绘制入口，并在相应 pass 的 GPU 提交成功后才取消该 pass 的原生入口。第一人称玩家可以只加入 shadow/UI 数据集，维持主画面不显示自身环绕图案的现有行为。短期恢复已准入玩家的阴影，则应先避免在尚无 GPU Hex shadow pass 的阴影上下文取消原生绘制。
