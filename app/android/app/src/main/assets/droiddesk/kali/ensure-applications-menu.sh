#!/usr/bin/env bash
set -Eeuo pipefail
export HOME=/root USER=root LOGNAME=root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LANG=C.UTF-8 BASH_ENV=/dev/null

MARKER=/root/.droiddesk/droiddesk-apps-menu-v2
APP_SOURCE=/root/.droiddesk/applications-menu.py
APP_TARGET=/usr/local/bin/droiddesk-applications-menu
DESKTOP_ID=droiddesk-applications-menu.desktop
ICON_DIR=/usr/share/icons/hicolor/scalable/apps
SESSION_ADDRESS_FILE=/home/kali/.local/share/droiddesk/dbus-session-address
if [[ ! -s "$APP_SOURCE" && -s "$(dirname "$0")/applications-menu.py" ]]; then
  APP_SOURCE="$(dirname "$0")/applications-menu.py"
fi

if [[ "${1:-}" == "--self-test" ]]; then
  grep -q 'droiddesk-apps-menu-v2' "$0"
  grep -q 'Add to Desktop' "$APP_SOURCE"
  grep -q 'Remove from Desktop' "$APP_SOURCE"
  grep -q 'Gio.AppInfo.get_all' "$APP_SOURCE"
  grep -q 'X-DroidDesk' "$APP_SOURCE" || grep -q 'DroidDesk desktop shortcut' "$APP_SOURCE"
  bash -n "$0"
  python3 -m py_compile "$APP_SOURCE"
  echo "[DroidDesk Panel] Installed-applications menu and desktop shortcut tests passed."
  exit 0
fi

if [[ ! -s "$APP_SOURCE" ]]; then
  echo "[DroidDesk Panel] Applications menu source is missing: $APP_SOURCE"
  exit 10
fi

install -d -m 0755 /usr/local/bin /usr/share/applications "$ICON_DIR"
install -m 0755 "$APP_SOURCE" "$APP_TARGET"
python3 -m py_compile "$APP_TARGET"
python3 -c 'import gi; gi.require_version("Gtk", "3.0"); from gi.repository import Gtk, Gio' || {
  echo "[DroidDesk Panel] Python GTK 3 introspection is unavailable."
  exit 11
}
cat > "/usr/share/applications/$DESKTOP_ID" <<'MENU_DESKTOP'
[Desktop Entry]
Version=1.0
Type=Application
Name=Applications
Comment=Browse and launch all installed Kali applications
Exec=/usr/local/bin/droiddesk-applications-menu
TryExec=/usr/local/bin/droiddesk-applications-menu
Icon=droiddesk-kali-menu
Terminal=false
StartupNotify=true
Categories=System;
MENU_DESKTOP
chmod 0644 "/usr/share/applications/$DESKTOP_ID"

cat > "$ICON_DIR/droiddesk-kali-menu.svg" <<'MENU_ICON'
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
  <g fill="#ffffff" stroke="#10233f" stroke-width="1.25">
    <circle cx="7" cy="7" r="3.1"/>
    <circle cx="17" cy="7" r="3.1"/>
    <circle cx="7" cy="17" r="3.1"/>
    <circle cx="17" cy="17" r="3.1"/>
  </g>
</svg>
MENU_ICON
chmod 0644 "$ICON_DIR/droiddesk-kali-menu.svg"
gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true
update-desktop-database /usr/share/applications >/dev/null 2>&1 || true

if [[ ! -s "$SESSION_ADDRESS_FILE" ]]; then
  echo "[DroidDesk Panel] XFCE D-Bus session is not ready; menu will retry on next desktop launch."
  exit 12
fi
session_address="$(cat "$SESSION_ADDRESS_FILE")"

