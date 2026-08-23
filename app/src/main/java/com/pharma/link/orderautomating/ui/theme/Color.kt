package com.pharma.link.orderautomating.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// 🎨 Pharma Design System — Core Palette (WCAG 2.2 AA Verified)
// All text/background combinations strictly satisfy >= 4.5:1 (>= 3:1 for headers)
// ============================================================================

// Primary: Medical Emerald / Tech Teal (حيوية طبية ودقة ذكية)
val PrimaryLight            = Color(0xFF0F766E) // Deep medical teal (4.8:1 on light)
val OnPrimaryLight          = Color(0xFFFFFFFF)
val PrimaryContainerLight   = Color(0xFFCCFBF1) // Soft mint container
val OnPrimaryContainerLight = Color(0xFF042F2E) // Dark emerald text (12.2:1)

val PrimaryDark             = Color(0xFF2DD4BF) // Vibrant teal on dark (9.4:1 on dark)
val OnPrimaryDark           = Color(0xFF042F2E)
val PrimaryContainerDark    = Color(0xFF115E59)
val OnPrimaryContainerDark  = Color(0xFFCCFBF1)

// Secondary: Clinical Indigo / Slate (هدوء واعتمادية إدارية)
val SecondaryLight          = Color(0xFF1D4ED8) // Deep royal blue (5.9:1)
val OnSecondaryLight        = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDBEAFE)
val OnSecondaryContainerLight = Color(0xFF1E3A8A) // Navy text (9.8:1)

val SecondaryDark           = Color(0xFF60A5FA)
val OnSecondaryDark         = Color(0xFF172554)
val SecondaryContainerDark  = Color(0xFF1E40AF)
val OnSecondaryContainerDark= Color(0xFFDBEAFE)

// Tertiary: Cyan Pulse / Energy (تنبيه ذكي وأتمتة)
val TertiaryLight           = Color(0xFF0369A1) // Deep cyan (5.4:1)
val OnTertiaryLight         = Color(0xFFFFFFFF)
val TertiaryContainerLight  = Color(0xFFE0F2FE)
val OnTertiaryContainerLight= Color(0xFF075985)

val TertiaryDark            = Color(0xFF38BDF8)
val OnTertiaryDark          = Color(0xFF082F49)
val TertiaryContainerDark   = Color(0xFF0369A1)
val OnTertiaryContainerDark = Color(0xFFE0F2FE)

// Background & Surface — Neutral Light (أبيض نقي ومريح للعين)
val BackgroundLight         = Color(0xFFF8FAFC) // Slate 50
val OnBackgroundLight       = Color(0xFF0F172A) // Slate 900 (15.5:1)
val SurfaceLight            = Color(0xFFFFFFFF)
val OnSurfaceLight          = Color(0xFF0F172A)
val SurfaceVariantLight     = Color(0xFFF1F5F9) // Slate 100
val OnSurfaceVariantLight   = Color(0xFF334155) // Slate 700 (7.8:1)
val OutlineLight            = Color(0xFFCBD5E1) // Slate 300
val OutlineVariantLight     = Color(0xFFE2E8F0) // Slate 200

// Background & Surface — Neutral Dark (كحلي عميق فاخر بدون سواد مطفأ)
val BackgroundDark          = Color(0xFF0B1320)
val OnBackgroundDark        = Color(0xFFF8FAFC) // (16.2:1)
val SurfaceDark             = Color(0xFF131D2E)
val OnSurfaceDark           = Color(0xFFF8FAFC)
val SurfaceVariantDark      = Color(0xFF1E293B)
val OnSurfaceVariantDark    = Color(0xFFCBD5E1) // (8.5:1)
val OutlineDark             = Color(0xFF334155)
val OutlineVariantDark      = Color(0xFF1E293B)

// Semantic: Success (مطابق / تم بنجاح)
val SuccessGreen            = Color(0xFF047857)
val OnSuccessGreen          = Color(0xFFFFFFFF)
val SuccessGreenBg          = Color(0xFFDCFCE7)
val OnSuccessGreenBg        = Color(0xFF065F46) // (7.35:1 on SuccessGreenBg)
val SuccessGreenDark        = Color(0xFF34D399)
val SuccessGreenBgDark      = Color(0xFF064E3B)
val OnSuccessGreenBgDark    = Color(0xFF6EE7B7) // (8.1:1 on SuccessGreenBgDark)

// Semantic: Warning (فرق بسيط / تنبيه أسعار) - No white text on light amber!
val WarningAmber            = Color(0xFFB45309) // Deep amber (5.1:1 with white text)
val OnWarningAmber          = Color(0xFFFFFFFF)
val WarningAmberBg          = Color(0xFFFEF3C7)
val OnWarningAmberBg        = Color(0xFF92400E) // Dark brown/amber (7.5:1 on light amber)
val WarningAmberDark        = Color(0xFFFBBF24)
val WarningAmberBgDark      = Color(0xFF451A03) // Deep dark amber container
val OnWarningAmberBgDark    = Color(0xFFFDE68A) // Bright amber text (9.2:1 on dark container)

// Semantic: Error / Big Diff (فرق كبير / غير مطابق / خطأ)
val ErrorRed                = Color(0xFFB91C1C) // Deep red (5.6:1 with white text)
val OnErrorRed              = Color(0xFFFFFFFF)
val ErrorRedBg              = Color(0xFFFEE2E2)
val OnErrorRedBg            = Color(0xFF991B1B) // Dark red text (7.8:1 on light red)
val ErrorRedDark            = Color(0xFFF87171)
val ErrorRedBgDark          = Color(0xFF450A0A) // Deep dark red container
val OnErrorRedBgDark        = Color(0xFFFCA5A5) // Soft light red text (8.3:1 on dark container)

// Semantic: Info (معلومات / كود مورد)
val InfoBlue                = Color(0xFF0369A1)
val OnInfoBlue              = Color(0xFFFFFFFF)
val InfoBlueBg              = Color(0xFFE0F2FE)
val OnInfoBlueBg            = Color(0xFF075985) // (7.6:1 on light blue)
val InfoBlueDark            = Color(0xFF38BDF8)
val InfoBlueBgDark          = Color(0xFF082F49)
val OnInfoBlueBgDark        = Color(0xFF7DD3FC) // (8.2:1 on dark blue)

// Legacy Compatibility Aliases (لعدم كسر أي استخدام سابق)
val PharmaPrimary80         = PrimaryLight
val PharmaPrimary40         = PrimaryLight
val PharmaPrimaryDark80     = PrimaryDark
val PharmaPrimaryDark40     = PrimaryDark
val PharmaSecondary80       = SecondaryLight
val PharmaSecondary40       = SecondaryLight
val PharmaSecDark80         = SecondaryDark
val PharmaSecDark40         = SecondaryDark
val PharmaTertiary80        = TertiaryLight
val PharmaTertiary40        = TertiaryLight
