# 同代 MODEL/STORM 活动索引与攻击目标修复（2026-10-10）

已实现活动索引并接入查询，修复本地 MODEL 命中与受击效果的三种错配路径。未拆模拟池、未改变运动算法；所有 MODEL 的 HP、回血、受击、尸体、永久生命周期、死亡 poof 和 Storm 网络协议保持原语义。实机复验由用户完成，自动夹具不能替代用户场景的最终验收。

## 活动索引

两个 population bank 跟随两个 pool bank。32 B header 保存完整 MODEL 数、STORM 数、精确总 live、有效标记、64-bit generation、两组列表容量；payload 分别保存 pool slot。

- `keygen` 在主视锥裁剪之前记录完整 MODEL，包括死亡动画中的尸体；STORM 为其中 header 开启 Storm 的子集，不提前剔除尸体。
- 利用已经存在的 keygen 扫描，未新增逐帧全池分类 kernel。keygen 在每个实际提交帧执行，不受滞后 CPU 存活估计控制；新出生 MODEL 当帧进入索引。
- `reset` 只清空写 bank。`prepare_dispatch` 完成 metadata，并整理 MODEL/STORM 的 indirect dispatch；池成功 swap 后才发布 Java generation。失败/未提交 bank 不暴露索引。
- `gridbuild` 使用上一成功提交池的 STORM 索引；hit、shadow、stormpos、wavecontact 使用所读池的同代索引。主视锥列表不参与这些查询。
- generation/live 不匹配、容量溢出或全部为目标角色时，走原精确 live 前缀扫描；不使用部分列表，不丢对象。MODEL 索引容量最多 262,144，STORM 最多 131,072，超额只影响优化路径。
- 默认 200 万容量时，两 bank 占 3,145,792 B（约 3 MiB），没有复制模拟记录。另增加 24 B dispatch args、512 B 本地攻击批准记录及 2 KiB 伤害队列；生产没有增加数量同步下载或 fence 等待。

消费者仍保留自己的过滤：hit/stormpos/wavecontact 排除不适用的尸体，shadow 保留死亡动画；grid 的尸体成员行为与原路径一致。carrier、主排序和主/阴影数据继续独立。

## 为什么会攻击 A，却选中/表现为 B

源码检查与实际 GPU 夹具确认了三个问题：

1. **距离量化参与选择。** 原 hit 将距离量化为 1/32 格，再与 pool slot 打包后 atomicMin。A、B 的 AABB 入口距离相差 0.01 格时可落入同一桶，较远但 slot 较小的 B 获胜。Storm 下压缩/追加会改变 slot，因此目标可能随池顺序变化。现在先按精确的非负 float 距离规约，再对完全相同的距离规约 slot；最后 capture 才生成原有打包 key。两遍都只消费 MODEL 工作集。
2. **异步结果套到新射线。** 命中读回此前不携带查询射线，CPU 用当前 camera 重建旧命中位置。现在 snapshot 携带不可变 eye/look/reach；大幅转向/移位、超过四个提交代的结果不用于交互。允许小幅位移以减少常规移动时的闪断，并通过下一条 GPU 验证防止剩余歧义伤到其他对象。
3. **本地效果仍使用旧 pool slot。** 本地暴击星/伤害粒子曾保存 `sourceIdx`，下一帧及 0.15 秒 tracking 内按旧 slot 取来源。现在保存完整 stable token，通过当前读池 MODEL 集合解析；token 不存在就丢弃效果，不回退旧 slot。Storm 仍使用原 member map。

这些问题不等于原稳定 token 伤害匹配器会按旧 slot 扣血：它已有 token 保护。修复补齐的是最近目标选择、延迟输入验证，以及仍保留 slot 寻址的效果路径。

## 本地攻击的提交验证

点击时冻结命中 token、member 分类和 HP，避免中途读取变化的 hit 字段。内部伤害条目扩为 64 B：原 payload/token/flags，加上点击 eye/look、reach、ray 标记和验证状态。这个布局仅在客户端 GPU 内部使用，不改 Storm 网络包。

`damage_prepare` 在下一次 update 前，读取同代已提交池和 MODEL 索引，用点击射线重新找精确最近的可攻击 MODEL；只有最近对象仍是该非 Storm stable token 才允许伤害。检查范围受真实方块/原生目标距离限制；不会把射线长度误夹到代理实体的量化距离。

若对象已退役、移动导致最近对象改变、旧 snapshot 属于 B 而点击射线现在指向 A，则拒绝旧 token 的这次本地伤害，**不会把该伤害转移给另一个对象**。下一份有效查询可以选中当前对象；快速移动/重负载下可能有保守取消，这是避免误伤的边界，不应声称异步输入与当前画面完全同步。

批准记录随 GL 命令流提供给 combat emission。被拒绝的初次攻击不生成对应伤害/暴击效果；后续 tracking 仍按同一 token 和 hurt 状态跟随。普通真实实体优先于 GPU 代理，不能因旧 GPU key 而抢走原生实体攻击。零方向射线显式处理平行 slab，避免边界上的 0/0。

