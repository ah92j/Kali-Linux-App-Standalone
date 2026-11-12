#!/usr/bin/env bash
set -Eeuo pipefail

export HOME=/root USER=root LOGNAME=root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8 TERM=dumb DEBIAN_FRONTEND=noninteractive
export APT_LISTCHANGES_FRONTEND=none BASH_ENV=/dev/null

readonly FEATURE_DIR="/root/.droiddesk"
readonly FEATURE_MARKER="$FEATURE_DIR/desktop-experience-v20"
readonly LOG_FILE="$FEATURE_DIR/desktop-experience.log"
readonly WALLPAPER_DIR="/usr/share/xfce4/backdrops/Kali Linux"
readonly FIREFOX_160_VERSION="160.0"
readonly FIREFOX_160_RELEASE_ROOT="https://ftp.mozilla.org/pub/firefox/releases/160.0"
readonly FIREFOX_160_INSTALL_DIR="/opt/droiddesk-firefox-160.0"
readonly MOZILLA_RELEASE_PRIMARY_FINGERPRINT="14F26682D0916CDD81E37B6D61B7B526D98F0353"

REQUIRED_PACKAGES=(
  kali-wallpapers-2026 firefox-esr xfce4 libpulse0 gvfs gvfs-daemons gvfs-backends pulseaudio-utils libasound2-plugins
  ffmpeg gstreamer1.0-libav gstreamer1.0-plugins-good
  gdebi zenity desktop-file-utils xdg-utils curl gnupg file tar xz-utils python3-gi gir1.2-gtk-3.0
)

if [[ "${1:-}" == "--self-test" ]]; then
  [[ "$WALLPAPER_DIR" == "/usr/share/xfce4/backdrops/Kali Linux" ]]
  grep -q 'gvfs-daemons' "$0"
  grep -q 'kali-cubes2-16x9.jpg' "$0"
  grep -q 'kali-wallpapers-2026' "$0"
  [[ "$FEATURE_MARKER" == "/root/.droiddesk/desktop-experience-v20" ]]
  grep -q 'FIREFOX_160_VERSION="160.0"' "$0"
  grep -q 'install_firefox_160()' "$0"
  grep -q 'linux-aarch64/en-US/firefox-160.0.tar.xz' "$0"
  grep -q 'MOZILLA_RELEASE_PRIMARY_FINGERPRINT="14F26682D0916CDD81E37B6D61B7B526D98F0353"' "$0"
  grep -q 'VALIDSIG' "$0"
  grep -Fq -- '--install-firefox-160' "$0"
  grep -q 'codec_recommendations' "$0"
  grep -q 'Firefox codec backend may require a different FFmpeg ABI' "$0"
  grep -q 'Route stock package-provided launchers through the same audio/codec wrapper' "$0"
  grep -q 'Synchronize desktop shortcuts for previously installed Kali store packages' "$0"
  grep -q 'Install the XFCE Applications menu on the bottom panel' "$0"
  grep -q 'applicationsmenu' "$0"
  grep -q 'droiddesk-kali-menu.svg' "$0"
  grep -q 'droiddesk-applications-menu' "$0"
  grep -q 'droiddesk-kali-menu' "$0"
  grep -q 'show-menu-icons' "$0"
  grep -q 'pref("media.mp4.enabled", true);' "$0"
  grep -q 'pref("media.opus.enabled", true);' "$0"
  grep -q 'user_pref("media.mp4.enabled", true);' "$0"
  grep -q 'panel-with-applications-menu.xml' "$0"
  grep -q 'Saved the updated panel XML' "$0"
  grep -q 'export LD_LIBRARY_PATH="/usr/lib/firefox-esr:' "$0"
  grep -q 'archive.kali.org/kali/' "$0"
  grep -Fq 'libavcodec-extra$codec_abi' "$0"
  grep -q 'codec_abi="$(ldconfig -p' "$0"
  grep -q 'apt-cache depends firefox-esr' "$0"
  grep -q 'ldd /usr/lib/firefox-esr/libmozavcodec.so' "$0"
  grep -q 'libavcodec-extra' "$0"
  grep -q "media.rdd-ffmpeg.enabled" "$0"
  grep -q 'user_pref("media.rdd-process.enabled", true);' "$0"
  grep -q 'user_pref("media.rdd-ffmpeg.enabled", true);' "$0"
  grep -q "MOZ_DISABLE_RDD_SANDBOX" "$0"
  grep -q "Preserve existing XFCE preferences" "$0"
  grep -q "policy-rc.d" "$0"
  grep -q "Still working:" "$0"
  grep -q "pulse_ready=0" "$0"
  grep -q "export LIBGL_ALWAYS_SOFTWARE=1" "$0"
  grep -q 'gfx.webrender.software' "$0"
  grep -q 'layers.acceleration.disabled' "$0"
  grep -q "launch-time diagnostics" "$0"
  grep -q "profile_is_newer_than_binary" "$0"
  grep -q "X-XFCE-Category=WebBrowser" "$0"
  grep -q "WebBrowser=droiddesk-browser" "$0"
  grep -q "x-scheme-handler/http" "$0"
  grep -q "DROIDDESK_FORCE_FIREFOX_160_REINSTALL" "$0"
  grep -q "rm -f /home/kali/Desktop/Firefox-Media-Diagnostic.txt" "$0"
  grep -q "kali-wallpapers-2026" "$0"
  ! grep -Eq '(^|[[:space:]])--install-recommends([[:space:]]|$)' "$0"
  printf '%s\n' "[Kali Linux] Desktop-experience checks passed."
  exit 0
fi

install -d -m 0700 "$FEATURE_DIR"
if [[ -f /home/kali/.local/bin/droiddesk-firefox ]] && ! grep -qF 'export LD_LIBRARY_PATH="/usr/lib/firefox-esr:' /home/kali/.local/bin/droiddesk-firefox; then
  sed -i '/^export PULSE_LATENCY_MSEC=80/a export LD_LIBRARY_PATH="/usr/lib/firefox-esr:${LD_LIBRARY_PATH:-}"' /home/kali/.local/bin/droiddesk-firefox
  chmod 0755 /home/kali/.local/bin/droiddesk-firefox
  echo "[Kali Linux] Firefox private library path was added for RDD/media child processes."
fi
exec > >(tee -a "$LOG_FILE") 2>&1

package_installed() {
  dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -qx 'install ok installed'
}

