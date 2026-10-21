package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.PresetWallpaper
import com.example.data.model.WallpaperConfig
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import com.example.ui.theme.DefaultWallpaperRes
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WallpaperCardFaceAlpha
import com.example.ui.theme.WallpaperCardFaceTint
import com.example.ui.theme.glassSurface
import java.io.File
import kotlin.math.abs

/**
 * 自定义壁纸设置弹层。
 *
 * 提供：
 * 1. 实时预览视口：展示当前壁纸与毛玻璃卡片的叠合渲染效果。
 * 2. 从系统相册选择新壁纸（SAF 图片选取，等比优化防 OOM）。
 * 3. 官方精选预设壁纸库（Kind Steel 极简冷灰粉彩、Soft Grass 清新草木绿意粉彩）。
 * 4. 恢复默认壁纸。
 * 5. 卡片文字保护遮罩调节（0% ~ 50%），解决深色或高反差自定义图片上的文字对比度问题。
 */
@Composable
fun WallpaperSettingDialog(
    currentConfig: WallpaperConfig,
    onDismiss: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectCustom: (Uri) -> Unit,
    onResetDefault: () -> Unit,
    onUpdateDimOpacity: (Float) -> Unit,
    onUpdateSoftFocus: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSelectCustom(it) }
    }

    val customBitmap: ImageBitmap? = remember(currentConfig.type, currentConfig.customPath) {
        if (currentConfig.type == WallpaperType.CUSTOM && !currentConfig.customPath.isNullOrBlank()) {
            val file = File(currentConfig.customPath)
            if (file.exists() && file.canRead()) {
                runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }.getOrNull()
            } else null
        } else null
    }

    GlassOverlayHost(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("wallpaper_dialog_confirm_button")
            ) {
                Text(
                    text = "完成",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Wallpaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "自定义壁纸",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "主界面背景与毛玻璃透光底色",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. 实时预览视口
                Text(
                    text = "效果预览",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                WallpaperLivePreviewCard(
                    config = currentConfig,
                    customBitmap = customBitmap
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 2. 从相册选择
                Text(
                    text = "相册导入",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { photoPickerLauncher.launch("image/*") }
                        .testTag("pick_custom_wallpaper_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "从系统相册选取",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (currentConfig.type == WallpaperType.CUSTOM) "已应用自定义壁纸" else "支持任意图片，自动优化存储",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        Button(
                            onClick = { photoPickerLauncher.launch("image/*") },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("pick_custom_wallpaper_button")
                        ) {
                            Text(
                                text = "选取",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 3. 精选预设
                Text(
                    text = "精选预设",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WallpaperPresets.ALL.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = when (currentConfig.type) {
                                    WallpaperType.DEFAULT -> preset.id == WallpaperPresets.DEFAULT.id
                                    WallpaperType.PRESET -> preset.id == currentConfig.presetId
                                    WallpaperType.CUSTOM -> false
                                }
                                PresetWallpaperCard(
                                    preset = preset,
                                    isSelected = isSelected,
                                    onSelect = { onSelectPreset(preset.id) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowPresets.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 4. 背景柔焦（仅自定义照片）：三档预设，不给连续滑杆
                if (currentConfig.type == WallpaperType.CUSTOM) {
                    TierSection(
                        title = "背景柔焦",
                        subtitle = "把照片细节柔化；清晰档保留原图，柔和 / 很柔是两档预设",
                        tiers = SoftFocusTiers,
                        selectedValue = currentConfig.softFocus,
                        onSelect = onUpdateSoftFocus,
                        testTag = "wallpaper_soft_focus_tiers"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 5. 文字保护遮罩：三档预设
                TierSection(
                    title = "文字保护遮罩",
                    subtitle = "增加浅色柔光层，避免深色壁纸影响文字可读性",
                    tiers = ProtectionTiers,
                    selectedValue = currentConfig.dimOpacity,
                    onSelect = onUpdateDimOpacity,
                    testTag = "wallpaper_dim_tiers"
                )

                // 5. 恢复默认壁纸按钮
                if (currentConfig.type != WallpaperType.DEFAULT || currentConfig.dimOpacity > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onResetDefault,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_default_wallpaper_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "恢复默认壁纸 (${WallpaperPresets.DEFAULT.name})")
                    }
                }
            }
        },
        modifier = modifier
    )
}

/** 壁纸参数档位：自定义参数只给预设档，不做连续滑杆。 */
private data class WallpaperTier(val label: String, val value: Float)

private val SoftFocusTiers = listOf(
    WallpaperTier("清晰", 0f),
    WallpaperTier("柔和", 0.5f),
    WallpaperTier("很柔", 1f)
)

private val ProtectionTiers = listOf(
    WallpaperTier("关闭", 0f),
    WallpaperTier("标准", 0.18f),
    WallpaperTier("加强", 0.36f)
)

@Composable
private fun TierSection(
    title: String,
    subtitle: String,
    tiers: List<WallpaperTier>,
    selectedValue: Float,
    onSelect: (Float) -> Unit,
    testTag: String
) {
    val nearest = tiers.minByOrNull { abs(it.value - selectedValue) }?.value
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tiers.forEach { tier ->
                val selected = nearest == tier.value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        )
                        .clickable { onSelect(tier.value) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tier.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 实时预览视口小组件
 */
@Composable
private fun WallpaperLivePreviewCard(
    config: WallpaperConfig,
    customBitmap: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 高度是下限：128dp 是按基准字号定的，系统字号 2.0 时
            // 预览卡里那两行字要更高的空间，写死高度会把第二行裁掉（实测截图）。
            .heightIn(min = 128.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        // 壁纸背景
        when {
            config.type == WallpaperType.CUSTOM && customBitmap != null -> {
                // 预览按真实比例显示柔焦，用户拖滑杆时能直接看到效果。
                val previewBlur = (config.softFocus * 8f).dp
                Image(
                    bitmap = customBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        // matchParentSize 不参与父级测量：背景图跟着**内容**定出来的
                        // 卡片高度走，而不是反过来把卡片撑到父级最大高度。
                        .matchParentSize()
                        .then(
                            if (config.softFocus > 0.01f) Modifier.blur(previewBlur)
                            else Modifier
                        )
                )
            }
            config.type == WallpaperType.PRESET -> {
                Image(
                    painter = painterResource(WallpaperPresets.getResOrDefault(config.presetId)),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            else -> {
                Image(
                    painter = painterResource(DefaultWallpaperRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }

        // 遮罩层
        if (config.dimOpacity > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = config.dimOpacity.coerceIn(0f, 0.6f)))
            )
        }

        // 模拟的毛玻璃卡片（直观展现透光效果）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .glassSurface(
                    shape = RoundedCornerShape(12.dp),
                    tint = WallpaperCardFaceTint,
                    tintAlpha = WallpaperCardFaceAlpha + 0.15f,
                    blurRadius = 0.dp
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "2026年10月17日 · 第 6 周",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "毛玻璃透光与文字对比度预览",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/**
 * 预设壁纸选择项
 */
@Composable
private fun PresetWallpaperCard(
    preset: PresetWallpaper,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .testTag("wallpaper_preset_${preset.id}")
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Image(
                painter = painterResource(preset.resId),
                contentDescription = preset.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = preset.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = preset.subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = TextSecondary,
            maxLines = 1
        )
    }
}
