#!/usr/bin/env bash
set -Eeuo pipefail

export HOME=/root
export USER=root
export LOGNAME=root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8
export TERM=dumb
export DEBIAN_FRONTEND=noninteractive
export APT_LISTCHANGES_FRONTEND=none
export BASH_ENV=/dev/null

readonly APT_SOURCE_FILE="/etc/apt/sources.list.d/droiddesk-kali.sources"
readonly APT_SOURCE_BACKUP="/root/.droiddesk/apt-sources-original"

# Direct HTTPS endpoints operated by Kali. The mirror redirector is avoided
# because it may select mirrors whose package URLs use HTTP.
readonly KALI_PRIMARY="https://kali.download/kali/"
readonly KALI_FALLBACK="https://archive.kali.org/kali/"

APT_OPTIONS=(
  -o Acquire::ForceIPv4=true
  -o Acquire::Retries=3
  -o Acquire::http::Timeout=20
  -o Acquire::https::Timeout=60
  -o Acquire::https::Verify-Peer=true
  -o Acquire::https::Verify-Host=true
  -o Acquire::AllowDowngradeToInsecureRepositories=false
  -o Acquire::Queue-Mode=access
  -o APT::Keep-Downloaded-Packages=true
  -o Dpkg::Use-Pty=0
  -o Dpkg::Options::=--force-confdef
  -o Dpkg::Options::=--force-confold
)

APT_PACKAGES=(
  kali-linux-default
  xfce4
  xfce4-terminal
  dbus-daemon
  dbus-x11
  bubblewrap
  shared-mime-info
  libgdk-pixbuf2.0-bin
  hicolor-icon-theme
  librsvg2-common
  glycin-loaders
  libglycin-2-0
  glycin-thumbnailers
  xclip
  x11-utils
  fonts-noto-core
  adwaita-icon-theme
  thunar
  exo-utils
  gvfs
  gvfs-backends
  kali-wallpapers-2026
  pulseaudio-utils
  libasound2-plugins
  procps
)

# CI-only validation mode. This does not modify /etc, /var, dpkg, or the network.
if [[ "${1:-}" == "--self-test" ]]; then
  [[ "$KALI_PRIMARY" == "https://kali.download/kali/" ]]
  [[ "$KALI_FALLBACK" == "https://archive.kali.org/kali/" ]]
  [[ "$KALI_PRIMARY" != "$KALI_FALLBACK" ]]
  [[ " ${APT_OPTIONS[*]} " == *"Acquire::ForceIPv4=true"* ]]
  [[ " ${APT_OPTIONS[*]} " == *"Acquire::https::Verify-Peer=true"* ]]
  [[ " ${APT_OPTIONS[*]} " == *"Acquire::https::Verify-Host=true"* ]]
  [[ " ${APT_OPTIONS[*]} " == *"APT::Keep-Downloaded-Packages=true"* ]]
  for package in bubblewrap dbus-daemon shared-mime-info libgdk-pixbuf2.0-bin hicolor-icon-theme librsvg2-common glycin-loaders libglycin-2-0 glycin-thumbnailers gvfs gvfs-backends kali-wallpapers-2026 pulseaudio-utils libasound2-plugins; do
    [[ " ${APT_PACKAGES[*]} " == *" ${package} "* ]]
  done
  printf '%s\n' "[DroidDesk Kali] APT setup configuration checks passed."
  exit 0
fi

install -d -m 0700 /root/.droiddesk
readonly LOG_FILE="/root/.droiddesk/kali-install.log"
exec > >(tee -a "$LOG_FILE") 2>&1

configure_proot_service_policy() {
  # A PRoot container has no system init; starting daemons during package setup
  # can stall maintainer scripts. Preserve any pre-existing site policy.
  install -d -m 0755 /usr/sbin
  if [[ ! -e /usr/sbin/policy-rc.d ]]; then
    cat > /usr/sbin/policy-rc.d <<'POLICY'
#!/bin/sh
exit 101
POLICY
    chmod 0755 /usr/sbin/policy-rc.d
  fi

  install -d -m 0755 /etc/apt/apt.conf.d
  cat > /etc/apt/apt.conf.d/99droiddesk-resume <<'APTCONF'
APT::Keep-Downloaded-Packages "true";
Acquire::Retries "3";
Dpkg::Use-Pty "0";
APTCONF
  echo "[DroidDesk Kali] PRoot service-start policy and persistent APT archive cache are enabled."
}