install_firefox_160() {
  local temp_dir keyring archive key_fingerprints verify_status expected_checksum actual_checksum
  local extract_dir candidate staged_dir backup_dir version_output dependencies

  if [[ "${DROIDDESK_FORCE_FIREFOX_160_REINSTALL:-0}" != "1" && -x "$FIREFOX_160_INSTALL_DIR/firefox" ]]; then
    version_output="$("$FIREFOX_160_INSTALL_DIR/firefox" --version 2>/dev/null || true)"
    if [[ "$version_output" == "Mozilla Firefox $FIREFOX_160_VERSION" ]]; then
      printf '%s\n' "$FIREFOX_160_VERSION" > "$FIREFOX_160_INSTALL_DIR/.droiddesk-managed-version"
      echo "[DroidDesk Firefox] Official Mozilla Firefox $FIREFOX_160_VERSION is already installed."
      return 0
    fi
  fi

  for required_tool in curl gpg sha256sum tar xz file ldd; do
    if ! command -v "$required_tool" >/dev/null 2>&1; then
      echo "[DroidDesk Firefox] Required verification tool is missing: $required_tool"
      return 1
    fi
  done

  temp_dir="$(mktemp -d /tmp/droiddesk-firefox-160.XXXXXX)" || return 1
  keyring="$temp_dir/gnupg"
  archive="$temp_dir/firefox-160.0.tar.xz"
  if ! install -d -m 0700 "$keyring"; then
    rm -rf "$temp_dir"
    return 1
  fi

  echo "[DroidDesk Firefox] Downloading Mozilla's signed Firefox 160.0 ARM64 release manifest."
  if ! curl -fsSL --retry 3 --connect-timeout 20 "$FIREFOX_160_RELEASE_ROOT/KEY" -o "$temp_dir/KEY" ||
     ! curl -fsSL --retry 3 --connect-timeout 20 "$FIREFOX_160_RELEASE_ROOT/SHA256SUMS" -o "$temp_dir/SHA256SUMS" ||
     ! curl -fsSL --retry 3 --connect-timeout 20 "$FIREFOX_160_RELEASE_ROOT/SHA256SUMS.asc" -o "$temp_dir/SHA256SUMS.asc"; then
    echo "[DroidDesk Firefox] Could not download Mozilla's signing key or checksum manifest."
    rm -rf "$temp_dir"
    return 1
  fi

  if ! gpg --homedir "$keyring" --batch --import "$temp_dir/KEY" >/dev/null 2>&1; then
    echo "[DroidDesk Firefox] Mozilla release signing key import failed."
    rm -rf "$temp_dir"
    return 1
  fi

  key_fingerprints="$(gpg --homedir "$keyring" --batch --with-colons --fingerprint --list-keys 2>/dev/null |
    awk -F: '$1 == "fpr" { print toupper($10) }')"
  if ! grep -Fxq "$MOZILLA_RELEASE_PRIMARY_FINGERPRINT" <<< "$key_fingerprints"; then
    echo "[DroidDesk Firefox] Mozilla primary release-key fingerprint verification failed."
    rm -rf "$temp_dir"
    return 1
  fi

  if ! verify_status="$(gpg --homedir "$keyring" --batch --status-fd=1 \
      --verify "$temp_dir/SHA256SUMS.asc" "$temp_dir/SHA256SUMS" 2>"$temp_dir/gpg-verify.log")"; then
    echo "[DroidDesk Firefox] Mozilla checksum-manifest signature is invalid:"
    tail -20 "$temp_dir/gpg-verify.log" || true
    rm -rf "$temp_dir"
    return 1
  fi
  if ! awk -v primary="$MOZILLA_RELEASE_PRIMARY_FINGERPRINT" '
      $1 == "[GNUPG:]" && $2 == "VALIDSIG" && toupper($12) == primary { verified=1 }
      END { exit !verified }
    ' <<< "$verify_status"; then
    echo "[DroidDesk Firefox] The signed manifest was not verified by a signing subkey certified by Mozilla's pinned primary key."
    rm -rf "$temp_dir"
    return 1
  fi

  expected_checksum="$(awk '
    $2 == "linux-aarch64/en-US/firefox-160.0.tar.xz" ||
    $2 == "*linux-aarch64/en-US/firefox-160.0.tar.xz" { print $1; exit }
  ' "$temp_dir/SHA256SUMS")"
  if [[ ! "$expected_checksum" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "[DroidDesk Firefox] The signed manifest has no ARM64 Firefox 160.0 checksum."
    rm -rf "$temp_dir"
    return 1
  fi

  echo "[DroidDesk Firefox] Downloading the official Mozilla Linux ARM64 archive."
  if ! curl -fL --retry 3 --connect-timeout 20 \
      "$FIREFOX_160_RELEASE_ROOT/linux-aarch64/en-US/firefox-160.0.tar.xz" \
      -o "$archive"; then
    echo "[DroidDesk Firefox] Firefox 160.0 download failed."
    rm -rf "$temp_dir"
    return 1
  fi

  actual_checksum="$(sha256sum "$archive" | awk '{ print tolower($1) }')"
  expected_checksum="$(printf '%s' "$expected_checksum" | tr '[:upper:]' '[:lower:]')"
  if [[ "$actual_checksum" != "$expected_checksum" ]]; then
    echo "[DroidDesk Firefox] Firefox 160.0 SHA-256 mismatch; refusing installation."
    rm -rf "$temp_dir"
    return 1
  fi

  if ! tar -tJf "$archive" >/dev/null 2>&1; then
    echo "[DroidDesk Firefox] Firefox archive integrity check failed."
    rm -rf "$temp_dir"
    return 1
  fi

  extract_dir="$temp_dir/extract"
  if ! install -d -m 0755 "$extract_dir" || ! tar -xJf "$archive" -C "$extract_dir"; then
    echo "[DroidDesk Firefox] Firefox archive extraction failed."
    rm -rf "$temp_dir"
    return 1
  fi
  candidate="$extract_dir/firefox"
  if [[ ! -x "$candidate/firefox" || ! -x "$candidate/firefox-bin" ]]; then
    echo "[DroidDesk Firefox] Mozilla archive does not contain the expected executable files."
    rm -rf "$temp_dir"
    return 1
  fi

  if ! file "$candidate/firefox-bin" | grep -Eqi 'ARM aarch64|ARM64'; then
    echo "[DroidDesk Firefox] Refusing a non-ARM64 Firefox executable."
    rm -rf "$temp_dir"
    return 1
  fi
  version_output="$("$candidate/firefox" --version 2>/dev/null || true)"
  if [[ "$version_output" != "Mozilla Firefox $FIREFOX_160_VERSION" ]]; then
    echo "[DroidDesk Firefox] Unexpected extracted browser version: $version_output"
    rm -rf "$temp_dir"
    return 1
  fi
  dependencies="$temp_dir/firefox-dependencies.txt"
  if ! ldd "$candidate/firefox-bin" > "$dependencies" 2>&1 ||
     grep -Fq 'not found' "$dependencies"; then
    echo "[DroidDesk Firefox] Firefox 160.0 has unresolved system libraries:"
    cat "$dependencies"
    rm -rf "$temp_dir"
    return 1
  fi

  if ! install -d -m 0755 /opt; then
    rm -rf "$temp_dir"
    return 1
  fi
  staged_dir="$FIREFOX_160_INSTALL_DIR.new"
  backup_dir="$FIREFOX_160_INSTALL_DIR.previous"
  rm -rf "$staged_dir" "$backup_dir"
  if ! mv "$candidate" "$staged_dir"; then
    echo "[DroidDesk Firefox] Could not stage the verified Firefox installation."
    rm -rf "$temp_dir"
    return 1
  fi
  if [[ -d "$FIREFOX_160_INSTALL_DIR" ]] && ! mv "$FIREFOX_160_INSTALL_DIR" "$backup_dir"; then
    echo "[DroidDesk Firefox] Could not preserve the previous standalone Firefox directory."
    rm -rf "$staged_dir" "$temp_dir"
    return 1
  fi
  if ! mv "$staged_dir" "$FIREFOX_160_INSTALL_DIR"; then
    if [[ -d "$backup_dir" ]]; then mv "$backup_dir" "$FIREFOX_160_INSTALL_DIR"; fi
    echo "[DroidDesk Firefox] Could not activate the verified Firefox installation."
    rm -rf "$staged_dir" "$temp_dir"
    return 1
  fi
  printf '%s\n' "$FIREFOX_160_VERSION" > "$FIREFOX_160_INSTALL_DIR/.droiddesk-managed-version"
  rm -rf "$backup_dir" "$temp_dir"
  echo "[DroidDesk Firefox] Installed verified Mozilla Firefox $FIREFOX_160_VERSION for Linux ARM64."
  return 0
}

if [[ "${1:-}" == "--install-firefox-160" ]]; then
  install_firefox_160
  exit $?
fi

configure_proot_service_policy() {
  install -d -m 0755 /usr/sbin
  if [[ ! -e /usr/sbin/policy-rc.d ]]; then
    cat > /usr/sbin/policy-rc.d <<'POLICY'
#!/bin/sh
exit 101
POLICY
    chmod 0755 /usr/sbin/policy-rc.d
  fi
}

run_with_package_heartbeat() {
  local label="$1"
  shift
  "$@" &
  local command_pid=$!
  (
    while kill -0 "$command_pid" 2>/dev/null; do
      sleep 15
      if kill -0 "$command_pid" 2>/dev/null; then
        latest_event="$(tail -n 1 /var/log/dpkg.log 2>/dev/null || true)"
        echo "[Kali Linux] Still working: $label. Last dpkg activity: ${latest_event:-No dpkg event recorded yet}"
      fi
    done
  ) &
  local heartbeat_pid=$!
  local command_status=0
  if wait "$command_pid"; then command_status=0; else command_status=$?; fi
  kill "$heartbeat_pid" 2>/dev/null || true
  wait "$heartbeat_pid" 2>/dev/null || true
  return "$command_status"
}

configure_proot_service_policy
APT_OPTIONS=(
  -o Acquire::ForceIPv4=true
  -o Acquire::Retries=3
  -o Acquire::http::Timeout=25
  -o Acquire::https::Timeout=45
  -o Acquire::https::Verify-Peer=true
  -o Acquire::https::Verify-Host=true
  -o APT::Keep-Downloaded-Packages=true
  -o Dpkg::Use-Pty=0
  -o Dpkg::Options::=--force-confdef
  -o Dpkg::Options::=--force-confold
)

if ! run_with_package_heartbeat "refreshing Kali ARM64 indexes from configured mirror" apt-get "${APT_OPTIONS[@]}" update; then
  echo "[Kali Linux] Configured Kali mirror failed; trying archive.kali.org over HTTPS."
  if [[ -f /etc/apt/sources.list.d/droiddesk-kali.sources ]]; then
    sed -i 's|^URIs:.*|URIs: https://archive.kali.org/kali/|' /etc/apt/sources.list.d/droiddesk-kali.sources
  fi
  run_with_package_heartbeat "refreshing Kali ARM64 indexes from archive mirror" apt-get "${APT_OPTIONS[@]}" update
fi
if ! run_with_package_heartbeat "finishing interrupted package configuration" dpkg --configure -a; then
  run_with_package_heartbeat "repairing interrupted package dependencies" env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" --fix-broken install -y --no-install-recommends
  run_with_package_heartbeat "completing repaired package configuration" dpkg --configure -a
fi
if dpkg --audit | grep -q .; then
  run_with_package_heartbeat "repairing incomplete package database" env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" --fix-broken install -y --no-install-recommends
  run_with_package_heartbeat "finishing package database repair" dpkg --configure -a
fi
if dpkg --audit | grep -q .; then
  echo "[Kali Linux] dpkg still reports incomplete packages; migration stopped safely."
  exit 23
fi

missing_packages=()
for package in "${REQUIRED_PACKAGES[@]}"; do
  if ! package_installed "$package"; then missing_packages+=("$package"); fi
done

if (("${#missing_packages[@]}" > 0)); then
  echo "[Kali Linux] Installing only required ARM64 desktop, codecs and installer support (without optional recommendations)..."
  run_with_package_heartbeat "installing required desktop packages" env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" --no-install-recommends install -y "${missing_packages[@]}"
fi

for package in "${REQUIRED_PACKAGES[@]}"; do
  if ! package_installed "$package"; then
    echo "[Kali Linux] Required package did not configure: $package"
    exit 20
  fi
done

echo "[Kali Linux] Upgrading the official Kali Rolling XFCE package set..."
run_with_package_heartbeat "upgrading XFCE from the official Kali ARM64 repository" \
  env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" --no-install-recommends install -y xfce4
xfce_version="$(xfce4-session --version 2>&1 | head -n 1 || true)"
echo "[Kali Linux] Installed XFCE session version: ${xfce_version:-version unavailable}"
if [[ "$xfce_version" != *"4.20"* ]]; then
  echo "[Kali Linux] NOTICE: the current Kali Rolling repository provides a different XFCE release; keeping its signed repository version."
fi

# Preserve custom wallpaper, panel/dock settings and desktop icon positions on upgrade.
PRESERVE_DIR="$(mktemp -d /tmp/droiddesk-xfce-preserve.XXXXXX)"
PRESERVE_ARCHIVE="$PRESERVE_DIR/user-xfce-settings.tar"
PRESERVE_TEMP="$PRESERVE_DIR/user-xfce-settings.tmp"
PRESERVE_READY=0
PRESERVE_ITEMS=()
for item in \
  .config/xfce4/xfconf/xfce-perchannel-xml \
  .config/xfce4/desktop \
  .config/xfce4/panel; do
  if [[ -e "/home/kali/$item" ]]; then
    PRESERVE_ITEMS+=("$item")
  fi
done
restore_user_xfce_preferences() {
  if ((PRESERVE_READY == 1)) && [[ -s "$PRESERVE_ARCHIVE" ]]; then
    if tar -xf "$PRESERVE_ARCHIVE" -C /home/kali; then
      chown -R kali:kali /home/kali/.config/xfce4 2>/dev/null || true
      echo "[Kali Linux] Restored the user's wallpaper, dock and desktop icon layout."
    else
      echo "[Kali Linux] WARNING: saved XFCE preferences could not be restored."
    fi
  fi

  # The App Store migration changes the live xfconf panel configuration. Preserve
  # that updated panel XML across the snapshot restore above, otherwise the EXIT
  # trap would put the old plugin list back and remove the Applications menu.
  panel_menu_snapshot="$PRESERVE_DIR/panel-with-applications-menu.xml"
  panel_config="/home/kali/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml"
  if [[ -s "$panel_menu_snapshot" ]]; then
    install -m 0644 "$panel_menu_snapshot" "$panel_config"
    chown kali:kali "$panel_config" 2>/dev/null || true
    echo "[DroidDesk Panel] Preserved the Applications menu in the saved bottom-panel configuration."
  fi
  rm -rf "$PRESERVE_DIR"
}
trap restore_user_xfce_preferences EXIT
if ((${#PRESERVE_ITEMS[@]} > 0)); then
  if tar -cf "$PRESERVE_TEMP" -C /home/kali "${PRESERVE_ITEMS[@]}"; then
    mv "$PRESERVE_TEMP" "$PRESERVE_ARCHIVE"
    PRESERVE_READY=1
    echo "[Kali Linux] Saved existing wallpaper, panel settings and desktop icon positions."
  else
    echo "[Kali Linux] WARNING: could not snapshot XFCE settings; migration will not replace existing files."
  fi
fi

# Ensure Firefox exists without forcing a reinstall or pulling optional recommendations.
echo "[Kali Linux] Ensuring Firefox ESR is configured for ARM64..."
run_with_package_heartbeat "refreshing the Kali Firefox ESR ARM64 package" env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" --no-install-recommends --reinstall install -y firefox-esr
if ! package_installed firefox-esr; then
  echo "[Kali Linux] Firefox ESR is not configured after package recovery."
  exit 22
fi

# Install the official Mozilla Firefox 160.0 ARM64 browser beside Kali ESR.
# This is a playback test candidate; the packaged Firefox ESR remains installed for rollback.
if ! DROIDDESK_FORCE_FIREFOX_160_REINSTALL=1 install_firefox_160; then
  echo "[DroidDesk Firefox] WARNING: Firefox 160.0 refresh failed; the last verified installation and Kali Firefox ESR remain available."
fi

# Select the codec package matching standalone Firefox first, then Kali Firefox ESR.
# Do not guess an ABI from the system FFmpeg command line.
# Select the codec package that matches the FFmpeg ABI installed on this Kali ARM64 rootfs.
# Firefox ESR needs the matching extra codec build for H.264/AAC; never choose an older ABI
# merely because apt-cache prints old Recommends alternatives alphabetically.
# Firefox's RDD backend may require a different FFmpeg ABI from the system CLI.
# Read the SONAME Firefox actually links against first; only fall back to the installed
# FFmpeg ABI when this Firefox build does not expose a dynamic libavcodec dependency.
firefox_codec_backend="$(find /opt/droiddesk-firefox-160.0 /usr/lib/firefox-esr /usr/lib/firefox -maxdepth 2 \\
  -name 'libmozavcodec.so' -type f -print -quit 2>/dev/null || true)"
codec_abi=""
if [[ -n "$firefox_codec_backend" && -f "$firefox_codec_backend" ]]; then
  codec_abi="$(
    LD_LIBRARY_PATH="/opt/droiddesk-firefox-160.0:/usr/lib/firefox-esr:/usr/lib/aarch64-linux-gnu:${LD_LIBRARY_PATH:-}" ldd "$firefox_codec_backend" 2>/dev/null |
      sed -nE 's/.*libavcodec\.so\.([0-9]+)([[:space:]].*)?$/\1/p' |
      head -n1
  )"
  if [[ -z "$codec_abi" ]] && command -v readelf >/dev/null 2>&1; then
    codec_abi="$(readelf -d "$firefox_codec_backend" 2>/dev/null |
      sed -nE 's/.*Shared library: \[libavcodec\.so\.([0-9]+)\].*/\1/p' |
      head -n1)"
  fi
fi
if [[ -z "$codec_abi" ]]; then
  codec_abi="$(ldconfig -p 2>/dev/null |
    sed -n 's/^[[:space:]]*libavcodec\.so\.\([0-9][0-9]*\) .*/\1/p' |
    sort -rn | head -n1)"
