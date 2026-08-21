// SPDX-FileCopyrightText: 2025 Android Port Contributors
// SPDX-License-Identifier: GPL-3.0+

#include <jni.h>
#include <string>
#include <android/log.h>
#include "rc_client.h"
#include "pcsx2/Achievements.h"
#include "pcsx2/Config.h"
#include "pcsx2/Host.h"
#include "common/Console.h"
#include "common/Error.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsIsActive(JNIEnv* env, jclass clazz)
{
	return Achievements::IsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsIsHardcoreMode(JNIEnv* env, jclass clazz)
{
	return Achievements::IsHardcoreModeActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsHasActiveGame(JNIEnv* env, jclass clazz)
{
	return Achievements::HasActiveGame() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsGetGameTitle(JNIEnv* env, jclass clazz)
{
	auto lock = Achievements::GetLock();
	const std::string& title = Achievements::GetGameTitle();
	return env->NewStringUTF(title.c_str());
}

JNIEXPORT jint JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsGetGameId(JNIEnv* env, jclass clazz)
{
	return static_cast<jint>(Achievements::GetGameID());
}

JNIEXPORT jstring JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsGetRichPresence(JNIEnv* env, jclass clazz)
{
	auto lock = Achievements::GetLock();
	const std::string& presence = Achievements::GetRichPresenceString();
	return env->NewStringUTF(presence.c_str());
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsLogin(JNIEnv* env, jclass clazz, 
	jstring username, jstring password)
{
	if (!username || !password)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Login called with null username or password");
		return;
	}

	const char* username_str = env->GetStringUTFChars(username, nullptr);
	const char* password_str = env->GetStringUTFChars(password, nullptr);

	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Attempting login for user: %s", username_str);

	Error error;
	bool result = Achievements::Login(username_str, password_str, &error);

	env->ReleaseStringUTFChars(username, username_str);
	env->ReleaseStringUTFChars(password, password_str);

	if (!result)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Login failed: %s", error.GetDescription().c_str());
		Console.Error("Achievements login failed: %s", error.GetDescription().c_str());
	}
	else
	{
		__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Login successful!");
	}
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsLogout(JNIEnv* env, jclass clazz)
{
	Achievements::Logout();
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsInitialize(JNIEnv* env, jclass clazz)
{
	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Initializing achievements system");
	
	// Check if already active
	if (Achievements::IsActive())
	{
		__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Achievements already active, skipping initialization");
		return;
	}
	
	// Enable achievements in config
	EmuConfig.Achievements.Enabled = true;
	
	// Initialize the achievements system
	bool result = Achievements::Initialize();
	
	if (result)
	{
		__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Achievements initialized successfully");
	}
	else
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Failed to initialize achievements");
	}
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsShutdown(JNIEnv* env, jclass clazz)
{
	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Shutting down achievements system");
	
	// Disable achievements in config
	EmuConfig.Achievements.Enabled = false;
	
	// Shutdown the achievements system
	Achievements::Shutdown(false);
}

JNIEXPORT jobjectArray JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsGetAchievementList(JNIEnv* env, jclass clazz)
{
	if (!Achievements::HasActiveGame())
	{
		__android_log_print(ANDROID_LOG_WARN, "PCSX2Achievements", "No active game, returning empty list");
		return env->NewObjectArray(0, env->FindClass("com/izzy2lost/psx2/Achievement"), nullptr);
	}

	auto lock = Achievements::GetLock();
	
	// Get the achievement list from rcheevos
	rc_client_achievement_list_t* list = static_cast<rc_client_achievement_list_t*>(
		Achievements::GetAchievementListForAndroid());
	
	if (!list || list->num_buckets == 0)
	{
		__android_log_print(ANDROID_LOG_WARN, "PCSX2Achievements", "No achievements found");
		if (list) rc_client_destroy_achievement_list(list);
		return env->NewObjectArray(0, env->FindClass("com/izzy2lost/psx2/Achievement"), nullptr);
	}

	// Count total achievements
	uint32_t total_count = 0;
	for (uint32_t i = 0; i < list->num_buckets; i++)
	{
		total_count += list->buckets[i].num_achievements;
	}

	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Found %d achievements", total_count);

	// Find Achievement class and constructor
	jclass achievementClass = env->FindClass("com/izzy2lost/psx2/Achievement");
	if (!achievementClass)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Could not find Achievement class");
		rc_client_destroy_achievement_list(list);
		return env->NewObjectArray(0, env->FindClass("com/izzy2lost/psx2/Achievement"), nullptr);
	}

	jmethodID constructor = env->GetMethodID(achievementClass, "<init>",
		"(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IZJLjava/lang/String;FIFF)V");
	if (!constructor)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Could not find Achievement constructor");
		rc_client_destroy_achievement_list(list);
		return env->NewObjectArray(0, achievementClass, nullptr);
	}

	// Create array
	jobjectArray result = env->NewObjectArray(total_count, achievementClass, nullptr);
	if (!result)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Could not create array");
		rc_client_destroy_achievement_list(list);
		return env->NewObjectArray(0, achievementClass, nullptr);
	}

	// Fill array
	uint32_t index = 0;
	for (uint32_t i = 0; i < list->num_buckets; i++)
	{
		const rc_client_achievement_bucket_t* bucket = &list->buckets[i];
		for (uint32_t j = 0; j < bucket->num_achievements; j++)
		{
			const rc_client_achievement_t* cheevo = bucket->achievements[j];
			
			jstring title = env->NewStringUTF(cheevo->title ? cheevo->title : "");
			jstring description = env->NewStringUTF(cheevo->description ? cheevo->description : "");
			jstring badgeName = env->NewStringUTF(cheevo->badge_name);
			jstring measuredProgress = env->NewStringUTF(cheevo->measured_progress);

			jobject achievement = env->NewObject(achievementClass, constructor,
				(jint)cheevo->id,
				title,
				description,
				badgeName,
				(jint)cheevo->points,
				(jboolean)cheevo->unlocked,
				(jlong)cheevo->unlock_time,
				measuredProgress,
				(jfloat)cheevo->measured_percent,
				(jint)cheevo->state,
				(jfloat)cheevo->rarity,
				(jfloat)cheevo->rarity_hardcore);

			env->SetObjectArrayElement(result, index++, achievement);

			env->DeleteLocalRef(title);
			env->DeleteLocalRef(description);
			env->DeleteLocalRef(badgeName);
			env->DeleteLocalRef(measuredProgress);
			env->DeleteLocalRef(achievement);
		}
	}

	rc_client_destroy_achievement_list(list);
	return result;
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsSetHardcoreMode(JNIEnv* env, jclass clazz, jboolean enabled)
{
	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Setting hardcore mode: %d", enabled);
	
	// Set in config - will apply on next game load
	EmuConfig.Achievements.HardcoreMode = enabled;
	
	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Hardcore mode will apply on next game load");
}

JNIEXPORT void JNICALL
Java_com_izzy2lost_psx2_NativeApp_achievementsLoginWithToken(JNIEnv* env, jclass clazz,
	jstring username, jstring token)
{
	if (!username || !token)
	{
		__android_log_print(ANDROID_LOG_ERROR, "PCSX2Achievements", "Login with token called with null username or token");
		return;
	}

	const char* username_str = env->GetStringUTFChars(username, nullptr);
	const char* token_str = env->GetStringUTFChars(token, nullptr);

	__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Attempting token login for user: %s", username_str);

	// Store credentials so a later Initialize() can reuse them...
	Host::SetBaseStringSettingValue("Achievements", "Username", username_str);
	Host::SetBaseStringSettingValue("Achievements", "Token", token_str);

	// ...but storing them is not a login. When the client is already up (the UI signs in
	// outside the VM lifecycle) nothing else will start one, leaving us logged out when
	// the game boots and achievements silently never load.
	if (Achievements::LoginWithTokenAsync(username_str, token_str))
		__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Token login started");
	else
		__android_log_print(ANDROID_LOG_INFO, "PCSX2Achievements", "Token credentials stored; login deferred to init");

	env->ReleaseStringUTFChars(username, username_str);
	env->ReleaseStringUTFChars(token, token_str);
}

} // extern "C"
