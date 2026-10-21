package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.source.weather.DailyWeather
import com.example.data.source.weather.WeatherFailureKind
import com.example.data.source.weather.WeatherService
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.measureTextHeight
import com.example.ui.theme.scaledWithText

/**
 * 总览页的当天天气，占的是**原来那个完成率圆环的位置**。
 *
 * ## 三个状态必须分开画
 *
 * [WeatherService.WeatherState] 的三个分支语义互不重叠，合并显示就会出现
 * 「界面上看着有东西，其实那不是今天的天气」：
 * - [WeatherService.WeatherState.Loading]：还不知道，显示 `--`，**不编数字**。
 * - [WeatherService.WeatherState.Available]：显示城市、天气、温度。
 * - [WeatherService.WeatherState.Unavailable]：显示**归类后的短标签**。
 *
 * ## 三态共用一套骨架，是为了让主卡的高度永远不变
 *
 * 这一块和左边那一列在同一个 Row 里。任何一态比另一态多一行、少一行，
 * 整张主卡就比原来高一截，底下所有内容的起始位置全跟着移 —— 天气一天里
 * 换好几次状态，那张卡就会在用户眼前反复跳。
 *
 * 失败态尤其容易破这条：它原本是「红色标题 + 两行灰色原因」，
 * 与另外两态的三行结构根本对不齐（实测会差出整整一行）。
 * 所以现在三个分支都走 [WeatherBlockFrame]，行数、行高、间距、字号全部一致，
 * **只有字重和内容不同**。
 *
 * ## 失败态不用红色
 *
 * 天气是**次要信息**（它不参与待办、不影响任何操作），而红色在这个 App 里
 * 是留给「用户必须处理的事」的。为了一个拉不到就自动放弃的装饰块铺一片红，
 * 等于每次断网都在主卡上喊一嗓子。改为中性色 + 一个图标 + 一句短标签，
 * 信息量没少，噪音没了。
 *
 * ## 弱化靠字重，不靠把字压成不合格的灰
 *
 * [com.example.ui.theme.TextMuted]（#78827B）在白卡上实测约 **3.98:1**，
 * 达不到 9sp 小字要的 4.5:1。真要用「更弱」的颜色，就得先降明度，
 * 而那一档（本项目的 `TextTertiary`）已经接近正文色、深得比正常态还重。
 *
 * 于是这里改用 [TextSecondary]（约 8:1，稳过 AA）配合递减的字重
 * （Normal → Medium → SemiBold）来表达「这是次要信息」——
 * 对比度不打折，层次靠排版而不是靠牺牲可读性。
 */
/**
 * 天气块的固定宽度。
 *
 * ## 它是被谁定的
 *
 * 不是拍脑袋，也不是「看着差不多」：左边那一列（周次徽标、学期名、日期、
 * 「N 门课」）占着剩下的全部空间，加宽这里就等于**从日期行那里抢**。
 * 所以每一分都花在**最长的一句上** ——
 * 「杭州 · 空气轻度污染 138」（两字地名 + 四字等级 + 三位数）。
 *
 * ## 现值是怎么校出来的（模拟器实测，非估算）
 *
 * `uiautomator dump` 取 Compose 的语义边界（那是排版宽度，不是墨迹宽度）：
 * - 9sp 的「杭州 · 空气良 67」= **180px = 68.6dp**（11 字）
 * - 12sp 的「12 门课 · 31.3 小时」= **273px = 104.0dp**（15 字）
 *
 * 拿这两条反推，字宽落在「CJK 约 0.93em（含 M3 labelSmall 那 0.5sp 字距）、
 * 数字 0.5625em、空格 0.25em、`·` 约 0.5em」，两条都在 ±2.5% 内对上。
 * 最坏那句按这套参数约 **93dp**；按各字取上界（CJK 1em + 0.5sp 字距、
 * `·` 0.5em）约 **106dp** —— 108.dp 是照**后者**定的，留约 2dp 余量。
 *
 * ## 411dp 屏上左边还剩多少
 *
 * 卡片内容宽 363dp（屏 411 − 页面边距 6×2 − 卡内边距 18×2），减掉 12dp 间隔
 * 与 108dp 天气块 = **243dp**；实测日期行「2026年10月19日 星期一」是 232.8dp，
 * 还剩 10dp。加到 120dp 以上日期就会折行，那才是真被挤坏的地方。
 *
 * ## 为什么还要有这条测试
 *
 * 那格 `maxLines = 1` + `TextOverflow.Ellipsis`，超出**不报错、不崩**，
 * 只是指数被悄悄截掉最后一位 —— 恰好是最需要看全的那一行。
 * 预算由 [WeatherCaptionBudgetTest] 守着；**别靠缩字号解决溢出**。
 */
