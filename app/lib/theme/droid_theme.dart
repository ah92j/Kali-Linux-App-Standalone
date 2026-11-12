import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

/// A quiet, content-first design system inspired by premium system UI.
///
/// Supports dark and light themes with fluid switching: surface, text and
/// gradient colors are getters that follow [currentThemeMode].
class DroidTheme {
  DroidTheme._();

  static ThemeMode currentThemeMode = ThemeMode.dark;

  static bool get isDark {
    if (currentThemeMode == ThemeMode.system) {
      final brightness =
          WidgetsBinding.instance.platformDispatcher.platformBrightness;
      return brightness == Brightness.dark;
    }
    return currentThemeMode == ThemeMode.dark;
  }

  // ── Core Dark Colors ──
  static const Color darkBackground = Color(0xFF000000);
  static const Color darkSurface = Color(0xFF111113);
  static const Color darkSurfaceLight = Color(0xFF1C1C1E);
  static const Color darkSurfaceBorder = Color(0xFF2C2C2E);
  static const Color darkCardBg = Color(0xFF1C1C1E);

  // ── Core Light Colors ──
  static const Color lightBackground = Color(0xFFF2F2F7);
  static const Color lightSurface = Color(0xFFFFFFFF);
  static const Color lightSurfaceLight = Color(0xFFE5E5EA);
  static const Color lightSurfaceBorder = Color(0xFFD1D1D6);
  static const Color lightCardBg = Color(0xFFFFFFFF);

  static Color get background => isDark ? darkBackground : lightBackground;
  static Color get surface => isDark ? darkSurface : lightSurface;
  static Color get surfaceLight => isDark ? darkSurfaceLight : lightSurfaceLight;
  static Color get surfaceBorder =>
      isDark ? darkSurfaceBorder : lightSurfaceBorder;
  static Color get cardBg => isDark ? darkCardBg : lightCardBg;

  // ── Accent Colors ──
  static const Color primary = Color(0xFF0A84FF);
  static const Color primaryLight = Color(0xFF64D2FF);
  static const Color secondary = Color(0xFF64D2FF);
  static const Color accent = Color(0xFF30D158);
  static const Color warning = Color(0xFFFF9F0A);
  static const Color error = Color(0xFFFF453A);
  static const Color success = accent;

  // ── Text Dark Colors ──
  static const Color darkTextPrimary = Color(0xFFF5F5F7);
  static const Color darkTextSecondary = Color(0xFFAEAEB2);
  static const Color darkTextMuted = Color(0xFF8E8E93);
  static const Color darkTextDim = Color(0xFF636366);

  // ── Text Light Colors ──
  static const Color lightTextPrimary = Color(0xFF1C1C1E);
  static const Color lightTextSecondary = Color(0xFF3A3A3C);
  static const Color lightTextMuted = Color(0xFF6E6E73);
  static const Color lightTextDim = Color(0xFFAEAEB2);

  static Color get textPrimary => isDark ? darkTextPrimary : lightTextPrimary;
  static Color get textSecondary =>
      isDark ? darkTextSecondary : lightTextSecondary;
  static Color get textMuted => isDark ? darkTextMuted : lightTextMuted;
  static Color get textDim => isDark ? darkTextDim : lightTextDim;