run_with_package_heartbeat() {
  local label="$1"
  shift

  "$@" &
  local command_pid=$!
  (
    while kill -0 "$command_pid" 2>/dev/null; do
      sleep 20
      if kill -0 "$command_pid" 2>/dev/null; then
        local latest_event
        latest_event="$(tail -n 1 /var/log/dpkg.log 2>/dev/null || true)"
        if [[ -z "$latest_event" ]]; then
          latest_event="No dpkg event has been recorded yet"
        fi
        echo "[DroidDesk Kali] Still working: $label. Last dpkg activity: $latest_event"
      fi
    done
  ) &
  local heartbeat_pid=$!

  local command_status=0
  if wait "$command_pid"; then
    command_status=0
  else
    command_status=$?
  fi

  kill "$heartbeat_pid" 2>/dev/null || true
  wait "$heartbeat_pid" 2>/dev/null || true
  return "$command_status"
}

recover_optional_stalled_theme() {
  # Papirus is an optional visual add-on, not required by Kali/XFCE.
  # If an older attempt left it half-configured, remove that incomplete
  # optional package so it cannot block recovery of the actual desktop.
  local package_status
  package_status="$(dpkg-query -W -f='${Status}' papirus-icon-theme 2>/dev/null || true)"
  case "$package_status" in
    *unpacked|*half-configured|*half-installed)
      echo "[DroidDesk Kali] Removing the incomplete optional Papirus icon theme before resuming core packages."
      dpkg --remove --force-remove-reinstreq papirus-icon-theme
      ;;
    *)
      ;;
  esac
}

prepare_apt_directories() {
  install -d -m 0755 /var/cache/apt/archives
  install -d -m 0700 /var/cache/apt/archives/partial
  install -d -m 0755 /var/lib/apt/lists
  install -d -m 0700 /var/lib/apt/lists/partial
  install -d -m 1777 /tmp
  install -d -m 0755 /var/tmp

  # Keep completed package archives and repository indexes for resumable retries.
  find /var/cache/apt/archives/partial -mindepth 1 -maxdepth 1 -type f -delete
  find /var/lib/apt/lists/partial -mindepth 1 -maxdepth 1 -type f -delete
  local cached_archives
  cached_archives="$(find /var/cache/apt/archives -maxdepth 1 -type f -name '*.deb' | wc -l)"
  echo "[DroidDesk Kali] Completed package archives preserved in cache: $cached_archives"
}

