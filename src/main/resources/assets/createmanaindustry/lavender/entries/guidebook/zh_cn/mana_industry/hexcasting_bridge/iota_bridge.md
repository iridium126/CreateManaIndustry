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

咒法学用 **iota** 表示信息，伟大语言则用**片段**。读取时，数、向量、布尔值与实体可直接成为对应片段；列表与其他 iota 由 **iota 片段**承载。

直接转换成片段时，咒法学的 null 与 garbage 均作为 void；整体包装的列表内部则保留原值。两套语言的空值并非完全相同。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_iota|>

从给定槽位的物品中读取 iota，返回对应片段。不传入槽位时，使用另一只手。

列表整体保留为一个 **iota 片段**，沿用咒法学的显示方式，可直接交给**运行Iota**。其中的嵌套列表和数据保持原样，不能直接使用伟大语言的列表操作。

读取范围为 **16格**。物品中没有可读取的 iota 时会失策，而不是返回一个空法术。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:eval_iota|>

接受承载可执行 iota 或咒术列表的 **iota 片段**；其余输入会作为咒法学的初始栈。

通过**赫尔墨斯之策略**运行，遵循其列表求值与暂停边界规则。仅执行最外层列表中的指令，不会自动展开内部列表；空列表不执行任何指令。

成功后，返回栈顶的值；栈为空时返回 null 的对应空值。运行失败则返回 void。费用由咒法学的媒质供应支付。

;;;;;

<|page-title@lavender:book_components|title=在哪里施放|>

玩家施放时使用自己的媒质物品，符合原版条件时也可生命透支，不必另外手持法杖。

组构台施放时，使用[内部媒质储备](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media)，以自身为中心取得32格范围。需要玩家或手持物品的操作仍须满足其原有条件。

相互调用共用同一份执行额度，跨语言调用的嵌套上限默认为32，可由服务器调整。

;;;;;

<|page-title@lavender:book_components|title=来源与恢复|>

法术环与法术组构台施放戏法 iota 时，以当前石板或组构台为施法位置；需要玩家的戏法仍须由玩家施放。

无绑定玩家的方块来源至多让五道法术继续运行；卸载或重启会使这些临时法术消散。等待中的法术可在法术槽中手动取消。

绑定施法者的法术环会记住调用时石板的位置、朝向与世界。施法者即使身处另一维度，也仍由原石板施法，并以其身份与物品满足法术要求；离线或来源区块未加载时暂停，同一来源重新可用后恢复。拆除或替换来源则终止，更换晶结按新晶结的当前余额结算。

;;;;;

<|page-title@lavender:book_components|title=把戏法交给咒法学|>

咒法学的**解结之精思**可以读出戏法学法术，将整棵法术树装入戏法 iota；再用**赫尔墨斯之策略**运行。

参数列表应放在戏法 iota 下方。一个游戏刻内完成的法术可把结果带回栈；转入续跑的长法术不再返回结果。详见咒法学手册的“戏法学图案”。
