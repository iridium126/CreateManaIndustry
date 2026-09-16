# 悦灵维度世界树开发方案

## 0. 项目基础设定

### 0.1 已确定条件

- Minecraft 版本：**1.21.1**
- Mod 平台：**NeoForge**
- 悦灵维度采用 **Y 轴方向流式加载**
- 维度范围：
  - X：约 ±30,000,000
  - Y：约 ±30,000,000
  - Z：约 ±30,000,000
- 世界树位于维度中心
- 世界树外围存在一圈通向地下区域的巨大空腔
- 空腔之外环绕 **淬晶花海**
- 玩家默认加载半径：**192 格**
- 玩家第一次到达时通常无法看到完整世界树，只能首先看到树干
- 当前开发顺序：
  1. 地上世界树
  2. 地表群系及探索内容
  3. 地下空腔接口
  4. 后续地下生态带

### 0.2 世界树已确定参数

| 项目 | 数值 |
|---|---:|
| 主干基部直径 | 128 格 |
| 主干主体平均直径 | 约 96 格 |
| 推荐总高度 | 约 1100～1150 格 |
| 主冠最大直径 | 约 520～580 格 |
| 一级主枝 | 约 7 条 |
| 巨型主根 | 约 8 条 |
| 主要探索节点 | 12～18 个 |

### 0.3 核心生成原则

世界树采用：

> **固定宏观模板 + 程序化细节生成**

而不是：

- 单一超大型固定 Structure
- 完全随机程序树
- 一次性生成整棵树

其技术本质应当是：

> **一套能够根据空间坐标确定世界树形态的特殊世界生成系统。**

---

# 1. 设计总览

## 1.1 世界树定位

世界树同时承担以下功能：

### 视觉核心

世界树是整个悦灵维度最重要的视觉锚点。

它不应该只是：

> 一棵非常大的树。

而应该给玩家形成：

> 整个世界似乎围绕它生长。

的感觉。

### 垂直探索核心

世界树用于向玩家建立悦灵维度最重要的认知：

> **探索方向不仅是水平，还包括非常大尺度的垂直方向。**

世界树本身就是整个维度垂直玩法的教学关卡。

### 导航核心

玩家可以使用自然语言辨认区域：

- 根部
- 下层主干
- 中层主干
- 第三主枝
- 外树冠
- 树冠核心
- 顶冠

世界树相当于悦灵维度天然的三维地标坐标系。

### 玩法枢纽

世界树未来连接：

```text
淬晶花海
    ↓
世界树根部
    ↓
环形空腔
    ↓
地下世界树根系
    ↓
地下生态带
```

因此即使当前不实现地下部分，也必须提前固定地下接口。

---

## 1.2 与悦灵风暴的关系

建议世界观采用统一能量体系：

```text
世界树
│
├─ 稳定
├─ 有序
├─ 循环
└─ 维持悦灵生态

悦灵风暴
│
├─ 失控
├─ 高浓度
├─ 外泄
└─ 能量扰动
```

可以理解为：

> 世界树代表稳定状态的悦灵能量，悦灵风暴代表这种能量失衡、泄漏或过饱和后的形态。

世界树中提前预留：

- 风暴灼痕
- 风暴结晶
- 异常能量节点
- 世界树伤口
- 风暴共鸣点
- 世界树核心能量

这些内容以后可以与 Boss 剧情连接，而不需要现在确定完整剧情。

---

# 2. 世界树结构分层方案

所有高度建议采用：

```text
localY = worldY - TREE_BASE_Y
```

进行计算。

推荐世界树地上高度约：

```text
1120 格
```

---

## 2.1 根基层

### 高度

```text
localY = -64 ～ +32
```

当前优先开发：

```text
localY = 0 ～ +32
```

负高度部分仅预留接口。

### 结构

世界树基部直径：

```text
128 格
```

向外延伸约 8 条大型板根。

单条主根推荐：

| 参数 | 数值 |
|---|---:|
| 长度 | 80～180 格 |
| 宽度 | 18～36 格 |
| 高度 | 12～40 格 |

主根不能简单做成圆柱。

应表现为：

- 山脊状
- 扭曲状
- 部分埋入地面
- 部分跨越地形
- 存在根下空间
- 存在树洞与裂隙

### 主要玩法

