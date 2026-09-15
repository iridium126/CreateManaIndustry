# ALLVR × Voxy 远景接入路线

状态：📐 设计已冻结，完整接入未实施/未验收。代码中的 ingest、Y slab 和 mixin 是适配基础，不代表远景数据已经可靠显示。

## 1. 目标与所有权

Voxy 只负责远景几何和层级缓存；ALLVR 仍是 Cube 数据、方块状态和持久化的唯一来源。近景 section、远景 Voxy node、服务端 Cube 和 GPU 资源必须各自使用明确的 key 和生命周期。

远景不得替代近景：进入近景范围时由 Sodium/客户端 Cube cache 接管，离开时才允许 Voxy resident；切换必须有 readiness handoff，避免空洞、双绘或旧层覆盖新层。

## 2. Y slab 契约

Voxy 的 Y 坐标空间有限，因此采用以玩家附近高度为中心的 slab：

- L0 cell 边长为 32 blocks；一个 slab 覆盖 256 个 L0 cell，即 8192 blocks；
- virtual section Y 范围为 `[-256, 255]`；
- `AllvrVoxyYSlab` 统一提供 block Y、section Y、cell Y 和 camera Y 的转换；
- slab 切换使用 epoch，旧 slab 的 ingest、write、forget 结果不能发布到新 slab。

所有 Voxy 调用都必须通过 `AllvrVoxyYSlab` 转换，不能直接把绝对 Y 当成 virtual Y。普通 forget 的参数也必须明确是绝对 Cube/section key 还是 Voxy virtual key。

## 3. 当前组件

- `AllvrVoxyClientIngest`：从客户端 Cube/section snapshot 排队 ingest、处理 block change、切换 slab 和 renderer refresh。
- `AllvrVoxyYSlab`：slab 映射和范围判断。
- `VoxyApi_0215_1211`：版本绑定的 Voxy 操作封装。
- `mixin/voxy/*`：world identifier、viewport、top-level range、raw ingest 和普通 ingest 的适配点。

这些组件必须由 Voxy 存在且 ABI probe 通过时启用；缺失、禁用或故障时 near-only 路径要端到端生效。

## 4. 实施阶段

| 阶段 | 内容 | 通过条件 |
| --- | --- | --- |
| P0 | 版本/符号/注入点探针 | 不兼容版本不加载 mixin，不污染 vanilla 渲染 |
| P1 | slab 绑定与 epoch | 相机跨 slab 时旧任务全部失效，Y 映射可逆 |
| P2 | L0 ingest | 近景 Cube 的 section 能稳定转换成 Voxy 输入 |
| P3 | L1–L3 聚合 | 每个粗层有可达祖先链，编辑/删除能向上失效 |
| P4 | resident/forget | acquire/release、enqueue、write、forget 有 ack/失败回压 |
| P5 | 近远景切换 | 近景 ready 后 Voxy 让位，远景 ready 前不掐断旧几何 |
| P6 | 性能与恢复 | 队列、预热、资源重载、换维度和关闭不会泄漏线程/资源 |
| P7 | 发布 | 与 Sodium、无 Sodium、故障回退和多客户端场景通过手工矩阵 |

## 5. 设计禁区

- 不把“enqueue 成功”当作“resident 已完成”。
- 不把近景 forget 当成远景 forget；两种资源生命周期不同。
- 不用全局当前 `mc.level` 猜测 Voxy ingest 对象对应的维度。
- 不在缺少完整版本门禁时调用 Voxy 私有字段/方法。
- 不为远景复制另一份持久化格式；远景输入必须可由 Cube snapshot 重建。

## 6. 验收清单

新世界中沿 Y 轴跨越多个 slab，快速水平移动并反复进出近景范围；修改方块后观察 L0–L3 是否更新；重载资源、换维度、退出重进并启用/禁用 Sodium。重点记录：空洞、双绘、旧 epoch 回写、忘记泄漏、Voxy 报错后是否仍能近景游玩。

实现评审中的 Voxy 问题见 [F20–F31](allay-dimension-implementation-review-fix-plan.md)。
