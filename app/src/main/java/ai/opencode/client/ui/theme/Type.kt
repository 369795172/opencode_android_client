package ai.opencode.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography

/** Markdown typography with headers one size smaller than default. */
@Composable
fun markdownTypographyCompact() = markdownTypography(
    h1 = MaterialTheme.typography.headlineLarge,
    h2 = MaterialTheme.typography.headlineMedium,
    h3 = MaterialTheme.typography.headlineSmall,
    h4 = MaterialTheme.typography.titleLarge,
    h5 = MaterialTheme.typography.titleMedium,
    h6 = MaterialTheme.typography.titleSmall,
    text = MaterialTheme.typography.bodyLarge,
    code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    inlineCode = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    quote = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyLarge,
    ordered = MaterialTheme.typography.bodyLarge,
    bullet = MaterialTheme.typography.bodyLarge,
    list = MaterialTheme.typography.bodyLarge,
    table = MaterialTheme.typography.bodyLarge
)

/** Slightly smaller typography for Files and Chat columns in tablet layout. */
fun compactTypography(base: Typography): Typography = base.copy(
    bodyLarge = base.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 11.sp, lineHeight = 16.sp),
    bodySmall = base.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
    labelLarge = base.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp),
    labelMedium = base.labelMedium.copy(fontSize = 10.sp, lineHeight = 12.sp),
    labelSmall = base.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp),
    titleLarge = base.titleLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    titleMedium = base.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    titleSmall = base.titleSmall.copy(fontSize = 12.sp, lineHeight = 18.sp)
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp
    )
)