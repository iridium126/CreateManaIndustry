# G-self / Iris MODEL 粒子接入

状态：🟡 M1/M2 实现和 `compileJava` 已通过；Photon/其他 shaderpack 的并排视觉矩阵待游戏内验证。

## 1. 目标与范围

G-self 指“项目自有 GPU MODEL 粒子进入 shaderpack 的实体语义”。当前只接入 `MODEL`（Allay 与 held item），不把 `ADDITIVE`、`ALPHA`、`OPAQUE` 精灵迁入 shaderpack entity pass。普通无 shaderpack 路径仍完全由项目自有 shader 绘制。

这样划分是因为 MODEL 需要 shaderpack 的实体光照、雾、色调映射和阴影语义，而精灵的混合/排序已经由本项目粒子 pass 定义。

## 2. 当前管线

```text
AFTER_SKY: CMI compute 更新/剔除/排序，提交当前 generation
    -> Iris entity hook：消费 MODEL 分区
    -> ShaderPackProgramCompiler：解析实体 program/fallback 并合并 CMI vertex
    -> cutout + ghost merged draw
AFTER_LEVEL: 无 shaderpack 或合并失败时的项目自有 MODEL draw
shadow track: 使用前一 generation 的 MODEL shadow draw
```

`CMIPackEntityMergeHook` 负责实体轨道和 shadow 轨道；`ParticleVertexInjector` 注入 CMI 的实例/TBO/姿态访问；`MixinProgramSamplers` 固定合并程序的 sampler 单元；`MixinIrisShadowRenderer` 遵守 shaderpack 的 entity-shadow 开关。

## 3. 合并契约

- 合并源以 shaderpack 解析出的 `gbuffers_entities` 及其 fallback 为基准，不能假定某个单一 program 永远存在。
- CMI 顶点阶段从 particle pool 的 MODEL 分区取实例，并调用 `allay_pose.glsl`/模型几何约定。
- cutout 变体保留实体 pass 的 alpha/depth 指令；ghost 变体只在项目约定的透明模型段使用固定覆盖。
- 受击 overlay 只有在目标 program 声明并实际使用 `entityColor` 时才注入；未使用该 uniform 的 pack 保持原行为。
- shadow 使用单独合并程序和正确的 alpha-test；shaderpack 禁止 entity shadow 时不绘制 MODEL shadow。
- 每次重编译都会使旧 program id、uniform cache 和 generation 关联失效，必须完整刷新。

## 4. 时序与 fallback

Iris 的 shadow track 早于 `AFTER_SKY`，因此 shadow 读取上一 generation；entity gbuffer hook 在当前 compute commit 之后运行。不要通过重复执行 compute 或把 sprites 提前绘制来“修正”这个差异。

以下情况必须回退到项目自有 MODEL pass，并保留错误状态：缺少 Iris、shaderpack program/fallback 不存在、GL compile/link 失败、sampler/attribute 不兼容、资源重载中或 shaderpack 明确关闭实体路径。回退不能清空粒子池或改变粒子生命周期。

## 5. 配置与诊断

`particles.shaderPackIntegration=false` 时禁用合并；`/cmip shaderpack status` 应显示 config、path、depth、shadow 和 permutation 状态及最近一次 fallback 原因。配置关闭只影响 MODEL 的 shaderpack 接入，不影响项目自有粒子 draw。

## 6. 验证矩阵

- 无 Iris/无 shaderpack：MODEL 只绘制一次。
- shaderpack 有/无 `gbuffers_entities`、有/无 entityColor、不同 alphaTest/blend：程序都能 fallback 或按声明绘制。
- entity shadow 开/关：Allay body/wing/held item 的阴影与原版实体规则一致。
- 相机移动、透明 MODEL、hurt overlay、资源重载和 shaderpack 切换：无旧 generation、旧 program 或 sampler 污染。
- `MODEL`、`ADDITIVE`、`ALPHA` 同时存在：MODEL 只由 entity hook 消费一次，其他材质仍在 AFTER_LEVEL 正常出现。

## 7. 代码入口

`client/particles/shaderpack/{ShaderPackProgramCompiler,ParticleVertexInjector,CMIPackEntityMergeHook}`；Iris mixin 位于 `mixin/irisveil/`；GPU 主流程和 generation 定义见 [particle-engine-dev.md](particle-engine-dev.md)。
