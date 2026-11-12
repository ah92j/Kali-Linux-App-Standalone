#!/usr/bin/python3
"""Touch-friendly installed-applications launcher for Kali XFCE.

Right-click an application to add/remove only its desktop shortcut. The package
and its application-menu entry are never removed by these shortcut actions.
"""
import configparser
import os
import shutil
import sys
import tempfile

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, Gio, GLib, Gtk, Pango

DESKTOP_DIR = "/home/kali/Desktop"
MANAGED_MARKER = "# DroidDesk desktop shortcut: "
SOURCE_MARKER = "# DroidDesk shortcut source: "
APPSTORE_MARKER = "# DroidDesk App Store package: "
DISABLED_SHORTCUTS = "/home/kali/.local/share/droiddesk/desktop-shortcuts-disabled"
WINDOW_WIDTH = 460
WINDOW_MAX_HEIGHT = 720
ICON_SIZE = 42

CATEGORY_ORDER = [
    "All applications",
    "Accessories",
    "Development",
    "Education",
    "Games",
    "Graphics",
    "Internet",
    "Multimedia",
    "Office",
    "Settings",
    "System",
    "Other",
]

CATEGORY_MAP = {
    "AudioVideo": "Multimedia",
    "Audio": "Multimedia",
    "Video": "Multimedia",
    "Network": "Internet",
    "WebBrowser": "Internet",
    "Development": "Development",
    "IDE": "Development",
    "Graphics": "Graphics",
    "2DGraphics": "Graphics",
    "3DGraphics": "Graphics",
    "Office": "Office",
    "WordProcessor": "Office",
    "Spreadsheet": "Office",
    "Education": "Education",
    "Game": "Games",
    "Settings": "Settings",
    "DesktopSettings": "Settings",
    "System": "System",
    "TerminalEmulator": "System",
    "Utility": "Accessories",
    "TextEditor": "Accessories",
    "FileManager": "Accessories",
}


def read_entry(path):
    parser = configparser.ConfigParser(interpolation=None, strict=False)
    parser.optionxform = str
    try:
        parser.read(path, encoding="utf-8")
        section = parser["Desktop Entry"]
        return {
            "Name": section.get("Name", "").strip(),
            "Exec": section.get("Exec", "").strip(),
            "Icon": section.get("Icon", "").strip(),
        }
    except (OSError, KeyError, configparser.Error):
        return {"Name": "", "Exec": "", "Icon": ""}


def app_categories(app):
    categories = []
    try:
        raw = app.get_categories() or ""
    except (AttributeError, TypeError):
        raw = ""
    for category in raw.split(";"):
        label = CATEGORY_MAP.get(category)
        if label and label not in categories:
            categories.append(label)
    return categories or ["Other"]


def list_applications():
    applications = {}
    for app in Gio.AppInfo.get_all():
        try:
            if not isinstance(app, Gio.DesktopAppInfo) or not app.should_show():
                continue
            desktop_id = app.get_id() or ""
            source = app.get_filename() or ""
            if not desktop_id.endswith(".desktop") or not source or not os.path.isfile(source):
                continue
            if desktop_id == "droiddesk-applications-menu.desktop":
                continue
            applications[desktop_id] = {
                "id": desktop_id,
                "source": source,
                "name": app.get_name() or desktop_id[:-8],
                "icon": app.get_icon(),
                "categories": app_categories(app),
                "app": app,
                "signature": read_entry(source),
            }
        except Exception as error:
            print("[Kali Applications] Skipping invalid desktop entry: %s" % error, file=sys.stderr)
    return sorted(applications.values(), key=lambda item: item["name"].casefold())


