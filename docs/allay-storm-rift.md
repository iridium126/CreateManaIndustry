# 悦灵风暴裂口、天空与传送

日期：2026-10-29。状态：✅ 阶段7的Julia窗口、原生天空／云及MODEL材质已由用户确认客户端验收通过。整组传送已实现且有独立服务端／重启验证；多人、大型载具的完整客户端组合验收仍按实际场景记录，本次LOD2确认不扩大该范围。当前必需协议22。

## 中心与入口

`StormCenterSample / Timeline / View`共享双精度中心、生成数量与采样tick；规范样本每20tick一次，保留20tick延迟、插值钳制、暂停及旧身份拒绝。首次订阅携带已有前后两个样本；相同tick不重复推进。裂口摘要使用XZ 512／576格滞回，独立于196／260格完整成员模拟及authority；粒子关闭仍可显示裂口。

仅主世界活动风暴且生成count≥16384时开放。触发玩家必须存活、水平距离严格小于64、脚点Y严格大于来源maxBuildHeight+64。首次开放时间持久化，成熟恢复直接稳定开放；入口资格使用服务端实际位置，不使用视觉插值。

## Julia与目标天空

- 原形为标准二次四元数`q(n+1)=q(n)^2-0.5`、`q0=(x,y,z,0)`；仅以其覆盖显示目标原生天空，不画实体表面、厚度或人工挖孔。
- 世界中心为风暴插值XZ、来源maxBuildHeight+64；实轴东西向，仅刚性放置和统一缩放。参考眼点Y=64，以半径1.2R的水平风暴圆及入口64圆共同校准，留1%覆盖余量；首帧覆盖入口，2秒展开。
- 距离≥256格使用以裂口中心方向为轴的弱透视，≤128格使用真实透视，中间平滑过渡；单纯转头不旋转集合。
- `RiftRenderFrame`按主视角帧发布矩阵、尺寸、pipeline代次、Julia覆盖与线性命中距离。天空、天体和云共用命中图，云循环不重新迭代分形；未命中为-1。
- 运行时只读取`data/createmanaindustry/quadratic-c050.bin`及`quadratic-c050-fit.json`。192迭代、2049×1025网格保留全部4662条轮廓及径向断开；有限数值精度不是无限细节几何。

当前正视参考（白色仅为覆盖，不是游戏材质）：

![Julia当前原形](design-assets/julia-offline/quadratic-c050-front-mask.png)

[侧视](design-assets/julia-offline/quadratic-c050-side-mask.png)／[斜视](design-assets/julia-offline/quadratic-c050-oblique-mask.png)及[独立数值复核](design-assets/julia-offline/quadratic-c050-verification.json)保留；旧c=-0.35／-0.45候选不再维护。离线生成、CPU复核与GPU投影入口见[验证说明](allay-storm-validation.md)。

悦灵维度与窗口使用同一`AllaySkyProvider`、原版／当前包原生天空、天体和天气；完整dayTime为主世界−12000，gameTime及机械／传送时钟不变。`SkyRenderScope`只影响所属渲染线程的客户端查询，`dayTime()`与`getDayTime()`均覆盖；服务端和来源世界不受目标作用域影响。

无包使用`VanillaSkyView`及真实ShaderInstance合成链；Iris使用独立`NativeSkyView`程序、uniform图、附件、天空缓存和云历史，不创建第二客户端世界或渲染远处地形。目标基础着色器在首块几何前绑定，沿用Iris附件交换及alt→main历史；Bliss保留原生云合成，iterationT输出倍率还原，来源曝光／镜头效果只应用一次。CMI uniform在程序实际绑定时更新，局部窗口不写入来源sky-map、SH或反射缓存；来源只读附件禁止作为目标写入。重载／切包／尺寸变化重建所属资源，并恢复纹理、sampler、混合、写入掩码、scissor、深度、viewport及pipeline phase。

客户端新增 `allayStorm.riftSkyClipping=true` 与 `allayStorm.riftCulling=FRUSTUM_DEPTH`，在主视图帧边界生效。剔除三档为 NONE、FRUSTUM、FRUSTUM_DEPTH；深度契约不可用时回退主视锥，辅助视图不复用主视图结果。裂口投影及目标回放在不透明深度完成后执行，同帧 GPU 条件标志同时保护回放和主场景消费；剔除不暂停战斗或传送。Complementary 使用末级掩码裁剪，无光影使用保守包围矩形，其他包暂保留完整回放。允许天空光照／云／历史差异，真实画面及性能仍待用户验收；采集与当前证据边界见 [性能验证](particle-engine-performance.md)。

## 原生云与光影契约

只修改有效光影包已有云的密度；无包／无云／关云不补云墙。原生噪声坐标、风向、质量、占用判定、云高及历史保留。`StormCloudFrame`每个规范时间样本缓存中心、R、开口锥和强度，不新增网络或成员模拟；实际悦灵维度与目标天空作用域不施加来源云墙。

