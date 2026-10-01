# 自由包裹 GPU 查询与紧急恢复（2026-10-01）

为后续安全停止权威客户端重复位置下行，增加自由包裹的 GPU 拾取原语和已完成姿态恢复。此阶段没有取消任何原生同步，也没有增加网络包；实际自由包裹输入/准星入口与交还绝对重同步仍待接线，不能认定网络验收完成。

`PackagePoseQueryGpu.pickFree` 按已提交绘制的插值查询真实箱体 AABB，工作组归约选择最近候选，返回完整稳定身份和 GPU 算出的命中点。沿用四槽非阻塞读回，每次拾取只传输 128B。自由权威 body 前缀是必需参数，排除共用 free flags 的纯视觉观察者；三个预留域允许 bodyCount 达到 393216，实际通用粒子上限仍为 131072。工作组尾部线程参与全部 barrier。

`PackagePoseCheckpointGpu` 抽出原链检查点的共用实现，`PackageChainCheckpointGpu` 保持原调用接口，`PackageFreeCheckpointGpu` 在每个 bank 保留自由 body 前缀。GPU 整理前后位置、速度、yaw、ground 和箱体高度。未完成 bank 不覆盖，最新完成 bank 留作恢复；正常运行只零 timeout 轮询，不在 Java 每帧遍历或解码粒子人口。成功物理/pool/admission 提交后才能捕获；失败帧不更新恢复状态。资源关闭时按完整身份读取已完成单条状态，恢复保留的 Create 客户端实体，不确认任何库存事务。

位置从中心转换为 feet，double 世界原点保持精度，blocks/s 速度除以 20 转回 Create 每 tick 单位。拒绝错误身份、generation、类型、高度、隐藏 active、prepared、非有限值和非法 ground；允许完整匹配的 retired 检查点。仍保留原生相对位置流时不能把 GPU 位置写入 vanilla VecDeltaCodec 基线，否则后续相对包将解码到错误位置。未来停止下行后的交还需服务端另发绝对重同步。

自由和链物理分别维护已发布版本。原生观察者采样只改变共用版本，不触发未变化的自由/链恢复副本；未发布的修改不推进对应版本。自由权威增量检测也使用自由发布版本，观察者单独插值不会触发额外检测/读回；真实 ACK 的确认版本仍能触发尚未发送的新状态重试。自由恢复资源第一次 OFFER 时才创建，观察者场景无需预分配它。131072 候选的默认复制路径每次 16MiB、资源 80MiB；混合场景自由与链各自扫描全候选，尚未合并为共享副本。这些是新增成本，没有本轮性能测量或整帧提速结论。

真实 RTX 4070 Laptop GPU / OpenGL 4.5 / NVIDIA 581.15 验证：

- 查询独立入口 3495 项断言，链与自由均覆盖。
- 检查点独立入口 550193 项断言，覆盖两种传输方式。
- 原生观察者回归 5251504 项断言，新增分域发布版本测试。
- 完整包裹 GPU 回归 16955744 项断言，包含实际 OFFER→PREPARED→FINAL→ACTIVE/admission→观察者单独发布不重复检测→失败帧→幂等紧急关闭恢复；build 与 Sable 缺席加载通过。全部 JUnit 为 68 个 suite、340 个测试、零失败。

边界覆盖 0/1/63/64/65/4095/4096/4097/131072。拾取以 Minecraft AABB.clip/contains 为参考，覆盖插值、平行、反向、内部、面起点、未命中与终点排除；完整身份和离散选择精确比较，命中坐标使用 1e-4 参考容差，固定测试点的浮点误差使用 1e-5。检查点覆盖前后历史、body 前缀隔离、四槽耗尽、源覆盖、迟到身份、重建失败、隐藏/准备/退休及重复关闭。最初紧急恢复 fixture 的速度超过现有 32 blocks/s 协议资格上限，正确触发拒绝接管；修正 fixture 后验证实际恢复路径，没有放宽生产速度或容量约束。

```powershell
.\gradlew.bat validatePackageGpu -PpackageQueryOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validatePackageGpu -PpackageCheckpointOnly --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageNativeObserverGpu validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```