def managed_shortcuts(application):
    desktop_id = application["id"]
    signature = application["signature"]
    found = []
    try:
        names = os.listdir(DESKTOP_DIR)
    except OSError:
        return found

    for name in names:
        if not name.endswith(".desktop"):
            continue
        path = os.path.join(DESKTOP_DIR, name)
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                content = handle.read()
        except OSError:
            continue

        # New desktop shortcuts and App Store-created launchers carry a stable source ID.
        if (MANAGED_MARKER + desktop_id) in content or (SOURCE_MARKER + desktop_id) in content:
            found.append(path)
            continue

        # Older App Store builds copied the original desktop entry after a package marker.
        # Recognize those entries by their original Name/Exec/Icon but never delete a
        # user-created desktop file without one of our ownership markers.
        if content.startswith(APPSTORE_MARKER):
            candidate = read_entry(path)
            if candidate == signature:
                found.append(path)
    return found


def read_disabled_shortcuts():
    try:
        with open(DISABLED_SHORTCUTS, "r", encoding="utf-8") as handle:
            return {line.strip() for line in handle if line.strip()}
    except OSError:
        return set()


def save_disabled_shortcuts(items):
    directory = os.path.dirname(DISABLED_SHORTCUTS)
    os.makedirs(directory, mode=0o755, exist_ok=True)
    temporary = DISABLED_SHORTCUTS + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        for item in sorted(items):
            handle.write(item + "\n")
    os.chmod(temporary, 0o644)
    os.replace(temporary, DISABLED_SHORTCUTS)


def write_desktop_shortcut(application):
    os.makedirs(DESKTOP_DIR, mode=0o755, exist_ok=True)
    desktop_id = application["id"]
    disabled = read_disabled_shortcuts()
    disabled.discard(desktop_id)
    save_disabled_shortcuts(disabled)
    target = os.path.join(DESKTOP_DIR, desktop_id)

    if os.path.exists(target):
        try:
            with open(target, "r", encoding="utf-8", errors="replace") as handle:
                existing = handle.read()
            if MANAGED_MARKER + desktop_id not in existing:
                target = os.path.join(DESKTOP_DIR, "DroidDesk-" + desktop_id)
        except OSError:
            target = os.path.join(DESKTOP_DIR, "DroidDesk-" + desktop_id)

    source = application["source"]
    try:
        with open(source, "r", encoding="utf-8", errors="replace") as handle:
            desktop_contents = handle.read()
        fd, temporary = tempfile.mkstemp(prefix=".droiddesk-", suffix=".desktop", dir=DESKTOP_DIR)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(MANAGED_MARKER + desktop_id + "\n")
                handle.write(desktop_contents)
                if not desktop_contents.endswith("\n"):
                    handle.write("\n")
            os.chmod(temporary, 0o755)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        return True
    except OSError as error:
        print("[Kali Applications] Could not create desktop shortcut: %s" % error, file=sys.stderr)
        return False


def remove_desktop_shortcuts(application):
    # Persist the user's choice independently of the package installation. Future
    # App Store upgrades and the legacy shortcut migration must respect this choice.
    disabled = read_disabled_shortcuts()
    disabled.add(application["id"])
    save_disabled_shortcuts(disabled)
    removed = 0
    for path in managed_shortcuts(application):
        try:
            os.unlink(path)
            removed += 1
        except OSError as error:
            print("[Kali Applications] Could not remove %s: %s" % (path, error), file=sys.stderr)
    return removed


