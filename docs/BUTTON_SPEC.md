# LUMO 按钮与表面设计规范

> 适用版本：1.04
> 相关源码：`app/src/main/java/com/lumobrowser/app/ui/components/Surfaces.kt`、`ui/theme/Shape.kt`、`ui/theme/LumoTheme.kt`、`ui/theme/Motion.kt`
> 相关测试：`app/src/test/java/com/lumobrowser/app/ui/components/ButtonStateTest.kt`、`SurfaceElevationTest.kt`

---

## 一、为什么需要这份规范

这一版收到的报告是「按下去的反馈和按钮的形状对不上」。审计之后，问题不是某一条修饰符写错了，而是**同一件事在三个按钮里各写了一遍**，三份副本各自漂移：

| 症状 | 真实原因 |
| --- | --- |
| 图标按钮按下去，涟漪是一个方块，从圆角外面溢出来 | `LumoIconButton` 的 `clickable` 挂在 **48dp 触控区**上，而按钮的视觉形状是里面那个 **44dp 圆角容器**；外层的 `clickable` 之前没有 `clip`，涟漪就按 48dp 的方形铺开 |
| 禁用按钮看起来像「能按，只是内容空了」 | 填色会淡到 45%，**描边却保持全强度**；一圈清晰的轮廓围着一个变暗的形状 |
| 浅色色票点下去没有任何反应 | 色票的涟漪颜色硬编码 `Color.White`，而八个色票里有三个是近白色 |
| 卡片贴在已经带阴影的面板上时，阴影叠成一块黑 | 父子两层各自画自己的 `Modifier.shadow`，中间没有协调 |

三条修法对应上面三行，第四条（父子阴影）在更早的版本已经用 `LocalLumoOnElevatedSurface` 解决。

**规范的核心主张**：按钮的外观不是「三个 Composable 各自的参数」，而是**一个状态表**。`LumoButtonStyle` 就是那张表 —— 图标按钮、主按钮、文字按钮都去问同一个对象「这个纹理在启用/禁用/按下时画什么」，所以答案只有一份，不可能互相矛盾。

---

## 二、五态定义

### 1. 正常态

填色、内容色、描边全部按用户选的**纹理**（`ButtonTexture`）原样输出，不做任何换算。

- 文本色不是写死的。`LumoPrimaryButton` 用的是 `style.contentStrongFor(enabled)`，而 `contentStrong` 由 `contrastContent(fill, brand, fallback)` 按 WCAG 相对对比度算出来（`contrastRatio` 门槛见 `Surfaces.kt`），所以换主题色之后标签仍然可读，不需要人工挑色。
- 纹理的装饰在同一个 `drawBehind` 里画：`highlightTop` 是一道从上边缘往下约 45% 高度的白色竖向渐变（α 0.14），`specular` 是沿对角线从 `(0,height)` 到 `(width,0)` 的高光带（α 0.10 → 透明 → 0.05）。**两者都是画在 `clip(shape)` 之后的**，所以高光不会跑到圆角外面。

### 2. 按压态

两条反馈同时发生，都必须走 `MotionConfig`，不能是写死的毫秒数：

- **缩放**：`.lumoPress(interaction, pressedScale = style.pressScale)`，读数取自 `motion.pressScaleDown`（Immersive 0.955 / Soft 0.975 / Minimal 0.99），回弹用 `motion.pressSpring()`。
- **涟漪**：`lumoRipple(color)`，颜色由调用方按自己的底色挑（见第七节）。

按下缩放的实现是 `graphicsLayer` 的 `scaleX/scaleY`，**只改绘制不改布局**，所以按下去不会让邻居跳动。

### 3. 禁用态

**填色、内容色、描边三者一起淡化，不允许任何一个落下。** 这是本版修掉的那条缺陷。

淡化规则统一在 `Color.disabledIf(disabled)` 里：

```
alpha' = (alpha * 0.45f).coerceAtLeast(0.08f)
```