- 玩家第一次接近世界树
- 新手资源
- 树根内部空间
- 根部探索路径
- 第一批遗迹
- 通向外围空腔的接口

### 固定内容

必须固定：

- 8 条主根
- 主要根部入口
- 空腔入口位置
- 主路线起点
- 第一观景平台

---

# 2.2 主干基部层

### 高度

```text
32 ～ 192
```

### 直径变化

推荐：

```text
128
↓
约 105
```

### 视觉设计

这是玩家第一次进入维度后最重要的区域。

主干不能表现成平滑圆柱。

建议使用：

```text
radius(y, θ)
=
baseRadius(y)
+ macroRidge(y, θ)
+ surfaceNoise(y, θ)
```

其中：

```text
baseRadius    = 主体半径
macroRidge    = ±8～12 格
surfaceNoise  = ±2～4 格
```

需要出现：

- 巨型树皮隆起
- 纵向沟槽
- 发光能量脉
- 晶体侵入
- 大型裂隙
- 树洞
- 苔藓带
- 垂藤

---

# 2.3 主干中层

### 高度

```text
192 ～ 512
```

### 推荐直径

```text
约 90～100 格
```

这是世界树最长的垂直探索区域。

这里不能设计成普通螺旋楼梯。

推荐交替使用：

- 树干外壁路线
- 树洞内部路线
- 藤蔓捷径
- 晶体裂缝
- 巨型树皮平台
- 半开放洞穴

形成：

```text
外壁
 ↓
树洞
 ↓
树皮平台
 ↓
藤蔓
 ↓
内部空腔
 ↓
晶体裂缝
 ↓
更高区域
```

---

# 2.4 高位枝干层

### 高度

```text
512 ～ 720
```

这一层是第一次重大尺度揭示区域。

建议设置约：

```text
7 条一级主枝
```

其中：

- 3 条属于主路线体系
- 2 条承担支线
- 1 条连接大型遗迹
- 1 条主要承担视觉构图

一级主枝最大长度推荐：

```text
240～300 格
```

这一阶段玩家第一次能够沿树枝离开树干很远，然后回头看到：

> 自己之前攀爬的“墙”实际上只是世界树主干。

---

# 2.5 树冠外层

### 高度

```text
720 ～ 900
```

### 最大半径

```text
260～290 格
```

### 结构

树冠不能形成实心球体。

建议总体保持：

```text
45%～60% 空域
```

形成：

- 枝桥
- 叶片岛屿
- 悬垂藤蔓
- 晶体平台
- 悦灵群活动区
- 小型遗迹
- 大量开放天空

玩家探索模式从：

```text
垂直攀登
```

逐渐转化为：

```text
三维空间探索
```

---

# 2.6 树冠核心层

### 高度

```text
900 ～ 1040
```

这里开始降低装饰密度。

外围：

```text
大量枝叶
大量粒子
大量悦灵
```

核心：

```text
更大的空间
更少的物体
更强的光效
更明显的巨构
```

建议存在：

## 世界树心腔

尺寸：

```text
直径：60～90 格
高度：80～120 格
```

作为地上世界树最重要的中后期空间之一。

---

# 2.7 顶冠 / 圣域层

### 高度

```text
1040 ～ 1120+
```

顶冠不应该继续提高结构密度。

应当：

- 开阔
- 安静
- 高对比
- 少量巨大结构
- 强烈天空感

这里适合：

- 世界树核心节点
- 顶级资源
- 风暴剧情
- 特殊传送结构
- 世界树终点
- 观景区域

---

# 3. 固定模板部分清单

核心原则：

> **凡是影响世界树轮廓、玩家导航、剧情、主要路线和可达性的结构，都必须固定。**

| 内容 | 类型 | 原因 |
|---|---|---|
| 世界树中心 | 固定 | 整个维度空间基准 |
| 主干中心线 | 固定 | 控制整体剪影 |
| 主干半径曲线 | 固定 | 保证稳定尺度 |
| 8 条主根 | 固定 | 地表主要轮廓 |
| 一级主枝 | 固定 | 关卡与剪影 |
| 树冠总体范围 | 固定 | 控制远景 |
| 主要树洞 | 固定 | 保证路线 |
| 世界树心腔 | 固定 | 核心地标 |
| 主要平台 | 固定 | 玩家导航 |
| 主路线 | 固定 | 防止断路 |
| 大型遗迹 | 固定 | 剧情稳定 |
| 顶冠 | 固定 | 最终目标 |
| 地下接口 | 固定 | 后续扩展 |

