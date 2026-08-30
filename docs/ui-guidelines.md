# Camera2 Magic UI 规范

Compose / Miuix 相关的全部约定与踩坑约束。改 `app/src/main/java/com/nothing/camera2magic/ui/` 下任何文件之前先读本文件；其余架构约束在仓库根的 [AGENTS.md](../AGENTS.md)。现有页面（MainActivity 主骨架、Scope/Settings/About/AppConfig/ThemeSettings）都是本规范的参照实现，改前先看同类页面怎么写。

## 组件与形状

- 所有组件用 Miuix（`top.yukonga.miuix.kmp.*`，内部已 squircle 渲染圆角），不要引入 Material 组件。返回按钮 `MiuixIcons.Back`。
- **自定义形状用 squircle modifier**（`top.yukonga.miuix.kmp.squircle.*`），非 Miuix 组件的手搓形状**禁止 `RoundedCornerShape`** clip/background。按性能三选一：
  - 非点击纯色背景 → `squircleBackground`（无 offscreen layer，**不要再 clip**）；
  - 图片 / 必须裁剪内容 → `squircleClip`（一个 offscreen layer）；
  - 可点击 → `squircleSurface` + `.clickable{}`（涟漪裁进圆角）；条件可点击时退化为 `squircleBackground`。
- 现存少数 `RoundedCornerShape` 用点（AboutScreen 的 `textureBlur(shape = ...)`）是**参数豁免**：那是 Miuix blur API 的 Shape 入参、非手搓 clip/background；待 miuix-blur 暴露 squircle Shape 后再迁移，新增 clip/background 零容忍。
- **卡片圆角同心跟随系统屏幕圆角**：一律用 [rememberConcentricCardRadius](../app/src/main/java/com/nothing/camera2magic/ui/component/ConcentricRadius.kt)（= 屏幕半径 − 12.dp 边距，下限回落 `CardDefaults.CornerRadius`；直屏自动回 16dp）。miuix `Card` 传 `cornerRadius = rememberConcentricCardRadius()`，AboutScreen 毛玻璃卡的 `textureBlur(shape = ...)` 也传同一值保持裁剪一致；`CardSegment` 默认已接入（显式传参可覆盖）。新增卡片禁止写死 `16.dp` 圆角。
- **弹出菜单圆角**：库内 `ListPopupContent` 写死 16dp 且未透出参数。裸用 `OverlayListPopup` 时以 `popupModifier = Modifier.squircleClip(rememberConcentricCardRadius())` 外层裁剪补齐（AppConfigScreen 的应用菜单即是）；miuix 的 `OverlayDropdownPreference` / `OverlaySpinnerPreference` 无注入点，暂保持库默认 16dp——**不要本地复刻偏好组件**，等上游透出圆角参数后统一替换。底部弹层类 Dialog（含 dropdown dialog）库内已按「屏幕圆角 − 边距」推导（钳 32–48dp），无需处理。

## 页面骨架

统一骨架：`Scaffold` + `AdaptiveTopAppBar(scrollBehavior)` + `LazyColumn`。

- LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`。
- `contentPadding` **只设 top 不设 bottom**（bottom 交给末尾 Spacer）：首个 item 是 Card/表单时加 `item { Spacer(12.dp) }`；`SmallTitle` 开头**不加**（自带 8dp 上下边距）。末尾 Spacer 分两型：**主界面 Tab 用 `item { Spacer(Modifier.height(24.dp)) }`**（导航条高度已由 bottomPadding 进 contentPadding，再加 navigationBarsPadding 会双倍）；**二级页用 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`**。需要覆盖小窗/桌面窗口 caption bar 时用 AppConfigScreen 的手写变体（navigationBars + captionBar 合计高度），别只写 navigationBarsPadding 就以为全覆盖。
- **二级页面签名禁止 `bottomPadding: Dp` 参数**——靠末尾 Spacer 自适应。**例外是主界面 Tab**（经 MainActivity 底栏切换的页面：主页状态区 / Scope / Settings）：外层主 Scaffold 持有 bottomBar，必须接 `bottomPadding` 透传给 `contentPadding`（SettingsScreen/ScopeScreen 现状即是）。判断标准 = 页面是否被 Navigator push，不是「有没有列表」。
- 顶栏一律走 [AdaptiveTopAppBar](../app/src/main/java/com/nothing/camera2magic/ui/component/AdaptiveTopAppBar.kt)，别直接用 miuix `TopAppBar`——将来宽屏差异收敛在这一层。

## 毛玻璃顶栏/底栏

每个页面自己一份 backdrop，主骨架的底栏另持一份（MainActivity 已如此），互不共享：

```kotlin
val backdrop = rememberBlurBackdrop()          // BlurExt.kt，随 LocalBlurEnabled 门控
val blurActive = backdrop != null
// bar 色：if (blurActive) Color.Transparent else surface
// 内容区根节点追加 .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
```

嵌套 layerBackdrop 合法（AboutScreen 的 hero 内层就是）。新页面漏掉 `layerBackdrop` 的症状：毛玻璃开着但顶栏底下是实色。搜索页在 BlurredBar 位置套 `searchStatus.TopAppBarAnim(backgroundColor = 同 bar 色)`，见 ScopeScreen。

## 缺口

