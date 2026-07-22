# 🎮 PSX2 - PlayStation 2 Emulator for Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0.en.html)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![ARM64](https://img.shields.io/badge/Architecture-ARM64-orange.svg)](https://developer.arm.com/)
[![GitHub](https://img.shields.io/badge/GitHub-izzy2lost%2FPSX2-black.svg)](https://github.com/izzy2lost/PSX2)


<a href="https://play.google.com/store/apps/details?id=com.izzy2lost.psx2&pcampaignid=web_share">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"/>
</a>


**PSX2** is a high-performance PlayStation 2 emulator for Android devices, bringing your favorite PS2 games to mobile platforms with enhanced graphics and modern features. Experience authentic retro gaming on the go.

## 🎮 About

PSX2 is an Android port of the renowned [PCSX2](https://github.com/PCSX2/pcsx2) emulator, specifically forked from [PCSX2_ARM64](https://github.com/pontos2024/PCSX2_ARM64) to provide optimized performance on ARM64 Android devices.

### Key Features

- **High Compatibility** - Play thousands of PlayStation 2 games
- **Enhanced Graphics** - Upscaling, texture filtering, and modern rendering
- **Multiple Formats** - Support for ISO, CHD, CSO, ZSO, and compressed formats
- **Namco Arcade Hardware** - System 246, System 256, and Super System 256 manifests via `.acgame`
- **Touch Controls** - Intuitive on-screen gamepad with customizable layout
- **External Controllers** - Full support for Bluetooth and USB gamepads
- **Save States** - Quick save and load functionality
- **Game Covers** - Automatic cover art downloading and display
- **Per-Game Settings** - Individual configuration for optimal performance

## 📱 Requirements

- **Android 8.0** (API level 26) or higher
- **ARM64 device** (64-bit processor required)
- **4GB RAM** minimum (8GB+ recommended)
- **OpenGL ES 3.2** or Vulkan support
- **Storage space** for games and BIOS files

## 🚀 Installation

### Option 1: Google Play Store (Recommended)

Download PSX2 directly from the Google Play Store for automatic updates and easy installation.

<a href="https://play.google.com/store/apps/details?id=com.izzy2lost.psx2&pcampaignid=web_share">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"/>
</a>

### Option 2: Build from Source (Free)

For users who prefer to build from source, follow the build instructions below.

**Note:** Paid users get convenient automatic updates through the Play Store, while free users can build from source using the instructions provided.

## 🔧 Build Instructions

### Prerequisites

1. **Android Studio** (latest version)
2. **Android NDK** 28.2.13676358
3. **CMake** 3.22.1+
4. **Git**
5. **Java 17**

### Step-by-Step Build Process

1. **Clone the repository:**

   ```bash
   git clone https://github.com/izzy2lost/PSX2.git
   cd PSX2
   ```

2. **Open in Android Studio:**
   - Launch Android Studio
   - Select "Open an existing project"
   - Navigate to the cloned PSX2-Android folder
   - Click "OK"

3. **Install required components:**
   - Android Studio will prompt to install missing SDK components
   - Accept all license agreements
   - Wait for Gradle sync to complete

4. **Build the APK:**

   ```bash
   ./gradlew assembleRelease
   ```

   Or use Android Studio:
   - Go to **Build** → **Generate Signed Bundle/APK**
   - Select **APK**
   - Choose **release** build variant
   - Click **Build**

5. **Install the APK:**

   ```bash
   adb install app/build/outputs/apk/release/PSX2_*.apk
   ```

### Build Variants

- **Release**: Optimized for performance and size
- **Debug**: For development with debugging symbols

## 📋 Setup Guide

### 1. BIOS Files

PSX2 requires PlayStation 2 BIOS files to function:

- **Required**: PS2 BIOS files (not included)
- **Location**: Follow the setup wizard to import BIOS
- **Supported**: All official PS2 BIOS versions
- **Arcade**: System 246/256 games require a COH-H arcade BIOS; a retail PS2 BIOS cannot replace it
- **Recognized arcade chip dumps**: System 246 `r27v1602f.7d` (SHA-1 `f0a74bbcaf801f3fd0b7002ebd0118564aae3528`) and System 256 `r27v1602f.8g` (SHA-1 `bc4fb4e1e53adbd92385f1726bd69663ff870f1e`)
- **Legal**: You must own a PlayStation 2 console to legally use BIOS files

### 2. Game Files

Supported game formats:

- **ISO** - Standard disc images
- **CHD** - Compressed Hunks of Data
- **CSO/ZSO** - Compressed ISO formats
- **BIN/CUE** - Binary disc images
- **IMG/MDF/NRG** - Various disc formats
- **ACGAME** - Namco System 246/256 arcade game manifests

### 3. Arcade Games

Arcade support is based on [PS2Homebrew-arcade/pcsx2x6](https://github.com/PS2Homebrew-arcade/pcsx2x6). Import a COH-H BIOS through the normal BIOS picker, then place each `.acgame` manifest with the ELF, media image, dongle, and optional memory-card files it names. Relative paths and the manifest's `subdir` are resolved inside the same Android Storage Access Framework game folder.

Add that folder to the game library and launch the `.acgame` entry. The manifest selects System 246, System 256, or Super System 256 timing, its JVS layout, media type, persistent SRAM, memory cards, and lightguns. See the upstream [game configuration reference](https://ps2homebrew-arcade.github.io/pcsx2x6/game_config/) for the manifest fields and [arcade BIOS guide](https://ps2homebrew-arcade.github.io/pcsx2x6/getting_a_bios/) for supported hardware.

Normal touch controls and external controllers are mirrored to JVS. Press **L3** to insert a coin and **R3** to toggle the test switch. GunCon trigger/start/select are forwarded as trigger/start/coin, with **A** used for the foot pedal where the game has one.

### 4. First Launch

1. Run PSX2 and complete the setup wizard
2. Pick your data/user folder
3. Choose your games directory
4. Select your BIOS folder
5. Start playing!

## ⚙️ Configuration

### Graphics Settings

- **Renderer**: Auto, Vulkan, OpenGL, Software
- **Resolution**: 1x to 8x native scaling
- **Aspect Ratio**: Multiple options including widescreen

### Game-Specific Settings

Each game can have individual settings for optimal compatibility and performance.

## 🎯 Compatibility

PSX2 aims for high compatibility with the PlayStation 2 library. Performance varies by device capabilities and game requirements.

## 📄 Legal Information

### Important Disclaimers

- **No Games Included**: PSX2 does not include any copyrighted games
- **No BIOS Included**: PlayStation 2 BIOS files are not provided
- **Legal Requirements**: Users must own original games and console
- **Educational Purpose**: This emulator is for educational and preservation purposes

### License

This project is licensed under the **GNU General Public License v3.0** - see the [LICENSE](LICENSE) file for details or view it here [Official License](https://www.gnu.org/licenses/gpl-3.0.en.html).

### Third-Party Licenses

PSX2 incorporates code from several open-source projects:

- [PCSX2](https://github.com/PCSX2/pcsx2) - GPL v3.0
- [PCSX2_ARM64](https://github.com/pontos2024/PCSX2_ARM64) - GPL v3.0
- Additional libraries listed in the app's About section

## 🤝 Contributing

We welcome contributions! Please read our contributing guidelines and submit pull requests for improvements.

### Development

- Follow Android development best practices
- Test on multiple devices and Android versions
- Ensure compatibility with existing save data
- Document any new features or changes

## 📞 Support

### Getting Help

- Check the [Issues](https://github.com/izzy2lost/PSX2/issues) page for known problems
- Search existing issues before creating new ones
- Provide device information and logs when reporting bugs

### Community

- Join discussions in the Issues section
- Share compatibility reports and performance tips
- Help other users with setup and configuration

## 🙏 Acknowledgments

Special thanks to:

- **PCSX2 Team** - For the incredible PlayStation 2 emulator
- **PCSX2_ARM64 Contributors** - For ARM64 optimization work
- **Android Community** - For testing and feedback
- **Open Source Contributors** - For various improvements and fixes

## 📊 Project Status

PSX2 is actively maintained and regularly updated with:

- Performance improvements
- Compatibility enhancements
- Bug fixes and stability improvements
- New features and quality of life updates

---

**Disclaimer**: PlayStation and PlayStation 2 are trademarks of Sony Interactive Entertainment. This project is not affiliated with or endorsed by Sony Interactive Entertainment.