---

## 3.1 Protected Volume

建议建立统一保护空间系统：

```java
record ProtectedVolume(
    AABB bounds,
    ProtectionType type
) {}
```

或者支持：

- Box
- Sphere
- Capsule
- Spline Tunnel

所有程序化装饰都必须检查：

```java
if (protectedVolumeIndex.intersects(candidate)) {
    reject();
}
```

防止：

- 树叶堵路
- 晶体堵门
- 藤蔓覆盖机关
- 随机树枝穿过平台
- 装饰破坏视线

---

# 4. 程序化生成部分清单

核心原则：

> **删除、移动或改变以后不会影响主要关卡结构的内容，优先程序化。**

---

## 4.1 二级枝干

一级主枝固定。

二级枝干从固定 Socket 生成：

```text
PrimaryBranch
│
├─ Socket A
├─ Socket B
├─ Socket C
└─ Socket D
```

不同世界决定：

- 是否生成
- 朝向
- 长度
- 曲率
- 分叉情况

但 Socket 的允许范围固定。

---

## 4.2 三级枝干

完全程序化。

推荐限制：

```text
长度：6～32 格
半径：1～5 格
最大递归深度：2～3
```

禁止无限递归式生长。

---

## 4.3 树叶簇

建议抽象成：

```java
record LeafCluster(
    Vec3 center,
    float radiusX,
    float radiusY,
    float radiusZ,
    float density,
    long seed
) {}
```

叶簇使用：

```text
Ellipsoid SDF
+
Noise
+
Density Threshold
```

生成。

避免规则球体。

---

## 4.4 藤蔓

适合程序化。

例如只有满足：

```text
位于枝条下侧
AND
附近有树冠
AND
法线朝下
AND
不属于 ProtectedVolume
```

时才允许生成。

推荐长度：

```text
普通：4～40 格
稀有：60～100 格
```

长藤蔓负责形成垂直视觉尺度。

---

## 4.5 晶体簇

优先生成于：

- 树皮裂缝
- 根部
- 树洞
- 能量节点周围
- 风暴异常区域
- 树干凹陷处

而不是均匀随机分布。

---

## 4.6 发光节点

大部分发光应使用：

```text
Emissive Texture
```

只有少量节点真正参与 Minecraft 光照计算。

建议：

```text
约 90% 视觉发光
约 10% 真正 Light Level
```

---

## 4.7 粒子锚点

不要保存或同步大量单独粒子。

保存或计算：

```java
record ParticleAnchor(
    Vec3 position,
    ParticleFieldType type,
    long seed,
    float radius,
    float density
) {}
```

客户端根据 Anchor 生成：

```text
数百
数千
甚至数万粒子
```

服务器无需管理全部视觉粒子。

---

# 4.8 确定性随机

禁止：

```java
Random random = new Random(worldSeed);
```

然后按照区块加载顺序连续调用。

否则：

```text
先加载 A 再 B
```

和：

```text
先加载 B 再 A
```

可能产生不同结果。

推荐：

```text
treeSeed
   ↓
nodeSeed
   ↓
cellSeed
   ↓
featureSeed
```

例如：

```java
long nodeSeed = hash(
    treeSeed,
    templateVersion,
    nodeId
);

long cellSeed = hash(
    nodeSeed,
    cellX,
    cellY,
    cellZ
);

long featureSeed = hash(
    cellSeed,
    featureType,
    localIndex
);
```

最终保证：

```text
世界 Seed
+
节点 ID
+
空间坐标
+
生成版本
=
固定结果
```

---

# 5. 玩家探索路径设计

## 5.1 第一次进入维度

推荐出生点距离树心：

```text
165～185 格
```

由于加载半径为：

```text
192 格
```

玩家第一次进入时看到的主要是：

```text
巨大的世界树树干
```

而不是一整棵完整树。

这不是技术缺陷，而应成为体验设计的一部分。

---

# 5.2 三阶段尺度揭示

## Reveal 1：树干

玩家出生。

认知：

> 这里有一个巨大的树状结构。

