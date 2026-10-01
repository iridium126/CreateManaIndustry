# 链包裹 GPU 拾取接线（2026-09-30）

本轮完成 Create 实际 package 输入 → 成功提交的 GPU 命中 → 稳定身份拾取请求 → 服务端原生物品处理 → 精确确认的接线。保持 CHAIN_READY 关闭；紧急回退姿态、移动光照、观察客户端、Iris/阴影及移动 parent 链路仍未完成，没有宣称完整计划或 131072 活动包裹验收完成。

## 本轮变化

- 输入注入仅在 Create 自己的 package 阶段，保留它前面的工具、链连接及其他交互。无活动链包或缺少可选拾取协议时仍用 Create。
- 每客户端 epoch 保存一个串行输入，不遍历活动包裹；失败帧不查询，四槽满时保留输入。拾取在退休 gather 前获得一次提交机会。未命中在 client tick 的 GPU pass 外重放原 vanilla use；已发送请求永不作为第二次原生拾取重放。
- 服务端通过 epoch/full ID/generation 直接定位包裹，以 lease、baseline、track revision 和串行事务校验。复核 Create 权限、服务端射线和可达轨道进度；不接受客户端物品、地址、位置或池索引。
- 删除准确的原对象，保留 Create 主手/库存分支；事务在副作用前认领。重复、改变正文、旧序号、旧身份和回调重入不重复操作库存。终止网络通知在库存回调之后发送，随后合并原生 BE 更新。
- 修复 tracked 模式的初始化 history：模式 2/3 的 startRadius 是进度/track 索引，不能按旧 position/radius 布局重算吊点。新断言检查第一次物理更新前的精确上传目标。
- stats 加入最新拾取 ACK 往返时间。输入/查询处理预算 100ms；ACK 等待的独立网络确认上限为 5 秒。该时间包括服务端处理和客户端派发，不能当作纯线路时延。

## 验证证据

RTX 4070 Laptop GPU，OpenGL 4.5，NVIDIA 581.15；实际解析 Create 6.0.10。Sable 保持 `compileOnly "maven.modrinth:T9PomCSv:U678xqle"`，直接 API，无反射，没有本轮依赖变更。

| 验证 | 结果 |
|---|---|
| Java 全部测试 | 238 项通过，0 failures/errors |
| 完整包裹 GPU 套件 | 13,341,125 项断言通过 |
| 链包裹 GPU 子集 | 158,581 项断言通过 |
| 无 Sable/companion 独立 JVM | 通过 |
| 构建 | `createmanaindustry-0.2.6.jar` 生成 |

Java 覆盖 pickup/observer actor 幂等边界、整序号/身份/修订拒绝、prepared 与过期租约、回调异常和重入、两方向环线 wrap、未确认路由节点、静止及线性距离、payload 全 long 身份和精确 progress bits、非法结果/NaN、输入槽耗尽、迟到查询、100ms 处理超时和独立 ACK 时间。线性位置数值比较容差 1e-6 格；离散字段和事务精确比较。

真实 GPU 测试检查 failed publication 禁止查询、命中可早于可见 admission 但不能生成拾取请求、generic candidate 与 fixed-offset body 的区别、ACTIVE 精确租约、ACK 身份关联、退休后的旧命中失效和 tracked 初始化吊点。使用真实 shader、buffer、fence 与模拟 transport，不执行 Minecraft 库存/玩家对象或真实网络循环。ASM 核对实际 Create `onUse()Z`、原生 packet/权限和 RaycastHelper 描述符；公共拾取 payload 不链接客户端实现。ASM 和编译不能证明实际 Mixin 启动成功。

本轮没有新整帧、server tick、带宽或库存实测；查询 kernel 不变，已有三组查询微基准见[姿态查询报告](package-pose-query-2026-09-30.md)。不能把小包尺寸和 O(1) 身份定位当作端到端低带宽证明：当前仍发送 Create 完整 BE 快照，观察客户端尚未使用专用增量。

后续游戏测试由用户执行：空/非空主手、实际内容/地址、其他交互优先级、未命中重放、玩家快速换视角/手持物、节点与拾取竞争、多人拾取、掉线、资源重载和换维度。必须补齐就绪门禁的其他行为后再开启 CHAIN_READY，随后验证同屏 131072 活动包裹的整帧与 server tick 目标。
