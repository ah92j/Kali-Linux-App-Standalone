import 'dart:async';

import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/droid_ui.dart';
import 'package:droiddesk/widgets/linux_app_icon.dart';
import 'package:droiddesk/widgets/package_operation_card.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

class AppCatalogScreen extends StatefulWidget {
  const AppCatalogScreen({super.key});

  @override
  State<AppCatalogScreen> createState() => _AppCatalogScreenState();
}

class _AppCatalogScreenState extends State<AppCatalogScreen> {
  static const _featured = [
    _FeaturedApp(
      'firefox',
      'Firefox',
      'Fast, private desktop web browser.',
      Icons.public_rounded,
      Color(0xFFFF7139),
      iconNames: ['firefox'],
    ),
    _FeaturedApp(
      'code-oss',
      'Code OSS',
      'Powerful desktop code editor and IDE.',
      Icons.code_rounded,
      Color(0xFF23A8F2),
      iconNames: ['vscode', 'code-oss'],
    ),
    _FeaturedApp(
      'libreoffice',
      'LibreOffice',
      'Documents, spreadsheets, and presentations.',
      Icons.description_rounded,
      Color(0xFF18A303),
      iconNames: ['libreoffice-startcenter', 'libreoffice-main'],
    ),
    _FeaturedApp(
      'gimp',
      'GIMP',
      'Professional image editing and design tools.',
      Icons.brush_rounded,
      Color(0xFF9A7654),
      iconNames: ['gimp'],
    ),
    _FeaturedApp(
      'blender',
      'Blender',
      'Complete open-source 3D creation suite.',
      Icons.view_in_ar_rounded,
      Color(0xFFF5792A),
      iconNames: ['blender'],
    ),
    _FeaturedApp(
      'vlc',
      'VLC',
      'Play almost every audio and video format.',
      Icons.play_circle_rounded,
      Color(0xFFFF8800),
      iconNames: ['vlc'],
    ),
    _FeaturedApp(
      'nodejs',
      'Node.js + npm',
      'JavaScript runtime and package manager.',
      Icons.javascript_rounded,
      Color(0xFF68A063),
      iconNames: [],
    ),
    _FeaturedApp(
      'python',
      'Python',
      'Popular programming language and tools.',
      Icons.terminal_rounded,
      Color(0xFFFFD43B),
      iconNames: ['python'],
    ),
    _FeaturedApp(
      'imagemagick',
      'ImageMagick',
      'Image conversion and processing toolkit.',
      Icons.image_rounded,
      DroidTheme.primaryLight,
      iconNames: ['display-im6.q16'],
    ),
    _FeaturedApp(
      'chromium',
      'Chromium',
      'Open-source web browser for a second browser option.',
      Icons.language_rounded,
      Color(0xFF4285F4),
      iconNames: ['chromium'],
    ),
    _FeaturedApp(
      'thunderbird',
      'Thunderbird',
      'Email, calendars and contacts in one desktop app.',
      Icons.mail_outline_rounded,
      Color(0xFF168DE2),
      iconNames: ['thunderbird'],
    ),
    _FeaturedApp(
      'filezilla',
      'FileZilla',
      'Transfer and manage files on remote servers.',
      Icons.folder_shared_rounded,
      Color(0xFFB3C7E6),
      iconNames: ['filezilla'],
    ),
    _FeaturedApp(
      'geany',
      'Geany',
      'Lightweight code editor for many programming languages.',
      Icons.code_rounded,
      Color(0xFF56A86B),
      iconNames: ['geany'],
    ),
    _FeaturedApp(
      'inkscape',
      'Inkscape',
      'Create and edit vector graphics.',
      Icons.draw_rounded,
      Color(0xFF7D66A8),
      iconNames: ['inkscape'],
    ),
    _FeaturedApp(
      'audacity',
      'Audacity',
      'Record and edit audio projects.',
      Icons.graphic_eq_rounded,
      Color(0xFF2E9D89),
      iconNames: ['audacity'],
    ),
    _FeaturedApp(
      'mpv',
      'MPV Player',
      'Lightweight desktop video player.',
      Icons.movie_rounded,
      Color(0xFF50A090),
      iconNames: ['mpv'],
    ),
    _FeaturedApp(
      'evince',
      'Document Viewer',
      'Read PDF and other document formats.',
      Icons.picture_as_pdf_rounded,
      Color(0xFFE05A5A),
      iconNames: ['evince'],
    ),
    _FeaturedApp(
      'htop',
      'Htop',
      'Monitor running processes and system resources.',
      Icons.monitor_heart_rounded,
      Color(0xFF42A5F5),
      iconNames: ['htop'],
    ),
    _FeaturedApp(
      'btop',
      'Btop',
      'Visual CPU, memory, disk and process monitor.',
      Icons.bar_chart_rounded,
      Color(0xFF8B79CE),
      iconNames: ['btop'],
    ),
    _FeaturedApp(
      'fastfetch',
      'Fastfetch',
      'Show system and hardware information in Terminal.',
      Icons.info_outline_rounded,
      Color(0xFF58A6A6),
      iconNames: [],
    ),
    _FeaturedApp(
      'git',
      'Git',
      'Version control for managing source code.',
      Icons.merge_type_rounded,
      Color(0xFFE96B45),
      iconNames: [],
    ),
    _FeaturedApp(
      'python3-pip',
      'Python pip',
      'Install and manage Python packages.',
      Icons.inventory_2_rounded,
      Color(0xFF3974A8),
      iconNames: [],
    ),
    _FeaturedApp(
      'tmux',
      'Tmux',
      'Keep multiple Terminal sessions organized.',
      Icons.terminal_rounded,
      Color(0xFF6B9E75),
      iconNames: [],
    ),
    _FeaturedApp(
      'zsh',
      'Zsh',
      'An alternative shell with customization support.',
      Icons.code_rounded,
      Color(0xFFDAAB45),
      iconNames: [],
    ),
    _FeaturedApp(
      'kali-tweaks',
      'Kali Tweaks',
      'Configure Kali desktop and common system options.',
      Icons.tune_rounded,
      Color(0xFF4B76A8),
      iconNames: [],
    ),
  ];

