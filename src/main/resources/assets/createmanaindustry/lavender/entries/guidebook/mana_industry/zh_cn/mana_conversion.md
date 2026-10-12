```json
{
  "title": "媒质转换",
  "icon": "hexcasting:battery",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 5,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": ["hexcasting", "trickster"]
  },
  "additional_search_terms": ["转化者之戏法", "魔力转媒质"]
}
```

将传统魔力交换为咒法学所使用的媒质的戏法。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:mana_to_media|>

将给定数量的魔力从施法者的储备中取出，转换为媒质。由玩家施放时，媒质存入背包中可再次充能的物品。交换的比例与 1 / mspt 成正相关。

;;;;;

全部产物必须能够存下。没有接收容器或空间不足会引发失误；不会消耗魔力，也不会存入部分媒质。反向交换可由咒法学的**织结之策略**完成。

;;;;;

<|page-title@lavender:book_components|title=由组构台施放|>

[法术组构台](^trickster:items/infrastructure/spell_construct)从放入的[晶结](^trickster:items/mana/knots)中抽取魔力，将媒质存入自身的储备。这份储备也为[执行 Iota](^createmanaindustry:mana_industry/tricks)所施放的咒法学法术供能。

[模块化法术组构台](^trickster:items/infrastructure/modular_spell_construct)的四个核心共用同一份媒质储备。储备已满，或晶结无法提供所需魔力时，转换失败，两份储备均保持原状。
