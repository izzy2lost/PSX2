// SPDX-FileCopyrightText: 2025 Android Port Contributors
// SPDX-License-Identifier: GPL-3.0+

#include <jni.h>
#include <string>
#include <mutex>
#include <android/log.h>
#include "pcsx2/Achievements.h"

#define LOG_TAG "PCSX2Achievements"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace AchievementsJNI
{
	static JavaVM* s_jvm = nullptr;
	static jclass s_achievements_class = nullptr;
	static std::mutex s_jni_mutex;

	// Method IDs for Java callbacks
	static jmethodID s_on_achievement_unlocked = nullptr;
	static jmethodID s_on_game_complete = nullptr;
	static jmethodID s_on_leaderboard_started = nullptr;
	static jmethodID s_on_leaderboard_submitted = nullptr;
	static jmethodID s_on_login_success = nullptr;
	static jmethodID s_on_challenge_indicator_show = nullptr;
	static jmethodID s_on_progress_indicator_update = nullptr;
	static jmethodID s_on_game_summary = nullptr;
	static jmethodID s_show_notification = nullptr;
	static jmethodID s_play_sound = nullptr;

	bool Initialize(JNIEnv* env)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);

		if (env->GetJavaVM(&s_jvm) != JNI_OK)
		{
			LOGE("Failed to get JavaVM");
			return false;
		}

		jclass local_class = env->FindClass("com/izzy2lost/psx2/RetroAchievementsManager");
		if (!local_class)
		{
			LOGE("Failed to find RetroAchievementsManager class");
			env->ExceptionClear(); // Clear any pending exception
			return false;
		}

		s_achievements_class = static_cast<jclass>(env->NewGlobalRef(local_class));
		env->DeleteLocalRef(local_class);

		if (!s_achievements_class)
		{
			LOGE("Failed to create global reference");
			return false;
		}

		// Cache method IDs - clear exceptions after each attempt
		s_on_achievement_unlocked = env->GetStaticMethodID(s_achievements_class, "onAchievementUnlocked",
			"(Ljava/lang/String;Ljava/lang/String;IZ)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_game_complete = env->GetStaticMethodID(s_achievements_class, "onGameComplete",
			"(Ljava/lang/String;II)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_leaderboard_started = env->GetStaticMethodID(s_achievements_class, "onLeaderboardStarted",
			"(Ljava/lang/String;)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_leaderboard_submitted = env->GetStaticMethodID(s_achievements_class, "onLeaderboardSubmitted",
			"(Ljava/lang/String;Ljava/lang/String;II)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_login_success = env->GetStaticMethodID(s_achievements_class, "onLoginSuccess",
			"(Ljava/lang/String;III)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_challenge_indicator_show = env->GetStaticMethodID(s_achievements_class, "onChallengeIndicatorShow",
			"(Ljava/lang/String;)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_progress_indicator_update = env->GetStaticMethodID(s_achievements_class, "onProgressIndicatorUpdate",
			"(Ljava/lang/String;Ljava/lang/String;)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_on_game_summary = env->GetStaticMethodID(s_achievements_class, "onGameSummary",
			"(Ljava/lang/String;IIIIZ)V");
		if (env->ExceptionCheck()) env->ExceptionClear();
		
		s_show_notification = env->GetStaticMethodID(s_achievements_class, "showNotification",
			"(Ljava/lang/String;I)V");
		if (env->ExceptionCheck()) env->ExceptionClear();

		s_play_sound = env->GetStaticMethodID(s_achievements_class, "playSound",
			"(Ljava/lang/String;)V");
		if (env->ExceptionCheck()) env->ExceptionClear();

		if (!s_on_achievement_unlocked || !s_on_game_complete || !s_on_leaderboard_started ||
			!s_on_leaderboard_submitted || !s_on_login_success || !s_on_challenge_indicator_show ||
			!s_on_progress_indicator_update || !s_on_game_summary || !s_show_notification)
		{
			LOGE("Failed to find one or more method IDs");
			return false;
		}

		LOGI("Initialized successfully");
		return true;
	}

	void Shutdown()
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);

		if (!s_jvm)
			return;

		JNIEnv* env = nullptr;
		if (s_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env)
		{
			if (s_achievements_class)
			{
				env->DeleteGlobalRef(s_achievements_class);
				s_achievements_class = nullptr;
			}
		}

		s_jvm = nullptr;
	}

	JNIEnv* GetEnv()
	{
		if (!s_jvm)
			return nullptr;

		JNIEnv* env = nullptr;
		if (s_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
		{
			// Try to attach the current thread
			if (s_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
				return nullptr;
		}

		return env;
	}

	void OnAchievementUnlocked(const char* title, const char* description, int points, bool is_hardcore)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_achievement_unlocked)
			return;

		jstring j_title = env->NewStringUTF(title);
		jstring j_description = env->NewStringUTF(description);

		env->CallStaticVoidMethod(s_achievements_class, s_on_achievement_unlocked,
			j_title, j_description, points, is_hardcore);

		env->DeleteLocalRef(j_title);
		env->DeleteLocalRef(j_description);
	}

	void OnGameComplete(const char* game_title, int achievement_count, int total_points)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_game_complete)
			return;

		jstring j_game_title = env->NewStringUTF(game_title);

		env->CallStaticVoidMethod(s_achievements_class, s_on_game_complete,
			j_game_title, achievement_count, total_points);

		env->DeleteLocalRef(j_game_title);
	}

	void OnLeaderboardStarted(const char* leaderboard_title)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_leaderboard_started)
			return;

		jstring j_title = env->NewStringUTF(leaderboard_title);

		env->CallStaticVoidMethod(s_achievements_class, s_on_leaderboard_started, j_title);

		env->DeleteLocalRef(j_title);
	}

	void OnLeaderboardSubmitted(const char* leaderboard_title, const char* score, int rank, int total_entries)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_leaderboard_submitted)
			return;

		jstring j_title = env->NewStringUTF(leaderboard_title);
		jstring j_score = env->NewStringUTF(score);

		env->CallStaticVoidMethod(s_achievements_class, s_on_leaderboard_submitted,
			j_title, j_score, rank, total_entries);

		env->DeleteLocalRef(j_title);
		env->DeleteLocalRef(j_score);
	}

	void OnLoginSuccess(const char* username, int score, int softcore_score, int unread_messages)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_login_success)
			return;

		jstring j_username = env->NewStringUTF(username);

		env->CallStaticVoidMethod(s_achievements_class, s_on_login_success,
			j_username, score, softcore_score, unread_messages);

		env->DeleteLocalRef(j_username);
	}

	void OnChallengeIndicatorShow(const char* achievement_title)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_challenge_indicator_show)
			return;

		jstring j_title = env->NewStringUTF(achievement_title);

		env->CallStaticVoidMethod(s_achievements_class, s_on_challenge_indicator_show, j_title);

		env->DeleteLocalRef(j_title);
	}

	void OnProgressIndicatorUpdate(const char* achievement_title, const char* progress)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_progress_indicator_update)
			return;

		jstring j_title = env->NewStringUTF(achievement_title);
		jstring j_progress = env->NewStringUTF(progress);

		env->CallStaticVoidMethod(s_achievements_class, s_on_progress_indicator_update,
			j_title, j_progress);

		env->DeleteLocalRef(j_title);
		env->DeleteLocalRef(j_progress);
	}

	void OnGameSummary(const char* game_title, int unlocked_count, int total_count,
		int earned_points, int total_points, bool is_hardcore)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_on_game_summary)
			return;

		jstring j_game_title = env->NewStringUTF(game_title);

		env->CallStaticVoidMethod(s_achievements_class, s_on_game_summary,
			j_game_title, unlocked_count, total_count, earned_points, total_points, is_hardcore);

		env->DeleteLocalRef(j_game_title);
	}

	void ShowNotification(const char* message, int duration)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_show_notification)
			return;

		jstring j_message = env->NewStringUTF(message);

		env->CallStaticVoidMethod(s_achievements_class, s_show_notification, j_message, duration);

		env->DeleteLocalRef(j_message);
	}

	void PlaySound(const char* path)
	{
		std::lock_guard<std::mutex> lock(s_jni_mutex);
		JNIEnv* env = GetEnv();
		if (!env || !s_achievements_class || !s_play_sound)
			return;

		jstring j_path = env->NewStringUTF(path);

		env->CallStaticVoidMethod(s_achievements_class, s_play_sound, j_path);

		env->DeleteLocalRef(j_path);
	}

	JavaVM* GetJavaVM()
	{
		return s_jvm;
	}

} // namespace AchievementsJNI