fi
echo "[Kali Linux] Firefox codec backend: ${firefox_codec_backend:-not found}; required FFmpeg ABI: ${codec_abi:-unknown}"
codec_candidate=""

package_supports_kali_arm64() {
  local package="$1"
  apt-cache show "$package" 2>/dev/null |
    awk -v wanted=arm64 '/^Architecture:/ { if ($2 == wanted || $2 == "all") found=1 } END { exit !found }'
}

if [[ "$codec_abi" =~ ^[0-9]+$ ]]; then
  for candidate in "libavcodec-extra$codec_abi" "libavcodec$codec_abi"; do
    if package_supports_kali_arm64 "$candidate"; then
      codec_candidate="$candidate"
      break
    fi
  done
fi

# If the system FFmpeg ABI is not installed yet, use only direct, available Firefox
# recommendations. Angle-bracket alternatives are virtual/missing candidates and skipped.
if [[ -z "$codec_candidate" ]]; then
  # Read both the primary Recommends field and its "|" alternatives. Prefer the
  # codec-enabled ABI-matched package before the basic FFmpeg runtime.
  mapfile -t codec_recommendations < <(
    apt-cache depends firefox-esr 2>/dev/null |
      sed -nE 's/^[[:space:]]*(Recommends:|\|)[[:space:]]*(libavcodec-extra[0-9]+|libavcodec[0-9]+)$/\2/p' |
      sort -u
  )
  for codec_prefix in libavcodec-extra libavcodec; do
    for candidate in "${codec_recommendations[@]}"; do
      [[ "$candidate" == "$codec_prefix"* ]] || continue
      if package_supports_kali_arm64 "$candidate"; then
        codec_candidate="$candidate"
        break 2
      fi
    done
  done
fi

if [[ -n "$codec_candidate" ]]; then
  echo "[Kali Linux] Installing Firefox ESR codec package for FFmpeg ABI ${codec_abi:-from-recommendation}: $codec_candidate"
  if run_with_package_heartbeat "installing Firefox ESR codec package $codec_candidate" \
      env DEBIAN_FRONTEND=noninteractive apt-get "${APT_OPTIONS[@]}" \
      --no-install-recommends install -y "$codec_candidate"; then
    ldconfig 2>/dev/null || true
    if package_installed "$codec_candidate"; then
      echo "[Kali Linux] Verified Firefox codec package: $codec_candidate"
    else
      echo "[Kali Linux] WARNING: codec package $codec_candidate did not reach install ok installed."
    fi
  else
    echo "[Kali Linux] WARNING: could not install matching Firefox codec package $codec_candidate."
  fi
else
  echo "[Kali Linux] No matching ARM64 FFmpeg codec package was found."
  echo "[Kali Linux] VP9/Opus software decoding remains enabled; H.264/AAC may be unavailable."
fi
# Install Google Chrome ARM64 from Google's signed repository first.
if ! package_installed google-chrome-stable; then
  key_tmp="$(mktemp)"
  if curl -fsSL --retry 3 --connect-timeout 20 https://dl.google.com/linux/linux_signing_key.pub -o "$key_tmp" &&
     gpg --dearmor --yes -o /usr/share/keyrings/google-chrome.gpg "$key_tmp"; then
    chmod 0644 /usr/share/keyrings/google-chrome.gpg
    printf '%s\n' 'deb [arch=arm64 signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main' > /etc/apt/sources.list.d/google-chrome.list
    if apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::https::Timeout=45 update &&
       apt-get -o Dpkg::Use-Pty=0 install -y --no-install-recommends google-chrome-stable; then
      echo "[Kali Linux] Google Chrome ARM64 installed from Google's signed repository."
    else
      rm -f /etc/apt/sources.list.d/google-chrome.list
    fi
  fi
  rm -f "$key_tmp"