但无法判断整体大小。

---

## Reveal 2：主枝

玩家到达：

```text
Y ≈ 450～550
```

沿第一条巨大主枝离开主干。

第一次回头看到：

```text
整个巨大树干局部
```

认知：

> 原来我刚才一直在一棵树的树干上。

---

## Reveal 3：树冠

玩家到达：

```text
Y ≈ 700～800
```

视野突然打开。

首次看到：

- 淬晶花海
- 巨型树根
- 环形空腔
- 主枝网络
- 树冠
- 世界树核心区域

此时才真正理解世界树整体结构。

---

# 5.3 推荐主路线

```text
淬晶花海
    ↓
巨大板根
    ↓
根部树洞
    ↓
树皮峡谷
    ↓
外部平台
    ↓
内部树洞
    ↓
垂藤捷径
    ↓
晶体裂隙
    ↓
一级主枝
    ↓
树冠外层
    ↓
叶冠岛群
    ↓
树冠核心
    ↓
世界树心
    ↓
顶冠圣域
```

---

# 5.4 探索节奏

始终维持：

```text
远景目标
    ↓
中距离路线
    ↓
近距离奖励
```

例如：

```text
远处看到发光节点
    ↓
寻找通往主枝的路线
    ↓
途中发现树洞
    ↓
获得资源
    ↓
到达节点
    ↓
看到下一个远景目标
```

玩家应该始终知道：

> 下一个“大方向”在哪里。

但不知道：

> 中间具体怎么过去。

---

# 6. 技术实现方案

# 6.1 核心设计原则

不要把世界树作为：

```text
一个超大型 NBT Structure
```

也不要让某个 Feature：

```text
一次性把整棵树全部生成
```

推荐结构：

> **世界树逻辑上完整，物理上按空间区域首次生成。**

---

# 6.2 Minecraft Chunk 与世界树数据的职责

这里需要明确区分。

## Minecraft Chunk

Minecraft 自身负责保存：

- 世界树原木
- 树皮
- 树叶
- 晶体
- 藤蔓
- 玩家挖掉的方块
- 玩家后来放置的方块

即：

> **世界树实际生成出的 BlockState 由 Minecraft Chunk 正常持久化。**

无需再维护一份完整方块坐标表。

---

## 世界树自定义数据

只保存：

```java
record WorldTreeDescriptor(
    BlockPos origin,
    long treeSeed,
    int generationVersion,
    ResourceLocation templateId
) {}
```

以及必要的世界状态：

```text
已激活大型节点
剧情进度
世界树状态
特殊事件状态
```

而不是：

```text
(x, y, z) -> BlockState
```

的完整副本。

---

# 6.3 权威数据原则

首次生成之前：

```text
世界树算法
```

决定这里应该是什么。

首次生成完成后：

```text
Minecraft Chunk
```

成为实际方块状态的权威来源。

即：

```text
Seed / Template
负责首次构造

Chunk
负责永久保存现实状态
```

因此已经生成且允许玩家修改的区域：

> **绝不能在正常加载时重新根据公式覆盖。**

---

# 6.4 推荐模块划分

```text
WorldTreeSystem
│
├─ WorldTreeDescriptor
├─ WorldTreeSavedData
│
├─ WorldTreeTemplate
├─ WorldTreeMacroGraph
│
├─ WorldTreeSpatialIndex
│
├─ WorldTreeCellGenerator
├─ WorldTreeSDF
├─ WorldTreeCarver
│
├─ WorldTreeMaterialResolver
│
├─ WorldTreeDecorator
│   ├─ BranchDecorator
│   ├─ LeafDecorator
│   ├─ VineDecorator
│   ├─ CrystalDecorator
│   └─ MossDecorator
│
├─ WorldTreePOIManager
├─ WorldTreeParticleSystem
└─ WorldTreeDebugRenderer
```

---

# 6.5 WorldTreeMacroGraph

宏观树体不保存成数百万方块坐标。

保存为：

```text
主干节点
主根节点
主枝节点
树洞节点
路线节点
```

例如：

```java
record TreeSplineNode(
    long nodeId,
    NodeType type,
    long parentId,
    Vec3[] controlPoints,
    float startRadius,
    float endRadius,
    AABB bounds
) {}
```

整棵世界树可能只需要：