- `0.45` 是「明显变暗但仍认得出原本是什么颜色」的折中。
- `coerceAtLeast(0.08f)` 是地板。没有它，一个本来就半透明的标签（例如 `Color(0x14F7F9FF)`）乘完会变成完全看不见 —— 用户看到的是「这个按钮消失了」，而不是「这个按钮不可用」。

描边走 `BorderStroke?.fadedIf(faded)`：

- **实心描边**（`SolidColor`）跟填色一起淡化，宽度与色相都不变。
- **渐变描边原样返回**。理由是没有单一 alpha 可以调；把渐变「淡一半」等于替用户发明第二个渐变，而那并不是他选的纹理。返回原样是唯一诚实的处理。

对外只暴露两个方法，调用方不需要知道 `fadedIf` 的存在：

```kotlin
fun borderFor(enabled: Boolean): BorderStroke? = border.fadedIf(!enabled)
fun quietBorderFor(enabled: Boolean): BorderStroke? = quietBorder.fadedIf(!enabled)
```

> **回归测试**：`ButtonStateTest.a disabled border fades along with its fill` 就是钉这一条的 —— 它同时断言淡化的方向、宽度不变、色相不变。谁把 `borderFor` 改回 `border`，这条会红。

### 4. 主题切换态

- **阴影颜色随主题走**，不是固定的黑。`theme.shadowAmbient` / `theme.shadowSpot`：深色主题是 `Color.Black` α 0.28 / 0.34；浅色主题是 `Color(0xFF2B3A5C)` α 0.18 / 0.24。浅色主题上糊一层黑阴影会显脏，带一点蓝的深灰才是这个配色下的自然投影。
- **发丝线随主题走**。`theme.hairline(alpha)` 在深色主题是白色 α `alpha`，在浅色主题是 `material.outline` α `alpha * 0.70`。旧版是固定黑色 α 0.55，在浅色主题上等于给每张卡、每个 chip、每个色票描一圈 49.5% 的黑边。
- **强调色过渡**用 `animateColorAsState(..., animationSpec = motion.accentSpec)`；动画被关掉时 `accentSpec` 会是 `snap()`，颜色直接跳过去而不是慢慢爬。
- **系统栏图标对比**在组合成功后、该帧绘制之前由 `SideEffect` 修正（`applySystemBarAppearance(window, isDark)`）。

### 5. 触控区域

- 最小触控目标是 `LumoMinTouchTarget = 48.dp`。
- **视觉尺寸可以小于 48dp，触控区不行。** `LumoIconButton` 的可见圆是 44dp，外面套一层 `size(maxOf(touchTarget, containerSize))` = 48dp 的可点区域；`AccentPicker` 的色点可见直径 30dp，外面套 48dp。
- **`clip` 不影响命中范围。** 这一条是本次修复的关键：在 48dp 的外层 `Box` 上加 `.clip(shape)` 只改变**绘制**的裁剪，指针输入仍然按整块 48dp 判定。所以「让涟漪留在按钮形状里」和「保住 48dp 触控区」并不冲突。
- 主按钮与文字按钮用 `defaultMinSize(minHeight = LumoMinTouchTarget)`，文字再长也不会把高度压到 48dp 以下。

---

## 三、三种按钮的对照表

| | `LumoIconButton` | `LumoPrimaryButton` | `LumoTextButton` |
| --- | --- | --- | --- |
| 形状 | `RoundedCornerShape(theme.buttonRadius.md)` | `RoundedCornerShape(theme.buttonRadius.lg)` | `RoundedCornerShape(theme.buttonRadius.sm)` |
| 填色 | `style.fillFor(enabled)` | `style.fillStrongFor(enabled)` | `style.fillQuietFor(enabled)` |
| 描边 | `style.borderFor(enabled)` | `style.borderFor(enabled)` | `style.quietBorderFor(enabled)` |
| 内容色 | `contentFor` / `tint` | `style.contentStrongFor(enabled)` | `tint ?: style.contentQuietFor(enabled)` |
| 涟漪 | `contentColor` α 0.22 | `contentColor` α 0.24 | `color` α 0.20 |
| 间距 | 44dp 容器 / 21dp 图标 | `horizontal = 22.dp, vertical = 13.dp` | `horizontal = 14.dp, vertical = 9.dp` |
| 触控 | 外层 48dp（带 `clip(shape)`） | `defaultMinSize(minHeight = 48.dp)` | `defaultMinSize(minHeight = 48.dp)` |

