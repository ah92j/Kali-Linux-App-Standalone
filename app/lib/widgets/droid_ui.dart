import 'package:flutter/material.dart';
import 'package:droiddesk/theme/droid_theme.dart';

/// Shared building blocks for DroidDesk's grouped-card screens.

/// Page header: round back button, title and subtitle.
class DroidPageHeader extends StatelessWidget {
  final String title;
  final String subtitle;

  const DroidPageHeader({super.key, required this.title, required this.subtitle});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 20, 0),
      child: Row(
        children: [
          DroidCircleButton(
            icon: Icons.arrow_back_rounded,
            onTap: () => Navigator.of(context).maybePop(),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: DroidTheme.headingMd),
                Text(subtitle, style: DroidTheme.bodySm),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class DroidCircleButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback onTap;

  const DroidCircleButton({super.key, required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: DroidTheme.surfaceLight,
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: onTap,
        child: SizedBox(
          width: 42,
          height: 42,
          child: Icon(icon, color: DroidTheme.textPrimary, size: 21),
        ),
      ),
    );
  }
}

class DroidSectionLabel extends StatelessWidget {
  final String text;

  const DroidSectionLabel(this.text, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(left: 4),
        child: Text(text.toUpperCase(), style: DroidTheme.label),
      );
}

/// Rounded card that holds a list of rows.
class DroidGroupCard extends StatelessWidget {
  final Widget child;

  const DroidGroupCard({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        border: Border.all(color: DroidTheme.surfaceBorder),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(color: Colors.transparent, child: child),
    );
  }
}

class DroidInsetDivider extends StatelessWidget {
  final double indent;

  const DroidInsetDivider({super.key, this.indent = 70});

  @override
  Widget build(BuildContext context) => Divider(
        height: 1,
        thickness: 1,
        indent: indent,
        color: DroidTheme.surfaceBorder,
      );
}

class DroidEmptyCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;

  const DroidEmptyCard({
    super.key,
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  @override
  Widget build(BuildContext context) {
    return DroidGroupCard(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 28),
        child: Column(
          children: [
            Icon(icon, size: 30, color: DroidTheme.textDim),
            const SizedBox(height: 10),
            Text(title, style: DroidTheme.headingSm),
            const SizedBox(height: 4),
            Text(subtitle, style: DroidTheme.bodySm, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}

/// Rounded search field matching the card style.
class DroidSearchField extends StatelessWidget {
  final TextEditingController controller;
  final String hint;
  final ValueChanged<String> onChanged;
  final bool busy;

  const DroidSearchField({
    super.key,
    required this.controller,
    required this.hint,
    required this.onChanged,
    this.busy = false,
  });

  @override
  Widget build(BuildContext context) {
    final border = OutlineInputBorder(
      borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
      borderSide: BorderSide(color: DroidTheme.surfaceBorder),
    );
    return TextField(
      controller: controller,
      onChanged: onChanged,
      style: DroidTheme.bodyMd.copyWith(color: DroidTheme.textPrimary),
      decoration: InputDecoration(
        hintText: hint,
        hintStyle: DroidTheme.bodyMd.copyWith(color: DroidTheme.textMuted),
        prefixIcon: Icon(Icons.search_rounded, color: DroidTheme.textMuted),
        suffixIcon: busy
            ? const Padding(
                padding: EdgeInsets.all(15),
                child: SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              )
            : controller.text.isEmpty
                ? null
                : IconButton(
                    icon: Icon(Icons.cancel_rounded, color: DroidTheme.textDim),
                    onPressed: () {
                      controller.clear();
                      onChanged('');
                    },
                  ),
        filled: true,
        fillColor: DroidTheme.cardBg,
        contentPadding: const EdgeInsets.symmetric(vertical: 14),
        border: border,
        enabledBorder: border,
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
          borderSide: const BorderSide(color: DroidTheme.primary, width: 1.4),
        ),
      ),
    );
  }
}

/// iOS-style segmented control with a sliding highlight.
class DroidSegmentedTabs extends StatelessWidget {
  final List<String> labels;
  final List<IconData> icons;
  final int selected;
  final ValueChanged<int> onChanged;

  const DroidSegmentedTabs({
    super.key,
    required this.labels,
    required this.icons,
    required this.selected,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 44,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: DroidTheme.surfaceLight,
        borderRadius: BorderRadius.circular(14),
      ),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final segmentWidth = constraints.maxWidth / labels.length;
          return Stack(
            children: [
              AnimatedPositioned(
                duration: const Duration(milliseconds: 220),
                curve: Curves.easeOutCubic,
                left: segmentWidth * selected,
                top: 0,
                bottom: 0,
                width: segmentWidth,
                child: Container(
                  decoration: BoxDecoration(
                    color: DroidTheme.isDark ? DroidTheme.surfaceBorder : Colors.white,
                    borderRadius: BorderRadius.circular(10),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.12),
                        blurRadius: 6,
                        offset: const Offset(0, 2),
                      ),
                    ],
                  ),
                ),
              ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = 0; i < labels.length; i++)
                    Expanded(
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () => onChanged(i),
                        child: Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            // Four or more segments: labels only, icons would not fit.
                            if (labels.length < 4) ...[
                            Icon(
                              icons[i],
                              size: 17,
                              color: i == selected
                                  ? DroidTheme.textPrimary
                                  : DroidTheme.textMuted,
                            ),
                            const SizedBox(width: 6),
                            ],
                            Text(
                              labels[i],
                              style: DroidTheme.bodySm.copyWith(
                                fontWeight: FontWeight.w600,
                                color: i == selected
                                    ? DroidTheme.textPrimary
                                    : DroidTheme.textMuted,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}
