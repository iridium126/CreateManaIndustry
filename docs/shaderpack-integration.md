# 通用光影集成与风暴视觉探针

状态：🟡 用户已确认八包实体合并、阴影、探针及地平线修复正常，Bliss／iterationT雾亮度正常。按本轮要求，另外六包已恢复／接入原生合成，源码、GPU和构建通过，六包的新原生路径待客户端验收；完整曝光选项矩阵与彩光尚未验收。修改前的悦灵风暴及悦灵维度视觉验收结论保留。日期：2026-10-25。

## 会话与能力

名称、版本只作诊断／样本标签，不参与运行时适配选择。`ShaderPackSession`在Iris加载包时读取shader文本（包括frag／vert／geom等库阶段），建立完整路径清单、摘要及整组补丁计划。新加载、同名更新与选项重载产生新会话；pipeline／尺寸变化更新运行代次。绘制期间不读取shader或旁边的选项txt文件。

`ActivePackOptions`读取Iris已解析的有效选项与默认值，不把未保存的选项直接当作关闭。MODEL、包裹、Hex原生编译器同时检查会话代次，保留已有program解析、fragment、混合指令、geometry／tessellation路径。

`PackCapabilities`按源码契约确认能力，`WorldEffectPass`分离条件、更新和绘制，`IrisWorldEffects`统一调度雾、燃料棒辉光与探针。同一主帧每项至多更新、绘制一次；阴影和Veil辅助视角不推进它们。

## 合成与失败恢复

| 路径 | 条件与行为 |
| --- | --- |
| 原生场景 | 校验实际composite读取的场景缓冲与颜色编码，在包的曝光／Bloom／tonemap之前合成 |
| 原生透明层 | 校验覆盖率／存储比例及曝光texel后使用对应层 |
| 原生HDR | 校验场景格式、曝光公式及LUT覆盖后，只转换新增发光 |
| 原生curve/raw | 整组合成补丁成立，且额外附件未占用时使用独立层 |
| 基础显示 | 原生契约不明、iris-veil-compat缺席或强制baseline时，在AFTER_LEVEL后、可选手持物前使用主画面与深度 |
| 无光影 | 雾／辉光保留原Veil post路径；探针使用公共世界末尾路径 |

场景颜色通过CMI拥有的 `SceneColorCopy` 复制，再由shader采样副本与有效深度，避免采样正在写入的颜色附件。绘制保存／恢复framebuffer、program、VAO、viewport、混合／深度／裁剪、颜色／深度写入、纹理和sampler状态；各附件独立的混合开关、函数、方程与颜色写入掩码也逐项恢复。使用自有sampler，不修改包拥有的深度纹理比较状态。相机参数取自主视角已捕获矩阵并通过专用uniform上传；局部绑定program，避免Veil通用bind／unbind清除外部shader block与sampler。

低分辨率目标不被冒充为完整场景，原生目标不可用时本帧尝试基础路径。未知／歧义阴影畸变与确认无畸变分开处理；缺少必要参数时不猜系数。彩色透明阴影只在相应契约、选项和资源均成立时启用。

基础显示保证可验证的效果可见性，不宣称参与包原生Bloom、TAA或彩光传播。colortex编号与纹理单元不同；源码清单支持16以上的逻辑编号，额外附件需检查原始源码与DRAWBUFFERS／RENDERTARGETS的占用。

## 原子彩光候选

`ShaderPatchPlan`取代按包名逐文件修改：全部必要结构通过后才暴露完整路径源码修改与ID映射。任一缺失／歧义产生空候选；原始zip／目录不写入。

- LPV契约解析数组长度、实际条目、发光范围函数、顶点ID编码偏移、setup派发／索引和image尺寸，覆盖当前Photon的48项表；32／64项由合成夹具覆盖。
- 只有捐赠槽与替代槽的颜色表达式相同且编码关系成立，才复用槽位；关联物品映射一起迁移。
- voxel／block-data／curve契约在已校验范围内选择空闲ID，检查属性与源码占用，不再假定固定ID永远空闲。
- 实际Iris材质映射在副本中校验／修改；失败不改变输入映射，并使候选加载失败。
- 候选加载失败至多重试一次未修改包；pipeline编译失败将重试排到事件循环，避免递归构造。用户关闭shader不触发恢复重载。

