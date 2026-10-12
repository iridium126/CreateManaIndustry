# Trickster 物品在 Stock Ticker 中回弹

验证版本：Create 6.0.10、Trickster 2.0.0-beta.56、Minecraft 1.21.1。

## 原因

参考 `.refs/Create` 和 `.refs/trickster`，并核对当前依赖的实际字节码：

- `StockKeeperRequestScreen.containerTick()` 在收到新库存快照后调用 `revalidateOrders()`。
- `InventorySummary.getCountOf()` 使用 `ItemStack.isSameItemSameComponents()` 查找待取物品。找不到时返回 0，界面清除订单，表现为物品从输出栏回弹。
- Trickster 的 `ManaComponent` 比较其中的魔力池。`SimpleManaPool.equals()` 使用 `other.variant == this.variant` 比较魔力类型；`ManaVariant` 在解码时创建新实例，尽管类型和组件相同，引用也不同。
- 因此带有非零魔力的晶结，在两次库存同步之间，以及客户端订单与服务器仓库之间，都会被误判为不同物品。打包机的请求匹配也使用相同的物品组件比较。

## 修复

`SimpleManaPoolEqualityMixin` 仅在 `equals()` 的第一次 `variant` 字段读取处，将值相等的比较对象替换为当前引用，使原有引用判断按 `ManaVariant.equals()` 的结果工作。不会修改实际魔力池字段。

保留原有的空池规则、魔力数量和容量判断，以及生息、可溢出魔力池子类的附加判断。现有 Mixin 插件仅在安装 Trickster 时加载此修复。

## 回归验证

```powershell
.\gradlew.bat runGameTestServer -I scripts/trickster/gametest.init.gradle --offline
```

测试通过 Create 的 `BigItemStack.STREAM_CODEC` 独立解码库存快照与订单，检查：

- 普通、空、彩色、可溢出、生息魔力池物品的库存刷新、哈希一致性、存档往返及 Create 物品提取。
- 魔力数量、容量、类型、颜色组件及生息利率不同的物品仍被区分。
- 实际打包机从箱子取出已同步订单对应的晶结，保留物品组件并生成带地址的包裹。
- 所有已注册 Trickster 物品的默认实例在库存同步后保持组件相等及哈希一致。

修复前，带魔力晶结的库存刷新测试失败；加入修复后通过。测试使用独立的 `build/gametest/trickster`，需要现有 `run/mods/owo-lib-neoforge-0.13.0-alpha.15+1.21.jar` 测试依赖。测试源码只由初始化脚本加入，不进入正常发行包。
