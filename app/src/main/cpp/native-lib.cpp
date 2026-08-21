#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <unistd.h>
#include "PrecompiledHeader.h"
#include "AchievementsJNI.h"
#include "common/StringUtil.h"
#include "common/FileSystem.h"
#include "common/Error.h"
#include "common/ZipHelpers.h"
#include "pcsx2/GS.h"
#include "pcsx2/VMManager.h"
#include "CDVD/CDVD.h"
#include "PerformanceMetrics.h"
#include "GameList.h"
#include "GS/GSPerfMon.h"
#include "GS/Renderers/HW/GSRendererHW.h"
#include "GS/Renderers/HW/GSTextureReplacements.h"
#include "GSDumpReplayer.h"
#include "ImGui/ImGuiManager.h"
#include "common/Path.h"
#include "common/MemorySettingsInterface.h"
#include "pcsx2/INISettingsInterface.h"
#include "SIO/Pad/Pad.h"
#include "Input/InputManager.h"
#include "ImGui/ImGuiFullscreen.h"
#include "Achievements.h"
#include "Host.h"
#include "ImGui/FullscreenUI.h"
#include "SIO/Pad/PadDualshock2.h"
#include "DEV9/ACJV.h"
#include "USB/USB.h"
#include "MTGS.h"
#include "GS/Renderers/Vulkan/VKLoader.h"
#include "SDL3/SDL.h"
#include <atomic>
#include <algorithm>
#include <cctype>
#include <deque>
#include <future>
#include <mutex>
#ifdef __ANDROID__
#include "SDL3/SDL.h"
#endif


bool s_execute_exit;
int s_window_width = 0;
int s_window_height = 0;
ANativeWindow* s_window = nullptr;

static std::mutex s_window_mutex;
static std::mutex s_vm_start_mutex;
// Held for the entire lifetime of a VM thread. VMManager::Shutdown() flips the
// state to Shutdown before CPUThreadShutdown() has finished tearing down MTGS/
// SysMemory, so a state check alone lets the next boot race the old teardown.
static std::mutex s_vm_lifecycle_mutex;
static std::mutex s_vm_error_mutex;
static ANativeWindow* s_render_window = nullptr;
static std::atomic_bool s_shutdown_requested{false};
// Bumped by prepareVMStart() before every boot. shutdown() hands its work to a
// detached thread, so without this a stop request issued while nothing is
// running can be scheduled late and kill the VM that was started right after
// it. The detached thread compares the generation it captured and bails when a
// newer boot has already begun.
static std::atomic<uint64_t> s_vm_start_generation{0};
static std::string s_last_vm_error;
static MemorySettingsInterface s_settings_interface;
static int s_pending_renderer = -1; // -1 = none; else 12=OpenGL,13=SW,14=Vulkan
static std::string s_verified_bios_usa;
static std::string s_verified_bios_europe;
static std::string s_verified_bios_japan;
static std::string s_verified_bios_arcade;

struct TouchscreenPointerUpdate
{
    float x;
    float y;
    bool pressed;
};

static std::mutex s_touchscreen_pointer_mutex;
static std::deque<TouchscreenPointerUpdate> s_touchscreen_pointer_updates;

static void QueueTouchscreenPointerUpdate(float x, float y, bool pressed)
{
    std::lock_guard lock(s_touchscreen_pointer_mutex);

    // MOVE events can arrive faster than the emulated input poll. Keep the
    // newest coordinates for the current state, but never discard a press or
    // release edge.
    if (!s_touchscreen_pointer_updates.empty() &&
        s_touchscreen_pointer_updates.back().pressed == pressed)
    {
        s_touchscreen_pointer_updates.back() = {x, y, pressed};
    }
    else
    {
        s_touchscreen_pointer_updates.push_back({x, y, pressed});
    }
}

static void ClearTouchscreenPointerUpdates()
{
    std::lock_guard lock(s_touchscreen_pointer_mutex);
    s_touchscreen_pointer_updates.clear();
}

// Renderer values already match GSRendererType. OpenGL is linked on Android and
// must not be silently rewritten to Vulkan.
static int NormalizeAndroidRenderer(int renderer)
{
    return renderer;
}

// Fallback JNI access for content:// when SDL's Android env is not yet ready
// (no JNI fallback)

////
std::string GetJavaString(JNIEnv *env, jstring jstr) {
    if (!jstr) {
        return "";
    }
    const char *str = env->GetStringUTFChars(jstr, nullptr);
    std::string cpp_string = std::string(str);
    env->ReleaseStringUTFChars(jstr, str);
    return cpp_string;
}

static std::string GetGameSerialForPath(const std::string& game_path)
{
    if (game_path.empty())
        return {};

    if (VMManager::isArcadeManifest(game_path))
    {
        INISettingsInterface manifest(game_path);
        return manifest.Load() ? manifest.GetStringValue("game", "gameid", "") : std::string();
    }

    // Determine serial via CDVD using the same path the core will open
    Error error;
    std::string serial;
    auto* prev = CDVD;
    CDVD = &CDVDapi_Iso;
    if (CDVD->open(game_path, &error))
    {
        (void)DoCDVDdetectDiskType();
        cdvdGetDiscInfo(&serial, nullptr, nullptr, nullptr, nullptr);
        DoCDVDclose();
    }
    CDVD = prev;
    return serial;
}

static void ApplyPerGameSettingsForSerial(const std::string& serial)
{
    if (serial.empty())
        return;

    // Build settings path and load
    const std::string settings_dir = Path::Combine(EmuFolders::DataRoot, "gamesettings");
    const std::string settings_path = Path::Combine(settings_dir, serial + ".ini");
    INISettingsInterface per_game(settings_path);
    if (!per_game.Load())
        return;

    // Map known keys into our in-memory settings layer and apply where possible
    std::string s;
    float fval = 0.0f;
    bool bval = false;

    if (per_game.GetStringValue("EmuCore/GS", "Renderer", &s))
    {
        s_settings_interface.SetStringValue("EmuCore/GS", "Renderer", s.c_str());
        // Defer actual renderer switch until VM is initialized
        int rend = -1;
        if (StringUtil::Strcasecmp(s.c_str(), "OpenGL") == 0) rend = 12;
        else if (StringUtil::Strcasecmp(s.c_str(), "Software") == 0) rend = 13;
        else if (StringUtil::Strcasecmp(s.c_str(), "Vulkan") == 0) rend = 14;
        if (rend >= 0)
            s_pending_renderer = rend;
    }
    if (per_game.GetFloatValue("EmuCore/GS", "upscale_multiplier", &fval))
        s_settings_interface.SetFloatValue("EmuCore/GS", "upscale_multiplier", fval);
    int abl_int = -1;
    if (per_game.GetIntValue("EmuCore/GS", "accurate_blending_unit", &abl_int))
    {
        s_settings_interface.SetStringValue("EmuCore/GS", "accurate_blending_unit", StringUtil::StdStringFromFormat("%d", abl_int).c_str());
    }
    else if (per_game.GetStringValue("EmuCore/GS", "accurate_blending_unit", &s))
    {
        int lvl = 1;
        if (StringUtil::Strcasecmp(s.c_str(), "Minimum") == 0) lvl = 0;
        else if (StringUtil::Strcasecmp(s.c_str(), "Basic") == 0) lvl = 1;
        else if (StringUtil::Strcasecmp(s.c_str(), "Medium") == 0) lvl = 2;
        else if (StringUtil::Strcasecmp(s.c_str(), "High") == 0) lvl = 3;
        else if (StringUtil::Strcasecmp(s.c_str(), "Full") == 0) lvl = 4;
        else if (StringUtil::Strcasecmp(s.c_str(), "Maximum") == 0) lvl = 5;
        s_settings_interface.SetStringValue("EmuCore/GS", "accurate_blending_unit", StringUtil::StdStringFromFormat("%d", lvl).c_str());
    }

    if (per_game.GetBoolValue("EmuCore", "EnableWideScreenPatches", &bval))
        s_settings_interface.SetBoolValue("EmuCore", "EnableWideScreenPatches", bval);
    if (per_game.GetBoolValue("EmuCore", "EnableNoInterlacingPatches", &bval))
        s_settings_interface.SetBoolValue("EmuCore", "EnableNoInterlacingPatches", bval);
    if (per_game.GetBoolValue("EmuCore", "EnablePatches", &bval))
        s_settings_interface.SetBoolValue("EmuCore", "EnablePatches", bval);
    if (per_game.GetBoolValue("EmuCore", "EnableCheats", &bval))
        s_settings_interface.SetBoolValue("EmuCore", "EnableCheats", bval);
}

enum class VerifiedBiosRegion
{
    Unknown,
    USA,
    Europe,
    Japan,
};

static std::string NormalizeSerialPrefix(const std::string& serial)
{
    std::string out;
    out.reserve(serial.size());
    for (unsigned char ch : serial)
    {
        if (std::isalnum(ch))
            out.push_back(static_cast<char>(std::toupper(ch)));
    }
    return out;
}

static VerifiedBiosRegion GetBiosRegionForSerial(const std::string& serial)
{
    const std::string normalized = NormalizeSerialPrefix(serial);
    if (normalized.empty())
        return VerifiedBiosRegion::Unknown;

    if (StringUtil::StartsWithNoCase(normalized, "SCUS") ||
        StringUtil::StartsWithNoCase(normalized, "SLUS"))
    {
        return VerifiedBiosRegion::USA;
    }

    if (StringUtil::StartsWithNoCase(normalized, "SCES") ||
        StringUtil::StartsWithNoCase(normalized, "SCED") ||
        StringUtil::StartsWithNoCase(normalized, "SLES") ||
        StringUtil::StartsWithNoCase(normalized, "SLED"))
    {
        return VerifiedBiosRegion::Europe;
    }

    if (StringUtil::StartsWithNoCase(normalized, "SCPS") ||
        StringUtil::StartsWithNoCase(normalized, "SLPS") ||
        StringUtil::StartsWithNoCase(normalized, "SLPM"))
    {
        return VerifiedBiosRegion::Japan;
    }

    return VerifiedBiosRegion::Unknown;
}

static const std::string& FirstVerifiedBios()
{
    if (!s_verified_bios_usa.empty())
        return s_verified_bios_usa;
    if (!s_verified_bios_europe.empty())
        return s_verified_bios_europe;
    return s_verified_bios_japan;
}