internal val BlockWidth = 108.dp

/**
 * 天气块**不缩窄**。
 *
 * ## 为什么不能跟着屏宽收
 *
 * 最后一行那句「杭州 · 空气轻度污染 138」按测试里的上界模型是 **105.9dp**
 * （见 [WeatherCaptionBudgetTest]）——宽度只比 108.dp 少 2dp 余量。
 * 任何比 108dp 窄的下限都会让最坏那句被 Ellipsis 截掉**指数**，
 * 而指数恰恰是这一行最需要看全的东西。
 *
 * 曾经试过「窄屏收到 84dp」，实测最坏那句放不下，指数会被截——
 * 那是拿信息完整性换版面，属于不能接受的交易，所以撤回。
 *
 * ## 窄屏上省下来的空间从哪来
 *
 * 从**左边那一列**：日期在窄屏上折行、被 140dp 的固定卡高裁掉半截，
 * 那是真正被挤坏的地方。修法是让日期退回它该有的字号（见
 * `MiuType.HeroDate`）并单行显示，而不是把天气块压扁。
 * 天气块保持 108dp，于是窄屏上左列少 108dp，但左列要装的东西也变小了。
 */
internal val BlockWidthMin = BlockWidth

/** 最后一行小字的字号。与 [BlockWidth] 是一对，改一个必须同时量另一个。 */
internal val CaptionFontSize = 9.sp

/**
 * 天气块的两种排法。
 *
 * ## 为什么要有两种
 *
 * [COLUMN] 是 411dp 基准屏上定稿的那一版：固定 108dp 宽的三行竖排，
 * 和日期并排放在主卡右半边。它的问题在**窄屏**上暴露出来：
 * 320dp 的屏上左列只剩 152dp，大号日期（22.sp 要 232.8dp）放不下，
 * 于是日期被迫降档——而 V3 定稿要的就是那个 22.sp。
 *
 * [STRIP] 是给窄屏用的第二版：横着排成一行，铺满整行宽度。
 * 主卡在窄屏上改成上下两段（日期一段、天气一条），于是：
 *
 * - 日期拿到**整行**宽度（`W − 48` 而不是 `W − 168`），22.sp 放得下；
 * - 天气那一行本来就比竖着排宽得多，那句「杭州 · 空气轻度污染 138」
 *   再也不会被 Ellipsis 截掉指数。
 *
 * 两版**高度都固定**（竖排三行 / 横排一行），所以「任何天气下这张卡的高度
 * 都不变」这条不变式两边都成立。
 */
enum class WeatherLayout {
    /** 竖排三行，固定 [BlockWidth] 宽。与日期并排时用。 */
    COLUMN,

    /** 横排一行，铺满可用宽度。日期另起一段时用。 */
    STRIP
}

/** 天气块图标尺寸（竖排版）。跟着字放大：字大了、图标不变，图标就小成一点。 */
private val WeatherIconSize = 15.dp

/** 天气条里图标的尺寸（横排版）。比竖排大一档：横排时它是一行里唯一的图形锚点。 */
private val WeatherStripIconSize = 16.dp

