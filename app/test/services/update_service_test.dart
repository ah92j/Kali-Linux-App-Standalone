import 'package:droiddesk/services/update_service.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> release({
  required String tag,
  bool prerelease = false,
  bool draft = false,
  String apk = 'Kali-Linux-arm64.apk',
}) => {
      'tag_name': tag,
      'name': 'Kali Linux $tag',
      'body': 'Test release',
      'html_url': 'https://github.com/ah92j/DroidDesk-GIO-Fix/releases/tag/$tag',
      'prerelease': prerelease,
      'draft': draft,
      'assets': [
        {
          'name': apk,
          'browser_download_url':
              'https://github.com/ah92j/DroidDesk-GIO-Fix/releases/download/$tag/$apk',
          'size': 123456,
        },
      ],
    };

void main() {
  group('UpdateService release selection', () {
    test('offers a newer signed prerelease to the installed app', () {
      final selected = UpdateService.selectNewerRelease(
        [
          release(tag: 'v1.4.32+49', prerelease: true),
        ],
        installedVersion: '1.4.31+48',
      );

      expect(selected, isNotNull);
      expect(selected!.tag, 'v1.4.32+49');
      expect(selected.apkUrl, contains('Kali-Linux-arm64.apk'));
    });

    test('uses the ARM64 APK and ignores a release without an ARM64 asset', () {
      final selected = UpdateService.selectNewerRelease(
        [
          {
            ...release(tag: 'v1.4.34+52'),
            'assets': [
              {
                'name': 'Kali-Linux-amd64.apk',
                'browser_download_url': 'https://example.com/amd64.apk',
                'size': 100,
              },
              {
                'name': 'Kali-Linux-arm64.apk',
                'browser_download_url': 'https://example.com/arm64.apk',
                'size': 200,
              },
            ],
          },
          release(tag: 'v1.4.33+51', apk: 'Kali-Linux-amd64.apk'),
        ],
        installedVersion: '1.4.32+49',
      );

      expect(selected, isNotNull);
      expect(selected!.tag, 'v1.4.34+52');
      expect(selected.apkUrl, 'https://example.com/arm64.apk');
    });

    test('ignores drafts and the version explicitly skipped by the user', () {
      final selected = UpdateService.selectNewerRelease(
        [
          release(tag: 'v1.4.35+53', draft: true),
          release(tag: 'v1.4.34+52'),
          release(tag: 'v1.4.33+51'),
        ],
        installedVersion: '1.4.32+49',
        skippedTag: 'v1.4.34+52',
      );

      expect(selected, isNotNull);
      expect(selected!.tag, 'v1.4.33+51');
    });

    test('does not offer the exact build already installed', () {
      final selected = UpdateService.selectNewerRelease(
        [release(tag: 'v1.4.33+50')],
        installedVersion: '1.4.33+50',
      );

      expect(selected, isNull);
    });

    test('offers a newer build with the same semantic version', () {
      final selected = UpdateService.selectNewerRelease(
        [release(tag: 'v1.4.33+51')],
        installedVersion: '1.4.33+50',
      );

      expect(selected, isNotNull);
      expect(selected!.tag, 'v1.4.33+51');
    });

    test('compares build numbers when semantic version components match', () {
      expect(UpdateService.isNewer('1.4.32+50', '1.4.32+49'), isTrue);
      expect(UpdateService.isNewer('1.4.32+49', '1.4.32+49'), isFalse);
      expect(UpdateService.isNewer('1.4.31+99', '1.4.32+1'), isFalse);
    });
  });
}