static void SelectVerifiedBiosForSerial(const std::string& serial, bool arcade)
{
    if (arcade)
    {
        s_settings_interface.SetStringValue("Filenames", "BIOS", s_verified_bios_arcade.c_str());
        EmuConfig.BaseFilenames.Bios = s_verified_bios_arcade;
        if (!s_verified_bios_arcade.empty())
            Console.WriteLn("Selected verified COH-H arcade BIOS '%s'", s_verified_bios_arcade.c_str());
        return;
    }

    const VerifiedBiosRegion region = GetBiosRegionForSerial(serial);
    const std::string* selected = nullptr;

    switch (region)
    {
        case VerifiedBiosRegion::USA:
            selected = !s_verified_bios_usa.empty() ? &s_verified_bios_usa : nullptr;
            break;
        case VerifiedBiosRegion::Europe:
            selected = !s_verified_bios_europe.empty() ? &s_verified_bios_europe : nullptr;
            break;
        case VerifiedBiosRegion::Japan:
            selected = !s_verified_bios_japan.empty() ? &s_verified_bios_japan : nullptr;
            break;
        case VerifiedBiosRegion::Unknown:
        default:
            break;
    }

    const std::string& fallback = FirstVerifiedBios();
    if (!selected && !fallback.empty())
        selected = &fallback;

    if (!selected || selected->empty())
        return;

    s_settings_interface.SetStringValue("Filenames", "BIOS", selected->c_str());
    EmuConfig.BaseFilenames.Bios = *selected;
    Console.WriteLn("Selected verified BIOS '%s' for game serial '%s'", selected->c_str(), serial.c_str());
}