- **横屏缺口**：Miuix Scaffold 不自动 padding 内容，每个二级页根 LazyColumn 在 `.fillMaxSize()` 后加 `Modifier.horizontalCutoutPadding()`（只补水平 `displayCutout ∪ navigationBars`，竖屏为 0）；顶栏由自身 inset 处理。主 Tab 内容居中在缺口内侧，无需此项。
- 搜索框动态 top padding 宽屏恒为 0。

## 列表性能

**多组件卡片必须拆成独立 lazy item**：LazyColumn 里禁止 `item { Card { 多行 } }`——整卡一次性组合，行多时滚动卡顿。改用 [GroupedCardItems](../app/src/main/java/com/nothing/camera2magic/ui/component/GroupedCardItems.kt)：

```kotlin
groupedCardItems("scope", items = listOf(
    CardItem("row1") { /* row */ },
    CardItem("row2") { /* row */ },
))
```

要点：

- 分角背景语义：首/末段 `CardSegment` 用 `squircleSurface`（fill+clip，**必须 clip**，否则段内 clickable 的方角涟漪溢出圆角）；中间段纯 `background`（无 offscreen 最省）。这些已封在组件里，别在调用处绕开它手拼。
- `outerBottomPadding` 按被替换 Card 原本的 bottom padding 传（组件默认 6.dp）。
- 条件行用 `buildList` 组 items，不要在 lambda 里写 if 空段。
- **不加 item 动画**（拆分是不可见的纯性能优化）；需要动画自行在 item 内 `Modifier.animateItem(...)`，且 **placement spec 不能设 null**——否则下方各组硬跳、无展开感。
- **豁免**：纯静态文本卡与 AboutScreen 的视差 hero 保持单 `item { Card }`——拆分反而破坏视差测量。

卡片间距：水平 12.dp；纵向间距走每项自身 bottom padding，**不用 `Arrangement.spacedBy`**（与 lazy item 拆分冲突）。TextField 表单不包 Card，直接同样 padding。

## Dialog

- **按钮顺序** `not_modified | cancel | confirm`，三按钮 `weight(1f)` + `spacedBy(8.dp)`，confirm 用 `textButtonColorsPrimary()`（参照 ThemeSettingsScreen）。
- **长内容 Dialog**：Miuix `WindowDialog` 手机上不限 content 高度，过长会把底部按钮顶出屏——包 `Column(Modifier.heightIn(max = 500.dp))`，滚动区 `weight(1f, fill = false).verticalScroll(...)`，按钮作非加权子项固定底部。
- **弹 Dialog 的入口行设 `holdDownState`**（dialog 打开期间保持按下态，MIUI 惯例；ThemeSettingsScreen/AppConfigScreen 已按此写）。
- 单选弹窗（互斥选项）优先 miuix `WindowDropdownDialog` + `DropdownDefaults.dialogDropdownColors()`（选中行高亮 + 点选即生效 + 底部仅取消），别手搓 TextButton 列表加确认钮。构造其条目的 `remember` **key 不能含回调 lambda**（每次重组都可能是新实例），用 `rememberUpdatedState` 接住。
- 弹层位置微调走 [ListPopupDefaults](../app/src/main/java/com/nothing/camera2magic/ui/component/ListPopupDefaults.kt)，不要在调用处手算 offset。

## 颜色 token

状态色统一走 [StatusColors](../app/src/main/java/com/nothing/camera2magic/ui/theme/StatusColors.kt)（`healthy`/`danger`/`runState`/`runStateContainer`）。**禁止屏幕里散落 `Color(0xFF...)`**；合法颜色源仅 `MiuixTheme.colorScheme.*` 与 `StatusColors`（后者内部的固定色谱是唯一字面量处——警示语义刻意不随 Monet 壁纸漂移，别「顺手」改成动态色）。深色判定读 `LocalAppDarkMode.current`，禁止 `isSystemInDarkTheme()`（见 AGENTS.md 深色判定单点）。

## 数据与动画纪律

- **Flow 收集一律 `collectAsStateWithLifecycle()`**，不用 compose runtime 的 `collectAsState`——后台时上游不再驱动重组。
- **强跳过友好的状态形状**：UiState `data class` 必须 `@Immutable`；大集合字段用 `ImmutableList` 等 kotlinx.collections.immutable 类型，且**从生产端（ViewModel）就是这个类型**——UI 层末端才转的话，中途任何一处裸 `List` 参数都会让该 composable 不可 skip。
- **帧率级 State 不能在组合期读**：滚动折叠比例、动画进度这类每帧都变的值，`derivedStateOf` 包完再 `by` 解包等于把失效上浮到整个 restart scope。一律以 `State<T>` 或 `() -> T` 透传，消费方在 **layout/draw 阶段**读：布局用自定义 `layout{}` modifier，绘制合并进已有的 `graphicsLayer{}` / `onDrawBehind{}`。只有夹紧成布尔或离散档位后才可在组合期读。
- **持续动画不可见时必须停**：[BgEffectModifier](../app/src/main/java/com/nothing/camera2magic/ui/component/effect/BgEffectModifier.kt) 的帧循环由 `alpha: () -> Float` 门控——判定只能在 draw 里做（alpha 是延迟读的 lambda，组合期拿不到；draw 快照读会在 alpha 回升时自动重触发，无需额外唤醒）。新增持续动画照此模式挂 alpha 门控，不停就是滚出屏幕后纯空转。协程侧门控用 `snapshotFlow { alpha() > 0f }.first { it }`，**不能 `by` 解包**（正是上一条禁止的组合期读）。