@Composable
fun WeatherBlock(
    state: WeatherService.WeatherState,
    /** 现在的钟点（0..23）。传进来是为了可测，也为了这一层不再读一次系统时间。 */
    hourOfDay: Int,
    /**
     * 排法：[WeatherLayout.COLUMN] 竖排三行（基准屏），
     * [WeatherLayout.STRIP] 横排一行（窄屏主卡的第二段）。
     */
    layout: WeatherLayout = WeatherLayout.COLUMN,
    /**
     * 竖排版（[WeatherLayout.COLUMN]）这一块的宽度。
     *
     * 108.dp 是基准屏那一档：最坏那句要 105.9dp（理由见 [BlockWidth]）。
     * 宽屏上这一栏独占一栏、字号又跟着宽屏字阶放大，所以那里传更大的值。
     * 横排版（[WeatherLayout.STRIP]）不用这个参数——它铺满父级给的宽度。
     */
    blockWidth: Dp = BlockWidth,
    /**
     * 这一块的修饰符。
     *
     * **宽度不由它决定**：竖排版固定 [blockWidth]，横排版铺满父级给的宽度。
     */
    modifier: Modifier = Modifier
) {
    when (state) {
        is WeatherService.WeatherState.Available -> {
            val weather = state.weather
            val text = weather.textAt(hourOfDay)
            WeatherBlockFrame(
                icon = glyphFor(text).icon(),
                headline = text.ifBlank { "天气" },
                headlineColor = MaterialTheme.colorScheme.onSurface,
                headlineWeight = FontWeight.SemiBold,
                value = weather.temperatureRange() ?: "--",
                valueColor = MaterialTheme.colorScheme.onSurface,
                valueWeight = FontWeight.ExtraBold,
                caption = weatherCaption(weather),
                captionColor = TextSecondary,
                layout = layout,
                blockWidth = blockWidth,
                modifier = modifier
            )
        }

        // 加载中与不可用共用同一套「弱化」配色，区别只在第三行那一句：
        // 用户看到的是「天气 / --° / 读取中」与「天气 / --° / 网络不通」，
        // 一眼分得清「还在等」和「等不到」。
        WeatherService.WeatherState.Loading -> WeatherBlockFrame(
            icon = Icons.Outlined.CloudQueue,
            headline = "天气",
            headlineColor = TextSecondary,
            headlineWeight = FontWeight.Medium,
            value = "--°",
            valueColor = TextSecondary,
            valueWeight = FontWeight.SemiBold,
            caption = "读取中",
            captionColor = TextSecondary,
            layout = layout,
            blockWidth = blockWidth,
            modifier = modifier
        )

        is WeatherService.WeatherState.Unavailable -> WeatherBlockFrame(
            icon = WeatherUnavailableLabel.iconOf(state.kind),
            headline = "天气",
            headlineColor = TextSecondary,
            headlineWeight = FontWeight.Medium,
            value = "--°",
            valueColor = TextSecondary,
            valueWeight = FontWeight.SemiBold,
            // **只显示归类，不显示 state.reason。** 理由写在类注释里：
            // 92.dp 宽的格子里放不下技术细节，硬放会折行并撑高主卡。
            caption = WeatherUnavailableLabel.labelOf(state.kind),
            captionColor = TextSecondary,
            layout = layout,
            blockWidth = blockWidth,
            modifier = modifier
        )
    }
}

/**
 * 天气块最后一行：「杭州 · 空气良 68」。
 *
 * ## 为什么中间是「·」而不是空格
 *
 * 空格分隔的「杭州 空气良 68」读起来像一句**断了的话** —— 地名后面没有动词，
 * 读者会以为是没显示完、或者中间漏了个分隔符。「·」明确地说：
 * 这是同一行里的**两件独立事实**，谁都不是谁的后半句。
 *
 * 城市与空气本来就不相干：一个回答「哪儿的」，一个回答「空气怎么样」，
 * 它们共用一行只因为新起一行会让主卡变高（见 [WeatherBlockFrame]），
 * 既然是被挤到一行的，就得用标点承认它们是并排的两件事。
 *
 * ## 任一段缺失时，标点不许留下
 *
 * 空气拉不到是常事（另一个端点，挂了就挂）。只显示「杭州」，
 * 绝不能显示成「杭州 ·」—— 那等于告诉用户有一段数据被吞了。
 */
internal fun weatherCaption(weather: DailyWeather): String =
    listOfNotNull(
        weather.cityName.takeIf { it.isNotBlank() },
        weather.airQualityLabel()
    ).joinToString(SEPARATOR)

/** 两段之间的分隔符，带两侧空格。UI 只有一个拼接点，符号与空格一起定在这里。 */
private const val SEPARATOR = " · "

/**
 * 天气块的骨架：两种排法共用同一份内容与配色。
 *
 * 三个状态都走这里，是「任何天气下这张卡的高度都不变」这条不变式的执行点 ——
 * 以后加第四个状态（比如夜间模式），也必须填满这三行（或横排那一行），
 * 不许自己长出新的一行。
 */
@Composable
private fun WeatherBlockFrame(
    icon: ImageVector,
    headline: String,
    headlineColor: Color,
    headlineWeight: FontWeight,
    value: String,
    valueColor: Color,
    valueWeight: FontWeight,
    caption: String,
    captionColor: Color,
    layout: WeatherLayout,
    blockWidth: Dp,
    modifier: Modifier
) {
    when (layout) {
        WeatherLayout.COLUMN -> WeatherBlockColumn(
            icon = icon,
            headline = headline,
            headlineColor = headlineColor,
            headlineWeight = headlineWeight,
            value = value,
            valueColor = valueColor,
            valueWeight = valueWeight,
            caption = caption,
            captionColor = captionColor,
            blockWidth = blockWidth,
            modifier = modifier
        )

        WeatherLayout.STRIP -> WeatherBlockStrip(
            icon = icon,
            headline = headline,
            headlineColor = headlineColor,
            headlineWeight = headlineWeight,
            value = value,
            valueColor = valueColor,
            valueWeight = valueWeight,
            caption = caption,
            captionColor = captionColor,
            modifier = modifier
        )
    }
}