参考眼点64，由Julia最终投影长半轴A、裂口高度H得到云样本高度h处开口`r0=max(16,A*abs(h-64)/(H-64))`。`r1=r0+max(16,.15r0)`、`r2=r0+max(48,.5r0)`、`r3=r2+max(32,.25r0)`；满权重下中央0、r1～r2为2倍、r3外为原生1倍，五次平滑使用平方距离。生成数量16384时满强度，出生／STOP各2秒过渡；裂口面以下最后16格恢复原生，面上及以上倍率严格为1，原生零密度不变。

来源云保留Julia命中前部分，预览目标云保留命中后部分。目标原版云有独立VBO缓存；Iris云按deferred后、composite前的实际顺序与附件交换。密度补丁与天空补丁独立预检、原子发布；密度失败先只排除密度候选重试，未知结构安全拒绝增强，不修改用户shader包。

| 样本族 | 关键接入约束 |
| --- | --- |
| Complementary | 原生tracePos；Reimagined保留占用和噪声，调整已有云的等效消光 |
| Photon | 实际样本Y反算缩放及海平面；卷云保留两参数接口，真实位置另传，覆盖prepare共享调用 |
| Bliss／BSL | 原生高层／积云或平面／体积云位置；视线与照明查询使用同一密度倍率 |
| iterationT／Sildur | 主视角与sky-map／SH／panorama缓存分离，法线和打包材质附件覆盖写入 |
| Solas／Sundial | 实际平面／体积Cloud库及主天空program；Sundial反射分支不写局部裂口 |

名称／版本只作验证样本标签，适配按源码结构、有效选项和实际include依赖选择。MODEL身体／翅膀从有效IdMap取得悦灵身份，持有物中性；apply前绑定实际图集，保留包原生分附件混合。共用光影会话及其他效果见[光影集成](shaderpack-integration.md)。

## 整组传送与恢复

`RiftTransferCoordinator`负责收集→冷准备→锁定／快照→旅行事件预检→标准DimensionTransition迁移→恢复挂载／座位→逐项校验→同行客户端就绪确认。先结构和非玩家、后玩家；就绪包只允许解除已有事务的冻结，不能发起运输。

| 边界 | 实现约束 |
| --- | --- |
| 成员闭包 | 根载具、嵌套乘客与玩家、Create稳定子结构／座位、Sable连接和加载依赖／反向actor／绳索／plot内附着实体；准备后重新核对 |
| 安全落点 | 请求时捕获XZ，原列优先，再查32格圆邻域；检查真实碰撞、乘客净空、危险、边界和预留，支持Y∈[-128,384)。乘骑需地面，船可用安全水面，飞行结构需整组净空；失败整组不动 |
| 加载预算 | 全服务端每tick至多2个新加载请求、1ms协作预算；非阻塞轮询，原生codec工作单元不可抢占。冷准备不冻结，60秒超时，失败2秒后重试；锁定后不新增冷加载 |
| 原生结构 | 当前Sable／Simulated ABI，原生序列化、目标plot分配及BE／关节／网络／计划tick坐标映射；保留UUID、库存、姿态、座位、工作状态、票据和跟踪点。未知codec、固定世界锚点、不完整依赖或冲突整组拒绝 |
| 冻结与补偿 | 仅锁定组的移动、AI、物理、BE与计划tick冻结；任何旅行取消、部分迁移或挂载失败都走整组补偿，恢复未完成继续隔离 |
| 持久日志 | 分离NBT由工作线程压缩、校验、force及原子替换。PREPARED先于迁移，COMMITTED先于来源清理；原生实体／玩家／结构检查点后才RELEASED |
| 重启 | 启动读取日志并锁定成员；PREPARED回源，COMMITTED／CHECKPOINTED向目标恢复；离线玩家及嵌套挂载恢复。释放记录只留小型决定，不复活后来死亡者或永久保留旧库存 |
| 到达 | 清零平移／角速度和fallDistance，保留结构旋转及创造飞行权限；跟踪／挂载就绪后才解除冻结。正常维度加载界面，成功约0.6秒青白淡出、峰值alpha≤0.35，失败无成功过渡 |

日志无法撤销第三方事件对组外产生的任意副作用。普通、Sable及Simulated进程中断恢复工具保留；多人／大型载具客户端组合验收与视觉验收分别记录。

## 诊断与回归

- `/cmi particle shaderpack status`：活动契约、实际程序、主帧、来源／目标／预期时钟、缓存和拒绝原因。
- `/cmi particle shaderpack stormcloud on|off`：本地密度A/B旁路，不存档、不发网络。
- `/cmi particle shaderpack stormcloud profile on|off`：异步测来源Iris完整阶段及目标原版云回放，不冒充单个密度函数成本。

修改后按[验证说明](allay-storm-validation.md)选择Julia／天空／重放／真实高层绘制／原生八包与服务端恢复检查；独立模板生成工具仍见[scripts/allaystorm](../scripts/allaystorm/README.md)。
