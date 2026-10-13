```json
{
  "title": "灵质反转",
  "icon": "hexcasting:battery",
  "ordinal": 11,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "additional_search_terms": [
    "灵质反转",
    "卓越戏法",
    "Anima",
    "真名"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

[卓越戏法](^createmanaindustry:mana_industry/great_tricks)。没有输入，返回 void；每次消耗 **4 Anima**。

在同一施法链中，施放一次开启反转，再施放一次解除。解除也需要支付灵魂。余额不足时，原有开关状态不会改变。

;;;;;

<|great-trick@createmanaindustry:book_components|trick-id=createmanaindustry:ectoplasmic_inversion|>

;;;;;

<|page-title@lavender:book_components|title=反转作用范围|>

咒法学的正常空间范围变为其外部，包括哨卫扩展区域；真名原有的距离豁免仍有效。

戏法师本体的近距读写、通道交互与魔力转换器检查同样反转。原本没有距离限制的戏法不增加限制。世界边界、区块加载与编辑权限仍需满足。

;;;;;

<|page-title@lavender:book_components|title=交换施法的代价|>

原来需要媒质的操作改用魔力，原来需要魔力的操作改用媒质，并按等价值支付**两倍**。

这里只用服务器设定的每桶媒质与每桶魔力含量比较等价值。它不使用[转化者之戏法](^createmanaindustry:mana_industry/mana_conversion)随世界繁忙程度变化的汇率，也不改变实际流体配方。

;;;;;

<|page-title@lavender:book_components|title=默认费用示例|>

默认一桶媒质相当于 40 份紫水晶粉，一桶魔力含 2,048 G。

- 原需 1 份紫水晶粉：改付 102.4 G。
- 原需 1,024 G：改付 40 份紫水晶粉等价媒质。

改付媒质时，仍沿用咒法学原有的生命透支条件。Anima 本身不参与交换。

;;;;;

<|page-title@lavender:book_components|title=让自己的真名通过|>

反转期间，任何玩家或施法环境的真名扫描都会跳过你的真名，即使扫描来自无人法术环或另一维度。

其他未受豁免的真名仍可能引发事故。跳过你的真名，并不使整份列表都免于检查，也不会撤去目标的其他施法条件。

;;;;;

<|page-title@lavender:book_components|title=随这一场法术结束|>

反转状态随同一施法链的子调用与长法术续跑保留。独立施放的法术不会因此交换费用或反转范围。

施法链全部结束、被取消，或施法者退出服务器时，真名豁免撤去。同一玩家若还有另一条开启反转的施法链，豁免会保持到最后一条解除或结束。
