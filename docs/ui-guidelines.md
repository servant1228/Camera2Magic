# Camera2 Magic UI 规范

Compose / Miuix 相关的全部约定与踩坑约束。改 `app/src/main/java/com/nothing/camera2magic/ui/` 下任何文件之前先读本文件；其余架构约束在仓库根的 [AGENTS.md](../AGENTS.md)。

页面清单（现存全部 6 个，`MainActivity` 是主骨架）：**主 Tab** = `HomePage` / `ScopePage`→`ScopeScreen` / `SettingsScreenContent`（经底栏 pager 切换）；**二级页** = `ThemeSettingsScreen` / `AboutScreen` / `AppConfigScreen`（经 Navigator push）。`ThemeSettingsScreen` 是骨架、Dialog、卡片圆角三方面最贴合本规范的参照实现，改前先看它。

本文件区分两种句式：**「必须/禁止」= 现有代码已全面遵守的约束**；**「已知偏差」= 代码尚未收敛、但不要跟着抄的地方**。凡是本文件里出现的 API 名都在当前代码里有真实调用点——写不出对应实现的处方一律不写。

## 组件与形状

- 所有组件用 Miuix（`top.yukonga.miuix.kmp.*`，内部已 squircle 渲染圆角），**禁止引入 Material / Material3 组件与主题**。当前 `androidx.compose.material` 的全部引用只有 `ModuleStatus.kt` 里的三个 icons，那是允许的（material-icons-extended 是显式依赖）。返回按钮 `MiuixIcons.Back`。
- **非 Miuix 组件的手搓圆角形状禁止 `RoundedCornerShape` 做 clip/background**，改用 squircle：
  - 需要裁剪内容（图片、段内涟漪）→ `squircleClip` / `squircleSurface`（各有一个 offscreen layer）；
  - 只需要纯色背景 → 直接 `Modifier.background(color)`（无 offscreen 最省），配合 miuix 组件自身的 `cornerRadius` 参数。
  - 当前真实用点只有两处：`AppConfigScreen` 的 `popupModifier = Modifier.squircleClip(...)`，与 `GroupedCardItems.CardSegment` 的首/末段 `squircleSurface`。
- **药丸 / 圆形是明确豁免**：`CircleShape` 没有 squircle 对应物，因此搜索框（`SearchBar`）与 iOS 悬浮底栏（`LiquidGlassNavigationBar`）的 `clip`/`background` 用 `CircleShape` 是正确写法，不要按上一条去「修」。`MainActivity` 自建的 `SquirclePillShape`（基于 `addSquircleRect`）同理，它只作为 `textureBlur(shape = ...)` 的入参。
- **`RoundedCornerShape` 的唯一残留是参数豁免**：`AboutScreen` 有 3 处，全部作为 miuix blur API 的 `textureBlur(shape = ...)` 入参（不是手搓 clip/background）。其中两处毛玻璃卡传的是 `rememberConcentricCardRadius()` 的结果，logo 那处写死 16dp。待 miuix-blur 暴露 squircle Shape 后再迁移；新增 clip/background 零容忍。
- **卡片圆角同心跟随系统屏幕圆角**：一律用 [rememberConcentricCardRadius](../app/src/main/java/com/nothing/camera2magic/ui/component/ConcentricRadius.kt)（`(rememberNavSystemCornerRadius() - inset).coerceAtLeast(CardDefaults.CornerRadius)`，`inset` 是**参数默认值** 12.dp，全部 12 个调用点都用默认值）。下限是 `CardDefaults.CornerRadius` = 16dp，**所以系统圆角 ≤ 28dp 的设备实际恒为 16dp**、同心效果不生效，直屏（radius 0）同样回落 16dp。现存 9 个 `Card(` 调用点全部传了 `cornerRadius`，零硬编码 16dp——新增卡片照做。
- **`CardSegment` 默认已接入**（`cornerRadius: Dp? = null` → `?: rememberConcentricCardRadius()`），显式传参可覆盖；另有 `topCornerRadius`/`bottomCornerRadius` 可单独覆盖上下两端。
- **弹出菜单圆角**：库内 `ListPopupContent` 写死 16dp 且未透出参数。裸用 `OverlayListPopup` 时以 `popupModifier = Modifier.squircleClip(rememberConcentricCardRadius())` 外层裁剪补齐（`AppConfigScreen` 的应用菜单即是）；miuix 的 `OverlayDropdownPreference`（6 处）/ `OverlaySpinnerPreference`（1 处）无注入点，暂保持库默认 16dp——**不要本地复刻偏好组件**，等上游透出圆角参数后统一替换。