/**
 * 竖排版：固定 108dp 宽的三行。
 *
 * 基准屏（411dp 及以上）上日期与天气并排时用它，
 * 宽度写死 108.dp 是因为最坏那句要 105.9dp（理由见 [BlockWidth]）。
 */
@Composable
private fun WeatherBlockColumn(
    icon: ImageVector,
    headline: String,
    headlineColor: Color,
    headlineWeight: FontWeight,
    value: String,
    valueColor: Color,
    valueWeight: FontWeight,
    caption: String,
    captionColor: Color,
    blockWidth: Dp,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            // 宽度固定：108.dp 是基准屏那一档（最坏那句要 105.9dp，
            // 窄了指数会被截，理由见 [BlockWidthMin]）；宽屏上由调用方给更大的值。
            .width(blockWidth)
            .clip(RoundedCornerShape(12.dp))
            // 不挂 clickable（2026-10-16 用户定）：原来点一下强制重拉，
            // 现在自动刷新窗口是 3 小时，手动刷新只会让人拿到同一份数据。
            // 更要紧的是：挂一个点得动、点了又不再有任何反馈的控件，
            // 比不挂更糟 —— 用户会以为那一下没生效，或者更糟，以为 App 卡了。
            // 要重拉就退出重进，那里有 3 小时窗口的判断在管着。
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = headlineColor,
                modifier = Modifier.size(WeatherIconSize.scaledWithText())
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = headlineWeight),
                color = headlineColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = valueWeight),
            color = valueColor,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(1.dp))
        // 城市名与空气读数压在最后一行：它们回答的是「哪儿的、空气怎么样」，
        // 比温度次要，但**有值时都必须显示** —— 否则那串温度不知道说的是哪个城市。
        //
        // 空气这一段放在同一行而不是新起一行，是为了守住上面那条
        // 「任何天气下这张卡的高度都不变」：新起一行就意味着多一个天气
        // 多一行高，主卡会跳。
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
            fontSize = CaptionFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 横排版：天气条，一行铺满。
 *
 * ## 为什么窄屏要另做一版
 *
 * 竖排版固定 108.dp 宽，是为了在 411dp 屏上能和日期并排而不挤掉日期。
 * 窄屏上恰恰是它把日期挤掉的：320dp 的屏上左列只剩 152dp，
 * 而 22.sp 的日期要 232.8dp，于是日期被迫降档（V3 定稿被打破）。
 *
 * 横排之后天气独占一整行，那句「杭州 · 空气轻度污染 138」
 * 有 272dp 可用（320dp 屏上的卡内容宽），**再也不会被 Ellipsis 截掉指数**。
 *
 * ## 一行里的顺序
 *
 * 图标 → 天气状况 → 温度 → 城市与空气。
 * 前三样是「此刻的天气」，最后一样是「哪儿的、空气怎么样」，
 * 所以最后一样拿剩下的宽度（`weight(1f)`）并允许省略：
 * 城市名比温度更该被保留，所以它排在前面、气温留在中间。
 *
 * 高度固定一行，所以「任何天气下这张卡的高度都不变」这条不变式仍然成立
 * （见 [weatherStripHeight]：高度由实际文字量出来，不是估的）。
 */
@Composable
private fun WeatherBlockStrip(
    icon: ImageVector,
    headline: String,
    headlineColor: Color,
    headlineWeight: FontWeight,
    value: String,
    valueColor: Color,
    valueWeight: FontWeight,
    caption: String,
    captionColor: Color,
    modifier: Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = headlineColor,
            modifier = Modifier.size(WeatherStripIconSize.scaledWithText())
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = headlineWeight),
            color = headlineColor,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = valueWeight),
            color = valueColor,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = captionColor,
            fontSize = CaptionFontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 天气条（横排）需要多高。
 *
 * 主卡的高度是**量出来的**（见 `heroCardHeightFor`），所以横排这一段的高度
 * 也得量，不能拿字号乘系数估 —— 中文回退字体的行高比字号本身大不少，
 * 估出来的那一档正好偏小，而偏小就是被裁的那一档。
 *
 * ## 必须用**和实际渲染完全一致**的 style
 *
 * 这里量的是「行盒高度」，而行盒高度由 style 里的 **lineHeight** 决定，
 * 不是由 fontSize 决定：M3 的 `titleMedium` 行高 24.sp、`labelMedium` 16.sp、
 * `labelSmall` 16.sp。所以拿 `TextStyle(fontSize = 15.sp)` 去量，
 * 量到的是 15.sp 的自然行高（≈18dp），比实际那 24dp 少 6dp ——
 * 差的这 6dp 就是字被裁掉的那一截。
 *
 * 三个 style 与 [WeatherBlockStrip] 里那三段 Text 逐字一致。
 */
