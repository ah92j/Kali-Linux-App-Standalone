import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/services/update_service.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

/// Shows the update sheet once per app session after an automatic check finds a release.
/// Call from a screen's build; it runs after the frame and only on the top route.
void promptForUpdateIfAvailable(BuildContext context, AppState state) {
  if (state.availableUpdate == null) return;
  WidgetsBinding.instance.addPostFrameCallback((_) {
    if (context.mounted && ModalRoute.of(context)?.isCurrent == true && state.takeUpdatePrompt()) {
      showUpdateSheet(context);
    }
  });
}

/// Shows the "update available" sheet for [AppState.availableUpdate].
Future<void> showUpdateSheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const UpdateSheet(),
  );
}

enum _Step { info, permission, downloading }

/// Update flow: release notes → "Install unknown apps" permission (if needed) →
/// download with progress → Android's installer.
class UpdateSheet extends StatefulWidget {
  const UpdateSheet({super.key});

  @override
  State<UpdateSheet> createState() => _UpdateSheetState();
}

class _UpdateSheetState extends State<UpdateSheet> with WidgetsBindingObserver {
  _Step _step = _Step.info;
  bool _waitingForPermission = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Back from Android settings: continue automatically if the permission was granted.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _waitingForPermission) {
      _waitingForPermission = false;
      _start();
    }
  }

  Future<void> _start() async {
    final state = context.read<AppState>();
    if (!await DroidDeskPlatform.canInstallUpdates()) {
      if (mounted) setState(() => _step = _Step.permission);
      return;
    }
    if (!mounted) return;
    setState(() => _step = _Step.downloading);
    final opened = await state.downloadAndInstallUpdate();
    if (!mounted) return;
    // On success the system installer is now in front; keep the sheet on its last state
    // so "Install" is one tap away if the user backs out of the installer.
    if (!opened) setState(() => _step = _Step.info);
  }

  Future<void> _onUpdatePressed() async {
    final state = context.read<AppState>();
    if (state.isRunning) {
      final proceed = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Update now?'),
          content: const Text(
            'Installing restarts DroidDesk. The running Linux desktop will stop; '
            'your files and apps stay.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('Update'),
            ),
          ],
        ),
      );
      if (proceed != true) return;
    }
    await _start();
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final release = state.availableUpdate;
    if (release == null) return const SizedBox.shrink();

    return Container(
      decoration: BoxDecoration(
        color: DroidTheme.surface,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(DroidTheme.radiusXl)),
        border: Border(top: BorderSide(color: DroidTheme.surfaceBorder)),
      ),
      constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
      padding: EdgeInsets.fromLTRB(20, 10, 20, 20 + MediaQuery.of(context).padding.bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 38,
              height: 5,
              decoration: BoxDecoration(
                color: DroidTheme.surfaceBorder,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text('Update available', style: DroidTheme.headingMd),
          const SizedBox(height: 2),
          Text(
            'v${state.appVersion}  →  v${release.version}'
            '${release.apkSize > 0 ? '  ·  ${_mb(release.apkSize)}' : ''}',
            style: DroidTheme.bodySm,
          ),
          const SizedBox(height: 18),
          Flexible(child: _body(state, release)),
        ],
      ),
    );
  }

  Widget _body(AppState state, AppRelease release) {
    switch (_step) {
      case _Step.permission:
        return _permissionStep();
      case _Step.downloading:
        return _downloadStep(state);
      case _Step.info:
        return _infoStep(state, release);
    }
  }

  Widget _infoStep(AppState state, AppRelease release) {
    final mismatch = state.updateErrorCode == 'signature_mismatch';
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Flexible(
          child: Container(
            decoration: BoxDecoration(
              color: DroidTheme.cardBg,
              borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
              border: Border.all(color: DroidTheme.surfaceBorder),
            ),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text("WHAT'S NEW", style: DroidTheme.label),
                  const SizedBox(height: 8),
                  Text(
                    release.notes.isEmpty ? release.title : _plain(release.notes),
                    style: DroidTheme.bodyMd.copyWith(color: DroidTheme.textPrimary),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (state.updateError != null) ...[
          const SizedBox(height: 12),
          _notice(state.updateError!, DroidTheme.error, Icons.error_outline_rounded),
        ],
        const SizedBox(height: 12),
        _notice(
          'Installing restarts DroidDesk. Your Linux files and apps stay.',
          DroidTheme.textMuted,
          Icons.info_outline_rounded,
        ),
        const SizedBox(height: 16),
        if (mismatch)
          ElevatedButton.icon(
            onPressed: () => launchUrl(
              Uri.parse(release.pageUrl),
              mode: LaunchMode.externalApplication,
            ),
            icon: const Icon(Icons.open_in_new_rounded, size: 19),
            label: const Text('Open release page'),
          )
        else
          ElevatedButton(
            onPressed: _onUpdatePressed,
            child: Text(
              state.downloadedUpdatePath != null
                  ? 'Install'
                  : state.updateError != null
                      ? 'Try again'
                      : 'Update',
            ),
          ),
        if (!mismatch) ...[
          const SizedBox(height: 4),
          TextButton.icon(
            onPressed: () => launchUrl(
              Uri.parse(release.apkUrl),
              mode: LaunchMode.externalApplication,
            ),
            icon: const Icon(Icons.download_rounded, size: 18),
            label: const Text('Direct APK download link'),
          ),
        ],
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(
              child: TextButton(
                onPressed: () async {
                  await state.skipUpdate();
                  if (mounted) Navigator.of(context).pop();
                },
                child: Text('Skip this version', style: TextStyle(color: DroidTheme.textMuted)),
              ),
            ),
            Expanded(
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Later'),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _permissionStep() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: DroidTheme.cardBg,
            borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
            border: Border.all(color: DroidTheme.surfaceBorder),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  color: DroidTheme.warning.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.security_update_good_rounded, color: DroidTheme.warning),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Allow app updates', style: DroidTheme.headingSm),
                    const SizedBox(height: 4),
                    Text(
                      'Android needs your permission for DroidDesk to install its own updates. '
                      'On the next screen turn on "Allow from this source", then come back.',
                      style: DroidTheme.bodySm,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        ElevatedButton(
          onPressed: () async {
            _waitingForPermission = true;
            await DroidDeskPlatform.openInstallPermission();
          },
          child: const Text('Continue'),
        ),
        const SizedBox(height: 6),
        TextButton(
          onPressed: () => setState(() => _step = _Step.info),
          child: const Text('Not now'),
        ),
      ],
    );
  }

  Widget _downloadStep(AppState state) {
    final downloading = state.isDownloadingUpdate;
    final total = state.updateTotal;
    final percent = (state.updateProgress * 100).round();
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: DroidTheme.cardBg,
            borderRadius: BorderRadius.circular(DroidTheme.radiusLg),
            border: Border.all(color: DroidTheme.surfaceBorder),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      downloading ? 'Downloading update…' : 'Opening installer…',
                      style: DroidTheme.headingSm,
                    ),
                  ),
                  Text(
                    downloading ? '$percent%' : '',
                    style: DroidTheme.headingSm.copyWith(
                      color: DroidTheme.primary,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: LinearProgressIndicator(
                  minHeight: 8,
                  value: downloading && total > 0 ? state.updateProgress : null,
                  backgroundColor: DroidTheme.surfaceLight,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                total > 0
                    ? '${_mb(state.updateDownloaded)} of ${_mb(total)}'
                    : _mb(state.updateDownloaded),
                style: DroidTheme.bodySm,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (downloading)
          TextButton(
            onPressed: state.cancelUpdateDownload,
            style: TextButton.styleFrom(foregroundColor: DroidTheme.error),
            child: const Text('Cancel download'),
          )
        else
          ElevatedButton(onPressed: _start, child: const Text('Install')),
      ],
    );
  }

  Widget _notice(String text, Color color, IconData icon) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 16, color: color),
        const SizedBox(width: 8),
        Expanded(child: Text(text, style: DroidTheme.bodySm.copyWith(color: color))),
      ],
    );
  }

  static String _mb(int bytes) => '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';

  /// Release notes are Markdown; show them as readable plain text.
  static String _plain(String markdown) => markdown
      .replaceAll('\r', '')
      .replaceAll(RegExp(r'^#{1,6}\s*', multiLine: true), '')
      .replaceAll(RegExp(r'^\s*[-*]\s+', multiLine: true), '•  ')
      .replaceAllMapped(RegExp(r'\*\*(.+?)\*\*'), (m) => m[1]!)
      .replaceAllMapped(RegExp(r'`([^`]+)`'), (m) => m[1]!)
      .replaceAllMapped(RegExp(r'\[([^\]]+)\]\([^)]+\)'), (m) => m[1]!)
      .trim();
}
