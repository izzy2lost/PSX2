// SPDX-FileCopyrightText: 2025 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#ifdef __ANDROID__

#include "AndroidDeviceDetection.h"
#include "common/Console.h"
#include <sys/system_properties.h>
#include <cstring>

namespace AndroidDeviceDetection
{
	static std::string GetSystemProperty(const char* key)
	{
		char value[PROP_VALUE_MAX] = {};
		if (__system_property_get(key, value) > 0)
			return std::string(value);
		return "";
	}

	std::string GetManufacturer()
	{
		return GetSystemProperty("ro.product.manufacturer");
	}

	std::string GetModel()
	{
		return GetSystemProperty("ro.product.model");
	}

	std::string GetGPURenderer()
	{
		// This would need to be called from GL/Vulkan context
		// For now, we rely on hardware detection
		return GetSystemProperty("ro.hardware");
	}

	bool IsMediatek()
	{
		std::string hardware = GetSystemProperty("ro.hardware");
		std::string board = GetSystemProperty("ro.product.board");
		std::string platform = GetSystemProperty("ro.board.platform");
		
		Console.WriteLn("Device Detection: hardware='%s', board='%s', platform='%s'", 
			hardware.c_str(), board.c_str(), platform.c_str());
		
		// Convert to lowercase for comparison
		auto toLower = [](std::string str) {
			for (char& c : str) c = std::tolower(c);
			return str;
		};
		
		hardware = toLower(hardware);
		board = toLower(board);
		platform = toLower(platform);
		
		// Check for Mediatek identifiers
		return (hardware.find("mt") == 0 || 
		        hardware.find("mediatek") != std::string::npos ||
		        board.find("mt") == 0 ||
		        platform.find("mt") == 0 ||
		        platform.find("mediatek") != std::string::npos);
	}

	bool IsSnapdragon()
	{
		std::string hardware = GetSystemProperty("ro.hardware");
		std::string board = GetSystemProperty("ro.product.board");
		std::string platform = GetSystemProperty("ro.board.platform");
		
		Console.WriteLn("Device Detection: hardware='%s', board='%s', platform='%s'", 
			hardware.c_str(), board.c_str(), platform.c_str());
		
		auto toLower = [](std::string str) {
			for (char& c : str) c = std::tolower(c);
			return str;
		};
		
		hardware = toLower(hardware);
		board = toLower(board);
		platform = toLower(platform);
		
		// Check for Qualcomm/Snapdragon identifiers
		return (hardware.find("qcom") != std::string::npos ||
		        hardware.find("qualcomm") != std::string::npos ||
		        platform.find("msm") == 0 ||
		        platform.find("sdm") == 0 ||
		        platform.find("sm") == 0 ||
		        platform.find("qcom") != std::string::npos);
	}

	GPUVendor DetectGPUVendor()
	{
		if (IsSnapdragon())
		{
			Console.WriteLn("Detected Qualcomm Snapdragon (Adreno GPU)");
			return GPUVendor::Qualcomm;
		}
		
		if (IsMediatek())
		{
			Console.WriteLn("Detected Mediatek (Mali GPU)");
			return GPUVendor::ARM;
		}
		
		// Check for other vendors via hardware string
		std::string hardware = GetSystemProperty("ro.hardware");
		std::string board = GetSystemProperty("ro.product.board");
		std::string platform = GetSystemProperty("ro.board.platform");
		std::string manufacturer = GetSystemProperty("ro.product.manufacturer");
		
		auto toLower = [](std::string str) {
			for (char& c : str) c = std::tolower(c);
			return str;
		};
		
		hardware = toLower(hardware);
		board = toLower(board);
		platform = toLower(platform);
		manufacturer = toLower(manufacturer);

		// Tensor Pixels use ARM Mali/Immortalis GPUs, but their system properties
		// contain Google codenames (gs101/gs201/zuma/etc.) rather than "mali".
		// Older Qualcomm Pixels were already handled by IsSnapdragon() above.
		if (manufacturer.find("google") != std::string::npos)
		{
			Console.WriteLn("Detected Google Pixel/Tensor (ARM GPU), hardware: %s, board: %s, platform: %s",
				hardware.c_str(), board.c_str(), platform.c_str());
			return GPUVendor::ARM;
		}
		
		// Samsung Exynos devices (Mali GPU)
		if (hardware.find("exynos") != std::string::npos || 
		    hardware.find("universal") != std::string::npos ||
		    (manufacturer.find("samsung") != std::string::npos && hardware.find("samsungexynos") != std::string::npos))
		{
			Console.WriteLn("Detected Samsung Exynos (Mali GPU)");
			return GPUVendor::ARM;
		}
		
		// Kirin devices (Mali GPU)
		if (hardware.find("kirin") != std::string::npos || hardware.find("hi") == 0)
		{
			Console.WriteLn("Detected HiSilicon Kirin (Mali GPU)");
			return GPUVendor::ARM;
		}
		
		// Rockchip devices (Mali GPU)
		if (hardware.find("rk") == 0 || hardware.find("rockchip") != std::string::npos)
		{
			Console.WriteLn("Detected Rockchip (Mali GPU)");
			return GPUVendor::ARM;
		}
		
		Console.WriteLn("Unknown GPU vendor, hardware: %s, manufacturer: %s", hardware.c_str(), manufacturer.c_str());
		return GPUVendor::Unknown;
	}
}

#endif // __ANDROID__
