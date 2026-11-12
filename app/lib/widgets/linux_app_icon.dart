import 'dart:typed_data';

import 'package:droiddesk/services/platform_bridge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Real icon for a Linux package (installed .desktop icon or Papirus), cached per package.
/// Shows [fallback] while loading and when no icon exists.
class LinuxAppIcon extends StatefulWidget {
  final String package;
  final List<String> iconNames;
  final Widget fallback;
  final double size;

  const LinuxAppIcon({
    super.key,
    required this.package,
    required this.iconNames,
    required this.fallback,
    this.size = 46,
  });

  static final Map<String, Future<Map<String, dynamic>?>> _cache = {};

  @override
  State<LinuxAppIcon> createState() => _LinuxAppIconState();
}

class _LinuxAppIconState extends State<LinuxAppIcon> {
  late Future<Map<String, dynamic>?> _icon;

  @override
  void initState() {
    super.initState();
    _icon = _load();
  }

  @override
  void didUpdateWidget(covariant LinuxAppIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.package != widget.package) _icon = _load();
  }

  Future<Map<String, dynamic>?> _load() => LinuxAppIcon._cache.putIfAbsent(
        widget.package,
        () => DroidDeskPlatform.getLinuxAppIcon(
          widget.package,
          iconNames: widget.iconNames,
        ).catchError((_) => null),
      );

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Map<String, dynamic>?>(
      future: _icon,
      builder: (context, snapshot) {
        final icon = snapshot.data;
        final bytes = icon?['bytes'];
        if (bytes is! Uint8List) return widget.fallback;
        final image = icon!['format'] == 'svg'
            ? SvgPicture.memory(
                bytes,
                width: widget.size,
                height: widget.size,
                placeholderBuilder: (_) => widget.fallback,
              )
            : Image.memory(
                bytes,
                width: widget.size,
                height: widget.size,
                filterQuality: FilterQuality.medium,
                errorBuilder: (_, _, _) => widget.fallback,
              );
        return SizedBox(width: widget.size, height: widget.size, child: image);
      },
    );
  }
}
