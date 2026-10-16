```json
{
  "title": "魔力与媒质",
  "icon": "hexcasting:battery",
  "ordinal": 8,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "转化者之戏法",
    "mana_to_media"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

魔力与咒法学的媒质可以互相交换。[转化者之戏法](^createmanaindustry:mana_industry/mana_conversion#1)负责将传统魔力换为媒质；反向交换则由咒法学的**织结之策略**完成。

需要安装 Hex Casting。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:mana_to_media|>

接受一个正数，表示要取用的甘道夫数。返回得到的媒质量，以紫水晶粉的等价值计。

由玩家施放时，产物存入物品栏中可再次充能的媒质容器。应先准备有足够余量的媒质之瓶等物品。

;;;;;

<|page-title@lavender:book_components|title=交换的比例|>

比例随服务器每刻的运转耗时而变化：世界越忙，每甘换得的媒质越少，反向交换得到的魔力则越多。

没有接收容器或空间不足时，会引发失误。转换要求全部产物都能存下，不会只存入一部分，也不会先扣去魔力。

;;;;;

<|page-title@lavender:book_components|title=由组构台施放|>

在两种法术组构台中，魔力来自放入的晶结，媒质则存入[组构台自身的储备](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media)。四枚核心共用这一份媒质。

晶结无法支付消耗或媒质储备空间不足时，两份储备均保持原状。
