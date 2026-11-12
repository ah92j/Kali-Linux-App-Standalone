import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'package:shared_preferences/shared_preferences.dart';

/// A GitHub release newer than the installed app.
class AppRelease {
  final String tag;
  final String version;
  final String title;
  final String notes;
  final String pageUrl;
  final String apkUrl;
  final int apkSize;

  const AppRelease({
    required this.tag,
    required this.version,
    required this.title,
    required this.notes,
    required this.pageUrl,
    required this.apkUrl,
    required this.apkSize,
  });
}

/// Checks GitHub Releases for a newer DroidDesk build.
class UpdateService {
  static const repo = 'ah92j/DroidDesk-GIO-Fix';
  // Test builds can point at a local mock: --dart-define=DROIDDESK_UPDATE_URL=http://...
  static const _latestUrl = String.fromEnvironment(
    'DROIDDESK_UPDATE_URL',
    defaultValue: 'https://api.github.com/repos/$repo/releases?per_page=20',
  );
  static const _lastCheckKey = 'update_last_check';
  static const _skippedTagKey = 'update_skipped_tag';
  static const checkInterval = Duration(hours: 6);

  /// Returns a newer release, or null.
  ///
  /// Automatic checks run at most every [checkInterval] and respect "Skip this version";
  /// [manual] checks always hit the network and report errors by throwing.
  static Future<AppRelease?> check({
    required String installedVersion,
    bool manual = false,
    bool force = false,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    if (!manual && !force) {
      final last = prefs.getInt(_lastCheckKey) ?? 0;
      final elapsed = DateTime.now().millisecondsSinceEpoch - last;
      if (elapsed < checkInterval.inMilliseconds) return null;
    }

    final List<Map<String, dynamic>> releases;
    try {
      releases = await _fetchReleases();
    } catch (error) {
      if (manual) rethrow;
      return null;
    }
    await prefs.setInt(_lastCheckKey, DateTime.now().millisecondsSinceEpoch);

    return selectNewerRelease(
      releases,
      installedVersion: installedVersion,
      skippedTag: manual ? null : prefs.getString(_skippedTagKey),
    );
  }

  /// Selects the highest compatible release from GitHub's releases collection.
  /// Signed device-test prereleases are eligible for the in-app update flow.
  @visibleForTesting
  static AppRelease? selectNewerRelease(
    List<Map<String, dynamic>> releases, {
    required String installedVersion,
    String? skippedTag,
  }) {
    AppRelease? newest;
    for (final json in releases) {
      final tag = json['tag_name']?.toString() ?? '';
      if (tag.isEmpty || json['draft'] == true) continue;
      if (!isNewer(tag, installedVersion) || skippedTag == tag) continue;

      final assets = (json['assets'] as List? ?? const [])
          .whereType<Map>()
          .map((asset) => Map<String, dynamic>.from(asset))
          .where((asset) =>
              (asset['name']?.toString() ?? '').toLowerCase().endsWith('.apk') &&
              (asset['browser_download_url']?.toString() ?? '').startsWith('https://'))
          .toList();
      final arm64Assets = assets.where((asset) =>
          (asset['name']?.toString() ?? '').toLowerCase().contains('arm64'));
      // This app targets ARM64. Never offer an APK of an unknown or incompatible ABI.
      if (arm64Assets.isEmpty) continue;
      final apk = arm64Assets.first;
      final candidate = AppRelease(
        tag: tag,
        version: _clean(tag),
        title: json['name']?.toString() ?? tag,
        notes: (json['body']?.toString() ?? '').trim(),
        pageUrl: json['html_url']?.toString() ?? 'https://github.com/$repo/releases',
        apkUrl: apk['browser_download_url'].toString(),
        apkSize: (apk['size'] as num?)?.toInt() ?? 0,
      );
      if (newest == null || isNewer(candidate.tag, newest.tag)) newest = candidate;
    }
    return newest;
  }

  static Future<void> skip(String tag) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_skippedTagKey, tag);
  }

  static Future<List<Map<String, dynamic>>> _fetchReleases() async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client.getUrl(Uri.parse(_latestUrl));
      request.headers
        ..set(HttpHeaders.acceptHeader, 'application/vnd.github+json')
        ..set(HttpHeaders.userAgentHeader, 'DroidDesk-Updater');
      final response = await request.close().timeout(const Duration(seconds: 15));
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode != 200) {
        throw HttpException('GitHub returned ${response.statusCode}');
      }
      final decoded = jsonDecode(body);
      if (decoded is! List) {
        throw const FormatException('GitHub releases response is not a list');
      }
      return decoded
          .whereType<Map>()
          .map((release) => Map<String, dynamic>.from(release))
          .toList();
    } finally {
      client.close();
    }
  }

  /// "v1.2.0" → "1.2.0"; drops a "-suffix" and "+build".
  static String _clean(String version) =>
      version.trim().replaceFirst(RegExp(r'^[vV]'), '').split(RegExp(r'[-+ ]')).first;

  /// True when [candidate] is a higher semantic version than [current].
  static bool isNewer(String candidate, String current) {
    List<int> parts(String value) {
      final normalized = value.trim().replaceFirst(RegExp(r'^[vV]'), '');
      final core = _clean(normalized)
          .split('.')
          .map((part) => int.tryParse(part) ?? 0)
          .toList();
      while (core.length < 3) {
        core.add(0);
      }
      final build = RegExp(r'\+(\d+)').firstMatch(normalized);
      core.add(int.tryParse(build?.group(1) ?? '') ?? 0);
      return core;
    }

    final a = parts(candidate);
    final b = parts(current);
    for (var i = 0; i < 4; i++) {
      if (a[i] != b[i]) return a[i] > b[i];
    }
    return false;
  }
}