## 页面骨架

统一骨架：`Scaffold` + `AdaptiveTopAppBar(scrollBehavior)` + `LazyColumn`。

- **顶栏一律走 [AdaptiveTopAppBar](../app/src/main/java/com/nothing/camera2magic/ui/component/AdaptiveTopAppBar.kt)**，别直接用 miuix `TopAppBar`——将来宽屏差异收敛在这一层。注意它目前是**纯转发**（只转发 `title/modifier/color/scrollBehavior/navigationIcon/actions/bottomContent` 七个参数，没有任何自适应逻辑）。
  - **唯一豁免 = `AboutScreen` 的 `SmallTopAppBar`**：它需要 `defaultWindowInsetsPadding = false` 与 `titleColor`，转发层没有这两个参数，也没有 Small 变体。要收敛必须先扩 `AdaptiveTopAppBar`，别直接把 `AboutScreen` 改成普通顶栏（会破坏视差 hero 的 inset 处理）。
- LazyColumn 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`。**唯一豁免**：`ScopeScreen` 的搜索结果列表在 `popupHost` 内，作用域里拿不到 `scrollBehavior`，因此只有前两个。
- `contentPadding` **只设 top 不设 bottom**（bottom 交给末尾 Spacer）：首个 item 是 Card/表单时加 `item { Spacer(Modifier.height(12.dp)) }`；`SmallTitle` 开头**不加**（自带 8dp 上下边距）。
  - **已知偏差**（不要跟着抄）：`HomePage` 的顶部 12dp 来自 `StatusSection` 内容自身的 `padding(top = 12.dp)`；`AppConfigScreen` 是 LazyColumn 修饰符上的 `padding(top = 16.dp)`，且它把整个 `innerPadding` 传给了 `contentPadding`（靠 `contentWindowInsets` 只含水平方向才等效于「不设 bottom」）。规范写法是独立的 12.dp Spacer item。
- **末尾 Spacer 分两型**：**主界面 Tab 用 `item { Spacer(Modifier.height(24.dp)) }`**（导航条高度已由 `bottomPadding` 进 `contentPadding`，再加 `navigationBarsPadding` 会双倍）；**二级页用 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }`**。需要覆盖小窗/桌面窗口 caption bar 时用 `AppConfigScreen` 的手写变体（`navigationBars.bottom + captionBar.bottom`），别只写 `navigationBarsPadding` 就以为全覆盖。
  - 底部 inset **只能在一个地方施加**。`ScopeScreen` 的搜索结果列表曾同时在 LazyColumn 修饰符与末尾 Spacer 上加 `navigationBarsPadding()`，产生双倍留白——已收敛为只在末尾 Spacer 上加。
  - **已知偏差**：`AboutScreen` 与 `AppConfigScreen` 的末尾 Spacer 写在 `item {}` 内部而不是独立 item，且 `AppConfigScreen` 没有 24.dp 基线。
- **二级页面签名禁止 `bottomPadding: Dp` 参数**——靠末尾 Spacer 自适应。**例外是主界面 Tab**（`HomePage` / `ScopePage` / `ScopeScreen` / `SettingsScreenContent`）：外层主 Scaffold 持有 bottomBar，必须接 `bottomPadding` 透传给 `contentPadding`。判断标准 = 页面是否被 Navigator push，不是「有没有列表」。当前三个二级页签名里都没有该参数，保持。

## 毛玻璃顶栏/底栏

每个页面自己一份 backdrop，主骨架的底栏另持一份（`MainActivity` 已如此），互不共享：

```kotlin
val backdrop = rememberBlurBackdrop()          // BlurExt.kt
val blurActive = backdrop != null
// bar 色：if (blurActive) Color.Transparent else surface
// 内容区根节点追加 .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
```