`LumoChip` 用 `theme.buttonRadius.pill`（`999.dp`），因为 chip 的语义就是胶囊，不跟随全局圆角。

---

## 四、修饰符链的顺序本身就是规范

按钮的绘制链在 `Modifier.lumoButtonSurface` 里，顺序不能换：

```
shadow(elevation, shape, clip = false, ambientColor = theme.shadowAmbient, spotColor = theme.shadowSpot)
  → clip(shape)
  → background(fill)
  → border(border, shape)
  → drawBehind { highlightTop / specular }
```

每一段的理由：

1. **`shadow` 在最外层、并且和后面用同一个 `shape`。** 阴影的轮廓就是按钮的轮廓，所以圆角按钮的投影自然是圆角的 —— 「阴影和形状不匹配」在结构上不可能发生。`clip = false` 指的是**不裁内容**，不是不裁阴影形状。
2. **`clip(shape)` 在 `background` 和 `border` 之前。** 否则 `drawBehind` 里的高光和渐变会溢出圆角。
3. **`clickable` 必须在 `clip` 之后。** Compose 的涟漪被约束在它所绘制的节点上，`clip` 会把它裁住。这就是 `LumoIconButton` 的修法：

```kotlin
Box(
    modifier = modifier
        .size(touch)                 // 48dp：命中范围
        .clip(shape)                 // 只裁绘制，不动命中范围
        .clickable(
            interactionSource = interaction,
            indication = lumoRipple(contentColor.copy(alpha = 0.22f)),
            onClick = onClick,
        )
)
```

4. **`clip` 在 `clickable` 之前、且在 `lumoButtonSurface` 内部也是一样**。主按钮和文字按钮的链序是 `defaultMinSize → lumoPress → lumoButtonSurface（内含 clip）→ clickable → padding`，所以它们本来就没有溢出的涟漪 —— 只有图标按钮因为「触控层与视觉层是两个 Box」才踩到。

---

## 五、形状 token

`RadiusTokens`（`ui/theme/Shape.kt`）从一个基准值派生出整套：

| token | 值 | 用途 |
| --- | --- | --- |
| `sm` | `(base * 0.55f).coerceAtLeast(3.dp)` | 文字按钮 |
| `md` | `base` | 图标按钮 |
| `lg` | `base * 1.35f` | 主按钮、卡片、面板 |
| `pill` | `999.dp` | chip、地址栏、圆形头像位 |

`theme.radius` 来自用户的「圆角」设置（`RadiusTokens.of(prefs.cornerRadiusDp)`，钳在 0..32）。

`theme.buttonRadius` 是**按按钮专用的覆写**：

```kotlin
val buttonRadius: RadiusTokens
    get() = if (prefs.buttonCustomShape) RadiusTokens.of(prefs.buttonCornerRadiusDp) else radius
```

也就是说默认情况下 `buttonRadius === radius`，三个按钮的 md/lg/sm 是**同一组 token 的三个刻度**，不是三套彼此无关的数字。用户单独打开「自定义按钮形状」之后才分叉。

---

## 六、阴影规则

| 条件 | 结果 |
| --- | --- |
| 纹理的 `shadowElevation == 0.dp`（`Outlined`、`Flat`） | 不画阴影 |
| 按钮处于禁用态 | 不画阴影（`enabled` 参与判断） |
| 位于一个已经带阴影的表面上（`LocalLumoOnElevatedSurface` 为真） | 不画阴影 |

