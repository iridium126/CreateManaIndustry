```json
{
  "title": "片段与 iota",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 0,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "读取Iota",
    "运行Iota",
    "iota"
  ]
}
```

咒法学用 **iota** 表示信息，伟大语言则用**片段**。读取时，数、向量、布尔值、实体与列表可直接成为对应片段；其他 iota 由 **iota 片段**承载。

咒法学的 null 与 garbage 在戏法师一侧均作为 void。两套语言的空值并非完全相同。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_iota|>

从给定槽位的物品中读取 iota，返回对应片段。不传入槽位时，使用另一只手。

读取范围为 **16 格**。物品中没有可读取的 iota 时会引发失误，而不是返回一个空法术。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:eval_iota|>

接受承载可运行图案或咒术列表的 **iota 片段**；其余输入会作为咒法学的初始栈。

成功后，返回栈顶的值；栈为空时返回 null 的对应空值。运行失败则返回 void。费用由咒法学的媒质供应支付。

;;;;;

<|page-title@lavender:book_components|title=在哪里施放|>

玩家施放时使用自己的媒质物品，符合原版条件时也可生命透支，不必另外手持法杖。

组构台施放时，使用[内部媒质储备](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media)，以自身为中心取得 32 格范围。需要玩家或手持物品的操作仍须满足其原有条件。

;;;;;

<|page-title@lavender:book_components|title=把戏法交给咒法学|>

咒法学的**解结之精思**可以读出戏法师法术，将整棵法术树装入戏法 iota；再用**赫尔墨斯之策略**运行。

参数列表应放在戏法 iota 下方。一个游戏刻内完成的法术可把结果带回栈；转入续跑的长法术不再返回结果。详见咒法学手册的“戏法学图案”。
