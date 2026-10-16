```json
{
  "title": "魔力齿轮",
  "icon": "createmanaindustry:mana_cogwheel",
  "ordinal": 6,
  "associated_items": [
    "createmanaindustry:mana_cogwheel",
    "createmanaindustry:andesite_encased_mana_cogwheel",
    "createmanaindustry:brass_encased_mana_cogwheel"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

魔力齿轮可以从雾场中获得转动。用动力雾化器持续供应[液态魔力](^createmanaindustry:mana_industry/liquid_mana)，并让齿轮处在产生的雾场中，即可得到 **256** 的转速。

若供给的是液态媒质雾，转速则为 **−256**。两种雾使齿轮反向转动。

;;;;;

<|page-title@lavender:book_components|title=让雾维持转动|>

齿轮的应力容量为 **8 × 转速绝对值 SU**，即在上述转速下提供 2,048 SU。离开有效雾场后，它不再提供转动。

雾化器需要流体和动力来维持雾场。可以把齿轮用于驱动产线，但仍应为雾化器安排持续的供给。