class ApplicationsMenu:
    def __init__(self):
        self.applications = list_applications()
        self.active_category = "All applications"
        self.query = ""
        self.window = Gtk.Window(type=Gtk.WindowType.TOPLEVEL)
        self.window.set_title("Kali Linux Applications")
        self.window.set_name("droiddesk-applications-menu")
        self.window.set_decorated(False)
        self.window.set_resizable(False)
        self.window.set_keep_above(True)
        self.window.set_skip_taskbar_hint(True)
        self.window.set_skip_pager_hint(True)
        self.window.set_type_hint(Gdk.WindowTypeHint.POPUP_MENU)
        self.window.set_default_size(WINDOW_WIDTH, WINDOW_MAX_HEIGHT)
        self.window.connect("key-press-event", self.on_key_press)

        display = Gdk.Display.get_default()
        monitor = display.get_primary_monitor() if display else None
        if monitor:
            geometry = monitor.get_geometry()
            height = min(WINDOW_MAX_HEIGHT, max(360, geometry.height - 96))
            self.window.set_default_size(min(WINDOW_WIDTH, max(320, geometry.width - 16)), height)
            self.window.move(geometry.x + 8, geometry.y + max(8, geometry.height - height - 76))
        self.build_ui()
        self.render_apps()

    def build_ui(self):
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        root.set_border_width(12)
        self.window.add(root)

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        title = Gtk.Label()
        title.set_markup("<b>Kali Linux</b>  <span foreground='#9ca3af'>Applications</span>")
        title.set_xalign(0)
        header.pack_start(title, True, True, 0)
        close = Gtk.Button.new_from_icon_name("window-close-symbolic", Gtk.IconSize.BUTTON)
        close.set_relief(Gtk.ReliefStyle.NONE)
        close.connect("clicked", lambda *_: self.window.destroy())
        header.pack_end(close, False, False, 0)
        root.pack_start(header, False, False, 0)

        self.search = Gtk.SearchEntry()
        self.search.set_placeholder_text("Search installed applications")
        self.search.connect("search-changed", self.on_search_changed)
        root.pack_start(self.search, False, False, 0)

        body = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        root.pack_start(body, True, True, 0)

        category_scroll = Gtk.ScrolledWindow()
        category_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        category_scroll.set_size_request(126, -1)
        category_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        category_scroll.add(category_box)
        body.pack_start(category_scroll, False, True, 0)

        categories = set()
        for application in self.applications:
            categories.update(application["categories"])
        ordered = [item for item in CATEGORY_ORDER if item == "All applications" or item in categories]
        for category in ordered:
            button = Gtk.Button.new_with_label(category)
            button.set_relief(Gtk.ReliefStyle.NONE)
            button.set_halign(Gtk.Align.FILL)
            button.connect("clicked", self.on_category_clicked, category)
            category_box.pack_start(button, False, False, 0)

        self.count_label = Gtk.Label()
        self.count_label.set_xalign(0)
        self.count_label.get_style_context().add_class("dim-label")
        root.pack_end(self.count_label, False, False, 0)

        app_scroll = Gtk.ScrolledWindow()
        app_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        body.pack_start(app_scroll, True, True, 0)

        self.flow = Gtk.FlowBox()
        self.flow.set_selection_mode(Gtk.SelectionMode.NONE)
        self.flow.set_row_spacing(8)
        self.flow.set_column_spacing(8)
        self.flow.set_homogeneous(True)
        self.flow.set_min_children_per_line(2)
        self.flow.set_max_children_per_line(3)
        self.flow.set_activate_on_single_click(False)
        app_scroll.add(self.flow)

        css = Gtk.CssProvider()
        css.load_from_data(b"""
            #droiddesk-applications-menu { background: #172033; color: #f8fafc; }
            #droiddesk-applications-menu button { color: #f8fafc; }
            #droiddesk-applications-menu .app-card {
                background: #24324a; border-radius: 12px; padding: 8px;
            }
            #droiddesk-applications-menu .app-card:hover { background: #31435f; }
            #droiddesk-applications-menu entry { min-height: 38px; }
        """)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

    def on_key_press(self, _widget, event):
        if event.keyval == Gdk.KEY_Escape:
            self.window.destroy()
            return True
        return False

    def on_search_changed(self, entry):
        self.query = (entry.get_text() or "").casefold().strip()
        self.render_apps()

    def on_category_clicked(self, _button, category):
        self.active_category = category
        self.render_apps()

    def render_apps(self):
        for child in self.flow.get_children():
            self.flow.remove(child)

        visible = []
        for application in self.applications:
            if self.active_category != "All applications" and self.active_category not in application["categories"]:
                continue
            if self.query and self.query not in application["name"].casefold() and self.query not in application["id"].casefold():
                continue
            visible.append(application)
            self.flow.add(self.create_app_card(application))

        self.count_label.set_text("%d applications" % len(visible))
        self.flow.show_all()

    def create_app_card(self, application):
        card = Gtk.EventBox()
        card.set_visible_window(False)
        card.add_events(Gdk.EventMask.BUTTON_PRESS_MASK)
        card.connect("button-press-event", self.on_app_card_press, application)

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        content.set_size_request(104, 100)
        content.set_halign(Gtk.Align.CENTER)
        content.set_valign(Gtk.Align.CENTER)
        content.get_style_context().add_class("app-card")

        icon = application["icon"]
        if icon is not None:
            image = Gtk.Image.new_from_gicon(icon, Gtk.IconSize.DIALOG)
            image.set_pixel_size(ICON_SIZE)
        else:
            image = Gtk.Image.new_from_icon_name("application-x-executable", Gtk.IconSize.DIALOG)
            image.set_pixel_size(ICON_SIZE)
        image.set_halign(Gtk.Align.CENTER)
        content.pack_start(image, False, False, 0)

        label = Gtk.Label(label=application["name"])
        label.set_line_wrap(True)
        label.set_max_width_chars(15)
        label.set_justify(Gtk.Justification.CENTER)
        label.set_lines(2)
        label.set_ellipsize(Pango.EllipsizeMode.END)

        footer = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=2)
        footer.pack_start(label, True, True, 0)
        more = Gtk.Button.new_with_label("⋮")
        more.set_tooltip_text("Add or remove desktop shortcut")
        more.set_relief(Gtk.ReliefStyle.NONE)
        more.set_size_request(28, 28)
        more.connect("clicked", self.on_more_button, application)
        footer.pack_end(more, False, False, 0)
        content.pack_start(footer, False, False, 0)

        card.add(content)
        return card

    def on_app_card_press(self, _widget, event, application):
        if event.type != Gdk.EventType.BUTTON_PRESS:
            return False
        if event.button == 1:
            try:
                application["app"].launch([], None)
                self.window.destroy()
            except Exception as error:
                print("[Kali Applications] Launch failed: %s" % error, file=sys.stderr)
                self.show_error("Could not start " + application["name"])
            return True
        if event.button == 3:
            self.show_context_menu(application, event)
            return True
        return False

    def on_more_button(self, button, application):
        self.show_context_menu(application, widget=button)

    def show_context_menu(self, application, event=None, widget=None):
        menu = Gtk.Menu()
        has_shortcut = bool(managed_shortcuts(application))
        label = "Remove from Desktop" if has_shortcut else "Add to Desktop"
        item = Gtk.MenuItem(label=label)
        item.connect("activate", self.on_shortcut_action, application, has_shortcut)
        menu.append(item)
        launch = Gtk.MenuItem(label="Open")
        launch.connect("activate", self.on_launch_menu, application)
        menu.append(launch)
        menu.show_all()
        if event is not None:
            menu.popup_at_pointer(event)
        elif widget is not None:
            menu.popup_at_widget(
                widget,
                Gdk.Gravity.SOUTH_WEST,
                Gdk.Gravity.NORTH_WEST,
                None,
            )
        else:
            menu.popup(None, None, None, None, 0, Gtk.get_current_event_time())

    def on_shortcut_action(self, _item, application, remove):
        if remove:
            removed = remove_desktop_shortcuts(application)
            if not removed:
                self.show_error("No managed desktop shortcut was found.")
        else:
            if not write_desktop_shortcut(application):
                self.show_error("Could not add the application shortcut.")
        self.applications = list_applications()
        self.render_apps()

    def on_launch_menu(self, _item, application):
        try:
            application["app"].launch([], None)
            self.window.destroy()
        except Exception as error:
            print("[Kali Applications] Launch failed: %s" % error, file=sys.stderr)
            self.show_error("Could not start " + application["name"])

    def show_error(self, message):
        dialog = Gtk.MessageDialog(
            transient_for=self.window,
            modal=True,
            message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.CLOSE,
            text=message,
        )
        dialog.run()
        dialog.destroy()

    def show(self):
        self.window.show_all()
        self.window.present()


def self_test():
    assert os.path.isdir("/usr/share/applications")
    assert os.path.isdir("/home/kali/Desktop")
    print("[Kali Applications] GTK launcher self-test passed.")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        Gtk.init(None)
        ApplicationsMenu().show()
        Gtk.main()
