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

将给定数量的魔力从施法者的储备中取出，转换为媒质。由玩家施放时，媒质存入物品栏中可再次充能的物品，包括护甲与副手中的物品。不返回任何值。

全部产物必须能够存下。没有接收容器或空间不足会引发失误；不会消耗魔力，也不会存入部分媒质。

;;;;;

<|page-title@lavender:book_components|title=由组构台施放|>

[法术组构台](^trickster:items/infrastructure/spell_construct)从放入的[晶结](^trickster:items/mana/knots)中抽取魔力，将媒质存入自身的储备。这份储备也为[执行 Iota](^createmanaindustry:mana_industry/tricks)所施放的咒法学法术供能。

[模块化法术组构台](^trickster:items/infrastructure/modular_spell_construct)的四个核心共用同一份媒质储备。储备已满，或晶结无法提供所需魔力时，转换失败，两份储备均保持原状。

;;;;;

<|page-title@lavender:book_components|title=交换比例|>

给定 **y** 点魔力，所得媒质为 `y / R × 10000` 点，其中：

`R = 25.6 + 76.8 / (1 + exp(-0.1 × (mspt - 25)))`

**mspt** 是最近完成的服务端 tick 的执行毫秒数，不计等待下一 tick 的间歇。在 25 ms 时，64 点魔力可转换为 10000 点媒质。

数量须为有限正数。魔力向下取至千分之一点，媒质向下取至整数；消耗或产出取整为零时会引发失误。

反向交换可由咒法学的**织结之策略**完成。在相同比例下，输入 1 消耗 10000 点媒质，向晶结存入 64 点魔力。需要同时安装两个模组。
