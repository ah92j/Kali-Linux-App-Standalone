import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/screens/setup/setup_progress.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/widgets/update_sheet.dart';

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    promptForUpdateIfAvailable(context, state);

    return Scaffold(
      body: Container(
        decoration: BoxDecoration(
          gradient: DroidTheme.backgroundGradient,
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Align(
                  alignment: Alignment.topRight,
                  child: IconButton(
                    onPressed: () => state.toggleThemeMode(),
                    tooltip: state.isDarkMode
                        ? 'Switch to Light Theme'
                        : 'Switch to Dark Theme',
                    icon: Icon(
                      state.isDarkMode
                          ? Icons.light_mode_rounded
                          : Icons.dark_mode_rounded,
                      color: DroidTheme.textMuted,
                    ),
                  ),
                ),
                const Spacer(flex: 2),
                Container(
                      width: 82,
                      height: 82,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(22),
                        boxShadow: [
                          BoxShadow(
                            color: DroidTheme.primary.withValues(alpha: 0.22),
                            blurRadius: 36,
                            offset: const Offset(0, 14),
                          ),
                        ],
                      ),
                      child: ClipRRect(
                        borderRadius: BorderRadius.circular(22),
                        child: Image.asset(
                          'assets/icons/logo.png',
                          fit: BoxFit.cover,
                        ),
                      ),
                    )
                    .animate()
                    .fadeIn(duration: 450.ms)
                    .scale(
                      begin: const Offset(0.92, 0.92),
                      curve: Curves.easeOutCubic,
                    ),
                const SizedBox(height: 34),
                Text(
                      'Your desktop.\nNow on Android.',
                      style: DroidTheme.headingXl,
                    )
                    .animate()
                    .fadeIn(delay: 100.ms, duration: 450.ms)
                    .slideY(begin: 0.08, curve: Curves.easeOutCubic),
                const SizedBox(height: 18),
                Text(
                  'A complete Desktop Environment that runs locally on your phone.',
                  style: DroidTheme.bodyLg,
                ).animate().fadeIn(delay: 180.ms, duration: 450.ms),
                const SizedBox(height: 28),
                _Feature(
                  icon: Icons.lock_outline_rounded,
                  text: 'Private and local',
                ),
                const SizedBox(height: 14),
                _Feature(
                  icon: Icons.bolt_rounded,
                  text: 'Optimized for your GPU',
                ),
                const SizedBox(height: 14),
                _Feature(
                  icon: Icons.desktop_mac_outlined,
                  text: 'Desktop Environment, ready to work',
                ),
                const Spacer(flex: 4),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const SetupProgressScreen(),
                      ),
                    ),
                    child: const Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('Set Up Desktop Environment'),
                        SizedBox(width: 8),
                        Icon(Icons.arrow_forward_rounded, size: 19),
                      ],
                    ),
                  ),
                ).animate().fadeIn(delay: 350.ms, duration: 450.ms),
                const SizedBox(height: 14),
                Center(
                  child: Text(
                    'Ubuntu tools · Desktop Environment',
                    style: DroidTheme.bodySm,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Feature extends StatelessWidget {
  final IconData icon;
  final String text;

  const _Feature({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: DroidTheme.primary.withValues(alpha: 0.12),
            shape: BoxShape.circle,
          ),
          child: Icon(icon, size: 17, color: DroidTheme.primary),
        ),
        const SizedBox(width: 13),
        Text(
          text,
          style: DroidTheme.bodyMd.copyWith(color: DroidTheme.textPrimary),
        ),
      ],
    );
  }
}