// (renderGpu JNI defined later; keep only one definition)

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setHudVisible(JNIEnv* env, jclass clazz, jboolean p_visible)
{
    const bool visible = (p_visible == JNI_TRUE);
    MemorySettingsInterface& si = s_settings_interface;

    // Toggle most HUD/OSD elements together
    si.SetBoolValue("EmuCore/GS", "OsdShowSpeed", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowFPS", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowVPS", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowCPU", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowGPU", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowResolution", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowGSStats", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowIndicators", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowSettings", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowInputs", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowFrameTimes", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowVersion", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowHardwareInfo", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowVideoCapture", visible);
    si.SetBoolValue("EmuCore/GS", "OsdShowInputRec", visible);

    // Apply changes to the running VM/renderer if active
    VMManager::ApplySettings();
    if (MTGS::IsOpen())
        MTGS::ApplySettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setMemoryCardSlots(JNIEnv* env, jclass,
                                                      jstring slot1_filename, jboolean slot1_enabled,
                                                      jstring slot2_filename, jboolean slot2_enabled)
{
    const std::string slot1 = GetJavaString(env, slot1_filename);
    const std::string slot2 = GetJavaString(env, slot2_filename);
    s_settings_interface.SetBoolValue("MemoryCards", "Slot1_Enable", slot1_enabled == JNI_TRUE);
    s_settings_interface.SetStringValue("MemoryCards", "Slot1_Filename", slot1.c_str());
    s_settings_interface.SetBoolValue("MemoryCards", "Slot2_Enable", slot2_enabled == JNI_TRUE);
    s_settings_interface.SetStringValue("MemoryCards", "Slot2_Filename", slot2.c_str());

    if (VMManager::HasValidVM())
        VMManager::ApplySettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setBlendingAccuracy(JNIEnv* env, jclass, jint level)
{
    // level: 0..5 -> numeric string
    if (level < 0) level = 0; if (level > 5) level = 5;
    s_settings_interface.SetStringValue("EmuCore/GS", "accurate_blending_unit", StringUtil::StdStringFromFormat("%d", level).c_str());
    if (VMManager::HasValidVM())
        VMManager::ApplySettings();
    if (MTGS::IsOpen())
        MTGS::ApplySettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setVsyncEnabled(JNIEnv* env, jclass, jboolean enabled)
{
    s_settings_interface.SetBoolValue("EmuCore/GS", "VsyncEnable", enabled == JNI_TRUE);
    if (VMManager::HasValidVM())
        VMManager::ApplySettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_initialize(JNIEnv *env, jclass clazz,
                                                jstring p_szpath, jint p_apiVer) {
    std::string _szPath = GetJavaString(env, p_szpath);
    EmuFolders::AppRoot = _szPath;
    EmuFolders::DataRoot = _szPath;
    EmuFolders::SetResourcesDirectory();

    Log::SetConsoleOutputLevel(LOGLEVEL_DEBUG);
    ImGuiManager::SetFontPathAndRange(Path::Combine(EmuFolders::Resources, "fonts" FS_OSPATH_SEPARATOR_STR "Roboto-Regular.ttf"), {});

    bool _SettingsIsEmpty = s_settings_interface.IsEmpty();
    if(_SettingsIsEmpty) {
        // don't provide an ini path, or bother loading. we'll store everything in memory.
        MemorySettingsInterface &si = s_settings_interface;
        Host::Internal::SetBaseSettingsLayer(&si);

        // The achievements code stores the RetroAchievements token in the
        // secrets settings layer and dereferences it unconditionally, so it
        // must exist. Back it with an INI file so the login survives restarts.
        static std::unique_ptr<INISettingsInterface> s_secrets_settings_interface;
        s_secrets_settings_interface =
            std::make_unique<INISettingsInterface>(Path::Combine(EmuFolders::DataRoot, "secrets.ini"));
        s_secrets_settings_interface->Load();
        Host::Internal::SetSecretsSettingsLayer(s_secrets_settings_interface.get());

        // Initialize emulator folders and ensure they exist (including GameSettings)
        EmuFolders::SetDefaults(si);
        EmuFolders::LoadConfig(si);
        EmuFolders::EnsureFoldersExist();

        VMManager::SetDefaultSettings(si, true, true, true, true, true);

        // complete as quickly as possible
        si.SetBoolValue("EmuCore/GS", "FrameLimitEnable", false);
        si.SetBoolValue("EmuCore/GS", "VsyncEnable", false);

        // ensure all input sources are disabled, we're not using them
        si.SetBoolValue("InputSources", "SDL", true);
        si.SetBoolValue("InputSources", "XInput", false);

        // audio output by default on Android
        si.SetStringValue("SPU2/Output", "Backend", "Oboe");
        si.SetIntValue("SPU2/Output", "BufferMS", 150);
        si.SetIntValue("SPU2/Output", "OutputLatencyMS", 40);

        // none of the bindings are going to resolve to anything
        Pad::ClearPortBindings(si, 0);
        si.ClearSection("Hotkeys");

        // force logging
        //si.SetBoolValue("Logging", "EnableSystemConsole", !s_no_console);
        si.SetBoolValue("Logging", "EnableSystemConsole", true);
        si.SetBoolValue("Logging", "EnableTimestamps", true);
        si.SetBoolValue("Logging", "EnableVerbose", true);

        // Default to a clean screen: hide HUD/OSD overlays by default
        si.SetBoolValue("EmuCore/GS", "OsdShowSpeed", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowFPS", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowVPS", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowCPU", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowGPU", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowResolution", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowGSStats", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowIndicators", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowSettings", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowInputs", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowFrameTimes", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowVersion", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowHardwareInfo", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowVideoCapture", false);
        si.SetBoolValue("EmuCore/GS", "OsdShowInputRec", false);

//        // remove memory cards, so we don't have sharing violations
//        for (u32 i = 0; i < 2; i++)
//        {
//            si.SetBoolValue("MemoryCards", fmt::format("Slot{}_Enable", i + 1).c_str(), false);
//            si.SetStringValue("MemoryCards", fmt::format("Slot{}_Filename", i + 1).c_str(), "");
//        }

        // Enable RetroAchievements
        si.SetBoolValue("Achievements", "Enabled", true);
        si.SetBoolValue("Achievements", "HardcoreMode", false);
        si.SetBoolValue("Achievements", "Notifications", true);
        si.SetBoolValue("Achievements", "LeaderboardNotifications", true);
        si.SetBoolValue("Achievements", "SoundEffects", true);
        si.SetBoolValue("Achievements", "EncoreMode", false);
        si.SetBoolValue("Achievements", "SpectatorMode", false);
        si.SetBoolValue("Achievements", "UnofficialTestMode", false);
    }

    VMManager::Internal::LoadStartupSettings();
    
    // Initialize RetroAchievements JNI bridge
    if (!AchievementsJNI::Initialize(env))
    {
        __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "Failed to initialize AchievementsJNI");
    }
    else
    {
        __android_log_print(ANDROID_LOG_INFO, "PCSX2", "AchievementsJNI initialized successfully");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getGameTitle(JNIEnv *env, jclass clazz,
                                                  jstring p_szpath) {
    std::string _szPath = GetJavaString(env, p_szpath);

    const GameList::Entry *entry;
    entry = GameList::GetEntryForPath(_szPath.c_str());

    std::string ret;
    ret.append(entry->title);
    ret.append("|");
    ret.append(entry->serial);
    ret.append("|");
    ret.append(StringUtil::StdStringFromFormat("%s (%08X)", entry->serial.c_str(), entry->crc));

    return env->NewStringUTF(ret.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getCurrentGameSerial(JNIEnv *env, jclass clazz) {
    std::string ret = VMManager::GetDiscSerial();
    return env->NewStringUTF(ret.c_str());
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_izzy2lost_psx2_NativeApp_getFPS(JNIEnv *env, jclass clazz) {
    return (jfloat)PerformanceMetrics::GetFPS();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getPauseGameTitle(JNIEnv *env, jclass clazz) {
    std::string ret = VMManager::GetTitle(true);
    return env->NewStringUTF(ret.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getPauseGameSerial(JNIEnv *env, jclass clazz) {
    std::string ret = StringUtil::StdStringFromFormat("%s (%08X)", VMManager::GetDiscSerial().c_str(), VMManager::GetDiscCRC());
    return env->NewStringUTF(ret.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getGameSerial(JNIEnv* env, jclass, jstring p_uri)
{
    if (!p_uri)
        return env->NewStringUTF("");
    const std::string path = GetJavaString(env, p_uri);

    // Reads the gameid out of an .acgame manifest and falls back to a direct
    // CDVD open (which supports content:// URIs) for ISO/CHD. An arcade
    // manifest is not a disc image, so probing it with CDVD yields no serial.
    return env->NewStringUTF(GetGameSerialForPath(path).c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getGameCrc(JNIEnv* env, jclass, jstring p_uri)
{
    if (!p_uri)
        return env->NewStringUTF("");
    std::string path = GetJavaString(env, p_uri);

    Error error;
    u32 crc = 0;
    auto* prev = CDVD;
    CDVD = &CDVDapi_Iso;
    if (CDVD->open(path, &error))
    {
        (void)DoCDVDdetectDiskType();
        cdvdGetDiscInfo(nullptr, nullptr, nullptr, &crc, nullptr);
        DoCDVDclose();
    }
    CDVD = prev;

    const std::string crc_hex = (crc != 0) ? StringUtil::StdStringFromFormat("%08X", crc) : std::string("");
    return env->NewStringUTF(crc_hex.c_str());
}


extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setPadVibration(JNIEnv *env, jclass clazz,
                                                     jboolean p_isOnOff) {
}


extern "C" JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setPadButton(JNIEnv *env, jclass clazz,
                                                  jint p_key, jint p_range, jboolean p_keyPressed) {
    PadDualshock2::Inputs _key;
    switch (p_key) {
        case 19: _key = PadDualshock2::Inputs::PAD_UP; break;
        case 22: _key = PadDualshock2::Inputs::PAD_RIGHT; break;
        case 20: _key = PadDualshock2::Inputs::PAD_DOWN; break;
        case 21: _key = PadDualshock2::Inputs::PAD_LEFT; break;
        case 100: _key = PadDualshock2::Inputs::PAD_TRIANGLE; break;
        case 97: _key = PadDualshock2::Inputs::PAD_CIRCLE; break;
        case 96: _key = PadDualshock2::Inputs::PAD_CROSS; break;
        case 99: _key = PadDualshock2::Inputs::PAD_SQUARE; break;
        case 109: _key = PadDualshock2::Inputs::PAD_SELECT; break;
        case 108: _key = PadDualshock2::Inputs::PAD_START; break;
        case 102: _key = PadDualshock2::Inputs::PAD_L1; break;
        case 104: _key = PadDualshock2::Inputs::PAD_L2; break;
        case 103: _key = PadDualshock2::Inputs::PAD_R1; break;
        case 105: _key = PadDualshock2::Inputs::PAD_R2; break;
        case 106: _key = PadDualshock2::Inputs::PAD_L3; break;
        case 107: _key = PadDualshock2::Inputs::PAD_R3; break;
        case 110: _key = PadDualshock2::Inputs::PAD_L_UP; break;
        case 111: _key = PadDualshock2::Inputs::PAD_L_RIGHT; break;
        case 112: _key = PadDualshock2::Inputs::PAD_L_DOWN; break;
        case 113: _key = PadDualshock2::Inputs::PAD_L_LEFT; break;
        case 120: _key = PadDualshock2::Inputs::PAD_R_UP; break;
        case 121: _key = PadDualshock2::Inputs::PAD_R_RIGHT; break;
        case 122: _key = PadDualshock2::Inputs::PAD_R_DOWN; break;
        case 123: _key = PadDualshock2::Inputs::PAD_R_LEFT; break;
        default: _key = PadDualshock2::Inputs::PAD_CROSS ; break;
    }

    float value = p_keyPressed ? 1.0f : 0.0f;
    if (p_keyPressed && p_range > 0) {
        const float denom = (p_range > 255) ? 32766.0f : 255.0f;
        value = std::clamp(static_cast<float>(p_range) / denom, 0.0f, 1.0f);
    }
    Pad::SetControllerState(0, static_cast<u32>(_key), value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_updateTouchscreenPointer(JNIEnv*, jclass,
                                                            jfloat x, jfloat y,
                                                            jboolean pressed) {
    const JVS_MODE mode = ACJV::GetMode();
    if (ACJV::GetGameId().empty() || (mode != JVS_MODE::LIGHTGUN && mode != JVS_MODE::TOUCH))
        return JNI_FALSE;

    // The Android UI thread must not mutate InputManager, ImGui or USB device
    // state while the CPU/GS threads are polling and rendering. The queue is
    // drained by Host::PumpMessagesOnCPUThread() at the next input poll.
    QueueTouchscreenPointerUpdate(x, y, pressed == JNI_TRUE);

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_resetKeyStatus(JNIEnv *env, jclass clazz) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setEnableCheats(JNIEnv *env, jclass clazz,
                                                     jboolean p_isonoff) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setAspectRatio(JNIEnv *env, jclass clazz,
                                                    jint p_type) {
    // AspectRatio values: 0=Stretch, 1=Auto 4:3/3:2, 2=4:3, 3=16:9, 4=10:7
    const char* aspect_ratio_names[] = {
        "Stretch",
        "Auto 4:3/3:2", 
        "4:3",
        "16:9",
        "10:7"
    };
    
    if (p_type >= 0 && p_type < 5) {
        s_settings_interface.SetStringValue("EmuCore/GS", "AspectRatio", aspect_ratio_names[p_type]);
        
        // Apply settings immediately if emulation is running
        if (VMManager::HasValidVM()) {
            VMManager::ApplySettings();
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_speedhackLimitermode(JNIEnv *env, jclass clazz,
                                                          jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_speedhackEecyclerate(JNIEnv *env, jclass clazz,
                                                          jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_speedhackEecycleskip(JNIEnv *env, jclass clazz,
                                                          jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_renderUpscalemultiplier(JNIEnv *env, jclass clazz,
                                                             jfloat p_value) {
    if (p_value < 1.0f) p_value = 1.0f;  // Ensure minimum 1x
    if (p_value > 12.0f) p_value = 12.0f; // Cap at maximum 12x
    
    s_settings_interface.SetFloatValue("EmuCore/GS", "upscale_multiplier", p_value);
    
    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setWidescreenPatches(JNIEnv *env, jclass clazz,
                                                          jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore", "EnableWideScreenPatches", p_enabled);
    
    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setNoInterlacingPatches(JNIEnv *env, jclass clazz,
                                                            jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore", "EnableNoInterlacingPatches", p_enabled);
    
    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setLoadTextures(JNIEnv *env, jclass clazz,
                                                   jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore/GS", "LoadTextureReplacements", p_enabled);
    
    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_reloadTextureReplacements(JNIEnv*, jclass) {
    if (!VMManager::HasValidVM() || !MTGS::IsOpen())
        return;

    MTGS::RunOnGSThread([]() {
        if (!g_gs_renderer || !GSConfig.LoadTextureReplacements)
            return;

        GSTextureReplacements::ReloadReplacementMap();
        g_gs_renderer->PurgeTextureCache(true, false, true);
    });
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setAsyncTextureLoading(JNIEnv *env, jclass clazz,
                                                          jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore/GS", "LoadTextureReplacementsAsync", p_enabled);
    
    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setPrecacheTextureReplacements(JNIEnv *env, jclass clazz,
                                                                 jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore/GS", "PrecacheTextureReplacements", p_enabled);

    // Apply the settings immediately if emulation is running
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setShadeBoost(JNIEnv *env, jclass clazz,
                                                 jboolean p_enabled) {
    s_settings_interface.SetBoolValue("EmuCore/GS", "ShadeBoost", p_enabled);
    
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setShadeBoostBrightness(JNIEnv *env, jclass clazz,
                                                           jint p_brightness) {
    int brightness = std::max(1, std::min(100, (int)p_brightness));
    s_settings_interface.SetIntValue("EmuCore/GS", "ShadeBoost_Brightness", brightness);
    
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setShadeBoostContrast(JNIEnv *env, jclass clazz,
                                                         jint p_contrast) {
    int contrast = std::max(1, std::min(100, (int)p_contrast));
    s_settings_interface.SetIntValue("EmuCore/GS", "ShadeBoost_Contrast", contrast);
    
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setShadeBoostSaturation(JNIEnv *env, jclass clazz,
                                                           jint p_saturation) {
    int saturation = std::max(1, std::min(100, (int)p_saturation));
    s_settings_interface.SetIntValue("EmuCore/GS", "ShadeBoost_Saturation", saturation);
    
    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setEdgeCrop(JNIEnv *env, jclass clazz,
                                               jint p_pixels) {
    // Trims junk columns the PS2 left at the edges of the visible framebuffer, which
    // a CRT's overscan hid. Cropped in native PS2 pixels, applied symmetrically so the
    // picture stays centred; the core rescales the remaining area to fill the display.
    const int pixels = std::max(0, std::min(32, (int)p_pixels));
    s_settings_interface.SetIntValue("EmuCore/GS", "CropLeft", pixels);
    s_settings_interface.SetIntValue("EmuCore/GS", "CropRight", pixels);

    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setAudioOutputDevice(JNIEnv *env, jclass clazz,
                                                       jint p_device_id) {
    // Pins game audio to a specific AAudio output device. Needed because a USB
    // controller that exposes an audio interface (e.g. the Amazon Luna pad) makes
    // Android route everything to the controller's headphone jack. A non-positive id
    // means "no preference", restoring stock system routing.
    //
    // The id rides in SPU2/Output DeviceName because SPU2 already recreates the output
    // stream when that value changes, so a switch takes effect while a game is running.
    if (p_device_id > 0) {
        s_settings_interface.SetStringValue("SPU2/Output", "DeviceName",
            StringUtil::StdStringFromFormat("%d", (int)p_device_id).c_str());
    } else {
        s_settings_interface.SetStringValue("SPU2/Output", "DeviceName", "");
    }

    if (VMManager::HasValidVM()) {
        VMManager::ApplySettings();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_saveGameSettings(JNIEnv *env, jclass clazz, jstring p_filename, 
                                                     jint p_blending_accuracy, jint p_renderer, 
                                                     jint p_resolution, jboolean p_widescreen_patches,
                                                     jboolean p_no_interlacing_patches, jboolean p_enable_patches,
                                                     jboolean p_enable_cheats)
{
    if (!p_filename)
        return;

    const char* filename_chars = env->GetStringUTFChars(p_filename, nullptr);
    if (!filename_chars)
        return;

    // Use DataRoot directly for game settings to ensure write permissions
    std::string settings_dir = Path::Combine(EmuFolders::DataRoot, "gamesettings");
    std::string settings_path = Path::Combine(settings_dir, filename_chars);
    env->ReleaseStringUTFChars(p_filename, filename_chars);

    // Debug logging
    printf("PCSX2: Saving game settings to: %s\n", settings_path.c_str());
    printf("PCSX2: Settings directory: %s\n", settings_dir.c_str());
    printf("PCSX2: Blending: %d, Renderer: %d, Resolution: %d\n", p_blending_accuracy, p_renderer, p_resolution);
    printf("PCSX2: Widescreen: %d, NoInterlacing: %d, Patches: %d, Cheats: %d\n", 
           p_widescreen_patches, p_no_interlacing_patches, p_enable_patches, p_enable_cheats);

    // Ensure directory exists
    FileSystem::CreateDirectoryPath(settings_dir.c_str(), false);

    // Build and write INI content directly to avoid any ambiguous formatting
    const char* renderers[] = {"Auto", "Vulkan", "OpenGL", "Software"};

    std::string ini;
    ini.reserve(512);
    ini += "[EmuCore/GS]\n";
    // Renderer
    if (p_renderer >= 0 && p_renderer < 4)
        ini += std::string("Renderer=") + renderers[p_renderer] + "\n";
    // Resolution scale (1..8)
    if (p_resolution >= 0 && p_resolution <= 7)
    {
        float multiplier = 1.0f + (float)p_resolution;
        ini += "upscale_multiplier=" + StringUtil::StdStringFromFormat("%.2f", multiplier) + "\n";
    }
    // Blending accuracy as numeric (0..5)
    if (p_blending_accuracy >= 0 && p_blending_accuracy < 6)
        ini += std::string("accurate_blending_unit=") + StringUtil::StdStringFromFormat("%d", p_blending_accuracy) + "\n";

    ini += "\n[EmuCore]\n";
    ini += std::string("EnableWideScreenPatches=") + (p_widescreen_patches ? "true" : "false") + "\n";
    ini += std::string("EnableNoInterlacingPatches=") + (p_no_interlacing_patches ? "true" : "false") + "\n";
    ini += std::string("EnablePatches=") + (p_enable_patches ? "true" : "false") + "\n";
    ini += std::string("EnableCheats=") + (p_enable_cheats ? "true" : "false") + "\n";

    const bool ok = FileSystem::WriteStringToFile(settings_path.c_str(), ini);
    printf("PCSX2: Settings write %s: %s\n", ok ? "OK" : "FAILED", settings_path.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_saveGameSettingsToPath(JNIEnv *env, jclass clazz, jstring p_full_path, 
                                                           jint p_blending_accuracy, jint p_renderer, 
                                                           jint p_resolution, jboolean p_widescreen_patches,
                                                           jboolean p_no_interlacing_patches, jboolean p_enable_patches,
                                                           jboolean p_enable_cheats)
{
    if (!p_full_path)
        return;

    const char* path_chars = env->GetStringUTFChars(p_full_path, nullptr);
    if (!path_chars)
        return;

    std::string settings_path(path_chars);
    env->ReleaseStringUTFChars(p_full_path, path_chars);

    // Debug logging
    printf("PCSX2: Saving game settings to full path: %s\n", settings_path.c_str());
    printf("PCSX2: Blending: %d, Renderer: %d, Resolution: %d\n", p_blending_accuracy, p_renderer, p_resolution);
    printf("PCSX2: Widescreen: %d, NoInterlacing: %d, Patches: %d, Cheats: %d\n", 
           p_widescreen_patches, p_no_interlacing_patches, p_enable_patches, p_enable_cheats);

    // Ensure parent directory exists
    std::string parent_dir(Path::GetDirectory(settings_path));
    printf("PCSX2: Parent directory: %s\n", parent_dir.c_str());
    bool dir_created = FileSystem::CreateDirectoryPath(parent_dir.c_str(), false);
    printf("PCSX2: Directory creation result: %s\n", dir_created ? "SUCCESS" : "FAILED");

    // Check if we can write to the directory
    bool can_write = FileSystem::DirectoryExists(parent_dir.c_str());
    printf("PCSX2: Directory exists: %s\n", can_write ? "YES" : "NO");

    INISettingsInterface game_settings(settings_path);
    
    // Blending accuracy (0=Minimum, 1=Basic, 2=Medium, 3=High, 4=Full, 5=Maximum)
    const char* blend_levels[] = {"Minimum", "Basic", "Medium", "High", "Full", "Maximum"};
    if (p_blending_accuracy >= 0 && p_blending_accuracy < 6) {
        game_settings.SetStringValue("EmuCore/GS", "accurate_blending_unit", blend_levels[p_blending_accuracy]);
    }

    // Renderer (0=Auto, 1=Vulkan, 2=OpenGL, 3=Software)
    const char* renderers[] = {"Auto", "Vulkan", "OpenGL", "Software"};
    if (p_renderer >= 0 && p_renderer < 4) {
        game_settings.SetStringValue("EmuCore/GS", "Renderer", renderers[p_renderer]);
    }

    // Resolution multiplier (same as global scale entries)
    if (p_resolution >= 0 && p_resolution <= 7) {
        float multiplier = 1.0f + (float)p_resolution;
        game_settings.SetFloatValue("EmuCore/GS", "upscale_multiplier", multiplier);
    }

    // Patches
    game_settings.SetBoolValue("EmuCore", "EnableWideScreenPatches", p_widescreen_patches);
    game_settings.SetBoolValue("EmuCore", "EnableNoInterlacingPatches", p_no_interlacing_patches);
    game_settings.SetBoolValue("EmuCore", "EnablePatches", p_enable_patches);
    game_settings.SetBoolValue("EmuCore", "EnableCheats", p_enable_cheats);

    // Test basic file write first
    std::FILE* test_file = std::fopen(settings_path.c_str(), "w");
    if (test_file) {
        fprintf(test_file, "# Test file write\n");
        std::fclose(test_file);
        printf("PCSX2: Basic file write test: SUCCESS\n");
    } else {
        printf("PCSX2: Basic file write test: FAILED - errno: %d\n", errno);
        return;
    }

    bool save_result = game_settings.Save();
    printf("PCSX2: Settings save result: %s\n", save_result ? "SUCCESS" : "FAILED");
    
    // Check if file actually exists and has content
    if (FileSystem::FileExists(settings_path.c_str())) {
        s64 file_size = FileSystem::GetPathFileSize(settings_path.c_str());
        printf("PCSX2: File exists with size: %lld bytes\n", static_cast<long long>(file_size));
    } else {
        printf("PCSX2: File does not exist after save attempt\n");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_deleteGameSettings(JNIEnv *env, jclass clazz, jstring p_filename)
{
    if (!p_filename)
        return;

    const char* filename_chars = env->GetStringUTFChars(p_filename, nullptr);
    if (!filename_chars)
        return;

    // Use DataRoot directly for game settings to ensure write permissions
    std::string settings_dir = Path::Combine(EmuFolders::DataRoot, "gamesettings");
    std::string settings_path = Path::Combine(settings_dir, filename_chars);
    env->ReleaseStringUTFChars(p_filename, filename_chars);

    if (FileSystem::FileExists(settings_path.c_str())) {
        FileSystem::DeleteFilePath(settings_path.c_str());
    }
}



extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_renderMipmap(JNIEnv *env, jclass clazz,
                                                  jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_renderHalfpixeloffset(JNIEnv *env, jclass clazz,
                                                           jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_renderPreloading(JNIEnv *env, jclass clazz,
                                                      jint p_value) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_renderGpu(JNIEnv *env, jclass clazz,
                                               jint p_value) {
    // Accept -1(Auto), 12(OpenGL), 13(Software), 14(Vulkan)
    if (p_value != -1 && p_value != 12 && p_value != 13 && p_value != 14)
    {
        return;
    }
    p_value = NormalizeAndroidRenderer(p_value);

    // Persist to base settings and apply immediately if possible
    s_settings_interface.SetIntValue("EmuCore/GS", "Renderer", (int)p_value);
    EmuConfig.GS.Renderer = static_cast<GSRendererType>(p_value);
    if (MTGS::IsOpen())
        MTGS::ApplySettings();
}

// Apply a set of global settings in one shot to avoid repeated ApplySettings calls
extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_applyGlobalSettingsBatch(JNIEnv* env, jclass,
                                                            jint renderer,
                                                            jfloat upscaleMultiplier,
                                                            jint aspectRatio,
                                                            jint blendingAccuracy,
                                                            jboolean widescreenPatches,
                                                            jboolean noInterlacingPatches,
                                                            jboolean loadTextures,
                                                            jboolean asyncTextureLoading,
                                                            jboolean vsyncEnabled,
                                                            jboolean hudVisible)
{
	renderer = NormalizeAndroidRenderer(renderer);
    // Clamp/normalize
    if (upscaleMultiplier < 1.0f) upscaleMultiplier = 1.0f;
    if (upscaleMultiplier > 12.0f) upscaleMultiplier = 12.0f;
    if (blendingAccuracy < 0) blendingAccuracy = 0; if (blendingAccuracy > 5) blendingAccuracy = 5;
    if (aspectRatio < 0) aspectRatio = 0; if (aspectRatio > 4) aspectRatio = 4; // 0..4 valid

    // Update in-memory settings layer
    // Renderer may be -1 (Auto) or 12/13/14; store and set into EmuConfig for immediate effect
    s_settings_interface.SetIntValue("EmuCore/GS", "Renderer", (int)renderer);
    EmuConfig.GS.Renderer = static_cast<GSRendererType>(renderer);

    s_settings_interface.SetFloatValue("EmuCore/GS", "upscale_multiplier", upscaleMultiplier);

    // Aspect ratio as string per existing helpers
    const char* aspect_ratio_names[] = { "Stretch", "Auto 4:3/3:2", "4:3", "16:9", "10:7" };
    s_settings_interface.SetStringValue("EmuCore/GS", "AspectRatio", aspect_ratio_names[aspectRatio]);

    // Blending accuracy numeric string 0..5
    s_settings_interface.SetStringValue("EmuCore/GS", "accurate_blending_unit",
        StringUtil::StdStringFromFormat("%d", (int)blendingAccuracy).c_str());

    // Widescreen, interlacing, textures
    s_settings_interface.SetBoolValue("EmuCore", "EnableWideScreenPatches", (widescreenPatches == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore", "EnableNoInterlacingPatches", (noInterlacingPatches == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore/GS", "LoadTextureReplacements", (loadTextures == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore/GS", "LoadTextureReplacementsAsync", (asyncTextureLoading == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore/GS", "VsyncEnable", (vsyncEnabled == JNI_TRUE));

    // HUD/OSD bundle
    const bool hv = (hudVisible == JNI_TRUE);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowSpeed", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowFPS", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowVPS", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowCPU", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowGPU", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowResolution", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowGSStats", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowIndicators", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowSettings", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowInputs", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowFrameTimes", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowVersion", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowHardwareInfo", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowVideoCapture", hv);
    s_settings_interface.SetBoolValue("EmuCore/GS", "OsdShowInputRec", hv);

    // Apply once
    if (VMManager::HasValidVM())
        VMManager::ApplySettings();
    if (MTGS::IsOpen())
        MTGS::ApplySettings();
}

// Apply per-game settings quickly without touching global-only fields
extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_applyPerGameSettingsBatch(JNIEnv* env, jclass,
                                                             jint renderer,
                                                             jfloat upscaleMultiplier,
                                                             jint blendingAccuracy,
                                                             jboolean widescreenPatches,
                                                             jboolean noInterlacingPatches,
                                                             jboolean enablePatches,
                                                             jboolean enableCheats)
{
	renderer = NormalizeAndroidRenderer(renderer);
    if (upscaleMultiplier < 1.0f) upscaleMultiplier = 1.0f;
    if (upscaleMultiplier > 12.0f) upscaleMultiplier = 12.0f;
    if (blendingAccuracy < 0) blendingAccuracy = 0; if (blendingAccuracy > 5) blendingAccuracy = 5;

    // Renderer (allow -1/12/13/14)
    s_settings_interface.SetIntValue("EmuCore/GS", "Renderer", (int)renderer);
    EmuConfig.GS.Renderer = static_cast<GSRendererType>(renderer);

    // Core per-game options
    s_settings_interface.SetFloatValue("EmuCore/GS", "upscale_multiplier", upscaleMultiplier);
    s_settings_interface.SetStringValue("EmuCore/GS", "accurate_blending_unit",
        StringUtil::StdStringFromFormat("%d", (int)blendingAccuracy).c_str());
    s_settings_interface.SetBoolValue("EmuCore", "EnableWideScreenPatches", (widescreenPatches == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore", "EnableNoInterlacingPatches", (noInterlacingPatches == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore", "EnablePatches", (enablePatches == JNI_TRUE));
    s_settings_interface.SetBoolValue("EmuCore", "EnableCheats", (enableCheats == JNI_TRUE));

    // Apply once
    if (VMManager::HasValidVM())
        VMManager::ApplySettings();
    if (MTGS::IsOpen())
        MTGS::ApplySettings();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_onNativeSurfaceCreated(JNIEnv *env, jclass clazz) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_onNativeSurfaceChanged(JNIEnv *env, jclass clazz,
                                                            jobject p_surface, jint p_width, jint p_height) {
    ANativeWindow* new_window = nullptr;
    if(p_surface != nullptr) {
        new_window = ANativeWindow_fromSurface(env, p_surface);
    }

    ANativeWindow* old_window = nullptr;
    {
        std::lock_guard lock(s_window_mutex);
        old_window = s_window;
        s_window = new_window;
        s_window_width = (new_window && p_width > 0) ? p_width : 0;
        s_window_height = (new_window && p_height > 0) ? p_height : 0;
    }

    if(old_window) {
        ANativeWindow_release(old_window);
    }

    if(MTGS::IsOpen()) {
        MTGS::UpdateDisplayWindow();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_onNativeSurfaceDestroyed(JNIEnv *env, jclass clazz) {
    ANativeWindow* old_window = nullptr;
    {
        std::lock_guard lock(s_window_mutex);
        old_window = s_window;
        s_window = nullptr;
        s_window_width = 0;
        s_window_height = 0;
    }

    if(old_window) {
        ANativeWindow_release(old_window);
    }
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_izzy2lost_psx2_NativeApp_getCurrentRenderer(JNIEnv*, jclass)
{
    return static_cast<jint>(EmuConfig.GS.Renderer);
}


std::optional<WindowInfo> Host::AcquireRenderWindow(bool recreate_window)
{
    ANativeWindow* window = nullptr;
    ANativeWindow* previous_render_window = nullptr;
    int window_width = 0;
    int window_height = 0;

    {
        std::lock_guard lock(s_window_mutex);
        if(!s_window || s_window_width <= 0 || s_window_height <= 0) {
            previous_render_window = s_render_window;
            s_render_window = nullptr;
        } else {
            window = s_window;
            ANativeWindow_acquire(window);
            previous_render_window = s_render_window;
            s_render_window = window;
            window_width = s_window_width;
            window_height = s_window_height;
        }
    }

    if(previous_render_window) {
        ANativeWindow_release(previous_render_window);
    }

    if(!window) {
        WindowInfo _windowInfo;
        memset(&_windowInfo, 0, sizeof(_windowInfo));
        _windowInfo.type = WindowInfo::Type::Surfaceless;
        _windowInfo.surface_scale = 1.0f;
        return _windowInfo;
    }

    float _fScale = 1.0;
    if (window_width > 0 && window_height > 0) {
        int _nSize = window_width;
        if (window_width <= window_height) {
            _nSize = window_height;
        }
        _fScale = (float)_nSize / 800.0f;
    }
    ////
    WindowInfo _windowInfo;
    memset(&_windowInfo, 0, sizeof(_windowInfo));
    _windowInfo.type = WindowInfo::Type::Android;
    _windowInfo.surface_width = window_width;
    _windowInfo.surface_height = window_height;
    _windowInfo.surface_scale = _fScale;
    _windowInfo.window_handle = window;

    return _windowInfo;
}

void Host::ReleaseRenderWindow()
{
    ANativeWindow* window = nullptr;
    {
        std::lock_guard lock(s_window_mutex);
        window = s_render_window;
        s_render_window = nullptr;
    }

    if(window) {
        ANativeWindow_release(window);
    }

}

static s32 s_loop_count = 1;

// Owned by the GS thread.
static u32 s_dump_frame_number = 0;
static u32 s_loop_number = s_loop_count;
static double s_last_internal_draws = 0;
static double s_last_draws = 0;
static double s_last_render_passes = 0;
static double s_last_barriers = 0;
static double s_last_copies = 0;
static double s_last_uploads = 0;
static double s_last_readbacks = 0;
static u64 s_total_internal_draws = 0;
static u64 s_total_draws = 0;
static u64 s_total_render_passes = 0;
static u64 s_total_barriers = 0;
static u64 s_total_copies = 0;
static u64 s_total_uploads = 0;
static u64 s_total_readbacks = 0;
static u32 s_total_frames = 0;
static u32 s_total_drawn_frames = 0;

void Host::BeginPresentFrame() {
    if (GSIsHardwareRenderer())
    {
        const u32 last_draws = s_total_internal_draws;
        const u32 last_uploads = s_total_uploads;

        static constexpr auto update_stat = [](GSPerfMon::counter_t counter, u64& dst, double& last) {
            // perfmon resets every 30 frames to zero
            const double val = g_perfmon.GetCounter(counter);
            dst += static_cast<u64>((val < last) ? val : (val - last));
            last = val;
        };

        update_stat(GSPerfMon::Draw, s_total_internal_draws, s_last_internal_draws);
        update_stat(GSPerfMon::DrawCalls, s_total_draws, s_last_draws);
        update_stat(GSPerfMon::RenderPasses, s_total_render_passes, s_last_render_passes);
        update_stat(GSPerfMon::Barriers, s_total_barriers, s_last_barriers);
        update_stat(GSPerfMon::TextureCopies, s_total_copies, s_last_copies);
        update_stat(GSPerfMon::TextureUploads, s_total_uploads, s_last_uploads);
        update_stat(GSPerfMon::Readbacks, s_total_readbacks, s_last_readbacks);

        const bool idle_frame = s_total_frames && (last_draws == s_total_internal_draws && last_uploads == s_total_uploads);

        if (!idle_frame)
            s_total_drawn_frames++;

        s_total_frames++;

        std::atomic_thread_fence(std::memory_order_release);
    }
}

void Host::OnGameChanged(const std::string& title, const std::string& elf_override, const std::string& disc_path,
                         const std::string& disc_serial, u32 disc_crc, u32 current_crc) {
}

void Host::PumpMessagesOnCPUThread() {
    std::deque<TouchscreenPointerUpdate> updates;
    {
        std::lock_guard lock(s_touchscreen_pointer_mutex);
        updates.swap(s_touchscreen_pointer_updates);
    }

    const JVS_MODE mode = ACJV::GetMode();
    if (ACJV::GetGameId().empty() || (mode != JVS_MODE::LIGHTGUN && mode != JVS_MODE::TOUCH))
        return;

    // Arcade light-gun manifests attach GunCon2 on USB1. Its trigger binding
    // also forwards the correct per-game trigger bit to the JVS board.
    static constexpr u32 GUNCON2_TRIGGER_BIND = 13;
    for (const TouchscreenPointerUpdate& update : updates)
    {
        // Android sends coordinates normalized to its SurfaceView. Convert to
        // the current native presentation size here so display-resolution and
        // orientation changes cannot offset the gun aim.
        const float window_x = update.x * static_cast<float>(ImGuiManager::GetWindowWidth());
        const float window_y = update.y * static_cast<float>(ImGuiManager::GetWindowHeight());
        InputManager::UpdatePointerAbsolutePosition(0, window_x, window_y);
        InputManager::InvokeEvents(InputManager::MakePointerButtonKey(0, 0), update.pressed ? 1.0f : 0.0f);

        if (mode == JVS_MODE::LIGHTGUN)
        {
            // GunCon2 A is the cabinet pedal for Time Crisis/Cobra. Raise the
            // pedal before pulling the trigger, and release the trigger before
            // returning to cover. Games without a pedal retain normal trigger-
            // only touchscreen behavior (notably Vampire Night).
            static constexpr u32 GUNCON2_PEDAL_BIND = 3;
            const float value = update.pressed ? 1.0f : 0.0f;
            const bool has_pedal = (ACJV::GetGunMapping().pedal != 0);
            if (update.pressed && has_pedal)
                USB::SetDeviceBindValue(0, GUNCON2_PEDAL_BIND, value);
            USB::SetDeviceBindValue(0, GUNCON2_TRIGGER_BIND, value);
            if (!update.pressed && has_pedal)
                USB::SetDeviceBindValue(0, GUNCON2_PEDAL_BIND, value);
        }
    }
}

int FileSystem::OpenFDFileContent(const char* filename)
{
    auto *env = static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv());
    if(env == nullptr) {
        return -1;
    }
    jclass NativeApp = env->FindClass("com/izzy2lost/psx2/NativeApp");
    jmethodID openContentUri = env->GetStaticMethodID(NativeApp, "openContentUri", "(Ljava/lang/String;)I");

    jstring j_filename = env->NewStringUTF(filename);
    int fd = env->CallStaticIntMethod(NativeApp, openContentUri, j_filename);
    return fd;
}

#ifdef __ANDROID__
// Helpers callable from core for SAF bridging
static jclass GetNativeAppClass(JNIEnv* env)
{
    return env->FindClass("com/izzy2lost/psx2/NativeApp");
}

std::string ResolveSafPathUriJNI(const char* relative_path, bool create)
{
    JNIEnv* env = reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env)
        return {};
    jclass cls = GetNativeAppClass(env);
    if (!cls)
        return {};
    jmethodID mid = env->GetStaticMethodID(cls, "resolveSafPathUri", "(Ljava/lang/String;Z)Ljava/lang/String;");
    if (!mid)
        return {};
    jstring jrel = env->NewStringUTF(relative_path);
    jobject juri = env->CallStaticObjectMethod(cls, mid, jrel, (jboolean)create);
    env->DeleteLocalRef(jrel);
    if (!juri)
        return {};
    const char* cstr = env->GetStringUTFChars((jstring)juri, nullptr);
    std::string out = cstr ? std::string(cstr) : std::string();
    if (cstr)
        env->ReleaseStringUTFChars((jstring)juri, cstr);
    env->DeleteLocalRef(juri);
    return out;
}

std::string ResolveArcadeAssetUriJNI(const char* manifest_uri, const char* relative_path)
{
    JNIEnv* env = reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env)
        return {};
    jclass cls = GetNativeAppClass(env);
    if (!cls)
        return {};
    jmethodID mid = env->GetStaticMethodID(cls, "resolveArcadeAssetUri",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (!mid)
        return {};

    jstring jmanifest = env->NewStringUTF(manifest_uri);
    jstring jrelative = env->NewStringUTF(relative_path);
    jstring jresult = static_cast<jstring>(
        env->CallStaticObjectMethod(cls, mid, jmanifest, jrelative));
    env->DeleteLocalRef(jmanifest);
    env->DeleteLocalRef(jrelative);
    if (!jresult)
        return {};

    const char* chars = env->GetStringUTFChars(jresult, nullptr);
    std::string result = chars ? chars : "";
    if (chars)
        env->ReleaseStringUTFChars(jresult, chars);
    env->DeleteLocalRef(jresult);
    return result;
}

std::vector<std::string> SafListRecursiveFilesJNI(const char* relative_dir)
{
    std::vector<std::string> out;
    JNIEnv* env = reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env)
        return out;
    jclass cls = GetNativeAppClass(env);
    if (!cls)
        return out;
    jmethodID mid = env->GetStaticMethodID(cls, "listSafRecursiveFiles", "(Ljava/lang/String;)[Ljava/lang/String;");
    if (!mid)
        return out;
    jstring jarg = env->NewStringUTF(relative_dir);
    jobjectArray arr = (jobjectArray)env->CallStaticObjectMethod(cls, mid, jarg);
    env->DeleteLocalRef(jarg);
    if (!arr)
        return out;
    jsize len = env->GetArrayLength(arr);
    out.reserve((size_t)len);
    for (jsize i = 0; i < len; i++)
    {
        jstring js = (jstring)env->GetObjectArrayElement(arr, i);
        if (!js) continue;
        const char* c = env->GetStringUTFChars(js, nullptr);
        if (c)
        {
            out.emplace_back(c);
            env->ReleaseStringUTFChars(js, c);
        }
        env->DeleteLocalRef(js);
    }
    env->DeleteLocalRef(arr);
    return out;
}

std::vector<std::string> SafListFilesFlatJNI(const char* relative_dir)
{
    std::vector<std::string> out;
    JNIEnv* env = reinterpret_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env)
        return out;
    jclass cls = GetNativeAppClass(env);
    if (!cls)
        return out;
    jmethodID mid = env->GetStaticMethodID(cls, "listSafFilesFlat", "(Ljava/lang/String;)[Ljava/lang/String;");
    if (!mid)
        return out;
    jstring jarg = env->NewStringUTF(relative_dir);
    jobjectArray arr = (jobjectArray)env->CallStaticObjectMethod(cls, mid, jarg);
    env->DeleteLocalRef(jarg);
    if (!arr)
        return out;
    jsize len = env->GetArrayLength(arr);
    out.reserve((size_t)len);
    for (jsize i = 0; i < len; i++)
    {
        jstring js = (jstring)env->GetObjectArrayElement(arr, i);
        if (!js) continue;
        const char* c = env->GetStringUTFChars(js, nullptr);
        if (c)
        {
            out.emplace_back(c);
            env->ReleaseStringUTFChars(js, c);
        }
        env->DeleteLocalRef(js);
    }
    env->DeleteLocalRef(arr);
    return out;
}
#endif // __ANDROID__

int FileSystem::OpenFDFileContentWithMode(const char* filename, const char* mode)
{
    auto *env = static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv());
    if(env == nullptr) {
        return -1;
    }
    jclass NativeApp = env->FindClass("com/izzy2lost/psx2/NativeApp");
    jmethodID openContentUriMode = env->GetStaticMethodID(NativeApp, "openContentUriMode", "(Ljava/lang/String;Ljava/lang/String;)I");
    jstring j_filename = env->NewStringUTF(filename);
    jstring j_mode = env->NewStringUTF(mode);
    int fd = env->CallStaticIntMethod(NativeApp, openContentUriMode, j_filename, j_mode);
    return fd;
}

std::string ResolveSafChildUriJNI(const char* subdir, const char* filename, bool create)
{
    auto *env = static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv());
    if(env == nullptr) return {};
    jclass NativeApp = env->FindClass("com/izzy2lost/psx2/NativeApp");
    jmethodID resolve = env->GetStaticMethodID(NativeApp, "resolveSafChildUri", "(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;");
    jstring j_sub = env->NewStringUTF(subdir);
    jstring j_file = env->NewStringUTF(filename);
    jstring j_uri = (jstring)env->CallStaticObjectMethod(NativeApp, resolve, j_sub, j_file, (jboolean)create);
    if (!j_uri) return {};
    const char* cstr = env->GetStringUTFChars(j_uri, nullptr);
    std::string ret(cstr);
    env->ReleaseStringUTFChars(j_uri, cstr);
    env->DeleteLocalRef(j_uri);
    return ret;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_prepareVMStart(JNIEnv *env, jclass clazz) {
    // Invalidate any stop request that has not been carried out yet, so it can
    // never land on the VM this call is about to start.
    s_vm_start_generation.fetch_add(1, std::memory_order_acq_rel);
    s_shutdown_requested.store(false, std::memory_order_release);
    ClearTouchscreenPointerUpdates();
    std::lock_guard error_lock(s_vm_error_mutex);
    s_last_vm_error.clear();
}

// Custom Vulkan driver pin. Called from MainActivity.startEmuThread() BEFORE the
// VM starts so the first MTGS::Open (which triggers Vulkan::LoadVulkanLibrary)
// picks up the custom driver. Empty strings revert to the system loader.
// See Vulkan::SetCustomDriverPath in VKLoader.cpp for the splice.
extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setCustomVulkanDriver(
    JNIEnv* env, jclass clazz,
    jstring driverDir, jstring driverName,
    jstring redirectDir, jstring hookLibDir) {
#if defined(__ANDROID__)
    const std::string dir   = GetJavaString(env, driverDir);
    const std::string name  = GetJavaString(env, driverName);
    const std::string redir = GetJavaString(env, redirectDir);
    const std::string hook  = GetJavaString(env, hookLibDir);
    Vulkan::SetCustomDriverPath(
        dir.c_str(), name.c_str(), redir.c_str(), hook.c_str());
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getLastVMError(JNIEnv *env, jclass clazz) {
    std::lock_guard error_lock(s_vm_error_mutex);
    return env->NewStringUTF(s_last_vm_error.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_setVerifiedBiosFiles(JNIEnv *env, jclass clazz,
                                                       jstring p_usa_bios,
                                                       jstring p_europe_bios,
                                                       jstring p_japan_bios,
                                                       jstring p_arcade_bios) {
    s_verified_bios_usa = GetJavaString(env, p_usa_bios);
    s_verified_bios_europe = GetJavaString(env, p_europe_bios);
    s_verified_bios_japan = GetJavaString(env, p_japan_bios);
    s_verified_bios_arcade = GetJavaString(env, p_arcade_bios);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_runVMThread(JNIEnv *env, jclass clazz,
                                                 jstring p_szpath) {
    std::string _szPath = GetJavaString(env, p_szpath);

    // Serialize with any previous VM thread that is still unwinding. If a VM is
    // actively running, refuse the duplicate start instead of blocking; if the
    // previous thread is merely finishing its teardown, wait for it to exit so
    // we never initialize globals (MTGS, SysMemory, GS device) concurrently
    // with their shutdown.
    std::unique_lock lifecycle_lock(s_vm_lifecycle_mutex, std::defer_lock);
    if (!lifecycle_lock.try_lock()) {
        const VMState st = VMManager::GetState();
        if (st != VMState::Shutdown && st != VMState::Stopping) {
            Console.Warning("runVMThread ignored duplicate start while VM state is %d", static_cast<int>(st));
            return false;
        }
        Console.WriteLn("runVMThread waiting for previous VM thread to finish shutting down...");
        lifecycle_lock.lock();
    }

    std::unique_lock vm_start_lock(s_vm_start_mutex);

    VMState current_state = VMManager::GetState();
    if (current_state != VMState::Shutdown) {
        Console.Warning("runVMThread ignored duplicate start while VM state is %d", static_cast<int>(current_state));
        return false;
    }
    if (s_shutdown_requested.load(std::memory_order_acquire)) {
        Console.Warning("runVMThread ignored start because shutdown was requested before initialization");
        return false;
    }

    /////////////////////////////

    s_execute_exit = false;

//    const char* error;
//    if (!VMManager::PerformEarlyHardwareChecks(&error)) {
//        return false;
//    }

    // fast_boot : (false:bios->game, true:game)
    VMBootParameters boot_params;
    boot_params.filename = _szPath;
    
    // Enable fast boot when booting BIOS-only (no game loaded)
    // This skips the BIOS animation and goes straight to the PS2 menu
    Console.WriteLn("runVMThread: path='%s', length=%zu", _szPath.c_str(), _szPath.length());
    if (_szPath.empty()) {
        boot_params.fast_boot = true;
        Console.WriteLn("BIOS-only boot: Fast boot ENABLED to skip animation");
    } else {
        Console.WriteLn("Game boot: path=%s, fast_boot will use default behavior", _szPath.c_str());
    }

    // Detect the disc serial before VM startup so settings and BIOS region can follow the game.
    const std::string game_serial = GetGameSerialForPath(_szPath);
    SelectVerifiedBiosForSerial(game_serial, VMManager::isArcadeManifest(_szPath));

    // Apply per-game settings (if any) before applying core settings
    ApplyPerGameSettingsForSerial(game_serial);

    if (s_shutdown_requested.load(std::memory_order_acquire)) {
        Console.Warning("runVMThread cancelled before VM initialize because shutdown was requested");
        return false;
    }

    current_state = VMManager::GetState();
    if (current_state != VMState::Shutdown) {
        Console.Warning("runVMThread aborted before VM initialize because VM state changed to %d", static_cast<int>(current_state));
        return false;
    }

    if (!VMManager::Internal::CPUThreadInitialize()) {
        Console.Error("CPUThreadInitialize failed");
        VMManager::Internal::CPUThreadShutdown();
        return false;
    }

    VMManager::ApplySettings();
    GSDumpReplayer::SetIsDumpRunner(false);

    if (s_shutdown_requested.load(std::memory_order_acquire)) {
        Console.Warning("runVMThread cancelled after applying settings because shutdown was requested");
        VMManager::Internal::CPUThreadShutdown();
        return false;
    }

    current_state = VMManager::GetState();
    if (current_state != VMState::Shutdown) {
        Console.Warning("runVMThread aborted after applying settings because VM state changed to %d", static_cast<int>(current_state));
        VMManager::Internal::CPUThreadShutdown();
        return false;
    }

    Error startup_error;
    const bool initialized =
        (VMManager::Initialize(boot_params, &startup_error) == VMBootResult::StartupSuccess);
    if (!initialized && startup_error.IsValid())
    {
        Console.ErrorFmt("VM startup failed: {}", startup_error.GetDescription());
        std::lock_guard error_lock(s_vm_error_mutex);
        s_last_vm_error = startup_error.GetDescription();
    }
    vm_start_lock.unlock();

    if (initialized)
    {
        if (s_shutdown_requested.load(std::memory_order_acquire)) {
            Console.Warning("runVMThread shutting down immediately because shutdown was requested during startup");
            VMManager::Shutdown(false);
            VMManager::Internal::CPUThreadShutdown();
            return false;
        }

        // If a per-game renderer was requested, apply it now that VM is up.
        if (s_pending_renderer >= 0)
        {
            EmuConfig.GS.Renderer = static_cast<GSRendererType>(s_pending_renderer);
            s_pending_renderer = -1;
            if (MTGS::IsOpen())
                MTGS::ApplySettings();
        }
        VMState _vmState = VMState::Running;
        VMManager::SetState(_vmState);
        ////
        while (true) {
            _vmState = VMManager::GetState();
            if (_vmState == VMState::Stopping || _vmState == VMState::Shutdown) {
                break;
            } else if (_vmState == VMState::Running) {
                s_execute_exit = false;
                VMManager::Execute();
                s_execute_exit = true;
            } else {
                usleep(250000);
            }
        }
        ////
        VMManager::Shutdown(false);
    }
    ////
    VMManager::Internal::CPUThreadShutdown();

    return initialized;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_pause(JNIEnv *env, jclass clazz) {
    const uint64_t generation = s_vm_start_generation.load(std::memory_order_acquire);
    std::thread([generation] {
        // Don't let a pause meant for the previous game land on the new one.
        if (s_vm_start_generation.load(std::memory_order_acquire) != generation)
            return;
        VMManager::SetPaused(true);
    }).detach();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_resume(JNIEnv *env, jclass clazz) {
    const uint64_t generation = s_vm_start_generation.load(std::memory_order_acquire);
    std::thread([generation] {
        if (s_vm_start_generation.load(std::memory_order_acquire) != generation)
            return;
        VMManager::SetPaused(false);
    }).detach();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_isPaused(JNIEnv *env, jclass clazz) {
    return VMManager::GetState() == VMState::Paused;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_isVMActive(JNIEnv *env, jclass clazz) {
    return VMManager::GetState() != VMState::Shutdown;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_shutdown(JNIEnv *env, jclass clazz) {
    const uint64_t generation = s_vm_start_generation.load(std::memory_order_acquire);
    s_shutdown_requested.store(true, std::memory_order_release);
    std::thread([generation] {
        // A boot that started after this request was made owns the VM now.
        if (s_vm_start_generation.load(std::memory_order_acquire) != generation) {
            Console.WriteLn("shutdown dropped: a newer VM start superseded it");
            return;
        }
        const VMState state = VMManager::GetState();
        if (state == VMState::Running || state == VMState::Paused || state == VMState::Resetting) {
            VMManager::SetState(VMState::Stopping);
        } else if (state != VMState::Shutdown && state != VMState::Stopping) {
            Console.Warning("shutdown ignored while VM state is %d", static_cast<int>(state));
        }
    }).detach();
}


extern "C"
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_saveStateToSlot(JNIEnv *env, jclass clazz, jint p_slot) {
    if (!VMManager::HasValidVM()) {
        return false;
    }

    std::future<bool> ret = std::async([p_slot]
    {
       if(VMManager::GetDiscCRC() != 0) {
           if(VMManager::GetState() != VMState::Paused) {
               VMManager::SetPaused(true);
           }

           // wait 5 sec
           for (int i = 0; i < 5; ++i) {
               if (s_execute_exit) {
                   VMManager::SaveStateToSlot(p_slot, false, [](const std::string& error) {
                       if (!error.empty())
                           ERROR_LOG("Failed to save state: {}", error);
                   });
                   return true;
               }
               sleep(1);
           }
       }
       return false;

    });

    return ret.get();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_loadStateFromSlot(JNIEnv *env, jclass clazz, jint p_slot) {
    if (!VMManager::HasValidVM()) {
        return false;
    }

    std::future<bool> ret = std::async([p_slot]
    {
       u32 _crc = VMManager::GetDiscCRC();
       if(_crc != 0) {
           if (VMManager::HasSaveStateInSlot(VMManager::GetDiscSerial().c_str(), _crc, p_slot)) {
               if(VMManager::GetState() != VMState::Paused) {
                   VMManager::SetPaused(true);
               }

               // wait 5 sec
               for (int i = 0; i < 5; ++i) {
                   if (s_execute_exit) {
                       if(VMManager::LoadStateFromSlot(p_slot)) {
                           return true;
                       }
                       break;
                   }
                   sleep(1);
               }
           }
       }
       return false;
    });

    return ret.get();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_getGamePathSlot(JNIEnv *env, jclass clazz, jint p_slot) {
    std::string _filename = VMManager::GetSaveStateFileName(VMManager::GetDiscSerial().c_str(), VMManager::GetDiscCRC(), p_slot);
    if(!_filename.empty()) {
        return env->NewStringUTF(_filename.c_str());
    }
    return nullptr;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_izzy2lost_psx2_NativeApp_getImageSlot(JNIEnv *env, jclass clazz, jint p_slot) {
    jbyteArray retArr = nullptr;

    std::string _filename = VMManager::GetSaveStateFileName(VMManager::GetDiscSerial().c_str(), VMManager::GetDiscCRC(), p_slot);
    if(!_filename.empty())
    {
        zip_error_t ze = {};
        auto zf = zip_open_managed(_filename.c_str(), ZIP_RDONLY, &ze);
        if (zf) {
            auto zff = zip_fopen_managed(zf.get(), "Screenshot.png", 0);
            if(zff) {
                std::optional<std::vector<u8>> optdata(ReadBinaryFileInZip(zff.get()));
                if (optdata.has_value()) {
                    std::vector<u8> vec = std::move(optdata.value());
                    ////
                    auto length = static_cast<jsize>(vec.size());
                    retArr = env->NewByteArray(length);
                    if (retArr != nullptr) {
                        env->SetByteArrayRegion(retArr, 0, length,
                                                reinterpret_cast<const jbyte *>(vec.data()));
                    }
                }
            }
        }
    }

    return retArr;
}


void Host::CommitBaseSettingChanges()
{
    // Save achievements settings to Android SharedPreferences
    // This is called after login to persist the token
    
    auto lock = Host::GetSettingsLock();
    SettingsInterface* si = Host::GetSettingsInterface();
    if (!si)
    {
        __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "No settings interface available");
        return;
    }

    // Get achievements credentials from settings
    std::string username = si->GetStringValue("Achievements", "Username", "");
    std::string token = si->GetStringValue("Achievements", "Token", "");
    std::string loginTimestamp = si->GetStringValue("Achievements", "LoginTimestamp", "");

    if (username.empty() && token.empty())
    {
        // Nothing to save
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, "PCSX2", "Saving achievements credentials to SharedPreferences");
    
    // Call the Java method to save to SharedPreferences
    // We'll use JNI to call NativeApp.saveAchievementsCredentials()
    JavaVM* jvm = AchievementsJNI::GetJavaVM();
    if (!jvm)
    {
        __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "JavaVM not available");
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    
    // Get JNI environment
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        // Try to attach current thread
        if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK)
        {
            attached = true;
        }
        else
        {
            __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "Failed to attach thread to JVM");
            return;
        }
    }

    // Find the NativeApp class and saveAchievementsCredentials method
    jclass nativeAppClass = env->FindClass("com/izzy2lost/psx2/NativeApp");
    if (nativeAppClass)
    {
        jmethodID saveMethod = env->GetStaticMethodID(nativeAppClass, "saveAchievementsCredentials",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (saveMethod)
        {
            jstring jUsername = env->NewStringUTF(username.c_str());
            jstring jToken = env->NewStringUTF(token.c_str());
            jstring jTimestamp = env->NewStringUTF(loginTimestamp.c_str());
            
            env->CallStaticVoidMethod(nativeAppClass, saveMethod, jUsername, jToken, jTimestamp);
            
            env->DeleteLocalRef(jUsername);
            env->DeleteLocalRef(jToken);
            env->DeleteLocalRef(jTimestamp);
            
            __android_log_print(ANDROID_LOG_INFO, "PCSX2", "Achievements credentials saved successfully");
        }
        else
        {
            __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "Could not find saveAchievementsCredentials method");
            env->ExceptionClear();
        }
        env->DeleteLocalRef(nativeAppClass);
    }
    else
    {
        __android_log_print(ANDROID_LOG_ERROR, "PCSX2", "Could not find NativeApp class");
        env->ExceptionClear();
    }

    // Detach thread if we attached it
    if (attached)
    {
        jvm->DetachCurrentThread();
    }
}

void Host::LoadSettings(SettingsInterface& si, std::unique_lock<std::mutex>& lock)
{
}

void Host::CheckForSettingsChanges(const Pcsx2Config& old_config)
{
}

bool Host::RequestResetSettings(bool folders, bool core, bool controllers, bool hotkeys, bool ui)
{
    // not running any UI, so no settings requests will come in
    return false;
}

void Host::SetDefaultUISettings(SettingsInterface& si)
{
    // nothing
}

std::unique_ptr<ProgressCallback> Host::CreateHostProgressCallback()
{
    return nullptr;
}

void Host::ReportErrorAsync(const std::string_view title, const std::string_view message)
{
    if (!title.empty() && !message.empty())
        ERROR_LOG("ReportErrorAsync: {}: {}", title, message);
    else if (!message.empty())
        ERROR_LOG("ReportErrorAsync: {}", message);
}

void Host::OpenURL(const std::string_view url)
{
    // noop
}

std::string Host::GetTextFromClipboard()
{
    return {};
}

int Host::LocaleSensitiveCompare(std::string_view lhs, std::string_view rhs)
{
    const size_t length = std::min(lhs.size(), rhs.size());
    const int result = std::char_traits<char>::compare(lhs.data(), rhs.data(), length);
    if (result != 0)
        return result;
    return (lhs.size() > rhs.size()) - (lhs.size() < rhs.size());
}

bool Common::InhibitScreensaver(bool inhibit)
{
    return true;
}

bool Common::PlaySoundAsync(const char* path)
{
    AchievementsJNI::PlaySound(path);
    return true;
}

bool Host::CopyTextToClipboard(const std::string_view text)
{
    return false;
}

void Host::BeginTextInput()
{
    // noop
}

void Host::EndTextInput()
{
    // noop
}

std::optional<WindowInfo> Host::GetTopLevelWindowInfo()
{
    return std::nullopt;
}

void Host::OnInputDeviceConnected(const std::string_view identifier, const std::string_view device_name)
{
}

void Host::OnInputDeviceDisconnected(const InputBindingKey key, const std::string_view identifier)
{
}

void Host::SetMouseMode(bool relative_mode, bool hide_cursor)
{
}

void Host::RequestResizeHostDisplay(s32 width, s32 height)
{
}

void Host::OnVMStarting()
{
}

void Host::OnVMStarted()
{
}

void Host::OnVMDestroyed()
{
}

void Host::OnVMPaused()
{
}

void Host::OnVMResumed()
{
}

void Host::OnPerformanceMetricsUpdated()
{
}

void Host::OnSaveStateLoading(const std::string_view filename)
{
}

void Host::OnSaveStateLoaded(const std::string_view filename, bool was_successful)
{
}

void Host::OnSaveStateSaved(const std::string_view filename)
{
}

void Host::RunOnCPUThread(std::function<void()> function, bool block /* = false */)
{
    pxFailRel("Not implemented");
}

void Host::RefreshGameListAsync(bool invalidate_cache)
{
}

void Host::CancelGameListRefresh()
{
}

bool Host::IsFullscreen()
{
    return false;
}

void Host::SetFullscreen(bool enabled)
{
}

void Host::OnCaptureStarted(const std::string& filename)
{
}

void Host::OnCaptureStopped()
{
}

void Host::RequestExitApplication(bool allow_confirm)
{
}

void Host::RequestExitBigPicture()
{
}

void Host::RequestVMShutdown(bool allow_confirm, bool allow_save_state, bool default_save_state)
{
    VMManager::SetState(VMState::Stopping);
}

void Host::OnAchievementsLoginSuccess(const char* username, u32 points, u32 sc_points, u32 unread_messages)
{
    // noop
}

void Host::OnAchievementsLoginRequested(Achievements::LoginRequestReason reason)
{
    // noop
}

void Host::OnAchievementsHardcoreModeChanged(bool enabled)
{
    // noop
}

void Host::OnAchievementsRefreshed()
{
    // noop
}

void Host::OnCoverDownloaderOpenRequested()
{
    // noop
}

void Host::OnCreateMemoryCardOpenRequested()
{
    // noop
}

bool Host::ShouldPreferHostFileSelector()
{
    return false;
}

void Host::OpenHostFileSelectorAsync(std::string_view title, bool select_directory, FileSelectorCallback callback,
                                     FileSelectorFilters filters, std::string_view initial_directory)
{
    callback(std::string());
}

std::optional<u32> InputManager::ConvertHostKeyboardStringToCode(const std::string_view str)
{
    return std::nullopt;
}

std::optional<std::string> InputManager::ConvertHostKeyboardCodeToString(u32 code)
{
    return std::nullopt;
}

const char* InputManager::ConvertHostKeyboardCodeToIcon(u32 code)
{
    return nullptr;
}

s32 Host::Internal::GetTranslatedStringImpl(
        const std::string_view context, const std::string_view msg, char* tbuf, size_t tbuf_space)
{
    if (msg.size() > tbuf_space)
        return -1;
    else if (msg.empty())
        return 0;

    std::memcpy(tbuf, msg.data(), msg.size());
    return static_cast<s32>(msg.size());
}

std::string Host::TranslatePluralToString(const char* context, const char* msg, const char* disambiguation, int count)
{
    TinyString count_str = TinyString::from_format("{}", count);

    std::string ret(msg);
    for (;;)
    {
        std::string::size_type pos = ret.find("%n");
        if (pos == std::string::npos)
            break;

        ret.replace(pos, pos + 2, count_str.view());
    }

    return ret;
}

void Host::ReportInfoAsync(const std::string_view title, const std::string_view message)
{
}

bool Host::LocaleCircleConfirm()
{
    return false;
}

bool Host::InNoGUIMode()
{
    return false;
}

// JNI: report if a SAF Data Root is configured
bool HasSafDataRootJNI()
{
    JNIEnv* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env) return false;
    jclass cls = env->FindClass("com/izzy2lost/psx2/NativeApp");
    if (!cls) return false;
    jmethodID mid = env->GetStaticMethodID(cls, "hasSafDataRoot", "()Z");
    if (!mid) return false;
    jboolean res = env->CallStaticBooleanMethod(cls, mid);
    return (res == JNI_TRUE);
}
static std::vector<std::string> SafListFilesJNI(const char* subdir)
{
    std::vector<std::string> ret;
    JNIEnv* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if (!env) return ret;
    jclass cls = env->FindClass("com/izzy2lost/psx2/NativeApp");
    if (!cls) return ret;
    jmethodID mid = env->GetStaticMethodID(cls, "listSafFilenames", "(Ljava/lang/String;)[Ljava/lang/String;");
    if (!mid) return ret;
    jstring j_sub = env->NewStringUTF(subdir);
    jobjectArray arr = (jobjectArray)env->CallStaticObjectMethod(cls, mid, j_sub);
    env->DeleteLocalRef(j_sub);
    if (!arr) return ret;
    jsize n = env->GetArrayLength(arr);
    ret.reserve(n);
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arr, i);
        if (!s) continue;
        const char* cs = env->GetStringUTFChars(s, nullptr);
        if (cs) ret.emplace_back(cs);
        env->ReleaseStringUTFChars(s, cs);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(arr);
    return ret;
}

// Get list of saves on a memory card using PCSX2's native parsing
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_izzy2lost_psx2_NativeApp_getMemoryCardSaves(JNIEnv* env, jclass, jstring p_memcard_path)
{
    if (!p_memcard_path) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    const char* path_chars = env->GetStringUTFChars(p_memcard_path, nullptr);
    if (!path_chars) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    std::string memcard_path(path_chars);
    env->ReleaseStringUTFChars(p_memcard_path, path_chars);

    std::vector<std::string> saves;

    // Open the memory card file
    auto fp = FileSystem::OpenManagedCFile(memcard_path.c_str(), "rb");
    if (!fp) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // Read superblock to get root directory cluster
    u8 superblock[512];
    if (std::fread(superblock, 1, 512, fp.get()) != 512) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // Extract alloc_offset (at 0x34) and rootdir_cluster (at 0x3C)
    u32 alloc_offset = *(u32*)&superblock[0x34];
    u32 rootdir_cluster = *(u32*)&superblock[0x3C];

    // Calculate directory start position (each cluster is 1024 bytes)
    u64 dir_start = (u64)(alloc_offset + rootdir_cluster) * 1024;

    // Seek to directory
    if (FileSystem::FSeek64(fp.get(), dir_start, SEEK_SET) != 0) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    // Read directory entries (each entry is 512 bytes)
    for (int i = 0; i < 100; i++) {
        u8 entry[512];
        if (std::fread(entry, 1, 512, fp.get()) != 512) break;

        // Read mode (first 4 bytes)
        u32 mode = *(u32*)&entry[0];

        // Skip empty entries
        if (mode == 0 || mode == 0xFFFFFFFF) continue;

        // Check if used (0x8000 flag)
        if (!(mode & 0x8000)) continue;

        // Read filename (at offset 0x40, max 32 bytes)
        u8 name_bytes[32];
        std::memcpy(name_bytes, &entry[0x40], 32);

        // Convert to string, stopping at null terminator
        std::string name_str;
        for (int j = 0; j < 32; j++) {
            if (name_bytes[j] == 0) break;
            // Only include printable ASCII
            if (name_bytes[j] >= 32 && name_bytes[j] <= 126) {
                name_str += (char)name_bytes[j];
            }
        }

        // Skip "." and ".." entries
        if (name_str == "." || name_str == "..") continue;
        if (name_str.empty()) continue;

        // Read length field (at offset 0x04)
        u32 length = *(u32*)&entry[0x04];

        // Check if it's a directory (0x0020 flag)
        bool is_dir = (mode & 0x0020) != 0;

        // Basic sanity check - skip if length is suspiciously large
        if (length > 1000000000) continue; // 1 billion is clearly wrong

        // Format: "filename|size|isDirectory"
        std::string save_info = StringUtil::StdStringFromFormat("%s|%u|%d", 
            name_str.c_str(), length, is_dir ? 1 : 0);
        saves.push_back(save_info);
    }

    // Convert to Java string array
    jobjectArray result = env->NewObjectArray(saves.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < saves.size(); i++) {
        jstring str = env->NewStringUTF(saves[i].c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }

    return result;
}
