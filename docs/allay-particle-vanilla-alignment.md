# GPU 粒子与原版视觉对齐

状态：🟡 Allay MODEL、战斗粒子、held item 和 Hexcasting 主要语义已实现；原版逐帧截图/不同资源包/不同 shaderpack 的最终矩阵仍需实测。

## 1. 对齐范围

粒子替代只在本项目 GPU 引擎可用时生效；否则走原版。验收按以下字段逐项比较：

| 字段 | 要求 |
| --- | --- |
| spawn | 原点、初速度、随机 seed/roll、生成数量和 limiter |
| motion | tick 到连续时间的换算、drag、gravity、碰撞和地面行为 |
| lifetime | 移除时刻、死亡 poof/战斗链和跨帧边界 |
| appearance | atlas/frame、UV、颜色、alpha、size、billboard、fullbright |
| ordering | opaque/cutout、透明深度排序、additive 顺序 |
| lighting | 原版 block/sky light 采样或明确的 fullbright 语义 |
| model | Allay 姿态、动画、受击闪烁、手持物和死亡姿态 |

连续时间实现必须以原版 20 Hz tick 行为为基准，不可只凭视觉近似重写常量。

## 2. Allay MODEL

Allay 使用 `MODEL` 材质，以实例数据驱动 `allay_pose.glsl` 和 `AllayModelGeometry`。Java 侧保留实体类型、seed、生命状态、动画切换和手持物信息；GPU 侧计算位置、姿态、hurt/death 时间并提交模型 draw。

held item 由 `HeldItemGeometry` 生成几何。验证至少覆盖空手、主手/副手物品、不同材质 sword、物品变更和资源重载。

## 3. 精灵和战斗视觉

`OPAQUE`/`ALPHA`/`ADDITIVE` 各自使用原版对应的 alpha、深度和混合语义。战斗 preset 的 atlas/frame、crit、magic、heart 和 death poof 必须按原版时序重放；不能因为统一 GPU draw 而把透明粒子排序到 additive pass，或把不同材质混在同一 indirect command。

Hexcasting 的 `conjure_particle` 与 `ParticleSpray` 对齐规则集中在 [particle-engine-dev.md](particle-engine-dev.md)，本文件只负责总验收，不重复维护 Hex 常量。

## 4. shaderpack 范围

shaderpack 集成当前只承诺 `MODEL`：

- 无 shaderpack 时由项目自有 MODEL shader 绘制；
- 有 shaderpack 时通过 Iris hook/程序合并接入 entity 与 shadow 轨道；
- `ADDITIVE`、`ALPHA`、`OPAQUE` 保持项目自有 pass，不把 shaderpack 的 entity 逻辑强行套到精灵上；
- 合并、sampler 或 shadow 失败时回退，并保留可诊断状态。

因此“支持 shaderpack”不等于所有粒子都进入 shaderpack 的实体 pass。

## 5. 验收矩阵

| 场景 | 对比重点 |
| --- | --- |
| 单个 Allay / 多个 Allay | 尺寸、朝向、动画相位、受击闪烁 |
| Allay 手持物 | 手部挂点、旋转、UV/材质 |
| 战斗粒子 | 生成数量、frame、透明排序、死亡链 |
| Hex direct/spray | 颜色冻结、速度、六边形软边、摩擦/重力、生命周期 |
| shaderpack 开/关 | MODEL 深度、光照、阴影和 fallback |
| 相机近远移动 | fade、frustum、排序和 generation 时序 |
| 资源重载/换维度 | atlas、program、GPU pool 和旧实例清理 |

## 6. 失败判定

若差异来自原版随机 seed、资源包纹理或 shaderpack 自身，应记录输入和截图后再判断；若差异来自 spawn 参数、生命周期、材质 pass、相机时序或旧 generation，则视为实现缺陷。性能优化不得降低上述字段的可观察语义。
