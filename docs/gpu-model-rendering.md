# MODEL 提交、阴影与交互

状态：已实现；真实光影包、快速转向、重载及攻击反馈仍需游戏内验收。本文保留当前契约，历史测量见 [阴影成本](benchmarks/model-current-frame-shadow-2026-10-09/summary.md) 和 [活动索引成本](benchmarks/model-population-hit-fix-2026-10-10/summary.md)。

## 帧生命周期与状态

`ParticleLevelFrameMixin` 包围整个 `GameRenderer.renderLevel`，在主相机的内部 `LevelRenderer.renderLevel` 调用点捕获 Camera、DeltaTracker、view/projection。帧 scope 持续到 `AFTER_LEVEL`，最外层 finally 才清理；NeoForge 在内部调用返回后派发晚阶段事件，不能在那里提前结束 scope。令牌只授予主调用，嵌套与辅助视角不能重复计算。

Iris `renderShadows` HEAD 在阴影相机安装前调用 `prepareCurrentFrame`，覆盖 MODEL-only 和新出生粒子。每令牌只准备一次，`AFTER_SKY` 复用；无启用光影集成时由 `AFTER_SKY` 准备。失败不在同帧重试，包裹与 MODEL 阴影跳过该帧；成功阴影使用当前 generation，age=0。`AFTER_LEVEL` 完成剩余绘制与计时。

提前 compute 保存并恢复 program、VAO/element、SSBO 0–31 的非零 range、indirect/copy buffer、纹理单元 0–15 的 2D/3D/TBO、RenderSystem shader texture、active texture 与 unpack PBO/布局。边界内初始化从纹理单元 0 开始；MODEL 初始化及绘制另恢复 blend/depth/cull、Iris alpha test 和 tessellation 状态。

每个普通材质 pass 显式绑定自己的当前池、emitter、排序、VAO 和 commands。包裹光照会覆盖 SSBO 7，不能依赖整个 draw 开头的单次绑定。每 compute 帧清除旧 MODEL/package 绘制仲裁；Hex 绘制结束清理 program/VAO。

## 同代 MODEL/STORM 索引

两个 population bank 跟随两个 pool bank。32 B header 保存 MODEL/STORM 数、精确总 live、有效标记、64-bit generation 和列表容量；payload 保存 pool slot。`keygen` 利用已有全池扫描，在主视锥裁剪前记录完整 MODEL 和 Storm 子集，包括死亡动画中的尸体；每实际提交帧执行，新生对象当帧可见。

`reset` 只清空写 bank，`prepare_dispatch` 整理 metadata/indirect args，池成功 swap 后才发布 Java generation。`gridbuild` 读上一成功提交池；hit、shadow、stormpos、wavecontact 使用所读池的同代索引。generation/live 不匹配、容量溢出或全部为目标角色时回退精确 live 前缀扫描，不能消费部分列表。

MODEL/STORM 列表容量最多 262,144/131,072，超额影响优化路径；默认 200 万池容量的两 bank 共 3,145,792 B。没有复制模拟记录、同步下载数量或增加 fence 等待。hit/stormpos/wavecontact 保留各自尸体过滤，shadow 保留死亡动画；carrier、主排序及主/阴影数据独立。

## 独立阴影

`ModelShadowGpu` 消费当前已提交池、精确 GPU counter 和同代活动索引。三个 compute pass 完成 command reset/indirect dispatch、MODEL/carrier 筛选以及 metadata/body/carrier/ghost commands。主视锥 permutation 和透明排序不参与阴影列表。

实例 buffer 为 `(capacity + CARRIER_CAP + 1) × 8` B，command/dispatch 为 156 B。body/ghost 共用列表，carrier 使用共享 `cmiModelCarrier` 谓词并钳制计数。主池、emitter、counter、排序和 carrier 保持只读。

MODEL 与包裹共享 `PackageShadowPolicy.copy` 的 Iris Camera/实体阴影视锥：支持 13 平面、安全区、距离盒、NonCulling/CullEverything；未知类型跳过并报告类名。MODEL 使用共用的 `cmiModelSize`、life easing、size multiplier 和 Storm identity 特例；悦灵原生宽高乘 `2 × size / MODEL_ABOVE_FEET`，足部 AABB inflate(0.5)，再执行实体距离和阴影裁剪。

`cmi_ShadowPass` 仅绕过阴影的 fade-distance 早退，主画面淡出保留。使用当前姿态/动画时间和 Iris 阴影矩阵，保留实体阴影开关、alpha test、翅膀/披风与持有物；阴影不能设置主画面已绘制标志。

## 精确拾取与延迟点击

hit 先按精确非负 float 距离规约，再对完全相同距离规约 slot，capture 最后生成原有打包 key。不能让 1/32 格量化参与最近对象选择。两遍都消费 MODEL 工作集。

异步 snapshot 携带不可变 eye/look/reach。大幅转向/移位或超过四个提交代的结果不用于交互；小幅移动仍由下一次 GPU 点击验证保护。本地伤害/暴击效果保存 stable token，在当前 MODEL 集合解析；token 消失就丢弃，不能回退旧 slot。Storm 继续使用 member map。

点击冻结 token、member 分类和 HP。64 B 内部伤害条目携带 payload/token/flags、点击射线与验证状态，不改网络格式。`damage_prepare` 在下一次 update 前用同代池/索引重算精确最近可攻击 MODEL，射程受真实方块/原生目标距离限制；只有最近对象仍为该非 Storm token 才批准伤害。

旧 token 退役、移动或目标改变时取消这次伤害，不能转给新目标。批准记录随 GL 命令流供 combat emission 使用；拒绝不生成初次攻击效果，后续 tracking 仍按 token/hurt 跟随。真实实体优先；零方向射线显式处理平行 slab。生产没有同步读回目标池或等待 GPU 重新拾取，Storm 服务端权威仍按 member key 处理。

## 验证与入口

```powershell
python scripts/benchmarks/run.py validateHexGpu validateModelPopulationGpu validateModelShadowGpu validateModelShadowIrisGpu --offline
python scripts/benchmarks/run.py validateModelPopulationGpu -PmodelPopulationBenchmark --offline
python scripts/benchmarks/run.py validateModelShadowGpu -PmodelShadowBenchmark --offline
```

自动回归覆盖同代发布/失败保留、容量溢出回退、尸体/非连续 slot、阴影列表与主列表隔离、当前尺寸/13 平面、非零 SSBO range、各材质实际像素、相近距离目标、旧 token/新射线拒绝和稳定身份效果。运行范围与限制见 [测试入口](testing.md)，当前数量读取本次输出。

游戏内复验包括 MODEL-only/混合与新生、快速移动转向、暂停、F3+T/光影切换、实体阴影关闭、合并 fallback；Storm 活跃时相邻 MODEL 的扣血、闪白、击退、血粒子和暴击星应属于同一目标，真实实体/墙遮挡仍有效。

入口：`ParticleLevelFrameMixin`、`ParticleBuffers`、`ModelShadowGpu`、`PackageShadowPolicy`；shader 的 `keygen/reset/prepare_dispatch`、`chunks/population_read/write.glsl`、`gridbuild/hit/stormpos/wavecontact`；攻击路径的 `ParticleHitRay`、`ParticleReadbacks`、`damage_prepare.comp`、`chunks/model_ray.glsl`、`CMIParticleEngine` 和 `emit.comp`。
