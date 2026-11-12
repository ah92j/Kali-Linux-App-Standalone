import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/linux_app_icon.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

/// Progress of the running App Store install/removal, driven by [AppState].
///
/// Shown at the bottom of the App Store and pinned on Home. With [showResult] it also shows
/// the outcome of the last finished operation until dismissed.
class PackageOperationCard extends StatefulWidget {
  final bool showResult;
  final VoidCallback? onTap;
  final EdgeInsetsGeometry margin;

  const PackageOperationCard({
    super.key,
    this.showResult = false,
    this.onTap,
    this.margin = EdgeInsets.zero,
  });

  @override
  State<PackageOperationCard> createState() => _PackageOperationCardState();
}

class _PackageOperationCardState extends State<PackageOperationCard> {
  bool _showLog = false;

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final Widget child;
    if (state.activePackage != null) {
      child = _running(state);
    } else if (widget.showResult && state.lastPackageResult != null) {
      child = _finished(state, state.lastPackageResult!);
    } else {
      child = const SizedBox(width: double.infinity);
    }
    return AnimatedSize(
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOutCubic,
      child: child,
    );
  }

  Widget _shell({required Widget child}) {
    return Container(
      margin: widget.margin,
      decoration: BoxDecoration(
        color: DroidTheme.cardBg,
        borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
        border: Border.all(color: DroidTheme.surfaceBorder),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: DroidTheme.isDark ? 0.4 : 0.10),
            blurRadius: 24,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(onTap: widget.onTap, child: child),
      ),
    );
  }

  Widget _icon(String package, List<String> iconNames) => package == AppState.updatesOperation
      ? Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: DroidTheme.primary.withValues(alpha: 0.14),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(Icons.system_update_alt_rounded, color: DroidTheme.primary, size: 21),
        )
      : LinuxAppIcon(
        package: package,
        iconNames: iconNames,
        size: 42,
        fallback: Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(
            color: DroidTheme.primary.withValues(alpha: 0.14),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(
            Icons.inventory_2_rounded,
            color: DroidTheme.primary,
            size: 21,
          ),
        ),
      );

  Widget _roundButton({
    required IconData icon,
    required Color color,
    required String tooltip,
    VoidCallback? onTap,
    bool busy = false,
  }) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: color.withValues(alpha: onTap == null ? 0.06 : 0.14),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox(
            width: 34,
            height: 34,
            child: busy
                ? Padding(
                    padding: const EdgeInsets.all(9),
                    child: CircularProgressIndicator(strokeWidth: 2, color: color),
                  )
                : Icon(
                    icon,
                    size: 19,
                    color: color.withValues(alpha: onTap == null ? 0.35 : 1),
                  ),
          ),
        ),
      ),
    );
  }

  Widget _running(AppState state) {
    final value = state.packageProgress > 0 ? state.packageProgress : null;
    final verb = state.packageCancelling
        ? 'Cancelling'
        : state.packageRemoving
            ? 'Removing'
            : state.isUpdatingAll
                ? 'Updating'
                : 'Installing';
    final log = state.packageLog
        .replaceAll(RegExp(r'\x1B\[[0-?]*[ -/]*[@-~]'), '')
        .split('\n')
        .where((line) => line.trim().isNotEmpty)
        .toList();
    final tail = log.length > 6 ? log.sublist(log.length - 6) : log;

    return _shell(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 14, 14, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _icon(state.activePackage!, state.activePackageIconNames),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '$verb ${state.activePackageName}',
                        style: DroidTheme.headingSm.copyWith(fontSize: 15),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        state.packageStatus,
                        style: DroidTheme.bodySm,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                Text(
                  value == null ? '' : '${(value * 100).round()}%',
                  style: DroidTheme.headingSm.copyWith(
                    color: DroidTheme.primary,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(width: 10),
                _roundButton(
                  icon: Icons.close_rounded,
                  color: DroidTheme.error,
                  tooltip: 'Cancel',
                  busy: state.packageCancelling,
                  onTap: state.packageCancelling || state.packageRemoving
                      ? null
                      : state.cancelPackageOperation,
                ),
              ],
            ),
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                minHeight: 8,
                value: value,
                backgroundColor: DroidTheme.surfaceLight,
              ),
            ),
            if (tail.isNotEmpty) ...[
              const SizedBox(height: 4),
              InkWell(
                borderRadius: BorderRadius.circular(8),
                onTap: () => setState(() => _showLog = !_showLog),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      Text(
                        _showLog ? 'Hide details' : 'Show details',
                        style: DroidTheme.bodySm.copyWith(
                          color: DroidTheme.primary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Icon(
                        _showLog
                            ? Icons.expand_less_rounded
                            : Icons.expand_more_rounded,
                        size: 18,
                        color: DroidTheme.primary,
                      ),
                    ],
                  ),
                ),
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 200),
                curve: Curves.easeOutCubic,
                child: _showLog
                    ? Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: DroidTheme.surfaceLight,
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text(
                          tail.join('\n'),
                          style: DroidTheme.monoSm,
                          maxLines: 6,
                          overflow: TextOverflow.ellipsis,
                        ),
                      )
                    : const SizedBox(width: double.infinity),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _finished(AppState state, PackageOperationResult result) {
    final success = result.ok && !result.cancelled;
    final color = success
        ? DroidTheme.accent
        : result.cancelled
            ? DroidTheme.warning
            : DroidTheme.error;
    return _shell(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
        child: Row(
          children: [
            _icon(result.package, result.iconNames),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    result.message,
                    style: DroidTheme.headingSm.copyWith(fontSize: 15),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Text(
                    !result.isUpdateAll && success && !result.removed
                        ? 'Find it in the desktop app menu'
                        : 'Open the App Store for details',
                    style: DroidTheme.bodySm,
                  ),
                ],
              ),
            ),
            Icon(
              success ? Icons.check_circle_rounded : Icons.info_rounded,
              color: color,
              size: 22,
            ),
            const SizedBox(width: 6),
            _roundButton(
              icon: Icons.close_rounded,
              color: DroidTheme.textMuted,
              tooltip: 'Dismiss',
              onTap: state.dismissPackageResult,
            ),
          ],
        ),
      ),
    );
  }
}
