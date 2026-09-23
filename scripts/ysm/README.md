# YSM 验证

脚本按需启动隔离客户端或服务器，工作目录和日志放在 `build/ysm-*`。不会复制原实例存档或登录凭据；服务器只监听回环地址，并要求来源服务器已接受 EULA。依赖 YSM `2.6.5-neoforge+mc1.21.1`。

## 快速检查

```powershell
# 运行时符号与几何数据边界检查
./scripts/ysm/verify-runtime.ps1

# 可选：验证本地编译模型（样本保存在忽略目录）
./gradlew.bat -I scripts/ysm/compiled-validation.init.gradle `
  '-Dcmi.ysm.sample=build/ysm-private-fixtures/sample.ysm' validateCompiledYsm --offline

# Hexcasting 注册、标签和几何编辑 GameTest；不加载 YSM native runtime
./gradlew.bat -I scripts/ysm/gametest.init.gradle `
  '-Dcmi.ysm.dataOnly=true' runGameTestServer --offline
```

## 完整客户端/服务器探针

先构建当前版本的 CMI 和探针：

```powershell
./gradlew.bat jar --offline
./gradlew.bat -p scripts/ysm/full-integration-probe jar --offline
```

启动隔离服务端，`-ModSource` 指向含依赖 mod 的已安装服务端目录：

```powershell
./scripts/ysm/production-server.ps1 `
  -InstalledServer D:/Minecraft/server -ModSource D:/Minecraft/mods-source `
  -NeoForgeVersion 21.1.236 -JavaHome D:/Minecraft/java `
  -ModelFile build/ysm-private-fixtures/sample.ysm `
  -PlaintextDirectory build/ysm-private-fixtures/plain-export `
  -RenderPort 25577 -FullCMI -FullProbe
```

服务端到达 `Done` 后，以相同端口启动客户端：

```powershell
./scripts/ysm/production-client.ps1 `
  -Instance D:/Minecraft/.minecraft/versions/NeoForge `
  -ModSource D:/Minecraft/mods-source -JavaHome D:/Minecraft/java `
  -RenderPort 25577 -FullCMI -FullProbe
```

多人验证可给服务端加 `-MultiplayerProbe`；第二个客户端使用 `-PlayerName YsmObserver -ObserverProbe`。晚加入观察者场景再给服务端加 `-LateObserverProbe`，待日志显示目标模型已应用后启动观察者。

## 边界

- 生产探针覆盖运行时加载、资源读写、应用/还原及观察者同步；它直接调用运行时，不代替玩家手工施放法术、死亡重生或跨维度验收。
- YSM 几何与原始资源保存在当前世界存档的内容寻址存储中，Iota 持有轻量节点引用；读取结果的编码量主要随根部件数增长。物品和世界存档需要一起备份，引用不能直接用于其他世界。缺失或损坏的节点会明确报错。
- 通过列表图案显式读取大量直接子项时，结果大小仍随该列表项数增长；无需访问的子树继续以引用形式保存。
- 未验证的 UV 四分之一转、静态骨骼缩放和无法可靠重建的资源会被拒绝。
