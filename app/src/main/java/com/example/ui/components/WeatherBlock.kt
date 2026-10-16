package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.source.weather.DailyWeather
import com.example.data.source.weather.WeatherFailureKind
import com.example.data.source.weather.WeatherService
import com.example.ui.theme.TextSecondary

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
private val BlockWidth = 92.dp

@Composable
fun WeatherBlock(
    state: WeatherService.WeatherState,
    /** 现在的钟点（0..23）。传进来是为了可测，也为了这一层不再读一次系统时间。 */
    hourOfDay: Int,
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
                caption = listOfNotNull(
                    weather.cityName.takeIf { it.isNotBlank() },
                    weather.airQualityLabel()
                ).joinToString(" "),
                captionColor = TextSecondary,
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
            modifier = modifier
        )
    }
}

/**
 * 天气块唯一的骨架：三行、固定宽、行距固定。
 *
 * 三个状态都走这里，是「任何天气下这张卡的高度都不变」这条不变式的执行点 ——
 * 以后加第四个状态（比如夜间模式），也必须填满这三行，不许自己长出新的一行。
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
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .width(BlockWidth)
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
                modifier = Modifier.size(15.dp)
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
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
