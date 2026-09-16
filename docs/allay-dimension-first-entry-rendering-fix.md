# 首次进入悦灵维度的渲染初始化修复

## 根因

NeoForge 21.1.236 的实际顺序是：

1. `ClientPacketListener` 构造新 `ClientLevel`，构造函数发出新世界的 `LevelEvent.Load`。
2. `Minecraft.setLevel` 发出旧世界的 `LevelEvent.Unload`。
3. `Minecraft.level` 更新为新世界。
4. `updateLevelInEngines` 调用 `LevelRenderer.setLevel`，初始化 Sodium/Voxy 渲染器。

原实现在步骤 1 绑定 cube 缓存、Sodium 和 Voxy，在步骤 2 又将它们清空。
后续 cube 包的发布能恢复缓存的 level 引用和方块碰撞，但不会恢复光照引擎、Sodium 的 initialized 或 Voxy 的 initialized，造成有碰撞但无近景和远景。

## 修复

`AllvrMinecraftLevelMixin` 在步骤 4 的方法入口清理并绑定新世界，确保旧世界已经卸载、新渲染器尚未创建。传入 null 时仅清理，覆盖退出世界路径。Load 事件不再绑定地形状态。

Sodium 绑定时只允许属于新世界的玩家参与初始窗口定位；切换过程中遗留的旧玩家不会提前初始化窗口。Voxy 保留首次 tick 的 renderer refresh，以等待新玩家的位置可用。

## 游戏内回归步骤（待实机执行）

使用兼容的 Sodium 0.8.13、Voxy 0.2.15-beta，并开启 `allvr.lod`：

1. 从主世界首次进入悦灵维度，前往中央原版带之外（Y < -128 或 Y >= 384）的有地形位置。确认 cube 地形与碰撞一致，无需 F3+A 或重进。
2. 等待 Voxy 导入，移动到已访问岛屿离开近景范围的位置，确认远景显示；日志应出现 `[Allvr] refreshed Voxy renderer for Allay Y slab`（或 slab 切换日志）。
3. 返回主世界，再进入悦灵维度，重复近景和远景检查。
4. 在悦灵维度退出存档后直接重进；再退出并进入另一存档，确认没有旧 cube 残留。
5. 在高 Y slab 和负 Y slab 重复切换，并执行资源重载，检查高度映射与方块编辑后的网格更新。

JVM 编译和单元测试不能替代以上 GPU、Mixin 运行时和维度切换验证。