```text
几十～几百个宏观节点
```

即可描述主要结构。

---

# 6.6 使用 SDF 表示巨型树体

推荐使用：

```text
Signed Distance Field
```

表示：

- 主干
- 根系
- 树枝
- 树洞
- 隧道

例如：

```text
TREE
=
TRUNK
UNION ROOTS
UNION BRANCHES
MINUS CAVITIES
MINUS PATH_TUNNELS
```

这样比维护巨型方块模板更加适合：

- 分区生成
- Y 轴流式加载
- 程序表面起伏
- 树洞
- 根系
- 弯曲树枝

---

# 6.7 Spatial Index

绝不能每检查一个方块就遍历：

```text
整棵树所有 Node
```

应先为每个 MacroNode 计算：

```text
AABB
```

然后建立空间索引：

```java
Map<TreeCellPos, int[]> nodesByCell;
```

生成某个 Cell 时只查询：

```text
与当前 Cell 相交的节点
```

---

# 6.8 Tree Cell

建议世界树逻辑生成单元采用：

```text
32 × 32 × 32
```

或者直接与你现有的 Y 轴流式单元保持一致。

例如：

```java
record TreeCellPos(
    int x,
    int y,
    int z
) {}
```

逻辑上：

```text
世界树
↓
Cell
↓
Chunk Section
↓
Block
```

分级处理。

---

# 6.9 Cell 生成流程

```text
玩家接近某区域
    ↓
判断对应世界区域是否需要首次生成
    ↓
计算 TreeCellPos
    ↓
查询 SpatialIndex
    ↓
没有世界树节点
    └──结束

存在节点
    ↓
计算 Macro SDF
    ↓
写入主干 / 根 / 主枝
    ↓
执行固定 Carver
    ↓
生成固定路线
    ↓
分配材料
    ↓
程序化二级枝
    ↓
生成叶簇
    ↓
生成藤蔓
    ↓
生成晶体
    ↓
生成装饰
    ↓
生成粒子 Anchor
    ↓
Minecraft Chunk 正常保存结果
```

---

# 6.10 伪代码

```java
void generateTreeCell(
    TreeCellPos cell,
    GenerationContext context
) {
    WorldTreeDescriptor tree =
        context.worldTree();

    List<MacroNode> nodes =
        spatialIndex.query(cell.bounds());

    if (nodes.isEmpty()) {
        return;
    }

    long cellSeed = StableHash.hash(
        tree.treeSeed(),
        tree.generationVersion(),
        cell.x(),
        cell.y(),
        cell.z()
    );

    CellClassification classification =
        worldTreeSdf.classify(cell, nodes);

    if (classification == FULLY_OUTSIDE) {
        return;
    }

    generateMacroGeometry(
        cell,
        nodes
    );

    applyFixedCarvers(cell);

    assignMaterials(
        cell,
        cellSeed
    );

    secondaryBranchGenerator.generate(
        cell,
        cellSeed
    );

    leafGenerator.generate(
        cell,
        cellSeed
    );

    vineGenerator.generate(
        cell,
        cellSeed
    );

    crystalGenerator.generate(
        cell,
        cellSeed
    );

    particleAnchorGenerator.generate(
        cell,
        cellSeed
    );
}
```

---

# 6.11 表面计算优化

主干内部不应该每格都计算复杂噪声。

例如：

```text
distanceToSurface < 6
```

才计算：

- Bark Noise
- Crystal Noise
- Moss
- Energy Vein
- Surface Detail

如果：

```text
distanceToSurface < -8
```

则直接填充：

```text
world_tree_heartwood
```

---

# 6.12 Section 快速分类

对于一个 16³ Section：

先判断：

```text
完全在树外
```

则直接跳过。

如果：

```text
完全在树内
```

则直接批量填充。

只有：

```text
穿过树表面
```

的 Section 才逐 Block 求 SDF。

结构：

```text
Cell
 ↓
Section Classification
 ↓
OUTSIDE → skip

INSIDE → fast fill

SURFACE → block sampling
```

这是世界树生成最重要的性能优化之一。

---

# 6.13 玩家修改保护

世界树首次生成完成后：

```text
Chunk 中的方块状态
```

就是实际状态。

例如玩家：

```text
砍掉 100 个世界树方块
```

之后重新加载：

