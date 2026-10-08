# 局部变化的静态 GPU 上传等待

## 本轮日志

2026-10-08 的 debug.log 截至 14:35:55，2528 条单体记录全部为 SECTION_VERSION、worldWait=upload、stillFrozen=false。没有仍冻结的单体汇总、移动几何等待或 GPU 运行时异常。

| section | requiredRevision | 记录数 | 失效来源 |
| --- | --- | --- | --- |
| (-1,4,0) | 314 | 1435 | BLOCK，最后坐标 (-5,77,14)，合并 2 次通知 |
| (-1,4,1) | 315 | 1093 | BLOCK，最后坐标 (-5,77,19)，合并 7 次通知 |

全部样本来自物理 step 1873。CPU 快照已完整发布；这些是延迟、限流输出的已恢复事件，不代表包裹持续冻结到输出时。原因坐标是该 revision 最后一次合并通知的位置，不是全部变化单元列表。日志没有记录方块类型、当时的 atlas 复制字节或 fence 状态，不能逐条重放原存档。

## 局部变化的上传证明

此前局部更新可以复用完整 CPU 快照中未变化的单元，但 GPU 仍重传 4096 个 16 字节单元头和全部形状。

现在 worker 在既有完整打包完成后生成可选证明：原 GPU 形状字典必须逐字节保持为新字典的完整前缀，再比较所有 4096 个单元头的实际字节差异。记录基线 revision、全局唯一的纯数值快照身份、原形状数量、变化单元和总字节数。没有改变标准完整快照格式，也不保留前一快照引用链。形状字典改变、缩短、重排、超限或完整上下文重捕获均不使用该证明。

证明基于全部实际编码差异，而不是只假定通知周围几个单元改变。摩擦、标志、形状索引和数量都参加比较。

## GPU 读取和历史保护

仅当当前可见 GPU revision 和快照身份都与证明基线一致、旧版本不再被未消费历史输入引用、所有引用该 slot 的 GPU 表 fence 已完成且没有 lease 时，才能在原 slot 更新。

更新总字节必须同时满足本次剩余额度和原来的 16 KiB 分片上限。全部写入在同一调用完成，再发布新的可见 revision、空几何标志和表代数；不会向物理查询暴露半更新的 section。预算、GPU slot 容量和零等待 fence 检查不变。

保护未解除或证明不兼容时继续使用已有 staging 完整上传。若 staging 只复制了一部分而旧版本保护随后解除，可安全转为局部更新，并释放从未发布的 staging slot。局部更新从不可变完整快照读取，不依赖已被分片上传修改过 position/limit 的临时 ByteBuffer。

## 回归

真实 GPU 夹具覆盖局部通知/完整失效与保留/不保留旧历史的四种组合。一个空气 section 新增一块立方体，标准上传为 65568 字节；满足保护条件的局部更新为 48 字节。局部和完整上传的全部 GPU 包裹状态逐字节一致：变化单元有新支撑，未变化单元仍为空气。保留的旧输入继续读取旧空气几何，没有泄漏新形状。

另一个夹具在历史保护期间先复制 47 字节，保护解除后以 48 字节完成局部更新，总计 95 字节；检查没有 pending 或 retired slot 泄漏。GPU view lease 期间上传被拒绝。

另有真实 GPU 回归使用不同缓存产生相同 revision 的两份不同快照，确认基线身份不匹配时拒绝局部更新并进行完整替换。身份只保存一个数值 token，不保留前一快照对象。

单元回归验证 48 字节证明、不可变单元列表、完整失效不产生证明，以及形状字典重排拒绝局部更新。

## 追加诊断

SECTION_VERSION 事件追加 observedUploadRevision、observedUpload、observedUploadBytes、observedPatchBytes、observedPatchGate。进度明确是日志输出时的观察，不能当作历史事件发生时的状态。

局部保护原因包括 history、gpu-reader、base-revision、base-snapshot、dictionary-or-full-capture 和 eligible。未保留的旧版本若已有不同新版本上传完成，标为 complete-other-revision，不误报为仍在复制。诊断只查询数值状态，不增加同步 GPU 等待或采样频率。

## 验证命令

```powershell
.\gradlew.bat test validatePackageGpu -PpackageWorldHistory --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat test validatePackageGpu build --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

针对性日志：build/sparse-world-upload-regression.log，160 项世界版本 GPU 断言通过。原存档尚未复测；本轮减少了具备严格证明时的上传量，真实 GPU 读取尚未结束、历史版本仍被需要或字典不兼容时仍可能暂时等待。

最终日志为 build/package-sparse-world-upload-final.log。543 项单元测试（106 个套件，无失败、错误或跳过）、14,308,782 项完整 GPU 断言、无 Sable 桥接检查及正式构建通过。

发布包为 build/libs/createmanaindustry-0.2.6.jar；内嵌主 JAR 的 47 个相关类及内部类已与最终编译输出逐项核对。SHA-256：45BFF26CD8A3134F1ED44699460828E5AF35F2B866FCAB2817332EDDAD12F451。