彩光预检成功只表示候选完整，实际效果仍取决于有效选项和客户端编译。未证明BSL、Solas等包的彩光算法时，保留原生映射并明确降级，不用包名猜测。

## 视觉帧与命令

`StormVisualFrame`只在粒子GPU成功提交之后发布身份、主帧／GPU代次、中心、半径、积分相位和时间；失败候选不发布，换世界／关闭清理。不改变风暴运动、攻击或网络。

探针只在客户端本地使用，默认关闭，无网络包／存档记录：

```text
/cmi particle shaderpack probe on
/cmi particle shaderpack probe mode auto
/cmi particle shaderpack probe mode baseline
/cmi particle shaderpack status
/cmi particle shaderpack probe off
```

开启时捕获固定锚点：优先取当前已提交风暴中心XZ，否则取玩家XZ；高度为建筑上限+64与相机高度+64中的较大值。青色细环半径24格，绿色指示+Z、红色指示+X。移动相机应产生视差，实心地形应遮挡环，暂停保持脉冲相位。

`mode baseline`将同一调度器的雾／辉光／探针一起切至基础路径进行比较；`auto`恢复能力选择。它不修改包选项或MODEL原生路径。换维度关闭探针，F3+T重建合成资源。

`status`报告会话／源码摘要、能力路径、彩光候选、每项效果路径与原因、CPU更新时间及最近完成的逐项GPU复制+绘制时间，并显示MODEL、包裹和Hex状态。原生失败后基础路径成功仍保留失败原因，编译失败恢复原包也明确报告；路径变化写入日志，无源效果标为inactive。GPU数值不是整帧耗时或全部效果之和。

### 六包原生路径迁移（2026-10-25）

用户实机确认其余六包的暗雾发生在baseline，并要求参考Git原有适配改回原生路径。因此撤回本轮尚未交付的baseline颜色换算，迁移Git `2b65a5ac` 中的场景合成及Sundial HDR算法（相关历史提交：`613b93ba`、`58bab456`），保留统一会话、独立颜色副本和GL状态恢复。

| 验证样本 | auto原生路径 | 场景附件 | 注入时的颜色契约 |
| --- | --- | --- | --- |
| Photon v1.4 | SCENE | colortex0 | 线性场景；随后进入blend-layers／fog及后处理 |
| Complementary Unbound r5.9.3 | SCENE | colortex0 | composite1的2.2次幂解码之前，按相同编码回写 |
| BSL v10.1.1 | SCENE | colortex0 | ALPHA_BLEND=0时平方／平方根编码；=1时线性，读取Iris有效选项 |
| Sildur’s Vibrant v2.02 Extreme | SCENE | colortex4 | 当前样本的deferred1／composite1使用附件4，2.2次幂编码；不照搬历史默认0 |
| Solas V3.8 | SCENE | colortex0 | gbuffersLighting编码与composite解码对应的2.2次幂编码 |
| Sundial Lite v1.2.0 | HDR | colortex3 | 线性HDR；沿用0.2与EXPOSURE_VALUE、RGB16 LUT覆盖下的曝光补偿 |

Bliss继续使用TRANSLUCENT，iterationT继续使用RAW，两条已验收路径的合成公式保留。雾、辉光和探针共用原生场景编码参数；baseline与原生线性／透明／raw路径取gamma=1。只在雾实际覆盖处在线性光中叠加，保持原场景alpha。未知包仍可安全降级，不能仅凭名称强制写某个buffer。

`NativeSceneContract`通过完整路径和读写／编码结构识别，不匹配包名或精确版本。首次观察pipeline时，`ShaderPackSession`还检查当前维度ProgramSet中的有效composite、sampler2D和目标声明；结果按pipeline／尺寸代次缓存，不逐帧扫描源码。`native-program`与效果reason显示生效program、附件和gamma，条件程序缺席或源码契约改变时报告具体降级原因。

