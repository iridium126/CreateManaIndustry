```json
{
  "title": "工业戏法",
  "icon": "trickster:amethyst_knot",
  "ordinal": 7,
  "additional_search_terms": [
    "动力应力戏法",
    "temporary_kinetic_stress"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

本节的戏法把魔力用于驱动机械。它只需戏法师与机械动力；咒法学互通的[读取与运行戏法](^createmanaindustry:mana_industry/hexcasting_bridge/iota_bridge)另列于后。

;;;;;

<|ploy@trickster:templates|trick-id=createmanaindustry:temporary_kinetic_stress,cost=0.008 × |转速| × 刻数 G|>

为给定位置的动力方块临时提供转速和应力来源，返回目标向量。

输入依次为：**目标位置、转速、持续刻数**。负转速使方向反转；持续时间向下取整，且须大于零。零转速不能使用。

;;;;;

<|page-title@lavender:book_components|title=持续时间与耗费|>

默认耗费为 **0.008 × 转速绝对值 × 持续刻数 G**。例如，转速 32、持续 20 刻，消耗 5.12 G。服务器可调整应力换算与费用倍率。

时间结束后，临时来源撤去，目标重新按原有动力连接运转。目标必须是已加载的动力方块。
