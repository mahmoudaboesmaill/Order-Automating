package com.pharma.link.orderautomating.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pharma.link.orderautomating.ui.theme.*

// ============================================================================
// 🔘 Buttons with Minimum 48dp Touch Targets & Debounced Click Protection
// ============================================================================

@Composable
fun PharmaPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }

    Button(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= 600L && !loading) {
                lastClickTime = now
                onClick()
            }
        },
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = PharmaDimens.minTouchTarget)
            .height(PharmaDimens.buttonHeight),
        shape = PharmaShapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = PharmaDimens.elevationLow)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = contentColor
            )
            Spacer(Modifier.width(PharmaDimens.space8))
            Text(
                "جاري التنفيذ...",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(PharmaDimens.space8))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PharmaSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outline
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }

    OutlinedButton(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= 500L) {
                lastClickTime = now
                onClick()
            }
        },
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = PharmaDimens.minTouchTarget)
            .height(PharmaDimens.buttonHeight),
        shape = PharmaShapes.medium,
        border = BorderStroke(PharmaDimens.borderThin, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PharmaDimens.space8))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ============================================================================
// 🃏 Action Cards (Camera / Gallery / PDF) with Clear Visual Hierarchy
// ============================================================================

@Composable
fun PharmaActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    isHero: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val containerBg = if (isHero) {
        if (isDark) color.copy(alpha = 0.22f) else color.copy(alpha = 0.12f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface
    }
    val borderCol = if (isHero) color.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PharmaDimens.actionCardHeight)
            .clip(PharmaShapes.large)
            .clickable { onClick() },
        shape = PharmaShapes.large,
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(if (isHero) PharmaDimens.borderThick else PharmaDimens.borderThin, borderCol),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHero) PharmaDimens.elevationLow else PharmaDimens.elevationNone)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PharmaDimens.space16, vertical = PharmaDimens.space14),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = PharmaShapes.medium,
                color = if (isHero) color else color.copy(alpha = 0.18f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = title,
                        modifier = Modifier.size(24.dp),
                        tint = if (isHero) Color.White else color
                    )
                }
            }
            Spacer(Modifier.width(PharmaDimens.space16))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badgeText != null) {
                        Spacer(Modifier.width(PharmaDimens.space8))
                        Surface(
                            shape = PillShape,
                            color = color.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
                        ) {
                            Text(
                                badgeText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDark) color else if (isHero) color else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PharmaDimens.space4))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = if (isHero) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ============================================================================
// 🏷️ Status Badges & Pills (Success / Warning / Error / Info)
// 100% WCAG 2.2 AA Contrast Verified (No light text on light yellow)
// ============================================================================

enum class PharmaBadgeType {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL
}

@Composable
fun PharmaStatusBadge(
    text: String,
    type: PharmaBadgeType,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val isDark = isSystemInDarkTheme()
    val (bgColor, fgColor) = when (type) {
        PharmaBadgeType.SUCCESS -> if (isDark) Pair(SuccessGreenBgDark, OnSuccessGreenBgDark) else Pair(SuccessGreenBg, OnSuccessGreenBg)
        PharmaBadgeType.WARNING -> if (isDark) Pair(WarningAmberBgDark, OnWarningAmberBgDark) else Pair(WarningAmberBg, OnWarningAmberBg)
        PharmaBadgeType.ERROR   -> if (isDark) Pair(ErrorRedBgDark, OnErrorRedBgDark) else Pair(ErrorRedBg, OnErrorRedBg)
        PharmaBadgeType.INFO    -> if (isDark) Pair(InfoBlueBgDark, OnInfoBlueBgDark) else Pair(InfoBlueBg, OnInfoBlueBg)
        PharmaBadgeType.NEUTRAL -> if (isDark) Pair(SurfaceVariantDark, OnSurfaceVariantDark) else Pair(SurfaceVariantLight, OnSurfaceVariantLight)
    }

    val defaultIcon = when (type) {
        PharmaBadgeType.SUCCESS -> Icons.Default.CheckCircle
        PharmaBadgeType.WARNING -> Icons.Default.Warning
        PharmaBadgeType.ERROR   -> Icons.Default.Cancel
        PharmaBadgeType.INFO    -> Icons.Default.Info
        PharmaBadgeType.NEUTRAL -> null
    }

    val finalIcon = icon ?: defaultIcon

    Surface(
        modifier = modifier.defaultMinSize(minHeight = 28.dp),
        shape = PillShape,
        color = bgColor,
        border = BorderStroke(PharmaDimens.borderThin, fgColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (finalIcon != null) {
                Icon(
                    finalIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = fgColor
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
        }
    }
}

// ============================================================================
// 💳 Metric Tile (Financial Reconciliation Card)
// ============================================================================

@Composable
fun PharmaMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 60.dp),
        shape = PharmaShapes.medium,
        color = containerColor,
        border = BorderStroke(PharmaDimens.borderThin, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = PharmaDimens.space12, vertical = PharmaDimens.space10)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Spacer(Modifier.height(PharmaDimens.space4))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor
            )
        }
    }
}

// ============================================================================
// ✏️ Text Field with Accessible Contrast and Touch Height >= 48dp
// ============================================================================

@Composable
fun PharmaInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder, style = MaterialTheme.typography.bodyMedium) }
        } else null,
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
        } else null,
        trailingIcon = trailingIcon,
        modifier = modifier.defaultMinSize(minHeight = PharmaDimens.minTouchTarget),
        shape = PharmaShapes.medium,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

// ============================================================================
// 📱 Previews for UI Verification in Android Studio
// ============================================================================

@Preview(name = "Pharma Components - Light", showBackground = true)
@Composable
private fun PreviewComponentsLight() {
    OrderAutomatingTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PharmaStatusBadge(text = "مطابق تماماً (100%)", type = PharmaBadgeType.SUCCESS)
            PharmaStatusBadge(text = "فرق مالي بسيط (2.1%)", type = PharmaBadgeType.WARNING)
            PharmaStatusBadge(text = "فرق مالي كبير (12%)", type = PharmaBadgeType.ERROR)
            PharmaStatusBadge(text = "كود المورد: 29", type = PharmaBadgeType.INFO)

            PharmaPrimaryButton(text = "إرسال إلى E-PLUS", onClick = {})
            PharmaSecondaryButton(text = "فاتورة جديدة", onClick = {})

            PharmaActionCard(
                title = "تصوير الفاتورة بالكاميرا",
                subtitle = "التقاط فوري ومباشر",
                icon = Icons.Default.PhotoCamera,
                color = MaterialTheme.colorScheme.primary,
                onClick = {}
            )
        }
    }
}

@Preview(name = "Pharma Components - Dark", showBackground = true)
@Composable
private fun PreviewComponentsDark() {
    OrderAutomatingTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PharmaStatusBadge(text = "مطابق تماماً (100%)", type = PharmaBadgeType.SUCCESS)
            PharmaStatusBadge(text = "فرق مالي بسيط (2.1%)", type = PharmaBadgeType.WARNING)
            PharmaStatusBadge(text = "فرق مالي كبير (12%)", type = PharmaBadgeType.ERROR)
            PharmaStatusBadge(text = "كود المورد: 29", type = PharmaBadgeType.INFO)

            PharmaPrimaryButton(text = "إرسال إلى E-PLUS", onClick = {})
        }
    }
}