@Composable
fun weatherStripHeight(): Dp {
    val headlineHeight = measureTextHeight(
        text = "多云",
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
    )
    val valueHeight = measureTextHeight(
        text = "26°",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp
        )
    )
    val captionHeight = measureTextHeight(
        text = "杭州 · 空气良 68",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = CaptionFontSize)
    )
    return maxOf(
        WeatherStripIconSize.scaledWithText(),
        headlineHeight,
        valueHeight,
        captionHeight
    )
}

/**
 * 失败类别 → 界面上的图标与**一个词**。
 *
 * 抽成不带 Composable 的对象有两个理由：
 * 1. 能直接跑单测 —— 这句话是用户判断「要不要管这件事」的唯一依据，
 *    把它改成「暂无」或换掉措辞不该只有截图能发现（而「暂无」正是这里要避免的：
 *    用户会理解成「今天没天气」，而真实情况是没连上）。
 * 2. 图标与 `SyncFailureAdvice` 用的是同一套（`WifiOff` / `CloudOff`），
 *    两处失败在同一个 App 里用同一个符号，不用再记一遍哪个图标是哪类。
 */
internal object WeatherUnavailableLabel {

    fun labelOf(kind: WeatherFailureKind): String = when (kind) {
        // 「网络不通」给用户一个明确的下一步：检查网络/代理。
        WeatherFailureKind.OFFLINE -> "网络不通"
        // 「暂未取到」只陈述事实，不指挥用户做什么 —— 这一类查网络没有帮助，
        // 能做的只有下次进 App 再试，而手动刷新已经取消了。
        WeatherFailureKind.REJECTED -> "暂未取到"
    }

    fun iconOf(kind: WeatherFailureKind): ImageVector = when (kind) {
        WeatherFailureKind.OFFLINE -> Icons.Outlined.WifiOff
        WeatherFailureKind.REJECTED -> Icons.Outlined.CloudOff
    }
}

/**
 * 天气文字 → 图标类别。
 *
 * 天气码先译成中文（「小雨」「多云」），所以按**关键词**归到几个大类，
 * 而不是穷举每一种现象 —— 归类的目的只是「一眼看出要不要带伞」，
 * 区分「毛毛雨」和「小雨」对这件事没有区别。
 *
 * 单独拆成枚举（而不是直接返回 ImageVector）是为了能被单测钉住：
 * 判定顺序有讲究，先判**降水的量级**（雷 / 雪 / 雨），再判天空状态。
 * 「雷阵雨」含「雨」，「多云转小雨」也含「多云」，反过来先找「云」就永远命中错的那一档。
 */
enum class WeatherGlyph {
    THUNDERSTORM,
    SNOW,
    RAIN,
    HAZE,
    OVERCAST,
    CLOUDY,
    SUNNY,
    NEUTRAL
}

internal fun glyphFor(text: String): WeatherGlyph = when {
    text.contains("雷") -> WeatherGlyph.THUNDERSTORM
    text.contains("雪") || text.contains("雹") || text.contains("冻") -> WeatherGlyph.SNOW
    text.contains("雨") -> WeatherGlyph.RAIN
    text.contains("雾") || text.contains("霾") || text.contains("沙") || text.contains("尘") ->
        WeatherGlyph.HAZE
    text.contains("阴") -> WeatherGlyph.OVERCAST
    text.contains("云") -> WeatherGlyph.CLOUDY
    text.contains("晴") -> WeatherGlyph.SUNNY
    else -> WeatherGlyph.NEUTRAL
}

private fun WeatherGlyph.icon(): ImageVector = when (this) {
    WeatherGlyph.THUNDERSTORM -> Icons.Outlined.Thunderstorm
    WeatherGlyph.SNOW -> Icons.Outlined.AcUnit
    WeatherGlyph.RAIN -> Icons.Outlined.WaterDrop
    WeatherGlyph.HAZE -> Icons.Outlined.Dehaze
    WeatherGlyph.OVERCAST -> Icons.Outlined.Cloud
    WeatherGlyph.CLOUDY -> Icons.Outlined.WbCloudy
    WeatherGlyph.SUNNY -> Icons.Outlined.WbSunny
    WeatherGlyph.NEUTRAL -> Icons.Outlined.WbTwilight
}
