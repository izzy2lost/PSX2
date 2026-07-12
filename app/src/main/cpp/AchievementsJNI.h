// SPDX-FileCopyrightText: 2025 Android Port Contributors
// SPDX-License-Identifier: GPL-3.0+

#pragma once

#include <jni.h>

namespace AchievementsJNI
{
	/// Initialize the JNI bridge for achievements. Call this from JNI_OnLoad or similar.
	bool Initialize(JNIEnv* env);

	/// Shutdown the JNI bridge. Call this from JNI_OnUnload or similar.
	void Shutdown();

	/// Called when an achievement is unlocked
	void OnAchievementUnlocked(const char* title, const char* description, int points, bool is_hardcore);

	/// Called when the game is completed (all achievements unlocked)
	void OnGameComplete(const char* game_title, int achievement_count, int total_points);

	/// Called when a leaderboard attempt starts
	void OnLeaderboardStarted(const char* leaderboard_title);

	/// Called when a leaderboard score is submitted
	void OnLeaderboardSubmitted(const char* leaderboard_title, const char* score, int rank, int total_entries);

	/// Called when login is successful
	void OnLoginSuccess(const char* username, int score, int softcore_score, int unread_messages);

	/// Called when a challenge indicator should be shown
	void OnChallengeIndicatorShow(const char* achievement_title);

	/// Called when progress towards an achievement is made
	void OnProgressIndicatorUpdate(const char* achievement_title, const char* progress);

	/// Called when game summary is available
	void OnGameSummary(const char* game_title, int unlocked_count, int total_count,
		int earned_points, int total_points, bool is_hardcore);

	/// Show a generic notification
	void ShowNotification(const char* message, int duration);

	/// Play a short notification sound from an absolute file path
	void PlaySound(const char* path);

	/// Get the JavaVM instance (for internal use)
	JavaVM* GetJavaVM();

} // namespace AchievementsJNI
