```json
{
  "title": "动力注魔台",
  "icon": "createmanaindustry:kinetic_mana_generator",
  "ordinal": 3,
  "associated_items": [
    "createmanaindustry:kinetic_mana_generator"
  ],
  "category": "createmanaindustry:mana_industry"
}
```

动力注魔台将旋转动力用于补充[晶结](^trickster:items/mana/knots)。把[充能阵列](^trickster:items/mana/charging_array)、[法术组构台](^trickster:items/infrastructure/spell_construct)或[模块式组构台](^trickster:items/infrastructure/modular_spell_construct)放在它输出面紧邻的一格，再放入晶结并接通动力。

传动轴位于输出面的另一侧。

;;;;;

<|page-title@lavender:book_components|title=应力与充能|>

滚动控件可设定每转速的应力需求，范围为 **4 至 256**，默认为 4。

默认每刻获得的魔力为：

**应力设定 × 转速绝对值 × 0.001 G**

例如，应力设定为 4、转速为 256 时，每刻供给 1.024 G。换算比例可由服务器调整。

;;;;;

<|page-title@lavender:book_components|title=留出容器|>

充能阵列中的魔力按放入的晶结数量平分；两种组构台只补充中央晶结。只有可接收传统魔力的容器能够受益，已满或无限的晶结不会继续储存。

没有转动或网络过应力时，充能停止。护目镜可用于查看当前负载。

;;;;;

<|page-title@lavender:book_components|title=自动更换晶结|>

机械臂可以把充能阵列和两种组构台设为取料点或送料点。阵列可使用全部晶结槽；组构台只开放中央晶结槽，不搬运法术核心。

你可以把已充能的晶结取走，再送入下一枚，或直接把晶结留在组构台中供法术使用。