- `rememberBlurBackdrop()` 的门控是 `LocalBlurEnabled.current` **且** `isRuntimeShaderSupported()`，任一不满足返回 `null`。
- 顶栏/底栏一律包 `BlurredBar(backdrop, blurActive)`（`BlurExt.kt`）。**`blurActive` 要显式传**：省略时它会退回自己的 `rememberBlurEnabled()` 默认值，与页面算出的值可能不一致。
- 当前 6 个页面 + 底栏全部既取了 backdrop 又调用了 `layerBackdrop`，零遗漏。新页面漏掉 `layerBackdrop` 的症状：毛玻璃开着但顶栏底下是实色。
- 嵌套 `layerBackdrop` 合法：`AboutScreen` 的 hero 内层与 `IosLiquidGlassNavigationBar` 内层都另起一份 `rememberLayerBackdrop()`。
- **`blurActive` 不总等于 `backdrop != null`**：`AboutScreen` 是 `backdrop != null && scrollProgress == 1f`，并且 bar 色在视差 hero 可见期间强制透明（即使 blur 关闭）。照抄上面两行 recipe 会做出错误的 AboutScreen 顶栏。
- 搜索页在 `BlurredBar` **内层**套 `searchStatus.TopAppBarAnim(backgroundColor = 同 bar 色)`（不是替代 `BlurredBar`），见 `ScopeScreen`。

## 缺口

- **横屏缺口**：Miuix Scaffold 不自动 padding 内容，二级页根 LazyColumn 在 `.fillMaxSize()` 后加 `Modifier.horizontalCutoutPadding()`（定义在 [ui/util/WindowSize.kt](../app/src/main/java/com/nothing/camera2magic/ui/util/WindowSize.kt)——**文件名有误导性，里面只有这一个函数**）；它只补水平 `displayCutout ∪ navigationBars`，竖屏为 0。顶栏由自身 inset 处理。
  - **已知偏差**：`AppConfigScreen` 加在 `.fillMaxHeight()` 之后，且同时通过 `contentWindowInsets` + `contentPadding` 重复施加了同一水平 inset；`AboutScreen` 根本没加在列表上，而是把水平 inset 走 `contentPadding` 并手动给顶栏补 padding（因为它关掉了 `defaultWindowInsetsPadding`）。
- 主 Tab 内容居中在缺口内侧，通常无需此项；但 `ScopeScreen` 的主列表是手算 `calculateStartPadding/calculateEndPadding` 自己处理的。

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
- `outerBottomPadding` 按被替换 Card 原本的 bottom padding 传。两个默认值不同：`groupedCardItems` 默认 **6.dp**，`CardSegment` 自身默认 **0.dp**。
- 条件行用 `buildList` 组 items，不要在 lambda 里写 if 空段。
- **不加 item 动画**（拆分是不可见的纯性能优化，当前全模块零 `animateItem`）；需要动画自行在 item 内 `Modifier.animateItem(...)`，且 **placement spec 不能设 null**——否则下方各组硬跳、无展开感。
- **豁免**：纯静态文本卡保持单 `item { Card }`（`DeviceInfoCard` 即是）。
- **已知违例**（新代码不要照抄）：`StatusSection` 的 `item(key = "status")` 一个 item 里塞了 3 个 Card + 提示文本 + Dialog；`AppConfigScreen` 把整页塞进单个 `item {}`（该文件已 import `CardSegment` 并用在其中一组，其余没拆）。`AboutScreen` 的 `about` item 也是两个 Card 未拆——注意它**不是**视差豁免：真正的视差 hero 在 LazyColumn **之外**（`BgEffectBackground` 里的兄弟 Column），lazy 侧只有一个纯测量占位的 `logoSpacer` item。

卡片间距：水平 12.dp（含 `GroupedCardItems` 的两个默认值）；纵向间距走每项自身 bottom padding，**LazyColumn 上不用 `Arrangement.spacedBy`**（与 lazy item 拆分冲突；现存 5 处 `spacedBy` 全在普通 Row/Column 上）。TextField 表单不包 Card，直接同样 padding。

## Dialog

全模块只有两个 Dialog，都是 miuix `WindowDialog`：`ThemeSettingsScreen.DensityScaleDialog`（参照实现）与 `StatusSection.HookModeDialog`。

- **按钮顺序 `cancel | confirm`**，两个按钮各 `weight(1f)` + `Arrangement.spacedBy(8.dp)`，confirm 用 `ButtonDefaults.textButtonColorsPrimary()`。
- **弹 Dialog 的入口行设 `holdDownState`**（dialog 打开期间保持按下态，MIUI 惯例），如 `ThemeSettingsScreen` 的 `ArrowPreference`。注意**该参数只存在于 `BasicComponent` 家族（`ArrowPreference` 等）与 `IconButton` 上，miuix `Card` 没有**——所以以 `Card` 作 Dialog 入口（`StatusSection` 的 Hook 模式卡）无法遵守这条，别去硬加参数。`AppConfigScreen` 那处 `holdDownState` 配的是 `OverlayListPopup`（弹出菜单）而不是 Dialog，同样是正确用法。
- **单选弹窗**当前是 `WindowDialog` + `TextButton` 列表 + 确认按钮（`HookModeDialog`），选中行用 `textButtonColorsPrimary()` 高亮。它控制的 `main_hook_mode` 目前是死键（见 AGENTS.md 配置流），所以这个 Dialog 只是 UI 参照、不影响 Hook 行为。
- 弹层位置微调走 [ListPopupDefaults](../app/src/main/java/com/nothing/camera2magic/ui/component/ListPopupDefaults.kt)，不要在调用处手算 offset。它不只是「微调」：内含四角对齐分支、RTL 镜像与窗口边界钳制，唯一的字面量是 20.dp 起始边距。