Photon同名colortex0另有3D噪声绑定，必须区分sampler2D场景与sampler3D自定义纹理；运行时只拒绝覆盖场景的2D绑定。Sundial的格式指令在注释中，按Iris资源指令保留，不能先删除注释再识别。清单现在沿include关系读取shaders.settings等任意后缀文本，避免漏掉当前Sildur选项。

实机复验保持 `probe mode auto`，预期五包为 `mist=SCENE`、Sundial为 `mist=HDR`；同时检查 `native-program=effective composite… confirmed`、自动／强制baseline切换、默认及BSL ALPHA_BLEND=1、曝光选项、切包／重载／换维度。新路径尚未以自动验证替代客户端亮度验收。

## 八款本地样本与自动验证

样本通过任务扫描 `run/shaderpacks`，至少八个zip，缺失时报错；普通构建不依赖第三方shader资源，样本文件不进入分发JAR。

| 样本标签 | 验证重点 |
| --- | --- |
| Complementary Unbound r5.9.3 | voxel映射、颜色函数及18／19附件引用 |
| Photon v1.4 | 48项LPV、image／setup范围、顶点编码 |
| Bliss v2.1.2 Chocapic13 edit | block-data表、透明层／曝光、阴影 |
| BSL v10.1.1 | 自定义image／纹理及16号附件引用 |
| iterationT 3.2.0 | 整组curve／raw补丁、geometry源码 |
| Sildur’s Vibrant v2.02 Extreme | 无compute管线与基础能力识别 |
| Solas V3.8 | voxel／floodfill资源与未知增强降级 |
| Sundial Lite v1.2.0 | 实际HDR／曝光／LUT关系，不凭名称套用 |

矩阵比较重命名副本和解压目录的摘要／补丁等价性，输出 `build/reports/shaderpack/source-matrix.md`。报告包含源码摘要、候选结果、世界路径及附件引用，不代表真实pipeline已编译或画面已验收。

```powershell
.\gradlew.bat test validateShaderPackSamples check build --offline --no-configuration-cache
.\gradlew.bat validateWorldEffectsGpu validateSceneFrameGpu validateParticleProgramsGpu validateModelShadowIrisGpu validatePackageIrisGpu validateHexSharedIrisGpu --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
.\gradlew.bat validateShaderPackPatches --offline --no-configuration-cache -I scripts/particles/validation.init.gradle
```

完整构建／check通过，877项JUnit无失败、错误或跳过；单独八包矩阵要求全部产生原生候选，并核对重命名／目录结果等价。新增测试覆盖32／48／64项表、结构歧义、ID冲突、映射副本、编码偏移变化、库阶段扩展名、同名更新、会话／尺寸代次、无Iris会话初始化及实际依赖中mixin目标／发布时序；包含合成补丁换行、雾vec3参数、阴影资源归属、原生契约冲突、2D／3D绑定及非标准后缀include回归。

已通过的真实驱动回归（RTX4070 Laptop、OpenGL4.5、NVIDIA581.15）：world-effects 287,785项、场景帧72,764项、程序生命周期575项、MODEL/Iris阴影4,133,608项、包裹/Iris 26,771项、Hex/Iris 5,291项断言。生命周期中的ERROR为预期失败注入。原回归日志保存到 `build/shaderpack-final-gpu.log`；本轮完整构建、world-effects及53项Iris解析检查为 `build/shaderpack-native-final.log`。

新增GPU工具编译三份生产fragment与生产 `world_effect_space` 头文件，通过测试相机uniform执行复制、32／64尺寸切换、遮挡／输出模式、逐附件混合／颜色写入掩码和状态恢复；追加头顶锚点的地平线全像素不串色、太阳方向的雾亮度响应，以及gamma=1／2／2.2在不同背景下与线性光合成公式的逐像素对照。不启动Minecraft世界，不证明完整Iris最终画面正确。`validateShaderPackPatches`使用现有Iris运行库验证六包的有效composite源码、BSL ALPHA_BLEND=1源码变体和iterationT完整补丁／附件指令，不能替代真实pipeline编译。

### 启动故障修复（2026-10-25）

