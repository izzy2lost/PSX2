package com.izzy2lost.psx2;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.ref.WeakReference;

/**
 * Manages RetroAchievements integration for the Android app.
 * Handles achievement unlock notifications, leaderboard updates, and rich presence.
 */
public class RetroAchievementsManager {
    private static WeakReference<Activity> sActivity;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    
    // Achievement notification types
    public static final int NOTIFICATION_ACHIEVEMENT_UNLOCKED = 0;
    public static final int NOTIFICATION_GAME_COMPLETE = 1;
    public static final int NOTIFICATION_LEADERBOARD_STARTED = 2;
    public static final int NOTIFICATION_LEADERBOARD_SUBMITTED = 3;
    public static final int NOTIFICATION_LOGIN_SUCCESS = 4;
    public static final int NOTIFICATION_CHALLENGE_INDICATOR = 5;
    public static final int NOTIFICATION_PROGRESS_INDICATOR = 6;

    /**
     * Initialize the RetroAchievements manager with the main activity.
     */
    public static void initialize(Activity activity) {
        sActivity = new WeakReference<>(activity);
    }

    /**
     * Called from native code when an achievement is unlocked.
     * @param title Achievement title
     * @param description Achievement description
     * @param points Points earned
     * @param isHardcore Whether hardcore mode is active
     */
    public static void onAchievementUnlocked(final String title, final String description, 
                                            final int points, final boolean isHardcore) {
        android.util.Log.i("Achievements", "Achievement unlocked: " + title + " (" + points + " points)");
        
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) {
                android.util.Log.w("Achievements", "Activity is null, cannot show toast");
                return;
            }
            
            // Check if notifications are enabled
            android.content.SharedPreferences prefs = activity.getSharedPreferences("RetroAchievements", android.content.Context.MODE_PRIVATE);
            boolean notificationsEnabled = prefs.getBoolean("notifications", true);
            
            if (!notificationsEnabled) {
                android.util.Log.d("Achievements", "Notifications disabled, skipping toast");
                return;
            }
            
            String message = String.format("🏆 Achievement Unlocked!\n%s\n%s\n%d points%s", 
                title, description, points, 
                isHardcore ? " (Hardcore)" : "");
            
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Called from native code when the game is completed (all achievements unlocked).
     * @param gameTitle Game title
     * @param achievementCount Total achievements unlocked
     * @param totalPoints Total points earned
     */
    public static void onGameComplete(final String gameTitle, final int achievementCount, 
                                     final int totalPoints) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("🏆 Mastered %s!\n%d achievements, %d points", 
                gameTitle, achievementCount, totalPoints);
            
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Called from native code when a leaderboard attempt starts.
     * @param leaderboardTitle Leaderboard title
     */
    public static void onLeaderboardStarted(final String leaderboardTitle) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("📊 %s\nLeaderboard attempt started", leaderboardTitle);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Called from native code when a leaderboard score is submitted.
     * @param leaderboardTitle Leaderboard title
     * @param score Score value
     * @param rank Player's rank
     * @param totalEntries Total entries in leaderboard
     */
    public static void onLeaderboardSubmitted(final String leaderboardTitle, final String score,
                                             final int rank, final int totalEntries) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("📊 %s\nScore: %s\nRank: %d of %d", 
                leaderboardTitle, score, rank, totalEntries);
            
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Called from native code when successfully logged in.
     * @param username User's display name
     * @param score Total score
     * @param softcoreScore Softcore score
     * @param unreadMessages Number of unread messages
     */
    public static void onLoginSuccess(final String username, final int score, 
                                     final int softcoreScore, final int unreadMessages) {
        android.util.Log.i("Achievements", "Login success: " + username + " (Score: " + score + ")");
        
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("✓ Logged in as %s\nScore: %d pts (softcore: %d pts)\nUnread messages: %d",
                username, score, softcoreScore, unreadMessages);
            
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Called from native code when a challenge indicator should be shown.
     * @param achievementTitle Achievement title
     */
    public static void onChallengeIndicatorShow(final String achievementTitle) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("⚡ Challenge: %s", achievementTitle);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Called from native code when progress towards an achievement is made.
     * @param achievementTitle Achievement title
     * @param progress Progress string (e.g., "50%")
     */
    public static void onProgressIndicatorUpdate(final String achievementTitle, final String progress) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message = String.format("📈 %s: %s", achievementTitle, progress);
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Called from native code when game summary is available.
     * @param gameTitle Game title
     * @param unlockedCount Unlocked achievements
     * @param totalCount Total achievements
     * @param earnedPoints Earned points
     * @param totalPoints Total points
     * @param isHardcore Whether hardcore mode is active
     */
    public static void onGameSummary(final String gameTitle, final int unlockedCount, 
                                    final int totalCount, final int earnedPoints, 
                                    final int totalPoints, final boolean isHardcore) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            String message;
            if (totalCount > 0) {
                message = String.format("%s%s\nUnlocked %d of %d achievements\nEarned %d of %d points",
                    gameTitle, isHardcore ? " (Hardcore)" : "",
                    unlockedCount, totalCount, earnedPoints, totalPoints);
            } else {
                message = String.format("%s\nThis game has no achievements.", gameTitle);
            }
            
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Called from native code to show a generic notification.
     * @param message Message to display
     * @param duration Duration (0 = SHORT, 1 = LONG)
     */
    public static void showNotification(final String message, final int duration) {
        sMainHandler.post(() -> {
            Activity activity = getActivity();
            if (activity == null) return;
            
            int toastDuration = duration == 0 ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            Toast.makeText(activity, message, toastDuration).show();
        });
    }

    private static Activity getActivity() {
        return sActivity != null ? sActivity.get() : null;
    }

    /**
     * Called from native code (Common::PlaySoundAsync) to play a short
     * notification sound (achievement unlock, etc.) from an absolute path.
     */
    public static void playSound(final String path) {
        sMainHandler.post(() -> {
            try {
                final android.media.MediaPlayer player = new android.media.MediaPlayer();
                player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_GAME)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setDataSource(path);
                player.setOnCompletionListener(android.media.MediaPlayer::release);
                player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
                player.prepare();
                player.start();
            } catch (Exception e) {
                android.util.Log.w("RetroAchievements", "Failed to play sound: " + path, e);
            }
        });
    }
}
