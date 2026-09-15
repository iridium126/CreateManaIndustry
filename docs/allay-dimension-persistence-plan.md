# ALLVR Cube 持久化说明

状态：🟡 P0–P6 已实现，JVM 持久化测试已覆盖主要路径；GameTest、真实服务器停机/崩溃和长期兼容仍需验证。本文是实现说明，不再是逐阶段施工计划。

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

格式常量唯一来源：`AllvrStorageFormat`。当前 region format version 为 `1`，Cube NBT schema 由 `AllvrCubeDataFixes` 管理。

## 3. Cube NBT 契约

根记录包含 `AllvrFormatVersion`、`GeneratorVersion`、`isLightOn`、`xPos/yPos/zPos`、`LastUpdate`、`sections` 和 `block_entities`。

- Cube 固定 8 个 16³ section，section 通过 `Index` 定位，不信任列表顺序。
- 每个 section 保存 block states、biomes、2048 字节 sky light 和 2048 字节 block light。
- block entity 必须位于目标 Cube，且其类型必须与记录的 block state 匹配；非法项记录错误并跳过。
- section 缺失、重复、越界、坐标不匹配、codec 失败或 light 长度错误均视为 corruption。
- Cube 层不拥有 vanilla heightmap、实体、random tick 或 scheduled tick；这些不能悄悄写进 Cube NBT。

实现入口：`AllvrCubeSerializer`、`AllvrCubeSnapshot`、`AllvrCubeDataFixes`。

## 4. 写入与读取

写入：

1. 服务端捕获不可变 snapshot，按 Cube key 合并 pending write。
2. IO worker 小批量写 payload，再写 inactive header 并提升 generation。
3. 成功后才回收旧 payload sector；失败保留 pending，flush 最多重试固定次数。
4. `/save-all flush` 和正常关闭等待 pending 清空并 force 文件。

读取：

1. 优先返回 pending map，保证 read-your-writes。
2. 否则从 region index 读并校验 CRC/长度，再异步 decode NBT。
3. write epoch 变化时重读，避免读到并发提交前的旧 header。
4. 服务端线程安装 fresh Cube；corrupt record 保持 unloaded，不能退回生成器覆盖。

## 5. 失败处理和诊断

`AllvrCubeCorruptedException` 表示结构损坏；`AllvrStorageDiagnostics` 记录读取、提交、延迟、payload、pending 和错误。区域文件损坏时先备份并人工修复/移除，不能用“重新生成”掩盖数据丢失。

`AllvrCubeIoWorker` 限制后台 batch、并发读、pending read 和关闭等待时间；读请求优先于后台保存，但关闭时必须先停止读任务，再 drain/force/close。

## 6. 测试与待验证项

已有 JVM 测试覆盖：坐标/region slot 映射、header 选择与 checksum、批量写入、回收、损坏记录、并发读写、latest-wins、flush/close 和 Cube serializer 的关键约束。

仍需 GameTest/手工验证：

- 真实服务器停止、崩溃和重启后的双 header 恢复。
- 大量 Cube、超大 block entity、磁盘写满/权限错误时的 fail-closed 行为。
- 资源/数据迁移后的旧 NBT 版本恢复。
- Cube 与客户端 full snapshot、block update、forget 的顺序一致性。

相关总览见 [allay-dimension-dev.md](allay-dimension-dev.md)。
