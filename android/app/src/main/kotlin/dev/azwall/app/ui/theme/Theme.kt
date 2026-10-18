package dev.azwall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors with a wallet meaning, beyond Material's roles. */
@Immutable
data class WalletColors(
    /** Private (shielded) funds and actions. */
    val private: Color,
    /** Public funds. */
    val public: Color,
    val success: Color,
    val pending: Color,
    val danger: Color,
)

val LocalWalletColors = staticCompositionLocalOf { DarkWallet }

private val DarkWallet = WalletColors(
    private = Color(0xFFB9A4FF),
    public = Color(0xFF8FA3BF),
    success = Color(0xFF5BD6A2),
    pending = Color(0xFFF2C46D),
    danger = Color(0xFFFF7B7B),
)

private val LightWallet = WalletColors(
    private = Color(0xFF5B3FD6),
    public = Color(0xFF55657D),
    success = Color(0xFF13855A),
    pending = Color(0xFFA86A00),
    danger = Color(0xFFC62828),
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFFB9A4FF),
    onPrimary = Color(0xFF1C1238),
    primaryContainer = Color(0xFF34275E),
    onPrimaryContainer = Color(0xFFE6DDFF),
    secondary = Color(0xFF9FB4D6),
    background = Color(0xFF0D0B16),
    onBackground = Color(0xFFECE8F7),
    surface = Color(0xFF0D0B16),
    onSurface = Color(0xFFECE8F7),
    surfaceVariant = Color(0xFF1E1A2E),
    onSurfaceVariant = Color(0xFFA9A3BE),
    surfaceContainer = Color(0xFF17142A),
    surfaceContainerHigh = Color(0xFF221D38),
    outline = Color(0xFF3A3452),
    outlineVariant = Color(0xFF2A253F),
    error = Color(0xFFFF7B7B),
)

private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF5B3FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1C1238),
    secondary = Color(0xFF55657D),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF16131F),
    surface = Color(0xFFF8F7FC),
    onSurface = Color(0xFF16131F),
    surfaceVariant = Color(0xFFEEEBF6),
    onSurfaceVariant = Color(0xFF5E5872),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1EEF9),
    outline = Color(0xFFD5D0E3),
    outlineVariant = Color(0xFFE6E2F0),
    error = Color(0xFFC62828),
)

private val Mono = FontFamily.Monospace

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Addresses, hashes and keys. */
val MonoStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, letterSpacing = 0.sp)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AzwallTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWalletColors provides if (dark) DarkWallet else LightWallet) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object WalletTheme {
    val colors: WalletColors @Composable get() = LocalWalletColors.current
}