> 不应该通过 SDF 自动恢复。

否则世界树将无法被正常修改。

---

# 6.14 Generation Version

从第一版开始就加入：

```java
int generationVersion;
```

例如：

```text
World Tree Generator v1
World Tree Generator v2
World Tree Generator v3
```

原则：

```text
旧区域 → 保持旧结果

新区域 → 使用新算法
```

不要自动重新生成玩家已经探索过的区域。

---

# 7. 美术资产建议

# 7.1 世界树木材

推荐至少包含：

```text
world_tree_log
world_tree_wood
stripped_world_tree_log
world_tree_heartwood
world_tree_bark
ancient_world_tree_bark
```

视觉基调：

```text
浅蓝
灰白
青蓝
少量蓝紫
```

树心亮度高于树皮。

---

# 7.2 树皮

重点不是提高纹理分辨率。

而是建立：

```text
8～20 格尺度的大型表面结构
```

例如：

- 深色纵向沟槽
- 浅蓝色隆起
- 发光细脉
- 巨型裂口
- 局部晶化

这样即使玩家距离几十格，也能够感受到树皮尺度。

---

# 7.3 树叶

可以继续沿用：

```text
Lumina Leaf
```

视觉体系。

建议增加：

```text
lumina_leaf
ancient_lumina_leaf
luminous_lumina_leaf
```

不同区域：

```text
外树冠
→ 青蓝

核心树冠
→ 蓝白

高能区域
→ 接近发光白色
```

---

# 7.4 藤蔓

推荐：

```text
world_tree_vine
luminous_vine
crystal_vine
ancient_thick_vine
```

优先做静态生成装饰。

尽量避免：

- Random Tick
- BlockEntity
- 高频更新

---

# 7.5 晶体

世界树与淬晶花海可以共用同一晶体视觉体系。

世界观逻辑：

```text
世界树能量
    ↓
向外扩散
    ↓
环境结晶
    ↓
形成淬晶花海
```

因此：

```text
距离世界树越近
→ 晶体越巨大
→ 密度越高
→ 发光越明显
```

---

# 7.6 发光组织

推荐：

```text
lumen_vein
lumen_node
tree_heart_tissue
tree_core
```

形成能量层级：

```text
普通树皮
    ↓
细能量脉
    ↓
大型能量节点
    ↓
世界树心
```

---

# 7.7 粒子系统

建议主要包含：

| 粒子 | 使用区域 |
|---|---|
| 蓝白微光尘 | 全树 |
| 上升能量流 | 主干 |
| 落叶光点 | 树冠 |
| 晶体闪光 | 晶簇 |
| 螺旋能量 | 能量节点 |
| 悦灵轨迹 | 树冠核心 |

你的高性能粒子系统更适合表现：

> **大范围、低密度、具有空间流动感的粒子场。**

而不是简单在局部堆积极高密度粒子。

---

# 8. 开发阶段规划

# 阶段 0：生成架构验证

## 目标

只证明：

> 超大型世界树能够稳定分区生成。

实现：

- WorldTreeDescriptor
- MacroGraph
- TreeCell
- SpatialIndex
- SDF 主干
- 8 条主根
- 7 条一级主枝
- Seed 确定性
- Chunk 首次生成流程

方块仅使用：

```text
world_tree_bark
world_tree_heartwood
lumina_leaf
```

暂时不做：

- 藤蔓
- 晶体
- 粒子
- 遗迹
- 大量玩法

### 测试重点

测试：

```text
快速向上飞行
快速向下飞行
传送到高 Y
从不同方向接近
退出重新进入
不同 Chunk 加载顺序
多人从不同方向加载
```

生成结果必须一致。

---

# 阶段 1：世界树基础视觉

增加：

- 主干表面起伏
- 二级枝
- Leaf Cluster
- 固定树洞
- 基础藤蔓
- 发光纹路
- 晶体

目标：

```text
站在 170～180 格外
```

世界树基部具有巨大压迫感。

在：

```text
Y ≈ 700
```

仍具有合理整体剪影。

---

# 阶段 2：探索玩法

实现：

- 主路线
- 平台
- 树洞系统
- 藤蔓捷径
- 资源节点
- 固定遗迹
- 主枝探索
- 树冠路径
- 世界树心
- ProtectedVolume
- POI Node

