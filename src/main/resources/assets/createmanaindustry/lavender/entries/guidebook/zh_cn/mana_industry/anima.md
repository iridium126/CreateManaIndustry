```json
{
  "title": "灵魂与 Anima",
  "icon": "createmanaindustry:liquid_soul_bucket",
  "ordinal": 10,
  "fabric:load_conditions": {
    "condition": "fabric:all_mods_loaded",
    "values": [
      "hexcasting",
      "trickster"
    ]
  },
  "associated_items": [
    "hexcasting:battery",
    "createmanaindustry:liquid_soul_bucket"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

卓越戏法所用的灵魂以 **Anima** 计量。用注液器把液态灵魂注入咒法学的**媒质之瓶**，即可储存它。每桶默认含 **32 Anima**，此值可由服务器调整。

液态灵魂可由悦灵燃烧室产生的灵魂雾冷凝获得，冷凝时需要冷却液。

;;;;;

<|page-title@lavender:book_components|title=瓶子的容量|>

灵魂容量由瓶子实际的最大媒质容量决定。默认每份紫水晶粉等价容量对应 **0.1 Anima**。

例如，可存 64,000 份紫水晶粉等价媒质的瓶子，可再存 6,400 Anima。媒质与灵魂分别记录；注入灵魂不改变已有媒质。

;;;;;

<|page-title@lavender:book_components|title=施放时取用|>

把装有 Anima 的瓶子带在物品栏中。施放时会按物品栏顺序取用，必要时可由多个瓶子共同支付。

余额不足时引发失误，未付足的灵魂不会被扣去。接近满容量时，注液器只接纳能容纳的液量；灵魂目前不能通过分液池抽回。