fi
# If the repo install did not work, use Google's official ARM64 .deb and validate architecture.
if ! package_installed google-chrome-stable; then
  chrome_deb="$(mktemp --suffix=.deb)"
  if curl -fL --retry 3 --connect-timeout 20 https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb -o "$chrome_deb" &&
     [[ "$(dpkg-deb -f "$chrome_deb" Architecture 2>/dev/null || true)" == "arm64" ]] &&
     apt-get -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y --no-install-recommends "$chrome_deb"; then
    echo "[Kali Linux] Google Chrome ARM64 installed from official ARM64 .deb."
  else
    echo "[Kali Linux] Chrome ARM64 package unavailable; Firefox remains the fallback."
  fi
  rm -f "$chrome_deb"
fi

# One proper browser helper serves both the bottom dock and XFCE browser menu.
install -d -m 0755 /home/kali/.config/xfce4 /home/kali/.local/bin /home/kali/.local/share/applications /home/kali/Desktop
cat > /home/kali/.local/bin/droiddesk-browser <<'BROWSER'
#!/usr/bin/env bash
set -euo pipefail
if [[ -x /home/kali/.local/bin/droiddesk-firefox ]]; then
  if /home/kali/.local/bin/droiddesk-firefox "$@"; then exit 0; fi
  echo "[DroidDesk Browser] Firefox launch failed; trying the next ARM64 browser." >&2
fi
if [[ -x /usr/bin/google-chrome-stable ]]; then
  exec /usr/bin/google-chrome-stable --no-sandbox --disable-dev-shm-usage --ozone-platform=x11 "$@"
fi
if [[ -x /usr/bin/chromium ]]; then
  exec /usr/bin/chromium "$@"
fi
zenity --error --title='Kali Linux' --text='No supported browser is installed.' 2>/dev/null || true
exit 127
BROWSER
chmod 0755 /home/kali/.local/bin/droiddesk-browser
cat > /home/kali/.local/share/applications/droiddesk-browser.desktop <<'BROWSER_DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=Web Browser
Comment=Open the installed Kali browser
Exec=/home/kali/.local/bin/droiddesk-browser %U
Icon=web-browser
Terminal=false
StartupNotify=true
Categories=Network;WebBrowser;
BROWSER_DESKTOP
chmod 0644 /home/kali/.local/share/applications/droiddesk-browser.desktop
install -d -m 0755 /home/kali/.local/share/xfce4/helpers
cat > /home/kali/.local/share/xfce4/helpers/droiddesk-browser.desktop <<'BROWSER_HELPER'
[Desktop Entry]
NoDisplay=true
Version=1.0
Encoding=UTF-8
Type=X-XFCE-Helper
X-XFCE-Category=WebBrowser
X-XFCE-Commands=/home/kali/.local/bin/droiddesk-browser
X-XFCE-CommandsWithParameter=/home/kali/.local/bin/droiddesk-browser "%s"
Icon=web-browser
Name=Kali Linux Web Browser
BROWSER_HELPER
chmod 0644 /home/kali/.local/share/xfce4/helpers/droiddesk-browser.desktop
helpers_file="/home/kali/.config/xfce4/helpers.rc"
install -d -m 0755 /home/kali/.config/xfce4
touch "$helpers_file"
if ! grep -q '^FileManager=' "$helpers_file"; then printf '%s\n' 'FileManager=Thunar' >> "$helpers_file"; fi
sed -i '/^WebBrowser=/d' "$helpers_file"
printf '%s\n' 'WebBrowser=droiddesk-browser' >> "$helpers_file"
export HOME=/home/kali XDG_CONFIG_HOME=/home/kali/.config XDG_DATA_HOME=/home/kali/.local/share
for mime_type in x-scheme-handler/http x-scheme-handler/https text/html application/xhtml+xml; do
  xdg-mime default droiddesk-browser.desktop "$mime_type" || true
done
update-desktop-database /home/kali/.local/share/applications >/dev/null 2>&1 || true

# Route terminal URL launches directly to the managed browser. This bypasses the broken
# generic Exo helper lookup which previously raised "Failed to execute default Web Browser".
cat > /usr/local/bin/xdg-open <<'XDG_OPEN'
#!/bin/sh
case "$1" in
  http://*|https://*) exec /home/kali/.local/bin/droiddesk-browser "$@" ;;
esac
exec /usr/bin/xdg-open "$@"
XDG_OPEN
chmod 0755 /usr/local/bin/xdg-open

cat > /usr/local/bin/exo-open <<'EXO_OPEN'
#!/bin/sh
if [ "$1" = "--launch" ] && [ "$2" = "WebBrowser" ]; then
  shift 2
  exec /home/kali/.local/bin/droiddesk-browser "$@"
fi
case "$1" in
  http://*|https://*) exec /home/kali/.local/bin/droiddesk-browser "$@" ;;
esac
exec /usr/bin/exo-open "$@"
EXO_OPEN
chmod 0755 /usr/local/bin/exo-open
export BROWSER=/home/kali/.local/bin/droiddesk-browser
# Replace the saved browser launcher used by the existing bottom dock.
install -d -m 0755 /home/kali/.config/xfce4/panel/launcher-23
cat > /home/kali/.config/xfce4/panel/launcher-23/droiddesk-browser.desktop <<'DOCK_BROWSER'
[Desktop Entry]
Version=1.0
Type=Application
Name=Web Browser
Comment=Open the installed Kali browser
Exec=/home/kali/.local/bin/droiddesk-browser %U
Icon=web-browser
Terminal=false
StartupNotify=true
DOCK_BROWSER
chmod 0755 /home/kali/.config/xfce4/panel/launcher-23/droiddesk-browser.desktop

# Applications remain in the Applications menu; desktop shortcuts are user-controlled.
rm -f "/home/kali/Desktop/Google Chrome.desktop" /home/kali/Desktop/Firefox.desktop

# Firefox ESR wrapper, desktop shortcut and PRoot-friendly media preferences.
cat > /home/kali/.local/bin/droiddesk-firefox <<'FIREFOX'
#!/usr/bin/env bash
set -euo pipefail
export PULSE_SERVER=tcp:127.0.0.1:4713
export PULSE_SINK=droiddesk_android
export PULSE_LATENCY_MSEC=80
export LIBGL_ALWAYS_SOFTWARE=1
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_ENABLE_WAYLAND=0
export GDK_BACKEND=x11
firefox_160_home="/opt/droiddesk-firefox-160.0"
firefox_profile=""

profile_is_newer_than_binary() {
  local profile_dir="$1" binary_version="$2" recorded_version latest_version
  [[ -s "$profile_dir/compatibility.ini" ]] || return 1
  recorded_version="$(sed -nE 's/^LastVersion=([0-9]+([.][0-9]+)*).*/\1/p' "$profile_dir/compatibility.ini" | head -n 1)"
  [[ -n "$recorded_version" ]] || return 1
  latest_version="$(printf '%s\n%s\n' "$binary_version" "$recorded_version" | sort -V | tail -n 1)"
  [[ "$latest_version" == "$recorded_version" && "$recorded_version" != "$binary_version" ]]
}
choose_firefox_profile() {
  local requested="$1" binary_version="$2" selected="$1" base="$1" suffix=2
  if profile_is_newer_than_binary "$requested" "$binary_version"; then
    selected="${requested}-safe"
    while [[ -e "$selected" ]]; do
      selected="${base}-safe-${suffix}"
      suffix=$((suffix + 1))
    done
    echo "[DroidDesk Firefox] Existing profile was last used by newer Firefox; preserving it and using $selected." >&2
  fi
  install -d -m 0700 "$selected"
  chown kali:kali "$selected" 2>/dev/null || true
  printf '%s\n' "$selected"
}
if [[ -x "$firefox_160_home/firefox" ]]; then
  firefox_bin="$firefox_160_home/firefox"
  export LD_LIBRARY_PATH="$firefox_160_home:/usr/lib/firefox-esr:/usr/lib/aarch64-linux-gnu:${LD_LIBRARY_PATH:-}"
  firefox_version="$("$firefox_bin" --version 2>/dev/null | sed -nE 's/.*Firefox ([0-9]+([.][0-9]+)*).*/\1/p' | head -n 1)"
  if [[ -n "$firefox_version" ]]; then
    firefox_profile="$(choose_firefox_profile "/home/kali/.mozilla/firefox/droiddesk-firefox-155" "$firefox_version")"
  fi
  echo "[DroidDesk Firefox] Selected the verified Mozilla ARM64 installation."
else
  firefox_bin="$(command -v firefox-esr || command -v firefox || true)"
  export LD_LIBRARY_PATH="/usr/lib/firefox-esr:/usr/lib/aarch64-linux-gnu:${LD_LIBRARY_PATH:-}"
  if [[ -n "$firefox_bin" && -x "$firefox_bin" ]]; then
    firefox_version="$("$firefox_bin" --version 2>/dev/null | sed -nE 's/.*Firefox ([0-9]+([.][0-9]+)*).*/\1/p' | head -n 1)"
    if [[ -n "$firefox_version" ]]; then
      firefox_profile="$(choose_firefox_profile "/home/kali/.mozilla/firefox/droiddesk-firefox-esr" "$firefox_version")"
    fi
  fi
  echo "[DroidDesk Firefox] Firefox 160.0 is unavailable; using the separate Kali Firefox ESR profile."