  // ── Gradients ──
  static const LinearGradient primaryGradient = LinearGradient(
    colors: [Color(0xFF0A84FF), Color(0xFF0071E3)],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  static LinearGradient get backgroundGradient => isDark
      ? const LinearGradient(
          colors: [Color(0xFF000000), Color(0xFF09090B)],
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
        )
      : const LinearGradient(
          colors: [Color(0xFFF2F2F7), Color(0xFFEDEDF2)],
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
        );

  static LinearGradient get cardGradient => isDark
      ? const LinearGradient(
          colors: [Color(0xFF1C1C1E), Color(0xFF161618)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        )
      : const LinearGradient(
          colors: [Color(0xFFFFFFFF), Color(0xFFFAFAFC)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        );

  // ── Distro Colors ──
  static const Color ubuntuColor = Color(0xFFE95420);
  static const Color alpineColor = Color(0xFF0D597F);
  static const Color kaliColor = Color(0xFF367BF0);

  // ── Radii & Spacing ──
  static const double radiusSm = 10;
  static const double radiusMd = 16;
  static const double radiusLg = 22;
  static const double radiusXl = 28;
  static const double spaceSm = 8;
  static const double spaceMd = 16;
  static const double spaceLg = 24;
  static const double spaceXl = 32;
  static const double space2xl = 48;

  // ── Typography ──
  static TextStyle get headingXl => GoogleFonts.inter(
    fontSize: 34,
    fontWeight: FontWeight.w700,
    color: textPrimary,
    height: 1.08,
    letterSpacing: -1.1,
  );
  static TextStyle get headingLg => GoogleFonts.inter(
    fontSize: 26,
    fontWeight: FontWeight.w700,
    color: textPrimary,
    letterSpacing: -0.7,
  );
  static TextStyle get headingMd => GoogleFonts.inter(
    fontSize: 20,
    fontWeight: FontWeight.w600,
    color: textPrimary,
    letterSpacing: -0.35,
  );
  static TextStyle get headingSm => GoogleFonts.inter(
    fontSize: 16,
    fontWeight: FontWeight.w600,
    color: textPrimary,
    letterSpacing: -0.15,
  );
  static TextStyle get bodyLg => GoogleFonts.inter(
    fontSize: 17,
    fontWeight: FontWeight.w400,
    color: textSecondary,
    height: 1.45,
  );
  static TextStyle get bodyMd => GoogleFonts.inter(
    fontSize: 15,
    fontWeight: FontWeight.w400,
    color: textSecondary,
    height: 1.45,
  );
  static TextStyle get bodySm => GoogleFonts.inter(
    fontSize: 13,
    fontWeight: FontWeight.w400,
    color: textMuted,
    height: 1.35,
  );
  static TextStyle get mono => GoogleFonts.jetBrainsMono(
    fontSize: 13,
    fontWeight: FontWeight.w400,
    color: textPrimary,
  );
  static TextStyle get monoSm => GoogleFonts.jetBrainsMono(
    fontSize: 11,
    fontWeight: FontWeight.w400,
    color: textMuted,
  );
  static TextStyle get label => GoogleFonts.inter(
    fontSize: 12,
    fontWeight: FontWeight.w600,
    color: textMuted,
    letterSpacing: 0.2,
  );

  // ── ThemeData ──
  static ThemeData get darkThemeData =>
      _buildThemeData(brightness: Brightness.dark);

  static ThemeData get lightThemeData =>
      _buildThemeData(brightness: Brightness.light);

  static ThemeData get themeData => isDark ? darkThemeData : lightThemeData;

  static ThemeData _buildThemeData({required Brightness brightness}) {
    final isDarkBg = brightness == Brightness.dark;
    final bg = isDarkBg ? darkBackground : lightBackground;
    final surf = isDarkBg ? darkSurface : lightSurface;
    final surfLight = isDarkBg ? darkSurfaceLight : lightSurfaceLight;
    final card = isDarkBg ? darkCardBg : lightCardBg;
    final border = isDarkBg ? darkSurfaceBorder : lightSurfaceBorder;
    final txtPrimary = isDarkBg ? darkTextPrimary : lightTextPrimary;

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      scaffoldBackgroundColor: bg,
      primaryColor: primary,
      splashFactory: InkSparkle.splashFactory,
      colorScheme: ColorScheme(
        brightness: brightness,
        primary: primary,
        onPrimary: Colors.white,
        secondary: secondary,
        onSecondary: Colors.white,
        surface: surf,
        onSurface: txtPrimary,
        error: error,
        onError: Colors.white,
      ),
      dividerColor: border,
      appBarTheme: AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        systemOverlayStyle: isDarkBg
            ? SystemUiOverlayStyle.light.copyWith(
                statusBarColor: Colors.transparent,
              )
            : SystemUiOverlayStyle.dark.copyWith(
                statusBarColor: Colors.transparent,
              ),
        titleTextStyle: GoogleFonts.inter(
          fontSize: 20,
          fontWeight: FontWeight.w600,
          color: txtPrimary,
          letterSpacing: -0.35,
        ),
        iconTheme: IconThemeData(color: txtPrimary),
      ),
      tabBarTheme: TabBarThemeData(
        labelColor: primary,
        unselectedLabelColor: isDarkBg ? darkTextMuted : lightTextMuted,
        indicatorColor: primary,
      ),
      cardTheme: CardThemeData(
        color: card,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusLg),
          side: BorderSide(color: border),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: surf,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radiusLg),
          side: BorderSide(color: border),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: surf,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(radiusXl)),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: Colors.white,
          elevation: 0,
          minimumSize: const Size(0, 54),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 15),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMd),
          ),
          textStyle:
              GoogleFonts.inter(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: txtPrimary,
          side: BorderSide(color: border),
          minimumSize: const Size(0, 54),
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 15),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radiusMd),
          ),
          textStyle:
              GoogleFonts.inter(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: surfLight,
        contentTextStyle: GoogleFonts.inter(
          fontSize: 15,
          color: txtPrimary,
          height: 1.45,
        ),
        actionTextColor: primary,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
    );
  }
}