此阶段完成后，世界树应该能够作为完整探索区域游玩。

---

# 阶段 3：技术美术增强

最后增加：

- 大规模粒子
- 高级晶体
- 多级藤蔓
- 悦灵群
- 风暴痕迹
- 环境音
- 特殊天空光效
- 高级能量视觉

这样不会让视觉开发阻塞核心生成架构。

---

# 阶段 4：地下接口

地上部分稳定以后再开发：

```text
主根
 ↓
环形空腔
 ↓
地下根网
 ↓
第一地下生态
 ↓
更深生态带
```

当前阶段只保留固定入口即可。

---

# 9. 风险点与优化建议

## 9.1 世界树变成巨型圆柱

风险最大。

解决方法：

不要依赖贴图解决轮廓。

必须增加：

```text
8～12 格甚至更大的宏观半径变化
```

包括：

- 隆起
- 沟壑
- 板根
- 断层
- 巨型树洞
- 纵向裂纹

---

# 9.2 程序装饰堵路

所有程序化模块统一接入：

```text
ProtectedVolumeIndex
```

并禁止侵入：

- 主路线
- 平台
- 门
- 树洞
- 剧情点
- 观景视线

---

# 9.3 SDF 计算过重

禁止：

```text
整棵树所有方块
×
所有 MacroNode
```

采用：

```text
Spatial Index
+
Cell Classification
+
Section Classification
```

三级裁剪。

---

# 9.4 光照计算压力

大量视觉发光采用：

```text
Emissive
```

只有关键节点：

```text
Light Level
```

真正参与方块光照传播。

---

# 9.5 BlockEntity 过多

以下内容默认不要使用 BlockEntity：

- 树皮
- 普通晶体
- 普通藤蔓
- 发光纹路
- 苔藓
- 普通植物

只有：

- 容器
- 剧情机关
- 世界树核心
- 复杂互动节点

才使用 BlockEntity。

---

# 9.6 程序生成覆盖玩家修改

必须坚持：

```text
算法负责首次生成
Chunk 负责之后的真实状态
```

禁止每次加载：

```text
重新根据 SDF 重写世界树
```

---

# 9.7 开发顺序失控

最容易拖慢开发的是：

1. 过早制作大量美术资产
2. 过早设计复杂遗迹
3. 过早加入百万粒子
4. 过早实现完整地下区域
5. 过早实现动态树木变化
6. 过早开发复杂生态 AI

第一优先级始终应该是：

```text
宏观形状
    ↓
分区生成
    ↓
确定性
    ↓
性能
    ↓
探索路线
    ↓
视觉细节
```

---

# 10. 最终推荐架构

```text
                    WorldTreeDescriptor
                            │
                            ▼
                     WorldTreeTemplate
                            │
                            ▼
                     Macro Node Graph
               ┌────────────┼────────────┐
               │            │            │
             Trunk         Roots       Branches
               │            │            │
               └────────────┼────────────┘
                            ▼
                           SDF
                            │
                            ▼
                     Spatial Index
                            │
                            ▼
                    Tree Cell Generator
                            │
              ┌─────────────┼─────────────┐
              │             │             │
         Macro Geometry   Carvers    ProtectedVolume
              │             │             │
              └─────────────┼─────────────┘
                            ▼
                    Material Resolver
                            │
                            ▼
                     Procedural Detail
             ┌────────┬──────┼───────┬─────────┐
             │        │      │       │         │
          Branches  Leaves  Vines  Crystals  Moss
             └────────┴──────┼───────┴─────────┘
                             ▼
                      Minecraft Blocks
                             │
                             ▼
                    Minecraft Chunk Save
                             │
                             ▼
                  玩家修改后的真实世界状态
```

整个系统最核心的设计原则可以最终归纳为四句话：

> **固定模板决定“它必须长成什么样”。**

> **程序生成决定“每一个世界具体长得有什么不同”。**

> **Seed、NodeId 和空间坐标决定“首次生成结果必须稳定”。**

> **世界树一旦生成成方块，就由 Minecraft Chunk 保存真实状态，生成器不再反复覆盖。**

这套架构能够同时满足世界树的巨构尺度、192 格加载体验、Y 轴流式加载、玩家可修改性、确定性生成、性能控制和未来地下扩展需求。