用户提供的 `run/logs/debug.log:6347` 记录 `ShaderPackSessionMixin` 应用失败：未限定描述符的 `<init>` 同时匹配Iris三参数委托构造器，非静态HEAD回调落在 `this(...)` 之前。现将HEAD／TAIL同时限定到实际四参数构造器，并将HEAD回调改为static，确保每次构造只初始化一次会话。回归测试读取实际Iris和Mixin字节码，校验唯一目标、参数签名及HEAD静态约束；原ABI测试仅确认目标存在，未覆盖该问题。修复构建记录为 `build/shaderpack-mixin-fix-build.log`；这些检查不能替代真实客户端Mixin应用和启动验收。

### 首轮实机反馈与修复（2026-10-25）

首轮用户确认八包实体合并、阴影与主细环正常，但探针在头顶时地平线仍串色、雾普遍偏暗。当时日志记录Bliss `mist=TRANSLUCENT`，iterationT `mist=BASELINE` 且 `colored-light=unmodified retry`；其余六款当时选择基础合成。后续用户已验收地平线修复和两款原生雾亮度，六款现按上节恢复原生路径。

- 探针：地平线处平面交点导数过大，造成抗锯齿宽度扩散；限制世界线宽和有限圆盘范围，保留真正的环与方向线。
- 雾：`SunDirection`是vec3，原上传使用四分量，Veil会忽略该调用；修正为三分量，恢复白天散射响应。亮度是否符合实机预期仍待复验，不通过统一倍增颜色替代曝光契约。
- iterationT：结构匹配吞掉末尾换行，补丁行注释遮住main闭括号，触发Iris解析EOF并恢复原包。修复匹配边界，额外层显式使用RGBA16F与透明清屏，并通过运行库解析验证；RAW路径的真实客户端表现待复验。
- 阴影资源：切包／销毁时清理所属句柄，每次阴影绘制后刷新当前renderer资源，采样前确认GL纹理有效；避免使用已删除包资源。旧日志中另有纹理对象错误，不能仅凭此修改断言全部GL错误已解决。

## 新集成路径的客户端检查

旧风暴／维度视觉及八包实体合并／阴影／主细环已验收；以下继续覆盖修复后及尚未验收的项目：

1. 使用新构建重启，检查无Iris、Iris无包及八款样本。每款记录默认选项、适用增强选项的开关和status结果。
2. 开探针仰望细环，移动、转头、暂停；比较auto／baseline，检查视差、方向、遮挡、双绘制和HUD污染。
3. 对照雾、燃料棒辉光、火把／蜡烛与燃料罐，确认材质复用未改变原版光色；检查MODEL、手持物、阴影。
4. 切包、同名替换重载、F3+T、分辨率变化与换维度。不可用项必须有原因，不能仅因“检测到包名”报告兼容。

八包真实客户端矩阵已完成部分验收，六款新原生雾路径、完整曝光和彩光仍需复验。正式天空、裂口、高空订阅和传送仍为后续步骤。

下表专门记录本轮真实客户端结果，源码预检／隐藏窗口GPU结果不填入此表；“待验”也不代表已支持该项原生增强。各项须分别记录auto／baseline、实际路径及降级原因。

| 环境／验证样本 | 基础显示 | 实体合并 | 阴影 | 曝光 | 彩光 |
| --- | --- | --- | --- | --- | --- |
| 无Iris | 待验 | 待验 | 待验 | 原生增强不适用 | 原生增强不适用 |
| Iris无包 | 待验 | 待验 | 待验 | 原生增强不适用 | 原生增强不适用 |
| Complementary Unbound r5.9.3 | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |
| Photon v1.4 | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |
| Bliss v2.1.2 Chocapic13 edit | 主细环及原生雾亮度已验 | 已验 | 已验 | 雾亮度已验；完整选项待验 | 待验 |
| BSL v10.1.1 | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |
| iterationT 3.2.0 | 主细环及RAW雾亮度已验 | 已验 | 已验 | 雾亮度已验；完整选项待验 | 待验 |
| Sildur’s Vibrant v2.02 Extreme | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |
| Solas V3.8 | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |
| Sundial Lite v1.2.0 | 主细环两模式已验；雾待复验 | 已验 | 已验 | 待验 | 待验 |

上述主细环与地平线修复验收保留；六款新原生路径的auto表现另行复验。