第三条是父子叠影的解：`LumoButtonStyle.hasShadow` 之上还有一层 `val stacked = LocalLumoOnElevatedSurface.current`，`lumoButtonSurface` 里的条件是 `style.hasShadow && enabled && !stacked`。

`LocalLumoOnElevatedSurface` 由**承载内容的容器**供应，不是由画背景的组件供应：`BrowserScreen`、`HomeScreen`、`SettingsScreen` 的 `SettingsPanel` 各供应一次（`treatment.shadowElevation > 0.dp`）。全 App 只有两处 `Modifier.shadow` —— `surfaceTreatment` 和 `lumoButtonSurface` —— 所以「谁能有阴影」是集中可数的。

表面纹理到高度的映射在 `lumoShadowElevation`：`Glass` / `Elevated` 在 `elevated` 时 14.dp，`Elevated` 非 elevated 时 6.dp，`Outlined` 一律 0.dp；并且 `simplified`（性能模式）时**无条件返回 0.dp**。

---

## 七、涟漪规则

- 默认用 `lumoRipple(color)`，它包的是 `rememberRipple(bounded = true, color)`。
- **`indication = null` 在这个项目里是被禁止的**，唯一的例外是模态面板背后的遮罩（`ExternalLinkSheet` 的 scrim），理由写在那一行：铺满整页的涟漪会被读成「页面在反应」，而不是「面板在反应」。
- **涟漪颜色必须相对它所覆盖的底派生，不能写死。** 白底用白涟漪等于没有反馈。色票的做法：

```kotlin
indication = lumoRipple(readableOn(Color(argb.toInt())).copy(alpha = 0.30f))
```

`readableOn(background)` 在亮底上返回近黑、暗底上返回近白。
- `bounded = true` 是默认值，配上一段能把它裁住的 `clip`，涟漪才真正落在形状内。

---

## 八、本次修复清单

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `ui/components/Surfaces.kt` | `LumoButtonStyle` | 新增 `borderFor(enabled)` / `quietBorderFor(enabled)` |
| `ui/components/Surfaces.kt` | 类外私有 | 新增 `BorderStroke?.fadedIf(faded)` |
| `ui/components/Surfaces.kt` | `LumoIconButton` | 外层 48dp `Box` 在 `size` 与 `clickable` 之间插入 `.clip(shape)` |
| `ui/components/Surfaces.kt` | `LumoIconButton` / `LumoPrimaryButton` / `LumoTextButton` | `border` 参数改为状态感知的 `borderFor` / `quietBorderFor` |
| `ui/SettingsScreen.kt` | `ColorSwatchRow` | 涟漪颜色 `Color.White` → `readableOn(Color(argb.toInt()))` |

**未做 / 已知遗留**：

- `ColorSwatchRow` 的形状仍是硬编码的 `RoundedCornerShape(10.dp)`，没有走 `theme.radius` token。它是唯一一处这样的圆角。
- `LumoChip` 与 `LumoMenuItem` 的形状一致性没有单独审。
- 「加载中 / 成功 / 失败」三个按钮状态**没有实现**。`ButtonTexture` 的七个值描述的是材质，不是进度状态；把两者混在一起会得到一个既不是纹理也不是状态的枚举。如果要做，它应该是独立的 `ButtonProgressState`，而不是往 `ButtonTexture` 里加值。

---

## 九、验证状态

| 项目 | 状态 |
| --- | --- |
| `:app:compileDebugKotlin` | 通过，0 错误 0 警告 |
| `ButtonStateTest`（8 例，纯 JVM） | 通过 |
| `SurfaceElevationTest` | 通过 |
| 真机目视确认（按压回弹、涟漪裁剪、禁用态） | **未执行** —— 本机没有连接设备，也没有可用的模拟器镜像 |

`ButtonStateTest` 覆盖的是**算术部分**（淡化方向、地板值、渐变描边不动、阴影判据）；「涟漪看起来是否落在形状里」是渲染结果，只能靠真机目视，本版没有条件做，所以在上表里如实标记。
