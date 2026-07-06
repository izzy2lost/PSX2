package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialog for managing RetroAchievements settings and login.
 */
public class AchievementsDialogFragment extends DialogFragment {
    private static final String PREFS_NAME = "RetroAchievements";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_HARDCORE = "hardcore_mode";
    private static final String PREF_NOTIFICATIONS = "notifications";
    private static final String PREF_USERNAME = "username";
    private static final String PREF_REMEMBER_ME = "remember_me";
    private static final String PREF_SAVED_PASSWORD = "saved_password";

    private CheckBox mEnabledCheckbox;
    private CheckBox mHardcoreModeCheckbox;
    private CheckBox mNotificationsCheckbox;
    private CheckBox mRememberMeCheckbox;
    private EditText mUsernameEdit;
    private EditText mPasswordEdit;
    private TextView mStatusText;
    private com.google.android.material.button.MaterialButton mLoginButton;
    private com.google.android.material.button.MaterialButton mLogoutButton;
    private com.google.android.material.button.MaterialButton mCreateAccountButton;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        
        // For now, create a simple layout programmatically
        // In production, you'd want to create a proper XML layout
        View view = createView(context);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        builder.setTitle("RetroAchievements")
               .setView(view)
               .setPositiveButton("Close", (dialog, which) -> {
                   saveSettings();
                   dismiss();
               });

