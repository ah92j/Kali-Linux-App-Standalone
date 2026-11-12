#!/bin/bash

# Termux packages URL base
URL_BASE="https://packages-cf.termux.dev/apt/termux-main/pool/main"

# Packages we need to download
PACKAGES=(
    "w/wlroots/wlroots_0.17.4-1_aarch64.deb"
    "w/wayland/wayland_1.22.0-1_aarch64.deb"
    "l/libxkbcommon/libxkbcommon_1.7.0-1_aarch64.deb"
    "p/pixman/pixman_0.43.4-1_aarch64.deb"
    "l/libdrm/libdrm_2.4.120-1_aarch64.deb"
    "l/libffi/libffi_3.4.6_aarch64.deb"
)

# Output directories
JNILIBS_DIR="/Users/orailnoor/workspace/DroidDesk/app/android/app/src/main/jniLibs/arm64-v8a"
INCLUDE_DIR="/Users/orailnoor/workspace/DroidDesk/app/android/app/src/main/cpp/include"

mkdir -p "$JNILIBS_DIR"
mkdir -p "$INCLUDE_DIR"
mkdir -p /tmp/wlroots_deps
cd /tmp/wlroots_deps

for pkg in "${PACKAGES[@]}"; do
    filename=$(basename "$pkg")
    echo "Downloading $filename..."
    curl -sL "$URL_BASE/$pkg" -o "$filename"
    
    # Extract using bsdtar (macOS compatible)
    bsdtar -xf "$filename"
    bsdtar -xf data.tar.xz
    
    # Copy shared libraries
    find ./data/data/com.termux/files/usr/lib -name "*.so*" -type f -exec cp {} "$JNILIBS_DIR/" \;
    find ./data/data/com.termux/files/usr/lib -name "*.so*" -type l -exec cp -a {} "$JNILIBS_DIR/" \;
    
    # Copy headers
    if [ -d "./data/data/com.termux/files/usr/include" ]; then
        cp -r ./data/data/com.termux/files/usr/include/* "$INCLUDE_DIR/"
    fi
    
    # Clean up for next package
    rm -rf data control.tar.xz data.tar.xz debian-binary
done

echo "Done fetching wlroots dependencies!"
