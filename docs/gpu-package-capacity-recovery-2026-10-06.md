# 客户端碰撞容量与原生恢复

日期：2026-10-06。

## 配置

客户端 `createmanaindustry-client.toml`：

```toml
[particles]
packageCollisionMaxSections = 256
```

范围 1–1024，只控制静态碰撞 atlas。配置不进入服务端握手或同步，同服客户端可分别使用不同值。进入世界时固定请求容量，重载配置不会重建当前世界缓存；重新进入世界后生效。实际容量按两份数据槽和显卡单 SSBO 上限计算，诊断输出 configured/allocated。默认约 48 MiB 数据槽；光照容量、上传预算和 section 使用保护保持独立。

## 服务端恢复边界

`gpuPackages.authorityEnabled=false` 是轻量自由记录恢复为包裹实体的唯一条件。普通 Create 初始化临时对象保留；掉线、客户端关闭、资源失败及碰撞缺失仍仅暂停轻量记录。关闭时立即拒绝旧控制、运动和环境修改；能力声明可以保留，供随后在线启用时选举。

恢复入口 `PackageLightRestoration` 从捕获 NBT 加载，再覆盖最新确认物品和状态。速度从 blocks/s 转为 blocks/tick，并在 Entity.load 后显式赋值，避免 NBT 读取对高速运动的限幅。保留 UUID、逻辑身份、剩余箱内物品、地址、附加数据、生命值、火焰、着地、朝向、插入延迟、传送门冷却和投掷者 UUID。后两个扩展原生字段使用可选 NBT，兼容旧存档；投掷者离线时保留 UUID，在线后可恢复引用。

仅服务端内存中的精确实体恢复上下文可以绕过轻量捕获，不能通过存档标记伪造。实体成功加入且实际登记后才移除记录、索引及轻量显示；创建或加入失败、未知物品和 UUID 冲突保留记录并轮转重试，不覆盖冲突实体。旧实体存档与记录 UUID 重复时仍以轻量记录为准。

只恢复已加载区块。未加载区块的数据继续存档，自然加载事件登记后恢复；不增加区块加载票据。启动关闭和在线关闭使用同一路径。重新启用时，已加载原生包裹逐批捕获，保存成功后删除实体，并提升生命周期代次。锁链只恢复原生容器与确认进度，重新启用时重新登记已有结构；不由此生成自由包裹实体。

自由、锁链迁移共享每维度每 tick 至多 64 次处理额度，同时分别受现有主线程预算限制。两类同时待迁移时各预留最多 32 次，避免彼此长期饥饿。实体及结构索引由加载、卸载事件维护；不会逐 tick 扫描全世界。现有存档文件名及网络协议不变。

## 回归与复现

- 单元测试检查双槽容量计算、默认配置、恢复 NBT、关闭状态的网络动作门禁、唯一实体恢复调用边界及锁链分批关闭。
- 真实 OpenGL 同时创建容量 1 和 257 的独立 atlas，验证容量不耦合、超过旧 256 上限，并比较覆盖相同区域的运动结果。
- 隔离串行恢复 GameTest 覆盖：66 条记录的迁移额度、最新确认状态与剩余物品、原生 NBT 保存重载、普通关闭状态创建、双向切换、加入取消重试、UUID 冲突、启动关闭时恢复持久化记录、未加载区块保留及自然加载后恢复。
- 既有 6 项真实 Create 输出 GameTest 继续覆盖机器、库存、环境与高 tick rate；夹具的旧碰撞白名单断言已换回现有形状比较验证。

```powershell
.\gradlew.bat runGameTestServer --offline --no-configuration-cache -PpackageRecoveryGameTest -I scripts/particles/package-output-gametest.init.gradle
.\gradlew.bat runGameTestServer --offline --no-configuration-cache -I scripts/particles/package-output-gametest.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

恢复和机器输出夹具分开编译与运行，使用独立 build 测试世界，避免共享服务端配置互相影响。最终正式构建不使用 GameTest 初始化脚本，需检查发布 JAR 不含夹具。

最终验证：499 项单元测试（100 个套件，无失败、错误或跳过）、13,615,504 项完整包裹 GPU 断言、1 项串行恢复及 6 项机器输出 GameTest 通过；正式构建和无 Sable 桥接检查通过，发布 JAR 及其内嵌 mod JAR 均不含测试夹具。见[结构化摘要](benchmarks/package-capacity-recovery-2026-10-06/tests.json)。未运行两台真实客户端的联机测试，也未在用户原存档上执行恢复实测。