if ! sudo -E -u kali env DISPLAY=:0 XDG_RUNTIME_DIR=/tmp/runtime-kali DBUS_SESSION_BUS_ADDRESS="$session_address" /bin/bash -s -- "$DESKTOP_ID" <<'PANEL'
set -Eeuo pipefail
channel=xfce4-panel
panel_path=/panels/panel-2/plugin-ids
desktop_id="$1"
current_ids="$(xfconf-query -c "$channel" -p "$panel_path" 2>/dev/null |
  sed -nE 's/^[[:space:]]*(int[[:space:]]+)?([0-9]+)[[:space:]]*$/\2/p' | xargs)"
menu_id=""
other_menu_ids=()
for id in $current_ids; do
  plugin="$(xfconf-query -c "$channel" -p "/plugins/plugin-$id" 2>/dev/null || true)"
  items="$(xfconf-query -c "$channel" -p "/plugins/plugin-$id/items" 2>/dev/null || true)"
  if [[ "$plugin" == *applicationsmenu* || "$items" == *droiddesk-applications-menu.desktop* ]]; then
    if [[ -z "$menu_id" ]]; then menu_id="$id"; else other_menu_ids+=("$id"); fi
  fi
done

if [[ -z "$menu_id" ]]; then
  for candidate in $(seq 25 250); do
    if ! xfconf-query -c "$channel" -p "/plugins/plugin-$candidate" >/dev/null 2>&1; then
      menu_id="$candidate"
      break
    fi
  done
  [[ -n "$menu_id" ]] || { echo "[DroidDesk Panel] No free panel plugin ID."; exit 3; }
  args=(xfconf-query -c "$channel" -p "$panel_path" --force-array --type int --set "$menu_id")
  for id in $current_ids; do args+=(--type int --set "$id"); done
  "${args[@]}"
fi

plugin_path="/plugins/plugin-$menu_id"
if ! xfconf-query -c "$channel" -p "$plugin_path" --set launcher >/dev/null 2>&1; then
  xfconf-query -c "$channel" -p "$plugin_path" --create --type string --set launcher
fi
launcher_dir="/home/kali/.config/xfce4/panel/launcher-$menu_id"
install -d -m 0755 "$launcher_dir"
cp -f "/usr/share/applications/$desktop_id" "$launcher_dir/$desktop_id"
chown -R kali:kali "$launcher_dir"
chmod 0644 "$launcher_dir/$desktop_id"
xfconf-query -c "$channel" -p "$plugin_path/items" --create --force-array --type string --set "$desktop_id"
xfconf-query -c "$channel" -p "$plugin_path/show-label" --create --type bool --set false

# Remove duplicate legacy Applications-menu plugins from the panel order.
if (("${#other_menu_ids[@]}" > 0)); then
  filtered=()
  for id in $current_ids; do
    skip=0
    for other in "${other_menu_ids[@]}"; do [[ "$id" == "$other" ]] && skip=1; done
    [[ "$skip" -eq 0 ]] && filtered+=("$id")
  done
  args=(xfconf-query -c "$channel" -p "$panel_path" --force-array --type int --set "${filtered[0]}")
  for id in "${filtered[@]:1}"; do args+=(--type int --set "$id"); done
  "${args[@]}"
fi

xfce4-panel --restart
sleep 2
plugin="$(xfconf-query -c "$channel" -p "$plugin_path" 2>/dev/null)"
items="$(xfconf-query -c "$channel" -p "$plugin_path/items" 2>/dev/null)"
[[ "$plugin" == *launcher* ]] || { echo "[DroidDesk Panel] Launcher plugin type was not saved."; exit 4; }
[[ "$items" == *"$desktop_id"* ]] || { echo "[DroidDesk Panel] Menu launcher item was not saved."; exit 5; }
[[ -x "/home/kali/.config/xfce4/panel/launcher-$menu_id/$desktop_id" ]] || exit 6
echo "[DroidDesk Panel] Verified custom all-applications launcher on bottom panel 2 (plugin $menu_id)."
PANEL
then
  echo "[DroidDesk Panel] Could not replace the old menu button; retrying on next desktop launch."
  exit 13
fi

touch "$MARKER"
chmod 0600 "$MARKER"
echo "[DroidDesk Panel] Custom application launcher installed. Right-click an app to add/remove its desktop shortcut."