## 颜色 token

- 状态色统一走 [StatusColors](../app/src/main/java/com/nothing/camera2magic/ui/theme/StatusColors.kt)：属性 `healthy` / `danger`，以及**带 `RunState` 参数的函数** `runState(state)` / `runStateContainer(state)`（`RunState` 枚举也在同一文件）。
- **禁止在 `ui/screen/**` 里出现 `Color(0xFF...)` 字面量**（当前零违例）。合法颜色源是 `MiuixTheme.colorScheme.*` 与 `StatusColors`。
- 色值字面量只允许出现在这 4 个文件：`theme/StatusColors.kt`（状态色谱）、`theme/ThemeConfig.kt`（accent seed 调色板）、`theme/Theme.kt`（pureBlack 与背景回退）、`component/effect/ColorBlendToken.kt`（blur blend token）。`StatusColors` 里 `Stopped` 恒用固定红、不走 Monet——**警示语义刻意不随壁纸漂移，别「顺手」改成动态色**。
- **深色判定单点**：`ThemeConfig.resolveIsDark(systemDark)` 是 colorMode→isDark 的唯一实现，`Theme.kt` 里唯一一次 `isSystemInDarkTheme()` 喂给它，结果经 `LocalAppDarkMode` 下发。组合树内一律读 `LocalAppDarkMode.current`，**新增代码严禁直接 `isSystemInDarkTheme()`**（当前零违例，保持）。
- 主题枚举的用户可见名统一走 [ThemeLabels.kt](../app/src/main/java/com/nothing/camera2magic/ui/theme/ThemeLabels.kt)，禁止各自 when 映射。

## 组合根与主题状态

- **主题状态双份是设计而非缺陷**：`MainActivity` 持有 `themeConfig` state，`SettingsViewModel.uiState` 里还有一份；UI 改主题经 `onThemeConfigChanged` 回调上抛到 MainActivity 统一持久化。持久化的类型陷阱在 AGENTS.md 配置流一节（读写都走 `ConfigRepository` 的属性就安全）。
- `theme_predictive_back` 变化会 `recreateWithoutTransition()` 整个重建 Activity。根因不在 manifest：预测性返回是 `HiddenApiBypass` + 反射 `ApplicationInfo.setEnableOnBackInvokedCallback`，只在 `onCreate` 生效，所以必须重建。这也是 `MainActivity.onCreate` 唯一合法地绕过 `ConfigRepository` 直读该键的原因（要早于 Compose）。
- **ViewModel 无 DI 框架**：手写 [ViewModelFactory](../app/src/main/java/com/nothing/camera2magic/viewmodel/ViewModelFactory.kt)（`when` 分支 new，只认 `SettingsViewModel`/`HomeViewModel`，其余抛），经 `LocalViewModelFactory` 下发。
- CompositionLocal 分两类，加新的照此判断：**装配必需的用 `error()` 默认值**——`LocalConfigRepository`、`LocalViewModelFactory`、`LocalNavigator`，新 composable 直接读 `.current` 会在预览/测试里炸，这是故意的，缺 provider 是装配错误要修装配而不是给默认值；**纯表现型的给真实默认值**——`LocalThemeConfig`（注意它是 `compositionLocalOf` 而非 `static`，重组作用域不同）、`LocalAppDarkMode`、`LocalAppMonetEnabled`、`LocalBlurEnabled`。

## 数据与动画纪律

