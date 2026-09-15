# Allay Storm AI 与攻击规则

状态：🟡 服务端增长、波次选择、路径规划和伤害验证已实现；平衡性、复杂地形和多人实战仍需游戏内验证。

## 1. 设计边界

Allay Storm 是“群体生命条 + GPU 群体运动”，不是生成大量实体。服务端只维护可持久化且必须一致的状态；客户端 GPU 负责每个 member 的轨道、分离力和姿态。人口减少就是 boss 的生命反馈，dead index 不复活。

## 2. 生成与增长

`AllayStormData.create` 在创建时冻结 seed、初始 count、最终 count、growth rate、创建时间和 phase law。count 在服务端 tick 增长到 `finalCount`，增长与玩家是否在范围内无关；新成员使用递增 index，并在可见包络外生成后飞入轨道。

半径由 `sqrt(count) / 8` 推导并限制在 `[2, 64]`；角速度按当前半径推导，旋向由 seed 决定。增长期的 phase 使用闭式增长积分，不能简单用“当前角速度 × 总时间”，否则半径变化时会造成群体跳变。

## 3. 波次状态机

每个玩家独立计时。一次发射必须同时满足：storm 激活、目标在 chase range、目标处于开放天空、当前运行波次少于 4。波次成员通过 `(stormSeed, memberIdx, waveSeed)` 的确定性 hash 按 alive population fraction 选出，并受 `stormWaveMaxSize` 限制。

```text
ELIGIBLE -> LAUNCH -> CORRIDOR -> DIVE -> CONTACT/EXPIRE
             ^                         |
             +---- cooldown -----------+
```

波次只改变被选 member 的临时运动目标，不改变成员的持久化 identity 或 HP。波次结束后成员回到主 vortex 轨道。

## 4. 路径规划

`AllayStormWaves` 在服务端以 3 blocks 网格做有限 A* 搜索，使用目标和起点周围 padding；每个搜索有 expansion 上限，成功路径再用 RDP 简化为最多 6 个 waypoint。阻挡检测考虑 divers 的约 3×3 footprint，并允许目标列作为终点例外。搜索失败退化为两点直线，客户端仍使用服务端给出的 corridor 参数。

路径规划的职责是绕开地形，不是保证任意高度都可达；chase Y 过低时仍可能造成视觉穿插，应通过配置和实战矩阵验证。

## 5. 接触和伤害

本地玩家客户端从 GPU `wavecontact`/hit readback 报告候选接触；服务端校验 member 仍 alive、波次/目标归属、距离、冷却和有效 tick，然后以 `storm_peck` damage type 调用 `player.hurt`。原版护甲、吸收、totem 和 10 tick 无敌帧继续生效；并发 divers 不能绕过无敌帧造成叠加爆发。

服务端确认伤害后更新 sparse HP 或 dead set，并广播 `ClientboundStormDamagePacket`。攻击者本地可即时显示战斗粒子，其他客户端按 member identity 重放同一视觉。

## 6. 配置

| 键 | 含义 |
| --- | --- |
| `stormGrowthPerSecond` | 每秒生成量，0 表示不增长 |
| `stormWaveInterval` | 每玩家波次冷却秒数 |
| `stormWaveFraction` | alive population 的选取比例 |
| `stormWaveMaxSize` | 单波次成员上限 |
| `stormWaveDamage` | 每次有效接触伤害 |
| `stormWaveRange` | 玩家目标范围 |
| `stormChaseY` | 追击中心固定高度 |

默认值和范围只以 `ServerConfig` 为准；命令 count 只设置新 storm 的初始 population，半径和角速度自动推导。

## 7. 验证

- 空旷地形、岛屿边缘、洞穴/屋顶和复杂障碍物中的 corridor。
- 单人/多人同时触发，分别观察每人 cooldown 和最多 4 个并行波次。
- 伤害无效、重复报告、越界 index、dead member 报告和断线 authority。
- growth 中途保存/重启，确认 phase、count、dead/HP 不回滚。
- GPU readback 延迟或不可用时，服务端不会把客户端候选当作已确认伤害。

实现入口：`content/allaystorm/AllayStormData`、`AllayStormManager`、`AllayStormWaves`、`AllayStormCommand`，网络包位于 `network/*Storm*`。同步协议见 [allay-storm-sync.md](allay-storm-sync.md)。