fi
pulse_ready=0
if command -v pactl >/dev/null 2>&1; then
  for attempt in $(seq 1 15); do
    if pactl -s "$PULSE_SERVER" list short sinks 2>/dev/null |
       awk '$2 == "droiddesk_android" { found=1 } END { exit !found }'; then
      pulse_ready=1
      break
    fi
    sleep 1
  done
fi
if [[ "$pulse_ready" -eq 1 ]]; then
  pactl -s "$PULSE_SERVER" set-default-sink droiddesk_android >/dev/null 2>&1 || true
  pactl -s "$PULSE_SERVER" set-sink-mute droiddesk_android 0 >/dev/null 2>&1 || true
  echo "[DroidDesk Audio] Firefox connected to the Android AudioTrack sink."
else
  echo "[DroidDesk Audio] ERROR: droiddesk_android PulseAudio sink is unavailable; inspect /tmp/pulse/pulseaudio.log and DroidDesk runtime logs."
fi
if [[ -z "$firefox_bin" || ! -x "$firefox_bin" ]]; then
  zenity --error --title='Kali Linux' --text='Firefox is not installed.' 2>/dev/null || true
  exit 127
fi
firefox_log="/home/kali/.local/share/droiddesk/firefox-launch.log"
install -d -m 0755 "$(dirname "$firefox_log")"
{
  printf '%s\n' "=== Firefox launch $(date -Is) ==="
  printf '%s\n' "Selected browser: $firefox_bin"
  printf '%s\n' "PULSE_SERVER=$PULSE_SERVER"
  printf '%s\n' "LIBGL_ALWAYS_SOFTWARE=$LIBGL_ALWAYS_SOFTWARE"
  "$firefox_bin" --version 2>&1 || true
  pactl -s "$PULSE_SERVER" info 2>&1 || true
  pactl -s "$PULSE_SERVER" list short sinks 2>&1 || true
  ldconfig -p 2>/dev/null | grep -E "libavcodec|libavutil" || true
  if [[ -n "$firefox_profile" ]]; then
    printf 'Firefox profile: %s\n' "$firefox_profile"
  fi
} >> "$firefox_log" 2>&1
diagnostic_file="/home/kali/.local/share/droiddesk/firefox-media-diagnostic.txt"
rm -f /home/kali/Desktop/Firefox-Media-Diagnostic.txt
{
  echo "=== Firefox launch-time diagnostics $(date -Is) ==="
  echo "Selected Firefox binary: $firefox_bin"
  "$firefox_bin" --version 2>&1 || true
  echo "=== Kali Firefox ESR package fallback ==="
  dpkg-query -W firefox-esr ffmpeg 'libavcodec*' 2>&1 || true
  echo "=== active graphics environment ==="
  printf 'LIBGL_ALWAYS_SOFTWARE=%s\n' "$LIBGL_ALWAYS_SOFTWARE"
  echo "=== PulseAudio connection and Android sink ==="
  pactl -s "$PULSE_SERVER" info 2>&1 || true
  pactl -s "$PULSE_SERVER" list short sinks 2>&1 || true
  echo "=== software codec support ==="
  ffmpeg -hide_banner -decoders 2>&1 | grep -E ' (aac|h264|vp8|vp9|opus) ' || true
  echo "=== Firefox shared codec backend ==="
  find "$firefox_160_home" /usr/lib/firefox-esr /usr/lib/firefox -maxdepth 2 -name 'libmozavcodec.so' -type f -print 2>/dev/null || true
  if [[ -x "$firefox_160_home/firefox-bin" ]]; then
    echo "=== Official Firefox 160.0 shared-library resolution ==="
    LD_LIBRARY_PATH="$firefox_160_home:/usr/lib/firefox-esr:/usr/lib/aarch64-linux-gnu" ldd "$firefox_160_home/firefox-bin" 2>&1 | grep -E 'not found|libavcodec|libavutil' || true
    if [[ -s "$firefox_160_home/libmozavcodec.so" ]]; then
      LD_LIBRARY_PATH="$firefox_160_home:/usr/lib/firefox-esr:/usr/lib/aarch64-linux-gnu" ldd "$firefox_160_home/libmozavcodec.so" 2>&1 | grep -E 'not found|libavcodec|libavutil' || true
    fi
  elif [[ -s /usr/lib/firefox-esr/libmozavcodec.so ]]; then
    echo "=== Firefox ESR FFmpeg backend dependency resolution ==="
    LD_LIBRARY_PATH="/usr/lib/firefox-esr" ldd /usr/lib/firefox-esr/libmozavcodec.so 2>&1 || true
  fi
  echo "=== active DroidDesk Firefox preferences ==="
  grep -hE 'layers\.acceleration|gfx\.webrender|gfx\.canvas|gfx\.x11-egl|media\.(rdd-process|rdd-ffmpeg|ffvpx|ffmpeg|mediasource|webm)' \
    "$firefox_160_home/defaults/pref/droiddesk-media.js" \
    /usr/lib/firefox-esr/defaults/pref/droiddesk-media.js \
    "$firefox_profile/user.js" \
    /home/kali/.mozilla/firefox/*/user.js 2>/dev/null || true
} > "$diagnostic_file" 2>&1
chmod 0644 "$diagnostic_file" 2>/dev/null || true

echo "[DroidDesk Audio] Firefox launch diagnostics: $firefox_log"
echo "[DroidDesk Media] Fresh media diagnostics: $diagnostic_file"
if [[ -n "$firefox_profile" ]]; then
  exec "$firefox_bin" --profile "$firefox_profile" "$@" >> "$firefox_log" 2>&1
fi
exec "$firefox_bin" "$@" >> "$firefox_log" 2>&1
FIREFOX
chmod 0755 /home/kali/.local/bin/droiddesk-firefox

if [[ -x /usr/bin/firefox-esr || -x /usr/bin/firefox ]]; then
  cat > /home/kali/.local/share/applications/droiddesk-firefox.desktop <<'FIREFOX_DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=Firefox
Comment=Browse the web with Firefox
Exec=/home/kali/.local/bin/droiddesk-firefox %U
TryExec=/home/kali/.local/bin/droiddesk-firefox
Icon=firefox-esr
Terminal=false
StartupNotify=true
Categories=Network;WebBrowser;
FIREFOX_DESKTOP
  chmod 0644 /home/kali/.local/share/applications/droiddesk-firefox.desktop
fi

# Route stock package-provided launchers through the same audio/codec wrapper.
# Menu launchers otherwise bypass the environment used by the desktop shortcut.
for launcher in /usr/share/applications/*firefox*.desktop /usr/local/share/applications/*firefox*.desktop; do
  [[ -f "$launcher" ]] || continue
  grep -Eiq '^Exec=.*(firefox-esr|firefox)([[:space:]]|$)' "$launcher" || continue
  sed -i -E '/^Exec=/s|=.*|=/home/kali/.local/bin/droiddesk-firefox %u|' "$launcher"
  if grep -q '^TryExec=' "$launcher"; then
    sed -i 's|^TryExec=.*|TryExec=/home/kali/.local/bin/droiddesk-firefox|' "$launcher"
  fi
  echo "[DroidDesk Firefox] Routed XFCE launcher through the media wrapper: $launcher"
done
update-desktop-database /usr/share/applications 2>/dev/null || true

# Keep Firefox's isolated RDD media process enabled. Disabling RDD can remove the normal
# external decoder path for H.264/AAC on some Firefox ESR builds. Hardware/VA-API stays off.
for firefox_root in /usr/lib/firefox-esr /usr/lib/firefox /opt/droiddesk-firefox-160.0; do
  pref_dir="$firefox_root/defaults/pref"
  if [[ -d "$pref_dir" ]]; then
    cat > "$pref_dir/droiddesk-media.js" <<'FIREFOX_PREFS'
pref("media.cubeb.sandbox", false);
pref("media.cubeb.backend", "pulse");
pref("layers.acceleration.disabled", true);
pref("gfx.webrender.software", true);
pref("gfx.canvas.accelerated", false);
pref("gfx.x11-egl.force-disabled", true);
pref("media.ffmpeg.enabled", true);
pref("media.mp4.enabled", true);
pref("media.ogg.enabled", true);
pref("media.opus.enabled", true);
pref("media.wave.enabled", true);
pref("media.rdd-ffmpeg.enabled", true);
pref("media.ffvpx.enabled", true);
pref("media.rdd-process.enabled", true);
pref("media.av1.enabled", false);
pref("media.ffmpeg.vaapi.enabled", false);
pref("media.hardware-video-decoding.enabled", false);
pref("media.mediasource.enabled", true);
pref("media.mediasource.webm.enabled", true);
pref("media.mediasource.webm.audio.enabled", true);
pref("media.mediasource.webm.video.enabled", true);
pref("media.mediasource.vp9.enabled", true);
pref("media.mediasource.mp4.enabled", true);
pref("media.mediasource.mp4.audio.enabled", true);
pref("media.mediasource.mp4.video.enabled", true);
pref("media.webm.enabled", true);
FIREFOX_PREFS
    chmod 0644 "$pref_dir/droiddesk-media.js"
  fi
done
if [[ -d /home/kali/.mozilla/firefox ]]; then
  while IFS= read -r -d '' profile_dir; do
    user_prefs="$profile_dir/user.js"
    touch "$user_prefs"
    while IFS= read -r pref_line; do
      pref_name="$(printf '%s' "$pref_line" | cut -d'"' -f2)"
      if grep -q "user_pref(\"$pref_name\"" "$user_prefs"; then
        sed -i "/^user_pref(\"$pref_name\"/d" "$user_prefs"
      fi
      printf '%s\n' "$pref_line" >> "$user_prefs"
    done <<'FIREFOX_USER_PREFS'
user_pref("media.cubeb.sandbox", false);
user_pref("media.cubeb.backend", "pulse");
user_pref("layers.acceleration.disabled", true);
user_pref("gfx.webrender.software", true);
user_pref("gfx.canvas.accelerated", false);
user_pref("gfx.x11-egl.force-disabled", true);
user_pref("media.ffmpeg.enabled", true);
user_pref("media.mp4.enabled", true);
user_pref("media.ogg.enabled", true);
user_pref("media.opus.enabled", true);
user_pref("media.wave.enabled", true);
user_pref("media.rdd-ffmpeg.enabled", true);
user_pref("media.ffvpx.enabled", true);
user_pref("media.av1.enabled", false);
user_pref("media.ffmpeg.vaapi.enabled", false);
user_pref("media.hardware-video-decoding.enabled", false);
user_pref("media.rdd-process.enabled", true);
user_pref("media.mediasource.enabled", true);
user_pref("media.mediasource.webm.enabled", true);
user_pref("media.mediasource.webm.audio.enabled", true);
user_pref("media.mediasource.webm.video.enabled", true);
user_pref("media.mediasource.vp9.enabled", true);
user_pref("media.mediasource.mp4.enabled", true);
user_pref("media.mediasource.mp4.audio.enabled", true);
user_pref("media.mediasource.mp4.video.enabled", true);
user_pref("media.webm.enabled", true);
FIREFOX_USER_PREFS
  done < <(find /home/kali/.mozilla/firefox -mindepth 1 -maxdepth 1 -type d -print0)
fi

# Keep diagnostics hidden in application data; remove the former Desktop report.
rm -f /home/kali/Desktop/Firefox-Media-Diagnostic.txt
# Record the actual Firefox/FFmpeg package and codec state for future diagnosis.
diagnostic_dir="/home/kali/.local/share/droiddesk"
install -d -m 0755 "$diagnostic_dir"
diagnostic_file="$diagnostic_dir/firefox-media-diagnostic.txt"
{
  echo "=== Firefox ==="
  firefox-esr --version 2>&1 || firefox --version 2>&1 || true
  dpkg-query -W firefox-esr ffmpeg 2>/dev/null || true
  echo "=== Firefox and codec package candidates ==="
  apt-cache policy firefox-esr libavcodec-extra62 libavcodec-extra63 2>/dev/null || true
  echo "=== Installed FFmpeg codec ABI alternatives ==="
  dpkg-query -W -f='${Status} ${Architecture}\\n' 'libavcodec-extra*' 2>/dev/null || true
  echo "=== FFmpeg libraries ==="
  dpkg-query -W 'libavcodec*' 2>/dev/null || true
  ldconfig -p 2>/dev/null | grep 'libavcodec.so' || true
  echo "=== Required software decoders ==="
  ffmpeg -hide_banner -decoders 2>/dev/null | grep -E ' (aac|h264|vp8|vp9|opus) ' || true
  echo "=== Firefox codec backend ==="
  find /opt/droiddesk-firefox-160.0 /usr/lib/firefox-esr /usr/lib/firefox -maxdepth 2 -name 'libmozavcodec.so' -type f -print 2>/dev/null || true
  echo "=== Firefox shared-library resolution ==="
  if [[ -f /usr/lib/firefox-esr/libxul.so ]]; then
    for library in libmozsandbox.so libgkcodecs.so liblgpllibs.so libmozsqlite3.so libmozgtk.so libmozwayland.so libmozavcodec.so; do
      if [[ -s "/usr/lib/firefox-esr/$library" ]]; then echo "PRESENT /usr/lib/firefox-esr/$library"; else echo "MISSING /usr/lib/firefox-esr/$library"; fi
    done
    LD_LIBRARY_PATH="/usr/lib/firefox-esr:${LD_LIBRARY_PATH:-}" ldd /usr/lib/firefox-esr/libxul.so 2>&1 | grep -E 'not found|libavcodec|libavutil' || true
    if [[ -s /usr/lib/firefox-esr/libmozavcodec.so ]]; then
      echo "=== Firefox FFmpeg backend dependencies ==="
      LD_LIBRARY_PATH="/usr/lib/firefox-esr:${LD_LIBRARY_PATH:-}" ldd /usr/lib/firefox-esr/libmozavcodec.so 2>&1 | grep -E 'not found|libavcodec|libavutil' || true
    fi
  fi
  echo "=== Active DroidDesk media preferences ==="
  grep -hE 'media\.(rdd-process|rdd-ffmpeg|ffvpx|ffmpeg|mediasource|webm|av1)' \
    /usr/lib/firefox-esr/defaults/pref/droiddesk-media.js \
    /home/kali/.mozilla/firefox/*/user.js 2>/dev/null || true
  echo "=== PulseAudio bridge ==="
  pactl info 2>&1 || true
  pactl list short sinks 2>&1 || true
  echo "=== Firefox ESR codec recommendations ==="
  apt-cache depends firefox-esr 2>&1 | grep -E 'Recommends:|^[[:space:]]+or ' | grep -E 'libavcodec|pulseaudio' || true
} > "$diagnostic_file"
chmod 0644 "$diagnostic_file"

