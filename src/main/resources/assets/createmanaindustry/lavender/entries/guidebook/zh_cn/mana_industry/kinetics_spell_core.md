```json
{
  "title": "动力法术核心",
  "icon": "createmanaindustry:kinetics_spell_core",
  "ordinal": 4,
  "associated_items": [
    "createmanaindustry:kinetics_spell_core"
  ],
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "trickster",
      "bits_n_bobs"
    ]
  },
  "category": "createmanaindustry:mana_industry"
}
```

动力法术核心是一种由 **Bits 'n' Bobs 齿轮链**驱动的[法术核心](^trickster:items/infrastructure/spell_core)。先在核心上抄入法术，再把它装入[模块式法术组构台](^trickster:items/infrastructure/modular_spell_construct)的核心槽，并把组构台接入齿轮链。

没有接入转动的链时，这枚核心不会执行法术。

;;;;;

<|page-title@lavender:book_components|title=执行的快慢|>

链速为 32 时，执行上限与普通核心相同；链速加倍，上限也相应加倍。它改变的是每刻可执行的圈数，不是法术所需的魔力。

四枚核心分别执行各自的法术，仍从组构台中央的晶结中取用魔力。

;;;;;

<|page-title@lavender:book_components|title=接入应力网络|>

每枚动力法术核心会给所在链的控制端增加 **4 × 转速绝对值 SU** 的负载。多枚核心的负载会相加，应提前为整条链留足应力容量。

可以用[动力注魔台](^createmanaindustry:mana_industry/charging_knots)补充中央晶结，再用[显示链接](^createmanaindustry:mana_industry/display_link)传入机器状态。