  int _tab = 0;
  final _searchController = TextEditingController();
  Timer? _searchDebounce;
  List<_PackageItem> _searchResults = const [];
  List<_PackageItem> _installedPackages = const [];
  bool _searching = true;
  bool _loadingInstalled = true;
  int _seenPackageResultId = -1;
  List<_PackageItem> _updates = const [];
  bool _loadingUpdates = false;
  bool _checkingUpdates = false;
  bool _updatesChecked = false;

  Set<String> get _installedNames =>
      _installedPackages.map((package) => package.name).toSet();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().refreshOptionalApps();
      _loadInstalled();
      _search('');
    });
  }

  @override
  void dispose() {
    // Installs keep running in AppState after leaving; progress stays pinned on Home.
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadInstalled() async {
    if (mounted) setState(() => _loadingInstalled = true);
    try {
      final packages = await DroidDeskPlatform.getInstalledNativePackages();
      if (!mounted) return;
      setState(() {
        _installedPackages = packages.map(_PackageItem.fromMap).toList();
        _loadingInstalled = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loadingInstalled = false);
    }
  }

  void _queueSearch(String query) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(
      const Duration(milliseconds: 350),
      () => _search(query),
    );
  }

  Future<void> _search(String query) async {
    if (mounted) setState(() => _searching = true);
    try {
      final packages = await DroidDeskPlatform.searchNativePackages(query);
      if (!mounted || query != _searchController.text.trim()) return;
      setState(() {
        _searchResults = packages.map(_PackageItem.fromMap).toList();
        _searching = false;
      });
    } catch (_) {
      if (mounted) setState(() => _searching = false);
    }
  }

  String _resolvedPackageName(String packageName) => switch (packageName) {
        'firefox' => 'firefox-esr',
        'python' => 'python3',
        _ => packageName,
      };

  bool _isInstalledName(String packageName) =>
      _installedNames.contains(packageName) ||
      _installedNames.contains(_resolvedPackageName(packageName));

  _FeaturedApp? _featuredFor(String packageName) {
    for (final app in _featured) {
      if (app.packageName == packageName ||
          _resolvedPackageName(app.packageName) == packageName) {
        return app;
      }
    }
    return null;
  }

  Future<bool> _install(String packageName) async {
    // Stale UI state must never turn a Get tap into an implicit package upgrade.
    if (_isInstalledName(packageName)) return true;
    final featured = _featuredFor(packageName);
    final ok = await context.read<AppState>().installPackage(
      packageName,
      displayName: featured?.name ?? _PackageItem(name: packageName).displayName,
      iconNames: featured?.iconNames ?? const [],
    );
    await _loadInstalled();
    if (!mounted) return ok;
    _showResultSnack(ok);
    return ok;
  }

  /// Shows the outcome of the operation that just finished in AppState (once).
  void _showResultSnack(bool ok) {
    final state = context.read<AppState>();
    final result = state.lastPackageResult;
    if (result == null || state.packageResultId == _seenPackageResultId) return;
    _seenPackageResultId = state.packageResultId;
    state.dismissPackageResult();
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(result.message),
        backgroundColor: result.cancelled
            ? DroidTheme.surfaceLight
            : result.ok
            ? DroidTheme.success
            : DroidTheme.error,
      ),
    );
  }

  Future<void> _remove(_PackageItem package) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Remove ${package.displayName}?'),
        content: const Text('Dependent packages may also be affected.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (!mounted || confirmed != true) return;
    final ok = await context.read<AppState>().removePackage(
      package.name,
      displayName: package.displayName,
    );
    await _loadInstalled();
    if (!mounted) return;
    _showResultSnack(ok);
  }

  void _selectTab(int index) {
    setState(() => _tab = index);
    if (index == 2 && !_updatesChecked) _loadUpdates();
    if (index == 3) _loadInstalled();
  }

  /// Shows the cached update list immediately, then refreshes package lists in the background.
  Future<void> _loadUpdates() async {
    _updatesChecked = true;
    setState(() => _loadingUpdates = _updates.isEmpty);
    try {
      final cached = await DroidDeskPlatform.getUpgradablePackages();
      if (mounted) {
        setState(() {
          _updates = cached.map(_PackageItem.fromMap).toList();
          _loadingUpdates = false;
          _checkingUpdates = true;
        });
      }
      final fresh = await DroidDeskPlatform.getUpgradablePackages(refresh: true);
      if (mounted) setState(() => _updates = fresh.map(_PackageItem.fromMap).toList());
    } catch (_) {
    } finally {
      if (mounted) {
        setState(() {
          _loadingUpdates = false;
          _checkingUpdates = false;
        });
      }
    }
  }

  Future<void> _updateAll() async {
    final ok = await context.read<AppState>().updateAllPackages();
    if (!mounted) return;
    _showResultSnack(ok);
    _updatesChecked = false;
    await _loadUpdates();
  }

  Future<void> _updateOne(_PackageItem package) async {
    final featured = _featuredFor(package.name);
    final ok = await context.read<AppState>().upgradePackage(
      package.name,
      displayName: featured?.name ?? package.displayName,
      iconNames: featured?.iconNames ?? const [],
    );
    await _loadInstalled();
    if (!mounted) return;
    _showResultSnack(ok);
    if (!ok) return;
    _updatesChecked = false;
    await _loadUpdates();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          bottom: false,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DroidPageHeader(
                title: 'Linux App Store',
                subtitle: 'Apps and tools for your desktop',
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 8),
                child: DroidSegmentedTabs(
                  labels: const ['Featured', 'Browse', 'Updates', 'Installed'],
                  icons: const [
                    Icons.auto_awesome_rounded,
                    Icons.search_rounded,
                    Icons.system_update_alt_rounded,
                    Icons.check_circle_outline_rounded,
                  ],
                  selected: _tab,
                  onChanged: _selectTab,
                ),
              ),
              Expanded(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 200),
                  child: KeyedSubtree(
                    key: ValueKey(_tab),
                    child: switch (_tab) {
                      0 => _featuredView(),
                      1 => _browseView(),
                      2 => _updatesView(),
                      _ => _installedView(),
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        top: false,
        child: PackageOperationCard(margin: EdgeInsets.fromLTRB(14, 0, 14, 12)),
      ),
    );
  }

  Widget _featuredView() {
    final state = context.watch<AppState>();
    final apps = [
      ..._featured,
      if (!state.hasRoot)
        const _FeaturedApp(
          'proot_debian',
          'Debian Compatibility',
          'Run packages unavailable in native repositories.',
          Icons.inventory_2_rounded,
          Color(0xFFD70A53),
          optional: true,
          iconNames: ['distributor-logo-debian'],
        ),
    ];
    final hero = apps.first;
    final rest = apps.skip(1).toList();

    _PackageItem itemFor(_FeaturedApp app) => _PackageItem(
          name: app.packageName,
          description: app.description,
          installed: app.optional
              ? state.optionalApps[app.packageName] == true
              : _isInstalledName(app.packageName),
        );
    Future<void> Function()? installerFor(_FeaturedApp app) => app.optional
        ? () => state.installOptionalApp(app.packageName)
        : null;

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _heroCard(hero, itemFor(hero), installerFor(hero)),
        const SizedBox(height: 26),
        DroidSectionLabel('Popular apps'),
        const SizedBox(height: 10),
        DroidGroupCard(
          child: Column(
            children: [
              for (var i = 0; i < rest.length; i++) ...[
                if (i > 0) DroidInsetDivider(indent: 76),
                _packageRow(
                  itemFor(rest[i]),
                  featured: rest[i],
                  onInstall: installerFor(rest[i]),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  /// Large spotlight card for the first featured app.
  Widget _heroCard(
    _FeaturedApp app,
    _PackageItem item,
    Future<void> Function()? onInstall,
  ) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(DroidTheme.radiusXl),
        gradient: LinearGradient(
          colors: [
            app.color.withValues(alpha: 0.30),
            app.color.withValues(alpha: 0.08),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: app.color.withValues(alpha: 0.30)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'FEATURED',
            style: DroidTheme.label.copyWith(color: app.color, letterSpacing: 1),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              LinuxAppIcon(
                package: app.packageName,
                iconNames: app.iconNames,
                fallback: _appTile(app.icon, app.color, size: 60),
                size: 60,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(app.name, style: DroidTheme.headingMd),
                    const SizedBox(height: 3),
                    Text(app.description, style: DroidTheme.bodySm),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          _actionButton(item, onInstall: onInstall, prominent: true),
        ],
      ),
    );
  }

  Widget _browseView() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 10),
          child: DroidSearchField(
            controller: _searchController,
            hint: 'Search packages, apps and tools',
            onChanged: (value) {
              setState(() {});
              _queueSearch(value);
            },
            busy: _searching,
          ),
        ),
        Expanded(
          child: _searchResults.isEmpty && !_searching
              ? ListView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                  children: [
                    DroidEmptyCard(
                      icon: Icons.search_off_rounded,
                      title: 'No matching packages',
                      subtitle: 'Try another name or keyword.',
                    ),
                  ],
                )
              : ListView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                  children: [
                    if (_searchResults.isNotEmpty)
                      DroidGroupCard(
                        child: Column(
                          children: [
                            for (var i = 0; i < _searchResults.length; i++) ...[
                              if (i > 0) DroidInsetDivider(indent: 76),
                              _packageRow(_searchResults[i]),
                            ],
                          ],
                        ),
                      ),
                  ],
                ),
        ),
      ],
    );
  }

  Widget _updatesView() {
    final appState = context.watch<AppState>();
    final busy = appState.activePackage != null;
    if (_loadingUpdates) {
      return const Center(child: CircularProgressIndicator());
    }
    return RefreshIndicator(
      onRefresh: () async {
        _updatesChecked = false;
        await _loadUpdates();
      },
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
        children: [
          DroidGroupCard(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Row(
                children: [
                  Container(
                    width: 46,
                    height: 46,
                    decoration: BoxDecoration(
                      color: (_updates.isEmpty ? DroidTheme.accent : DroidTheme.primary)
                          .withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(13),
                    ),
                    child: Icon(
                      _updates.isEmpty
                          ? Icons.verified_rounded
                          : Icons.system_update_alt_rounded,
                      color: _updates.isEmpty ? DroidTheme.accent : DroidTheme.primary,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _updates.isEmpty
                              ? 'Up to date'
                              : '${_updates.length} update${_updates.length == 1 ? '' : 's'}',
                          style: DroidTheme.headingSm,
                        ),
                        const SizedBox(height: 2),
                        Row(
                          children: [
                            if (_checkingUpdates) ...[
                              const SizedBox(
                                width: 11,
                                height: 11,
                                child: CircularProgressIndicator(strokeWidth: 1.6),
                              ),
                              const SizedBox(width: 6),
                            ],
                            Expanded(
                              child: Text(
                                _checkingUpdates
                                    ? 'Checking for updates…'
                                    : 'Ready to install',
                                style: DroidTheme.bodySm,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  if (_updates.isNotEmpty) ...[
                    const SizedBox(width: 10),
                    _capsule(
                      label: 'Update all',
                      compact: true,
                      color: DroidTheme.primary,
                      filled: true,
                      onTap: busy ? null : _updateAll,
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (_updates.isNotEmpty) ...[
            const SizedBox(height: 26),
            DroidSectionLabel('Available updates'),
            const SizedBox(height: 10),
            DroidGroupCard(
              child: Column(
                children: [
                  for (var i = 0; i < _updates.length; i++) ...[
                    if (i > 0) DroidInsetDivider(indent: 76),
                    _updateRow(_updates[i], busy: busy, active: appState.activePackage == _updates[i].name),
                  ],
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _updateRow(_PackageItem package, {required bool busy, required bool active}) {
    final featured = _featuredFor(package.name);
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
      child: Row(
        children: [
          LinuxAppIcon(
            package: package.name,
            iconNames: featured?.iconNames ?? const [],
            fallback: _appTile(
              featured?.icon ?? _categoryIcon(package.section, package.gui),
              featured?.color ?? _categoryColor(package.section),
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  featured?.name ?? package.displayName,
                  style: DroidTheme.headingSm.copyWith(fontSize: 15),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 3),
                Text(
                  package.currentVersion.isEmpty
                      ? package.version
                      : '${package.currentVersion}  →  ${package.version}',
                  style: DroidTheme.monoSm,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          active
              ? _actionButton(package)
              : _capsule(
                  label: 'Update',
                  color: DroidTheme.primary,
                  onTap: busy ? null : () => _updateOne(package),
                ),
        ],
      ),
    );
  }

  Widget _installedView() {
    if (_loadingInstalled && _installedPackages.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    return RefreshIndicator(
      onRefresh: _loadInstalled,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
        children: [
          Row(
            children: [
              DroidSectionLabel('Installed packages'),
              const Spacer(),
              if (_installedPackages.isNotEmpty)
                Text('${_installedPackages.length}', style: DroidTheme.bodySm),
            ],
          ),
          const SizedBox(height: 10),
          if (_installedPackages.isEmpty)
            DroidEmptyCard(
              icon: Icons.inventory_2_outlined,
              title: 'Nothing installed yet',
              subtitle: 'Apps you install from Featured or Browse show up here.',
            )
          else
            DroidGroupCard(
              child: Column(
                children: [
                  for (var i = 0; i < _installedPackages.length; i++) ...[
                    if (i > 0) DroidInsetDivider(indent: 76),
                    _packageRow(_installedPackages[i], allowRemove: true),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _appTile(IconData icon, Color color, {double size = 46}) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [color.withValues(alpha: 0.24), color.withValues(alpha: 0.12)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(size * 0.28),
      ),
      child: Icon(icon, color: color, size: size * 0.5),
    );
  }

  /// One package inside a grouped card: icon, name, description, meta and action.
  Widget _packageRow(
    _PackageItem package, {
    _FeaturedApp? featured,
    bool allowRemove = false,
    Future<void> Function()? onInstall,
  }) {
    final color = featured?.color ?? _categoryColor(package.section);
    final meta = [
      if (package.version.isNotEmpty) package.version,
      if (package.architecture.isNotEmpty) package.architecture.toUpperCase(),
      package.gui ? 'GUI app' : package.section,
    ].where((e) => e.isNotEmpty).join(' · ');
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
      child: Row(
        children: [
          LinuxAppIcon(
            package: package.name,
            iconNames: featured?.iconNames ?? const [],
            fallback: _appTile(
              featured?.icon ?? _categoryIcon(package.section, package.gui),
              color,
            ),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  featured?.name ?? package.displayName,
                  style: DroidTheme.headingSm.copyWith(fontSize: 15),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  package.description.isEmpty
                      ? 'Linux package'
                      : package.description,
                  style: DroidTheme.bodySm,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (meta.isNotEmpty && featured == null) ...[
                  const SizedBox(height: 3),
                  Text(meta, style: DroidTheme.monoSm),
                ],
              ],
            ),
          ),
          const SizedBox(width: 10),
          _actionButton(package, allowRemove: allowRemove, onInstall: onInstall),
        ],
      ),
    );
  }

  /// App Store style capsule: Get / progress / Installed / Remove.
  Widget _actionButton(
    _PackageItem package, {
    bool allowRemove = false,
    Future<void> Function()? onInstall,
    bool prominent = false,
  }) {
    final installed = package.installed || _isInstalledName(package.name);
    final appState = context.watch<AppState>();
    final busy = appState.activePackage != null;
    final active = appState.activePackage == package.name;

    if (active) {
      final progress = appState.packageProgress;
      final value = progress > 0 ? progress : null;
      final percent = '${(progress * 100).round()}%';
      if (prominent) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Text(
                  appState.packageCancelling ? 'Cancelling…' : 'Installing…',
                  style: DroidTheme.bodySm.copyWith(fontWeight: FontWeight.w600),
                ),
                const Spacer(),
                Text(
                  value == null ? '' : percent,
                  style: DroidTheme.bodySm.copyWith(
                    fontWeight: FontWeight.w700,
                    color: DroidTheme.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                minHeight: 8,
                value: value,
                backgroundColor: DroidTheme.surfaceBorder,
              ),
            ),
          ],
        );
      }
      return SizedBox(
        width: 74,
        height: 40,
        child: Center(
          child: SizedBox(
            width: 40,
            height: 40,
            child: Stack(
              alignment: Alignment.center,
              children: [
                SizedBox.expand(
                  child: CircularProgressIndicator(
                    strokeWidth: 3,
                    value: value,
                    backgroundColor: DroidTheme.surfaceBorder,
                  ),
                ),
                if (value != null)
                  Text(
                    percent,
                    style: DroidTheme.label.copyWith(
                      fontSize: 10,
                      letterSpacing: 0,
                      color: DroidTheme.primary,
                    ),
                  ),
              ],
            ),
          ),
        ),
      );
    }
    if (installed && allowRemove && package.removable) {
      return _capsule(
        label: 'Remove',
        color: DroidTheme.error,
        onTap: busy ? null : () => _remove(package),
      );
    }
    if (installed && prominent) {
      return Container(
        height: 46,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: DroidTheme.accent,
          borderRadius: BorderRadius.circular(DroidTheme.radiusMd),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.check_circle_rounded, size: 18, color: Colors.white),
            const SizedBox(width: 6),
            Text(
              'INSTALLED',
              style: DroidTheme.label.copyWith(
                fontSize: 13,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.4,
                color: Colors.white,
              ),
            ),
          ],
        ),
      );
    }
    if (installed) {
      return Container(
        padding: const EdgeInsets.fromLTRB(9, 6, 12, 6),
        decoration: BoxDecoration(
          color: DroidTheme.accent.withValues(alpha: 0.18),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: DroidTheme.accent.withValues(alpha: 0.45)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.check_rounded, size: 15, color: DroidTheme.accent),
            const SizedBox(width: 4),
            Text(
              'Installed',
              style: DroidTheme.label.copyWith(
                color: DroidTheme.accent,
                letterSpacing: 0.1,
              ),
            ),
          ],
        ),
      );
    }
    return _capsule(
      label: 'Get',
      color: DroidTheme.primary,
      filled: prominent,
      onTap: busy
          ? null
          : () async {
              if (onInstall != null) {
                await onInstall();
                if (mounted) setState(() {});
              } else {
                await _install(package.name);
              }
            },
    );
  }

  Widget _capsule({
    required String label,
    required Color color,
    required VoidCallback? onTap,
    bool filled = false,
    bool compact = false,
  }) {
    final enabled = onTap != null;
    return Material(
      color: filled
          ? color.withValues(alpha: enabled ? 1 : 0.4)
          : color.withValues(alpha: enabled ? 0.14 : 0.06),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minWidth: 74),
          padding: EdgeInsets.symmetric(horizontal: compact ? 12 : 16, vertical: 7),
          alignment: Alignment.center,
          child: Text(
            label.toUpperCase(),
            style: DroidTheme.label.copyWith(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.4,
              color: filled
                  ? Colors.white
                  : color.withValues(alpha: enabled ? 1 : 0.4),
            ),
          ),
        ),
      ),
    );
  }

  IconData _categoryIcon(String section, bool gui) {
    final value = section.toLowerCase();
    if (value.contains('internet')) return Icons.public_rounded;
    if (value.contains('develop')) return Icons.code_rounded;
    if (value.contains('graphic')) return Icons.palette_rounded;
    if (value.contains('media')) return Icons.movie_rounded;
    if (value.contains('office')) return Icons.description_rounded;
    return gui ? Icons.apps_rounded : Icons.terminal_rounded;
  }

  Color _categoryColor(String section) {
    final value = section.toLowerCase();
    if (value.contains('internet')) return const Color(0xFF39A0FF);
    if (value.contains('develop')) return const Color(0xFF9B7BFF);
    if (value.contains('graphic')) return const Color(0xFFFF6B9E);
    if (value.contains('media')) return const Color(0xFFFF9F43);
    if (value.contains('office')) return const Color(0xFF38C793);
    return DroidTheme.primaryLight;
  }
}

class _PackageItem {
  final String name;
  final String description;
  final String version;
  final String currentVersion;
  final String architecture;
  final String section;
  final bool installed;
  final bool gui;
  final bool removable;

  const _PackageItem({
    required this.name,
    this.description = '',
    this.version = '',
    this.currentVersion = '',
    this.architecture = '',
    this.section = 'System',
    this.installed = false,
    this.gui = false,
    this.removable = true,
  });

  factory _PackageItem.fromMap(Map<String, dynamic> map) => _PackageItem(
    name: map['name']?.toString() ?? '',
    description: map['description']?.toString() ?? '',
    version: map['version']?.toString() ?? '',
    currentVersion: map['currentVersion']?.toString() ?? '',
    architecture: map['architecture']?.toString() ?? '',
    section: map['section']?.toString() ?? 'System',
    installed: map['installed'] == true,
    gui: map['gui'] == true,
    removable: map['removable'] != false,
  );

  String get displayName => name
      .split('-')
      .map(
        (word) => word.isEmpty
            ? word
            : '${word[0].toUpperCase()}${word.substring(1)}',
      )
      .join(' ');
}

class _FeaturedApp {
  final String packageName;
  final String name;
  final String description;
  final IconData icon;
  final Color color;
  final bool optional;
  final List<String> iconNames;

  const _FeaturedApp(
    this.packageName,
    this.name,
    this.description,
    this.icon,
    this.color, {
    this.optional = false,
    this.iconNames = const [],
  });
}