# Build one flat XFCE gallery from every official Kali wallpaper package.
WALLPAPER_PACKAGES=(
  kali-wallpapers-2026 kali-wallpapers-2025 kali-wallpapers-2024
  kali-wallpapers-2023 kali-wallpapers-2022 kali-wallpapers-2020.4
  kali-wallpapers-2019.4 kali-wallpapers-legacy
)
install -d -m 0755 "$WALLPAPER_DIR"
# Remove links produced by the previous migration, then re-index every installed release.
find "$WALLPAPER_DIR" -maxdepth 1 -type l -delete
for package in "${WALLPAPER_PACKAGES[@]}"; do
  while IFS= read -r image_file; do
    [[ -f "$image_file" ]] || continue
    image_lower="$(printf '%s' "$image_file" | tr '[:upper:]' '[:lower:]')"
    case "$image_lower" in *.jpg|*.jpeg|*.png|*.webp|*.svg) ;; *) continue ;; esac
    image_name="$(basename "$image_file")"
    target="$WALLPAPER_DIR/$image_name"
    if [[ -e "$target" || -L "$target" ]]; then
      target="$WALLPAPER_DIR/$package-$image_name"
    fi
    [[ -e "$target" || -L "$target" ]] && continue
    ln -sfn "$image_file" "$target"
  done < <(dpkg-query -L "$package" 2>/dev/null || true)
done
wallpaper_count="$(find "$WALLPAPER_DIR" -maxdepth 1 \( -type f -o -type l \) | wc -l)"
echo "[Kali Linux] Official wallpaper choices exposed to XFCE: $wallpaper_count"

default_wallpaper="$WALLPAPER_DIR/kali-cubes2-16x9.jpg"
if [[ ! -f "$default_wallpaper" ]]; then
  default_wallpaper="$(find "$WALLPAPER_DIR" -maxdepth 1 \( -type f -o -type l \) -iname '*cubes2*' -print -quit || true)"
fi
if [[ -z "$default_wallpaper" ]]; then
  default_wallpaper="$(find "$WALLPAPER_DIR" -maxdepth 1 \( -type f -o -type l \) -print -quit || true)"
fi
if [[ -z "$default_wallpaper" || ! -f "$default_wallpaper" ]]; then
  echo "[Kali Linux] No official Kali wallpaper files were found after package installation."
  exit 21
fi

# Preserve existing XFCE preferences. Only add gallery/default keys when absent;
# never replace a user's selected wallpaper, panel style, compositing choice or icon layout.
desktop_xml="/home/kali/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml"
if [[ -f "$desktop_xml" ]]; then
  if ! grep -q 'name="image-path"' "$desktop_xml"; then
    sed -i '\#<property name="monitorbuiltin" type="empty">#a\                <property name="image-path" type="string" value="/usr/share/xfce4/backdrops/Kali Linux"/>' "$desktop_xml"
  fi
  if ! grep -q 'name="last-image"' "$desktop_xml"; then
    sed -i "/name=\"image-style\"/a\                  <property name=\"last-image\" type=\"string\" value=\"$default_wallpaper\"/>" "$desktop_xml"
  fi
  if ! grep -q 'name="show-trash"' "$desktop_xml"; then
    sed -i '/<property name="show-filesystem"/a\              <property name="show-trash" type="bool" value="true"/>' "$desktop_xml"
  fi
fi

# Add a transparent dock default only on first setup; don't overwrite user panel choices.
panel_xml="/home/kali/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml"
if [[ -f "$panel_xml" ]] &&
   ! grep -q 'name="background-style"' "$panel_xml"; then
  sed -i '/<property name="panel-2" type="empty">/a\              <property name="background-style" type="uint" value="1"/>' "$panel_xml"
fi

# Add compositing only if XFCE has no saved choice yet.
xfwm_xml="/home/kali/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml"
if [[ -f "$xfwm_xml" ]] &&
   ! grep -q 'name="use_compositing"' "$xfwm_xml"; then
  sed -i '/<property name="general" type="empty">/a\            <property name="use_compositing" type="bool" value="true"/>' "$xfwm_xml"
fi

# XFCE stores desktop icon coordinates in icons.screen*.rc; this migration never writes those files.

# Keep only XFCE's native Trash desktop icon; remove the duplicate custom shortcut.
rm -f /home/kali/Desktop/Trash.desktop

