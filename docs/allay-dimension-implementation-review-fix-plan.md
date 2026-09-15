# ALLVR 实现评审清单

状态：⚠ 以下是尚未关闭的风险清单，不是已完成实现的说明。关闭条目必须同时有代码、针对性测试和游戏内复现结果；修复后在提交中引用编号。

## P0：会造成错误数据或不可用渲染

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F01 | LOD 单材质压缩丢掉空气占用 | 编解码保留 palette/index 的真实占用，往返测试覆盖 `[air, stone]` |
| F04 | Tier C draw 命令/顶点属性可能为零或未启用 | 最小可见 mesh 在 GPU capture 中提交正确 instance/vertex |
| F22 | 孤立 L1–L3 几何无法建立可达祖先链 | 任意粗层都能从可见根建立完整父链，缺父节点不显示 |

## P1：正确性、生命周期和兼容性

### LOD 编解码与渲染描述

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F02 | LOD bit width 编解码契约不一致 | bit width、padding、边界值和旧数据都有 round-trip 测试 |
| F03 | payload 验证不足，128 KiB 上限未覆盖合法声明域 | 长度、索引、light flag、尾随字节和 packet 上限统一校验 |
| F05 | Tier C 依赖 Tier B 初始化，回退存在提前返回 | 各 capability 独立初始化，任一失败仍有可见 fallback |
| F06 | 没有 descriptor 时模型、流体和 BE 一并跳过 | fallback 分支按对象类型独立工作 |
| F07 | descriptor 认证不足，非 solid 可能双绘 | descriptor 明确绑定 render type，单一 owner 绘制 |
| F08 | 含水方块的模型与流体被当成互斥分支 | 模型和流体按原版语义分别进入对应 pass |
| F09 | `renderSingleBlock` 不是世界模型编译路径 | 使用真实 world/model data 编译并覆盖 BE/邻居依赖 |
| F10 | 透明、阴影、破坏覆盖没有真实 pass 管线 | 每个 pass 的深度、混合、阴影和 overlay 均有验收用例 |
| F11 | Iris/GL 失败路径可能选错 shader，状态恢复不完整 | 失败后状态、FBO、program、sampler 全部恢复且可重试 |

### 异步、光照和覆盖范围

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F12 | 近景异步结果存在同 key 重建 ABA | build identity/revision 不匹配的结果永不发布 |
| F13 | deferred mesh 没有 revision，可能覆盖新结果 | mesh 上传检查 cell/resource revision |
| F14 | resource reload 复用 ID，旧 mesh 与新材质表不匹配 | reload 使旧结果全部失效并重建 |
| F15 | identity、调度状态持续增长，关闭不完整 | session/identity/token/worker/GL 资源可观测且可回收 |
| F16 | worker 快照、重试、上传预算未覆盖实际成本 | 每类预算受限，失败留在可重试队列而非静默丢失 |
| F17 | 近景光照穿墙，边界失效不完整 | block/sky light、遮挡、边界邻居和 dirty 传播有测试 |
| F18 | 近景垂直覆盖与 LOD 排除区矛盾 | 每个 Y 区间只有一个 owner，边界无洞无双绘 |
| F19 | 分带计算不一致且没有 readiness handoff | server/client/render 使用同一分带，ready 交接可观测 |

### Voxy/Lod 与协议

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F20 | Voxy 普通 forget 的 key 编码错误 | 绝对 key 与 virtual key 转换有独立测试 |
| F21 | Voxy section acquire/release 不平衡，leave 不释放 registry | 每次 acquire 有对应 release，换维度/关闭后 registry 为空 |
| F23 | forget 粗节点会掐断仍存活的细节点 | 父子 resident 引用关系正确，细节点仍可达 |
| F24 | Voxy rebase 只有阶段名，没有 epoch 闭环 | rebase、ingest、forget、camera、renderer 都检查同一 epoch |
| F25 | enqueue 被当成 resident，写失败/forget 背压被吞掉 | 状态机区分 queued/written/resident/failed，失败可重试 |
| F26 | requestId/generation 未真正参与客户端归属校验 | 旧响应不能消费新 pending，重进/forget 后仍安全 |
| F27 | 服务端 build 失败永久占用 pending | 失败结算并释放/重试 request，客户端有超时 |
| F28 | 编辑过的天然非 surface 区域永远不进入远景 | dirty 编辑可提升到远景候选并最终可见 |
| F29 | LOD 邻域依赖未参与 invalidation | 修改一个节点会失效所有依赖它的邻域/祖先 |
| F30 | Voxy 缺失/禁用/故障的 near-only 未端到端生效 | 三种状态都能稳定游玩且不残留远景资源 |
| F31 | 版本门禁晚于 mixin，ingest 门禁看错对象 | 未知 ABI 在注入前拒绝，按真实 level/对象判断 |

### Cube、方块实体与 tick

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F33 | `Cube.setBlock` 缺少 block 生命周期回调，同状态判断错误 | 对齐 LevelChunk 的 onRemove/onPlace/同状态语义 |
| F34 | BE 客户端同步和销毁缺失 | full/update/forget、ticker、渲染器和 unload 有完整通道 |
| F35 | random tick 和 Cube scheduled tick 尚未实现 | 明确 Cube 所有权、预算、保存和重启恢复 |

## P2：优化和维护性

| 编号 | 问题 | 关闭标准 |
| --- | --- | --- |
| F32 | Sodium 只取消 draw，没有停止空 shell 构建维护 | near-only/禁用路径不再构建无用 section |
| F36 | GPU 预算、handle 和可见性模块仅部分落地 | handle generation/释放、GPU overflow、visibility budget 有闭环 |

## 使用方式

- 新发现先归入现有编号，避免创建同义问题。
- 关闭顺序优先 F01/F04/F22，再处理 revision/lifecycle，最后处理性能。
- 渲染/协议问题的设计背景见 [ALLVR 总览](allay-dimension-dev.md)、[Sodium 路线](allay-dimension-sodium-rendering-parity-plan.md) 和 [Voxy 路线](allay-dimension-voxy-lod-integration-plan.md)。
