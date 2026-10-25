# ALLVR Cube 持久化说明

状态：已使用 schema 3；旧存档不兼容，不迁移、不自动删除、不重新生成。自动化覆盖与手工验收边界见 [验收记录](allay-dimension-validation.md)。本文是当前实现契约。

## 1. 分层职责

```text
AllvrCubeMap（服务端线程，拥有 live Cube）
  -> AllvrCubeSerializer（live Cube ↔ immutable snapshot/NBT）
  -> AllvrCubeIoWorker（异步写入、读优先、重试、flush）
  -> AllvrRegionCubeStorage（Cube 坐标 ↔ region3d 文件）
  -> AllvrRegion3DFile（单文件索引、payload、shadow header）
```

IO 线程和 decode 线程不接触 live Cube；服务端只安装经过校验的加载结果。快照带 `mutationVersion`/`lightVersion`，写入和结果发布采用 latest-wins 语义。

## 2. 文件布局

文件名：`r.<rx>.<ry>.<rz>.3dr`，每 sector 4096 字节。

| 区域 | 内容 |
| --- | --- |
| sectors `0..23` | header A |
| sectors `24..47` | header B |
| sector `48+` | Cube payload |

一个 region 覆盖 `16³` 个 Cube，共 4096 个 slot。slot 记录 payload sector offset、sector 数、压缩长度和 CRC32C。打开文件时选择 generation 较新的有效 header；两份 header 都无效则 fail closed，不自动生成覆盖原记录。

格式常量唯一来源：`AllvrStorageFormat`。当前 region format version 为 `1`，Cube NBT schema 为 **3**，由 `AllvrCubeDataFixes` 管理。旧或未来 schema 明确拒绝，损坏/不兼容记录隔离并保留。

## 3. Cube NBT 契约

根记录包含 `AllvrFormatVersion`、`GeneratorVersion`、`isLightOn`、`xPos/yPos/zPos`、`LastUpdate`、`sections`、`block_entities`、`block_ticks` 和 `fluid_ticks`。

- Cube 固定 8 个 16³ section，section 通过 `Index` 定位，不信任列表顺序。
- 每个 section 保存 block states、biomes、2048 字节 sky light 和 2048 字节 block light。
- block entity 必须位于目标 Cube，且其类型必须与记录的 block state 匹配；非法项记录错误并跳过。
- section 缺失、重复、越界、坐标不匹配、codec 失败或 light 长度错误均视为 corruption。
- Cube 拥有独立 block/fluid scheduled tick 容器，队列变更和剩余延迟变化参与 dirty 判断。每条保存注册 ID、15 位局部位置、剩余延迟、原版优先级和同刻排序号；非法字段、未知 ID 和重复条目拒绝恢复。
- 无模拟票据时本地 tick 时钟停止，恢复与重启后按剩余延迟继续；不按世界离线时间追赶。
- Cube 不持有 vanilla heightmap 或实体 NBT；实体继续使用原版 XZ 列持久化，但运行期 section 索引使用完整 Y。
- random tick 由当前随机采样推进，不保存待执行列表。

实现入口：`AllvrCubeSerializer`、`AllvrCubeSnapshot`、`AllvrCubeDataFixes`。

## 4. 写入与读取

写入：

1. 服务端捕获不可变 snapshot，按 Cube key 合并 pending write。
2. IO worker 小批量写候选 payload，再写 inactive header；两者成功才发布索引/generation。
3. 分配器保护两份有效 header 的 sector。失败恢复 live slot 元数据；header 写入结果不确定时保留候选 sector，直到该 header 被成功覆盖才允许回收。pending 保留最新快照，flush 在连续失败上限后报告失败。
4. `/save-all flush` 同时报告快照和写入失败；正常关闭等待 pending 清空并 force 文件。
5. `save-off` 同时关闭快照入口和 IO 提交，在命令返回前等待进行中的提交结束。尚未执行的提交暂停，之后的修改保持 dirty；`save-on` 恢复并保存最新状态。保存关闭时退出不提交新状态。
6. 无玩家时仍执行有预算的快照和卸载；dirty/noSave、快照失败、pending IO 的 Cube 保留到可安全卸载。诊断分别记录保留数量。

读取：

1. 优先返回 pending map，保证 read-your-writes。
2. 否则从 region index 读并校验 CRC/长度，再异步 decode NBT。
3. write epoch 变化时重读，避免读到并发提交前的旧 header。
4. 服务端线程安装 fresh Cube，并进入 NeoForge BE 生命周期；corrupt record 保持 unloaded，不能退回生成器覆盖。
5. 临时加载/生成失败走独立队列，按 20、40、80、160、200 tick 退避并封顶；无需玩家移动即可重试，失去票据立即取消。

## 5. 失败处理和诊断

`AllvrCubeCorruptedException` 表示结构损坏；`AllvrStorageDiagnostics` 记录读取、提交、延迟、payload、pending 和错误。区域文件损坏时先备份并人工修复/移除，不能用“重新生成”掩盖数据丢失。

`AllvrCubeIoWorker` 限制后台 batch、并发读、pending read 和关闭等待时间；读请求优先于后台保存，但关闭时必须先停止读任务，再 drain/force/close。

## 6. 测试与待验证项

已有 JVM 测试覆盖：坐标/region slot 映射、header 选择与 checksum、批量写入、回收、损坏记录、并发读写、latest-wins、flush/close 和 Cube serializer 的关键约束。

仍需 GameTest/手工验证：

- 真实服务器停止、崩溃和重启后的双 header 恢复。
- 大量 Cube、超大 block entity、磁盘写满/权限错误时的 fail-closed 行为。
- 旧 schema 必须拒绝恢复；本版本没有迁移承诺。
- Cube 与客户端 full snapshot、block update、forget 的顺序一致性。

相关总览见 [allay-dimension-dev.md](allay-dimension-dev.md)。