# Open downloaded .deb files in the installer; AppImages get menu + desktop launchers.
install -d -m 0755 /home/kali/.local/bin /home/kali/.local/share/applications
cat > /home/kali/.local/bin/droiddesk-install-download <<'INSTALLER'
#!/usr/bin/env bash
set -Eeuo pipefail
export HOME=/home/kali
export DEBIAN_FRONTEND=noninteractive
file="$1"
[[ -f "$file" ]] || exit 1
file="$(readlink -f "$file")"
lower_file="$(printf '%s' "$file" | tr '[:upper:]' '[:lower:]')"
log_dir="$HOME/.local/share/droiddesk"
install -d -m 0755 "$log_dir"
log_file="$log_dir/package-install.log"
exec >> >(tee -a "$log_file") 2>&1

show_failure() {
  message="$1"
  zenity --error --title='Installation failed' --width=480 --text="$message
Full details were saved to:
$log_file" 2>/dev/null || true
  diagnostic_file="$(mktemp)"
  tail -n 45 "$log_file" > "$diagnostic_file" 2>/dev/null || true
  zenity --text-info --title='Installer diagnostics' --filename="$diagnostic_file" --width=760 --height=520 2>/dev/null || true
  rm -f "$diagnostic_file"
}

case "$lower_file" in
  *.deb)
    if ! command -v dpkg-deb >/dev/null 2>&1; then
      show_failure "The Debian package tools are missing."
      exit 2
    fi
    deb_arch="$(dpkg-deb -f "$file" Architecture 2>/dev/null || true)"
    deb_name="$(dpkg-deb -f "$file" Package 2>/dev/null || true)"
    host_arch="$(dpkg --print-architecture 2>/dev/null || true)"
    if [[ -z "$deb_arch" || -z "$deb_name" ]]; then
      show_failure "This file is not a valid Debian package."
      exit 2
    fi
    if [[ "$deb_arch" != "all" && "$deb_arch" != "$host_arch" ]]; then
      echo "[DroidDesk Installer] The selected DEB is $deb_arch; Kali is $host_arch."
      echo "[DroidDesk Installer] Looking for the same application in Kali's ARM64 repositories."
      if sudo -n apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Dpkg::Use-Pty=0 update; then
        candidate_arch="$(apt-cache show "$deb_name" 2>/dev/null |
          awk '/^Architecture:/ {
            if ($2 == "arm64") native=1
            else if ($2 == "all") common=1
          } END {
            if (native) print "arm64"
            else if (common) print "all"
          }')"
        if [[ "$candidate_arch" == "arm64" || "$candidate_arch" == "all" ]]; then
          package_spec="$deb_name"
          if [[ "$candidate_arch" == "arm64" ]]; then package_spec="$deb_name:arm64"; fi
          echo "[DroidDesk Installer] Installing compatible repository package $package_spec."
          if sudo -n apt-get -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --no-install-recommends install -y "$package_spec"; then
            package_status="$(dpkg-query -W -f='${Status}' "$deb_name" 2>/dev/null || true)"
            if [[ "$package_status" == "install ok installed" ]]; then
              sudo -n update-desktop-database /usr/share/applications 2>/dev/null || true
              zenity --info --title='Kali Linux' --text="Installed the ARM64 repository build of $deb_name." 2>/dev/null || true
              exit 0
            fi
          fi
        fi
      fi
      related_candidates="$(apt-cache search --names-only "$deb_name" 2>/dev/null | head -n 8 || true)"
      echo "[DroidDesk Installer] Related Kali ARM64 repository matches:"
      printf '%s\n' "$related_candidates"
      show_failure "Wrong package architecture.
Package: $deb_arch
Kali system: $host_arch
This file is an AMD64 build and cannot execute as an ARM64 Linux program.
I checked Kali's repositories for an ARM64 build of $deb_name, but none was available under that package name.
Related repository matches are listed in package-install.log.
Open Add applications > Browse or Featured for compatible ARM64 alternatives.
If the publisher does not provide an ARM64 build, this exact download cannot be installed on this phone."
      exit 3
    fi

    echo "[DroidDesk Installer] Installing $deb_name ($deb_arch) from $file"
    echo "[DroidDesk Installer] Package control metadata:"
    dpkg-deb -f "$file" Package Version Architecture Depends 2>&1 || true
    echo "[DroidDesk Installer] Simulating dependency resolution before installation:"
    sudo -n apt-get -s install "$file" 2>&1 || true
    # Refresh package indexes, then let APT resolve the local package and
    # all dependencies from the configured Kali repositories.
    if ! sudo -n apt-get -o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Dpkg::Use-Pty=0 update; then
      echo "[DroidDesk Installer] APT update failed; attempting installation with cached indexes."
    fi

    install_ok=0
    if ! sudo -n dpkg --configure -a; then
      echo "[DroidDesk Installer] Repairing the previously interrupted dpkg transaction before this package."
      sudo -n apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --fix-broken install -y --no-install-recommends
      sudo -n dpkg --configure -a
    fi
    if sudo -n apt-get -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --no-install-recommends install -y "$file"; then
      install_ok=1
    else
      echo "[DroidDesk Installer] APT local-file install failed; trying dpkg followed by dependency repair."
      sudo -n dpkg --configure -a || true
      sudo -n dpkg -i "$file" || true
      if sudo -n apt-get -o Acquire::Retries=3 -o Dpkg::Use-Pty=0 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --fix-broken install -y --no-install-recommends; then
        install_ok=1
      fi
    fi

    package_status="$(dpkg-query -W -f='${Status}' "$deb_name" 2>/dev/null || true)"
    if [[ "$install_ok" -eq 1 && "$package_status" == "install ok installed" ]]; then
      sudo -n update-desktop-database /usr/share/applications 2>/dev/null || true
      sudo -n update-mime-database /usr/share/mime 2>/dev/null || true
      zenity --info --title='Kali Linux' --text="Installed $deb_name successfully. Find it in the Applications menu." 2>/dev/null || true
    else
      show_failure "Could not finish installing $deb_name.
Architecture: $deb_arch
System architecture: $host_arch
APT could not resolve or configure all dependencies."
      exit 1
    fi
    ;;
  *.appimage)
    app_arch="$(file -b "$file" 2>/dev/null || true)"
    echo "[DroidDesk Installer] AppImage file type: $app_arch"
    if ! printf '%s' "$app_arch" | grep -Eqi 'ARM aarch64|ARM64|AArch64'; then
      show_failure "This AppImage is not identified as ARM64/AArch64.
Detected file type: $app_arch
This Kali environment is ARM64. Download the ARM64 AppImage, not x86_64/AMD64."
      exit 3
    fi
    install -d "$HOME/Applications" "$HOME/.local/share/applications"
    safe_file="$(basename "$file" | tr -cs '[:alnum:]._-' '_')"
    target="$HOME/Applications/$safe_file"
    cp -f "$file" "$target"
    chmod 0755 "$target"
    app_name="$(basename "$safe_file" .AppImage)"
    desktop="$HOME/.local/share/applications/$app_name.desktop"
    cat > "$desktop" <<ENTRY
[Desktop Entry]
Version=1.0
Type=Application
Name=$app_name
Exec=env APPIMAGE_EXTRACT_AND_RUN=1 "$target"
Icon=application-x-executable
Terminal=false
Categories=Utility;
ENTRY
    chmod 0755 "$desktop"
    zenity --info --title='Kali Linux' --text="Added $app_name to Applications. Use its options button to add a Desktop shortcut." 2>/dev/null || true
    ;;
  *)
    zenity --error --title='Unsupported file' --text='Choose a .deb or .AppImage file.' 2>/dev/null || true
    exit 2
    ;;
esac
INSTALLER
chmod 0755 /home/kali/.local/bin/droiddesk-install-download

cat > /home/kali/.local/share/applications/droiddesk-install-download.desktop <<'INSTALLER_DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=Install downloaded Linux app
Comment=Install a Debian package or add an AppImage to Desktop
Exec=/home/kali/.local/bin/droiddesk-install-download %f
Icon=system-software-install
Terminal=false
MimeType=application/vnd.debian.binary-package;application/x-deb;application/x-iso9660-appimage;
INSTALLER_DESKTOP
chmod 0644 /home/kali/.local/share/applications/droiddesk-install-download.desktop

mime_file="/home/kali/.config/mimeapps.list"
install -d -m 0755 /home/kali/.config
touch "$mime_file"
if ! grep -q '^\[Default Applications\]' "$mime_file"; then printf '\n[Default Applications]\n' >> "$mime_file"; fi
for mime in application/vnd.debian.binary-package application/x-deb application/x-iso9660-appimage; do
  if grep -q "^$mime=" "$mime_file"; then
    sed -i "s#^$mime=.*#$mime=droiddesk-install-download.desktop;#" "$mime_file"
  else
    sed -i "/^\[Default Applications\]/a $mime=droiddesk-install-download.desktop;" "$mime_file"
  fi
done

# Prevent expensive thumbnail decoding on the phone.
thunar_config="/home/kali/.config/Thunar/thunarrc"
install -d -m 0755 "$(dirname "$thunar_config")"
if [[ ! -f "$thunar_config" ]]; then
  printf '[Configuration]\nMiscShowThumbnails=NEVER\n' > "$thunar_config"
elif grep -q '^MiscShowThumbnails=' "$thunar_config"; then
  sed -i 's/^MiscShowThumbnails=.*/MiscShowThumbnails=NEVER/' "$thunar_config"
elif grep -q '^\[Configuration\]' "$thunar_config"; then
  sed -i '/^\[Configuration\]/a MiscShowThumbnails=NEVER' "$thunar_config"