        return builder.create();
    }

    private View createView(Context context) {
        // Create a ScrollView to contain everything
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        
        // Create a simple vertical layout with settings
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        // Status text
        mStatusText = new TextView(context);
        mStatusText.setPadding(0, 0, 0, 24);
        updateStatus();
        layout.addView(mStatusText);



        // Enabled checkbox
        mEnabledCheckbox = new CheckBox(context);
        mEnabledCheckbox.setText("Enable RetroAchievements");
        mEnabledCheckbox.setChecked(getPrefs().getBoolean(PREF_ENABLED, false));
        mEnabledCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            android.util.Log.d("Achievements", "Enable checkbox changed to: " + isChecked);
            getPrefs().edit().putBoolean(PREF_ENABLED, isChecked).apply();
            if (isChecked) {
                // Initialize achievements system
                new Thread(() -> {
                    android.util.Log.d("Achievements", "Initializing achievements system...");
                    NativeApp.achievementsInitialize();
                }).start();
            } else {
                // Shutdown achievements system
                new Thread(() -> {
                    android.util.Log.d("Achievements", "Shutting down achievements system...");
                    NativeApp.achievementsShutdown();
                }).start();
            }
            updateUIState();
        });
        layout.addView(mEnabledCheckbox);

        // Hardcore mode checkbox
        mHardcoreModeCheckbox = new CheckBox(context);
        mHardcoreModeCheckbox.setText("Hardcore Mode");
        mHardcoreModeCheckbox.setChecked(getPrefs().getBoolean(PREF_HARDCORE, false));
        mHardcoreModeCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            android.util.Log.d("Achievements", "Hardcore mode changed to: " + isChecked);
            NativeApp.achievementsSetHardcoreMode(isChecked);
        });
        layout.addView(mHardcoreModeCheckbox);

        // Notifications checkbox
        mNotificationsCheckbox = new CheckBox(context);
        mNotificationsCheckbox.setText("Show Notifications");
        mNotificationsCheckbox.setChecked(getPrefs().getBoolean(PREF_NOTIFICATIONS, true));
        layout.addView(mNotificationsCheckbox);

        // Spacer
        View spacer1 = new View(context);
        spacer1.setMinimumHeight(16);
        layout.addView(spacer1);

        // Username field
        mUsernameEdit = new EditText(context);
        mUsernameEdit.setHint("Username");
        String savedUsername = getPrefs().getString(PREF_USERNAME, "");
        mUsernameEdit.setText(savedUsername);
        layout.addView(mUsernameEdit);

        // Password field
        mPasswordEdit = new EditText(context);
        mPasswordEdit.setHint("Password");
        mPasswordEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | 
                                   android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // Load saved password if remember me is enabled
        boolean rememberMe = getPrefs().getBoolean(PREF_REMEMBER_ME, false);
        if (rememberMe) {
            String savedPassword = getPrefs().getString(PREF_SAVED_PASSWORD, "");
            mPasswordEdit.setText(savedPassword);
        }
        layout.addView(mPasswordEdit);

        // Remember Me checkbox
        mRememberMeCheckbox = new CheckBox(context);
        mRememberMeCheckbox.setText("Remember Me");
        mRememberMeCheckbox.setChecked(rememberMe);
        layout.addView(mRememberMeCheckbox);

        // Login button
        mLoginButton = new com.google.android.material.button.MaterialButton(context);
        mLoginButton.setText("Login");
        mLoginButton.setOnClickListener(v -> performLogin());
        layout.addView(mLoginButton);

        // Logout button
        mLogoutButton = new com.google.android.material.button.MaterialButton(context);
        mLogoutButton.setText("Logout");
        mLogoutButton.setOnClickListener(v -> performLogout());
        layout.addView(mLogoutButton);

        // Spacer
        View spacer2 = new View(context);
        spacer2.setMinimumHeight(16);
        layout.addView(spacer2);

        // View Achievements List button
        com.google.android.material.button.MaterialButton viewListButton = new com.google.android.material.button.MaterialButton(context);
        viewListButton.setText("View Achievements List");
        viewListButton.setOnClickListener(v -> openAchievementsList());
        layout.addView(viewListButton);

        // View Profile button
        com.google.android.material.button.MaterialButton viewProfileButton = new com.google.android.material.button.MaterialButton(context);
        viewProfileButton.setText("View Profile on RetroAchievements.org");
        viewProfileButton.setOnClickListener(v -> openProfilePage());
        layout.addView(viewProfileButton);

        // Spacer
        View spacer3 = new View(context);
        spacer3.setMinimumHeight(8);
        layout.addView(spacer3);

        // Create Account link
        TextView createAccountText = new TextView(context);
        createAccountText.setText("Don't have an account?");
        createAccountText.setTextSize(12);
        createAccountText.setPadding(0, 8, 0, 4);
        layout.addView(createAccountText);

        mCreateAccountButton = new com.google.android.material.button.MaterialButton(context);
        mCreateAccountButton.setText("Create Free Account");
        mCreateAccountButton.setOnClickListener(v -> openCreateAccountPage());
        layout.addView(mCreateAccountButton);

        // Add layout to ScrollView
        scrollView.addView(layout);

        updateUIState();
        return scrollView;
    }

    private void updateStatus() {
        if (mStatusText == null) return;

        if (NativeApp.achievementsIsActive()) {
            if (NativeApp.achievementsHasActiveGame()) {
                String gameTitle = NativeApp.achievementsGetGameTitle();
                String richPresence = NativeApp.achievementsGetRichPresence();
                boolean isHardcore = NativeApp.achievementsIsHardcoreMode();
                mStatusText.setText(String.format("Active: %s\n%s\nMode: %s", 
                    gameTitle, richPresence, isHardcore ? "Hardcore" : "Softcore"));
            } else {
                mStatusText.setText("Active: No game loaded\n\nNote: This is an unofficial build. Hardcore achievements may not be available.");
            }
        } else {
            mStatusText.setText("Status: Inactive\n\nNote: This is an unofficial build. You can still earn softcore achievements!");
        }
    }

    private void updateUIState() {
        if (mEnabledCheckbox == null || mLoginButton == null || mLogoutButton == null) {
            return;
        }
        
        boolean enabled = mEnabledCheckbox.isChecked();
        
        // Check if we're logged in by looking at saved token/active client
        SharedPreferences prefs = getPrefs();
        String savedUsername = prefs.getString(PREF_USERNAME, "");
        String savedToken = prefs.getString("token", "");
        boolean hasCredentials = !savedUsername.isEmpty();
        boolean hasToken = savedToken != null && !savedToken.isEmpty();
        boolean isActive = false;
        try {
            isActive = NativeApp.achievementsIsActive();
        } catch (Throwable t) {
            android.util.Log.w("Achievements", "Could not query active state: " + t.getMessage());
        }
        // Treat as logged in only when we actually have a saved token AND the client reports active
        boolean loggedIn = hasToken && isActive;

        if (mHardcoreModeCheckbox != null) mHardcoreModeCheckbox.setEnabled(enabled);
        if (mNotificationsCheckbox != null) mNotificationsCheckbox.setEnabled(enabled);
        if (mRememberMeCheckbox != null) mRememberMeCheckbox.setEnabled(enabled);
        if (mUsernameEdit != null) mUsernameEdit.setEnabled(enabled);
        if (mPasswordEdit != null) mPasswordEdit.setEnabled(enabled);
        
        // Login button: enabled when achievements are enabled and no active session/token
        mLoginButton.setEnabled(enabled && !loggedIn);
        mLoginButton.setVisibility(View.VISIBLE);
        if (loggedIn) {
            mLoginButton.setText("Logged In");
        } else {
            mLoginButton.setText("Login");
        }
        
        // Logout button: enabled if we have anything to clear
        mLogoutButton.setEnabled(enabled && (hasCredentials || hasToken || isActive));
        mLogoutButton.setVisibility(View.VISIBLE);
        
        // Create account button: always enabled
        if (mCreateAccountButton != null) {
            mCreateAccountButton.setEnabled(true);
            mCreateAccountButton.setVisibility(View.VISIBLE);
        }
        
        android.util.Log.d("Achievements", "updateUIState - enabled: " + enabled + 
            ", hasCredentials: " + hasCredentials + 
            ", loginEnabled: " + mLoginButton.isEnabled() +
            ", logoutEnabled: " + mLogoutButton.isEnabled());
    }

    private void performLogin() {
        String username = mUsernameEdit.getText().toString().trim();
        String password = mPasswordEdit.getText().toString().trim();
        boolean rememberMe = mRememberMeCheckbox.isChecked();

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            Toast.makeText(requireContext(), "Please enter username and password", 
                          Toast.LENGTH_SHORT).show();
            return;
        }

        android.util.Log.d("Achievements", "Starting login for user: " + username);
        Toast.makeText(requireContext(), "Logging in...", Toast.LENGTH_SHORT).show();

        // Save username and remember me preference
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(PREF_USERNAME, username);
        editor.putBoolean(PREF_REMEMBER_ME, rememberMe);
        
        // Save password only if remember me is checked (for fallback)
        if (rememberMe) {
            editor.putString(PREF_SAVED_PASSWORD, password);
        } else {
            editor.remove(PREF_SAVED_PASSWORD);
        }
        editor.apply();

        // Perform login on background thread
        new Thread(() -> {
            android.util.Log.d("Achievements", "Calling native login...");
            NativeApp.achievementsLogin(username, password);
            android.util.Log.d("Achievements", "Native login call completed");
            
            // The token will be automatically saved by Host::CommitBaseSettingChanges()
            // which is called from the native login callback
            
            // Wait a bit for the login to process
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            UiUtils.postIfFragmentAttached(this, () -> {
                Context context = getContext();
                if (context == null) return;
                boolean isActive = NativeApp.achievementsIsActive();
                android.util.Log.d("Achievements", "After login - isActive: " + isActive);
                
                if (isActive) {
                    Toast.makeText(context, "Signed into RetroAchievements.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Login may have failed - check logs", Toast.LENGTH_LONG).show();
                }
                
                updateStatus();
                updateUIState();
            });
        }).start();
    }

    private void performLogout() {
        NativeApp.achievementsLogout();
        
        // Clear saved credentials including token
        getPrefs().edit()
            .remove(PREF_SAVED_PASSWORD)
            .remove("token")
            .remove("login_timestamp")
            .putBoolean(PREF_REMEMBER_ME, false)
            .apply();
        
        // Clear password field
        if (mPasswordEdit != null) {
            mPasswordEdit.setText("");
        }
        if (mRememberMeCheckbox != null) {
            mRememberMeCheckbox.setChecked(false);
        }
        
        updateStatus();
        updateUIState();
        Toast.makeText(requireContext(), "Logged out", Toast.LENGTH_SHORT).show();
    }

    private void openAchievementsList() {
        if (!NativeApp.achievementsHasActiveGame()) {
            Toast.makeText(requireContext(), "No game loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        
        AchievementsListDialogFragment dialog = AchievementsListDialogFragment.newInstance();
        dialog.show(getParentFragmentManager(), "achievements_list");
    }

    private void openProfilePage() {
        try {
            String username = getPrefs().getString(PREF_USERNAME, "");
            if (username.isEmpty()) {
                Toast.makeText(requireContext(), "Please login first", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://retroachievements.org/user/" + username));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void openCreateAccountPage() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://retroachievements.org/createaccount.php"));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putBoolean(PREF_ENABLED, mEnabledCheckbox.isChecked());
        editor.putBoolean(PREF_HARDCORE, mHardcoreModeCheckbox.isChecked());
        editor.putBoolean(PREF_NOTIFICATIONS, mNotificationsCheckbox.isChecked());
        editor.apply();
        
        android.util.Log.d("Achievements", "Settings saved");
    }

    private SharedPreferences getPrefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static AchievementsDialogFragment newInstance() {
        return new AchievementsDialogFragment();
    }
}