configure_official_kali_source() {
  local repository_uri="$1"
  local source_backup_name

  case "$repository_uri" in
    "https://kali.download/kali/"|"https://archive.kali.org/kali/")
      ;;
    *)
      echo "[DroidDesk Kali] Refusing an unapproved or non-HTTPS repository: $repository_uri"
      return 1
      ;;
  esac

  if [[ ! -s /usr/share/keyrings/kali-archive-keyring.gpg ]]; then
    echo "[DroidDesk Kali] Kali archive signing key is missing; refusing an unsigned repository."
    return 1
  fi

  install -d -m 0755 /etc/apt/sources.list.d
  install -d -m 0700 "$APT_SOURCE_BACKUP"

  # Preserve original source files once, then use one controlled source file.
  shopt -s nullglob
  local source_files=(
    /etc/apt/sources.list
    /etc/apt/sources.list.d/*.list
    /etc/apt/sources.list.d/*.sources
  )

  for source_file in "${source_files[@]}"; do
    [[ -f "$source_file" ]] || continue
    [[ "$source_file" == "$APT_SOURCE_FILE" ]] && continue

    source_backup_name="$(basename "$source_file").original"
    if [[ ! -e "$APT_SOURCE_BACKUP/$source_backup_name" ]]; then
      cp -p "$source_file" "$APT_SOURCE_BACKUP/$source_backup_name"
    fi

    if [[ "$source_file" == "/etc/apt/sources.list" ]]; then
      : > "$source_file"
    else
      rm -f "$source_file"
    fi
  done

  cat > "$APT_SOURCE_FILE" <<EOF
Types: deb
URIs: $repository_uri
Suites: kali-rolling
Components: main contrib non-free non-free-firmware
Signed-By: /usr/share/keyrings/kali-archive-keyring.gpg
EOF

  if ! grep -Eq '^URIs: https://(kali\.download|archive\.kali\.org)/kali/$' "$APT_SOURCE_FILE"; then
    echo "[DroidDesk Kali] The configured source did not pass the HTTPS allow-list check."
    return 1
  fi
}

run_apt_transaction() {
  local repository_uri="$1"

  echo "[DroidDesk Kali] Using official HTTPS repository: $repository_uri"
  if ! configure_official_kali_source "$repository_uri"; then
    echo "[DroidDesk Kali] Could not configure the official repository."
    return 1
  fi

  if ! prepare_apt_directories; then
    echo "[DroidDesk Kali] Could not prepare APT cache directories."
    return 1
  fi

  echo "[DroidDesk Kali] Updating package indexes over HTTPS..."
  if ! apt-get "${APT_OPTIONS[@]}" update; then
    echo "[DroidDesk Kali] Repository index update failed: $repository_uri"
    return 1
  fi

  # Recover the optional icon theme separately from the core Kali/XFCE setup.
  if ! recover_optional_stalled_theme; then
    echo "[DroidDesk Kali] Could not clear the incomplete optional icon theme."
    return 1
  fi

  # Complete packages unpacked by an earlier interrupted attempt.
  if ! run_with_package_heartbeat "resuming dpkg configuration" dpkg --configure -a; then
    echo "[DroidDesk Kali] Repairing the interrupted dpkg transaction..."
    if ! run_with_package_heartbeat "repairing broken package dependencies" apt-get "${APT_OPTIONS[@]}" --fix-broken install -y; then
      echo "[DroidDesk Kali] The existing dpkg transaction could not be repaired."
      return 1
    fi
  fi

  if ! prepare_apt_directories; then
    echo "[DroidDesk Kali] Could not prepare APT cache directories."
    return 1
  fi

  echo "[DroidDesk Kali] Installing or resuming only missing Kali default tools and XFCE packages..."
  run_with_package_heartbeat "installing and configuring Kali packages" apt-get "${APT_OPTIONS[@]}" install -y "${APT_PACKAGES[@]}"
}

configure_proot_service_policy

echo "[DroidDesk Kali] Preparing APT cache and temporary directories..."
if ! prepare_apt_directories; then
  echo "[DroidDesk Kali] Could not prepare the APT cache."
  exit 1
fi


INSTALL_SUCCEEDED=0
for repository_uri in "$KALI_PRIMARY" "$KALI_FALLBACK"; do
  if run_apt_transaction "$repository_uri"; then
    INSTALL_SUCCEEDED=1
    break
  fi

  echo "[DroidDesk Kali] Repository attempt failed; preserving completed downloads and repairing dpkg state..."
  recover_optional_stalled_theme || true
  run_with_package_heartbeat "recovering dpkg after repository failure" dpkg --configure -a || true
  prepare_apt_directories || true
done

if [[ "$INSTALL_SUCCEEDED" -ne 1 ]]; then
  echo "[DroidDesk Kali] Installation failed through both official HTTPS repositories."
  echo "[DroidDesk Kali] Check that the phone can reach HTTPS on port 443, then reopen Install Kali Linux."
  exit 100
fi

echo "[DroidDesk Kali] Verifying the requested package set..."
dpkg-query -W -f='${Status}' kali-linux-default | grep -qx 'install ok installed'
dpkg-query -W -f='${Status}' thunar | grep -qx 'install ok installed'
test -x /usr/bin/thunar
test -x /usr/bin/exo-open
test -x /usr/bin/startxfce4
test -x /usr/bin/dbus-launch
test -x /usr/bin/xfce4-session

echo "[DroidDesk Kali] Package installation and verification completed."
