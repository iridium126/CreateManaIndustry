```json
{
  "title": "分域",
  "icon": "hexcasting:battery",
  "ordinal": 14,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "category": "createmanaindustry:mana_industry",
  "additional_search_terms": [
    "Partition",
    "分域",
    "Anima"
  ]
}
```

一个[卓越戏法](^createmanaindustry:mana_industry/great_tricks)。接受两个向量，返回布尔值。向量向下取整为方块坐标，以这两个方块为对角，围出包含两端方块的长方体。每个方块消耗 **1 Anima**；例如三乘三乘三的区域需要 **27 Anima**。

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:partition|>

;;;;;

<|page-title@lavender:book_components|title=域内与域外|>

分域的六个外表面划开内外。施法原点与目标分处两侧时，施法所需距离视为无穷：通常的有限施法范围与哨卫都无法跨越。两点同在域外时，即使连线穿过分域，距离仍按通常规则计算。嵌套的域分别判断内外。

玩家真名的距离豁免仍依 Hex Casting 的配置生效；[灵质反转](^createmanaindustry:mana_industry/ectoplasmic_inversion)仍将范围取反。按距离计费的戏法跨域时无法施放，费用交换也不能支付无穷的费用。单纯测距与本来没有距离要求的操作不受影响。

;;;;;

<|page-title@lavender:book_components|title=立下边界|>

两端方块的中心必须在当前链的空间施法范围内。不同施法链的边界不能相交或接触，但可以严格包容。每条链只能维持一个分域；再次施放按新域的完整体积收费，成功后立即替换旧域。替换时忽略旧域的边界。

成功返回“真”；参数、坐标、范围、边界或余额不满足要求时返回“假”，不消耗 Anima，也不改变旧域。边界不会因施法者走远或区域卸载而解除。

;;;;;

<|page-title@lavender:book_components|title=无形之壁|>

边界如同方块表面，阻挡普通实体、投射物和载具穿行，也阻止跨域的同维传送。同在域外的远距离传送可以通过。旁观者、无碰撞实体和跨维度移动不受约束。

边界切中已有实体时，该实体可先离开边界；完全脱离后，便要遵守所在一侧的约束。普通贴面不会再次取得豁免。服务端可按实体 ID 配置移动豁免，默认包含 Create 的移动结构及列车；其乘客一同通行。这项豁免不改变施法距离和费用。

;;;;;

<|page-title@lavender:book_components|title=维系分域|>

淡蓝边框和透明粒子薄幕标示分域，附近的同维观察者均可见。所属施法链结束、取消，或施法者死亡、退出时，屏障立即消失，余下的消散仅是光影。

单独从执行手镜运行本戏法，会在执行结束时一同解除分域；可借长法术维持，或在法杖会话中保留。长法术恢复执行时不重复收费；若存下的边界与现有域冲突，分域不再恢复，但法术的其他部分继续执行。