- **Flow 收集一律 `collectAsStateWithLifecycle()`**，不用 compose runtime 的 `collectAsState`——后台时上游不再驱动重组。当前 5 个收集点全部合规，零 `collectAsState`。
- **ViewModel 的 UiState `data class` 必须 `@Immutable`**（`HomeUiState`、`SettingsUiState`、`ThemeConfig` 都已标注）。注意 `SearchStatus` 是 `@Stable`——它是每次按键都 `copy()` 的活跃屏幕态，不适用 `@Immutable`。
- **`@Immutable` 加在含 `List` 字段的类上是一个未经编译器校验的承诺**：本项目**没有** kotlinx-collections-immutable 依赖，`ImmutableList` 之类的类型写出来编译不过。所以 `HomeUiState.scopeAppList: List<String>` 这类字段必须靠纪律保证——**改内容一定整体 `copy()` 换新实例，绝不原地 mutate**，否则 Compose 会跳过重组。同理，把裸 `List` 作参数往下传的 composable（`StatusSection` 的两处、`groupedCardItems` 的 `items`）本身不可 skip，这是当前已接受的代价。
- **帧率级 State 不能在组合期读**：滚动折叠比例、动画进度这类每帧都变的值，`derivedStateOf` 包完再 `by` 解包等于把失效上浮到整个 restart scope。一律以 `State<T>` 或 `() -> T` 透传，消费方在 **draw 阶段**读——合并进已有的 `graphicsLayer{}`（参照 `AboutScreen` 的视差 hero、`LiquidGlassNavigationBar` 的多处 `translationX`、`AppConfigScreen` 的 RTL 图标翻转）。只有夹紧成布尔或离散档位后才可在组合期读。
- 协程侧等待动画状态用 `snapshotFlow { ... }.first { ... }`（参照 `DampedDragAnimation`）或 `snapshotFlow { ... }.collectLatest { ... }`（参照 `LiquidGlassNavigationBar`），**不能 `by` 解包**（正是上一条禁止的组合期读）。回调型参数要先 `rememberUpdatedState` 接住再进 `snapshotFlow`，否则每次重组都是新 lambda 实例。
- **已知问题：`BgEffectModifier` 的帧循环并不随可见性停止。** 它的动画协程只由 **Boolean `playing`** 门控（`onAttach` / `update` 里起停），而 `AboutScreen` 传进去的 `playing` 是 `effectBackground`——一个「blur 是否可用」的标志，不是可见性信号。`alpha: () -> Float` 只在 `draw()` 里读，作用仅是跳过 painter 更新与 `drawRect`；`invalidateDraw()` 仍然每帧照发（上限 60fps）。**所以 hero 滚出屏幕后仍在空转。** 新增持续动画时按正确模式做：把可见性算成布尔喂给 `playing`，让协程真正取消，而不是只在 draw 里判 alpha。

## 未覆盖的子系统

本文件不逐一描述下列包的实现，只标出改动前必须知道的非显然约束：

- **`component/liquid/`**（`LiquidGlassNavigationBar` 及 `Lens`/`InnerShadow`/`Vibrancy`/`CombinedBackdrop`）：iOS 风悬浮底栏，最大的单文件。依赖 `RenderEffect`/着色器与自建 backdrop，几何全部基于 `CircleShape` 药丸（见上文形状豁免），大量 `graphicsLayer{}` 里读延迟 State。它内部还有一个 private 的 `LocalIosTabScale`。
- **`component/effect/`**（`BgEffectPainter` + `OS3BgFrag` AGSL 着色器 + `BgEffectConfig` 预设 + `DeviceType` + `ColorBlendToken`）：AboutScreen 的动态背景。着色器与预设色是成套的，单独改一边会串色；`BgEffectBackground` 里还有 840dp/600dp 的分栏宽度阈值（全模块唯一的宽屏分支）。帧循环门控见上一节的已知问题。
- **`component/animation/`**（`DampedDragAnimation`、`InteractiveHighlight`）：弹簧驱动的预测性返回与交互高光，`snapshotFlow` 用法的参照实现。
- **`component/SearchBar.kt` / `SearchStatus.kt`**：`ScopeScreen` 的搜索态机。`SearchStatus` 是 `@Stable` data class 且自带 `@Composable` 成员 `TopAppBarAnim`；top padding 随展开态 `animateDpAsState`，**没有任何宽度分支**。
- **`ui/util/`**：`WindowSize.kt` 只有 `horizontalCutoutPadding()`（名不符实）；`DeviceName.kt` 做机型市场名查询。
- **`ui/navigation3/`**（`Navigator` + `Route`）：包名是迁移遗留，实际基于 miuix-nav（`NavKey` / `rememberNavBackStack` / `NavDisplay`），不是 androidx navigation3。`Route` 4 个成员对应 6 个页面（`Main` 内含 3 个 tab）。