else
  printf '\n[Configuration]\nMiscShowThumbnails=NEVER\n' >> "$thunar_config"
fi

if [[ ! -e /home/kali/.asoundrc ]]; then
  cat > /home/kali/.asoundrc <<'ALSA'
pcm.!default {
  type pulse
}
ctl.!default {
  type pulse
}
ALSA
fi

# Synchronize desktop shortcuts for applications installed before the App Store
# learned to create launchers. This is intentionally limited to packages visible
# in DroidDesk's Kali catalog; it does not create icons for every dependency.
echo "[DroidDesk App Store] Synchronizing desktop shortcuts for previously installed packages..."
STORE_PACKAGES=(
  firefox-esr chromium code-oss libreoffice gimp blender vlc mpv thunderbird
  filezilla geany inkscape audacity evince htop btop fastfetch git python3
  python3-pip nodejs imagemagick tmux zsh fish p7zip-full file-roller kali-tweaks
)
STORE_TRACKED="/home/kali/.local/share/droiddesk/app-store-packages"
DISABLED_SHORTCUTS="/home/kali/.local/share/droiddesk/desktop-shortcuts-disabled"
DESKTOP_DIR="/home/kali/Desktop"
install -d -m 0755 "$(dirname "$STORE_TRACKED")" "$DESKTOP_DIR"
touch "$STORE_TRACKED" "$DISABLED_SHORTCUTS"
for package in "${STORE_PACKAGES[@]}"; do
  if ! package_installed "$package"; then continue; fi
  if ! grep -Fqx "$package" "$STORE_TRACKED"; then
    printf '%s\\n' "$package" >> "$STORE_TRACKED"
  fi

  # Firefox already has a dedicated DroidDesk audio/codec wrapper and launcher.
  [[ "$package" == "firefox-esr" ]] && continue
  while IFS= read -r desktop_file; do
    [[ -f "$desktop_file" ]] || continue
    if grep -Eiq '^(NoDisplay|Hidden)=true' "$desktop_file"; then continue; fi
    desktop_name="$(basename "$desktop_file")"
    if grep -Fqx "$desktop_name" "$DISABLED_SHORTCUTS"; then
      echo "[DroidDesk App Store] Respecting the user's Remove from Desktop choice for $desktop_name"
      continue
    fi
    managed_shortcut="$DESKTOP_DIR/store-$package-$desktop_name"
    [[ -e "$managed_shortcut" ]] && continue
    temporary_shortcut="$managed_shortcut.tmp"
    {
      printf '# DroidDesk App Store package: %s\\n' "$package"
      printf '# DroidDesk shortcut source: %s\\n' "$desktop_name"
      cat "$desktop_file"
    } > "$temporary_shortcut"
    chmod 0755 "$temporary_shortcut"
    chown kali:kali "$temporary_shortcut"
    mv -f "$temporary_shortcut" "$managed_shortcut"
    echo "[DroidDesk App Store] Restored desktop launcher for $package: $managed_shortcut"
  done < <(
    dpkg-query -L "$package" 2>/dev/null |
      grep -E '^/(usr/share|usr/local/share)/applications/[^/]+\\.desktop$' || true
  )
done
chown kali:kali "$STORE_TRACKED" "$DISABLED_SHORTCUTS" "$(dirname "$STORE_TRACKED")" 2>/dev/null || true
if [[ -s /home/kali/.local/share/droiddesk/dbus-session-address ]] && pgrep -x xfdesktop >/dev/null 2>&1; then
  session_address="$(cat /home/kali/.local/share/droiddesk/dbus-session-address)"
  sudo -E -u kali env DISPLAY=:0 XDG_RUNTIME_DIR=/tmp/runtime-kali \\
    DBUS_SESSION_BUS_ADDRESS="$session_address" xfdesktop --reload >/dev/null 2>&1 || true
fi

# A compact four-dot Kali-style launcher icon keeps the bottom panel uncluttered.
MENU_ICON_DIR="/usr/share/icons/hicolor/scalable/apps"
install -d -m 0755 "$MENU_ICON_DIR"
cat > "$MENU_ICON_DIR/droiddesk-kali-menu.svg" <<'KALI_MENU_ICON'
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
  <g fill="#ffffff">
    <circle cx="7" cy="7" r="3.1"/>
    <circle cx="17" cy="7" r="3.1"/>
    <circle cx="7" cy="17" r="3.1"/>
    <circle cx="17" cy="17" r="3.1"/>
  </g>
</svg>
KALI_MENU_ICON
chmod 0644 "$MENU_ICON_DIR/droiddesk-kali-menu.svg"
gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true

# Install the XFCE Applications menu on the bottom panel.
# It reads system .desktop entries, so apps appear even without a desktop shortcut.
# The applicationsmenu plugin is built into the XFCE panel used by this profile.
PANEL_MENU_CONFIGURED=0
SESSION_ADDRESS_FILE="/home/kali/.local/share/droiddesk/dbus-session-address"
if [[ -s "$SESSION_ADDRESS_FILE" ]] && command -v xfconf-query >/dev/null 2>&1; then
  SESSION_ADDRESS="$(cat "$SESSION_ADDRESS_FILE")"
  if sudo -E -u kali env DISPLAY=:0 XDG_RUNTIME_DIR=/tmp/runtime-kali \
      DBUS_SESSION_BUS_ADDRESS="$SESSION_ADDRESS" /bin/bash -s <<'PANEL_MENU'
set -Eeuo pipefail
channel="xfce4-panel"
panel_path="/panels/panel-2/plugin-ids"
mapfile -t current_ids < <(
  xfconf-query -c "$channel" -p "$panel_path" 2>/dev/null |
    sed -nE 's/^[[:space:]]*(int[[:space:]]+)?([0-9]+)[[:space:]]*$/\2/p'
)
if [[ ${#current_ids[@]} -eq 0 ]]; then
  echo "[DroidDesk Panel] Bottom panel 2 has no plugin list; refusing to replace the user's panel."
  exit 2
fi

menu_id=""
for current_id in "${current_ids[@]}"; do
  plugin_type="$(xfconf-query -c "$channel" -p "/plugins/plugin-$current_id" 2>/dev/null || true)"
  if [[ "$plugin_type" == *applicationsmenu* ]]; then
    menu_id="$current_id"
    break
  fi
done

if [[ -z "$menu_id" ]]; then
  for candidate in $(seq 25 250); do
    if ! xfconf-query -c "$channel" -p "/plugins/plugin-$candidate" >/dev/null 2>&1; then
      menu_id="$candidate"
      break
    fi
  done
  [[ -n "$menu_id" ]] || {
    echo "[DroidDesk Panel] No free XFCE panel plugin ID is available."
    exit 3
  }

  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id" \
    --create --type string --set applicationsmenu
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/show-button-title" \
    --create --type bool --set false
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/button-title" \
    --create --type string --set Applications
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/button-icon" \
    --create --type string --set droiddesk-kali-menu
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/show-menu-icons" \
    --create --type bool --set true

  panel_ids=("$menu_id" "${current_ids[@]}")
  args=(xfconf-query -c "$channel" -p "$panel_path" --force-array)
  for id in "${panel_ids[@]}"; do
    args+=(--type int --set "$id")
  done
  "${args[@]}"
else
  echo "[DroidDesk Panel] Applications menu already exists on the bottom panel (plugin $menu_id)."
fi

# Apply icon and compact-button settings even when an Applications plugin already existed.
if [[ -n "$menu_id" ]]; then
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/show-button-title" \
    --create --type bool --set false
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/button-title" \
    --create --type string --set Applications
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/button-icon" \
    --create --type string --set droiddesk-kali-menu
  xfconf-query -c "$channel" -p "/plugins/plugin-$menu_id/show-menu-icons" \
    --create --type bool --set true
fi

xfce4-panel --restart
xfconf-query -c "$channel" -p "$panel_path" >/dev/null
echo "[DroidDesk Panel] Four-dot Applications menu is active on bottom panel 2."
PANEL_MENU
  then
    PANEL_MENU_CONFIGURED=1
  else
    echo "[DroidDesk Panel] WARNING: the Applications menu could not be activated in this session."
  fi
else
  echo "[DroidDesk Panel] WARNING: XFCE D-Bus session is not available; menu setup will be retried."
fi

# Snapshot the now-updated panel XML before the EXIT trap restores the user's
# original XFCE snapshot. Only this file carries the additive Applications plugin.
if [[ "$PANEL_MENU_CONFIGURED" -eq 1 ]]; then
  PANEL_CONFIG_PATH="/home/kali/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml"
  PANEL_MENU_SNAPSHOT="$PRESERVE_DIR/panel-with-applications-menu.xml"
  if [[ -s "$PANEL_CONFIG_PATH" ]]; then
    sleep 1
    cp -f "$PANEL_CONFIG_PATH" "$PANEL_MENU_SNAPSHOT"
    echo "[DroidDesk Panel] Saved the updated panel XML so the menu survives migration cleanup."
  else
    echo "[DroidDesk Panel] WARNING: panel XML was not written; the Applications menu may need another migration run."
  fi
fi

chown -R kali:kali /home/kali/.config /home/kali/.local /home/kali/Desktop /home/kali/Applications 2>/dev/null || true
chown kali:kali /home/kali/.asoundrc 2>/dev/null || true
touch "$FEATURE_MARKER"
chmod 0600 "$FEATURE_MARKER"
echo "[Kali Linux] Desktop experience migration completed."