所有相关操作仍在同一渲染 context；没有同步读取目标池、没有 glFinish 或等待 GPU 重新拾取。Storm 的服务器权威伤害仍按 member key 处理，不经过本地 token 重解释。

## 自动回归

- Java：573 项、零失败/错误/跳过；新增旧射线不能用于新相机的纯 JVM 检查。
- 全普通粒子 GPU、包裹 14,675,334、原生 Iris 包裹 26,733、原生 Iris MODEL 4,133,200、共享 Hex/Iris 回归通过。
- 专项生产 shader 夹具覆盖 0/1/63/64/65/4,097、未剔除列表、尸体、越界哨兵、indirect 数量、容量溢出与错误 generation 回退。
- 确认 STORM 索引只用于 Storm，非连续 slot 网格不混入普通 MODEL，grid 保留尸体，而位置读回过滤尸体。
- 同一距离桶内，slot 1 的较近 A 必须胜过 slot 0 的较远 B；capture 必须得到 A 的 token。
- 旧 B token 配新 A 射线被拒绝，A token 配 A 射线只扣 A 的 HP；本地效果按 token 定位到 A，被拒绝 token 不产生效果。
- 成功/失败提交、pool swap 但尚未发布的 generation、旧 bank 保留由 GPU 生命周期夹具验证。

专项及完整日志见 [benchmarks/model-population-hit-fix-2026-10-10](benchmarks/model-population-hit-fix-2026-10-10/)。

## 计入维护成本的基准

RTX 4070 Laptop GPU、NVIDIA 581.15，隐藏 OpenGL 4.5 context。每个模式三轮，每轮至少 30 次且 1 秒预热，随后 120 样本；表为三轮 P95 的中位数，单位 ms。

双方使用相同提交池、相同可见 MODEL。测试区间包括相同 fixture reset/精确 live 设置、实际 keygen、dispatch 整理、实际 MODEL 阴影准备；对照关闭 population 写入和读取，当前模式包含实际角色维护。普通对象为 ADDITIVE，MODEL 均可见、无 carrier。没有测完整运动、最终阴影着色、真实 Minecraft/Iris、hit 两遍或点击验证的整帧成本。

| 总存活 / MODEL | 全扫描 GPU P95 | 活动索引 GPU P95 | 全扫描 CPU P95 | 活动索引 CPU P95 |
| ---: | ---: | ---: | ---: | ---: |
| 100,000 / 100 | 0.030720 | 0.036864 | 0.0376 | 0.0671 |
| 2,000,000 / 100 | 1.098752 | 0.585728 | 0.0405 | 0.0369 |
| 2,000,000 / 10,000 | 1.103872 | 0.592896 | 0.0409 | 0.0369 |
| 2,000,000 / 2,000,000 | 1.209344 | 1.217536 | 0.0388 | 0.0391 |

大混合池稀少 MODEL 的组合区间少约 0.51 ms（约 46%）；10 万档有约 0.006 ms 额外 GPU 固定成本，CPU 存在明显调度波动；全 MODEL 的维护开销约 0.7%。密集查询回退并不消除角色计数维护，所以不能称所有情况都提速。其他 MODEL/STORM 消费者的整个运行时累计收益尚未单独量化。

CPU 指标是 wall submit，夹具有 GL 错误检查/固定绑定；GPU elapsed 也可能包含 CPU 提交空档。不能将这些数字直接解释为 FPS 改善。原始逐次样本完整保存。

```powershell
.\gradlew.bat -I scripts/particles/validation.init.gradle build validateHexGpu validateModelPopulationGpu validatePackageGpu validatePackageIrisGpu validateModelShadowIrisGpu validateHexSharedIrisGpu --no-parallel --max-workers=1
.\gradlew.bat -I scripts/particles/validation.init.gradle validateModelPopulationGpu -PmodelPopulationBenchmark --no-parallel --max-workers=1
```

## 用户实机复验

替换最终 JAR 并重启。重点复验：Storm 活跃时两个相邻/重叠的调试 MODEL；攻击 A 的扣血、闪白、击退、血粒子和暴击星均属于 A；快速转向后旧目标不再受击；移动目标/持续点击；Storm 生成/增长/停止期间本地 MODEL；本地 MODEL 死亡和 poof；真实实体/墙遮挡；暂停、重载及 shaderpack 切换。HP/回血/永久生存应维持原行为。

## 入口

`ParticleBuffers` 的 population bank/dispatch、`keygen/reset/prepare_dispatch`、`chunks/population_read/write.glsl`、`ModelShadowGpu`、`gridbuild/hit/stormpos/wavecontact`；攻击路径为 `ParticleHitRay`、`ParticleReadbacks`、`damage_prepare.comp`、`chunks/model_ray.glsl`、`CMIParticleEngine` 与 `emit.comp` 的 local token resolver。
