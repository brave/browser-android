package com.linkbubble.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.linkbubble.BuildConfig;
import com.linkbubble.Constant;
import com.linkbubble.DRM;
import com.linkbubble.MainApplication;
import com.linkbubble.MainController;
import com.linkbubble.R;
import com.linkbubble.Settings;
import com.linkbubble.util.Analytics;
import com.linkbubble.util.CrashTracking;
import com.linkbubble.util.Tamper;
import com.linkbubble.util.Util;
import com.squareup.otto.Subscribe;

public class HomeActivity extends AppCompatActivity {

    private static final String TAG = "HomeActivity";

    Button mActionButtonView;
    FlipView mStatsFlipView;
    View mTimeSavedPerLinkContainerView;
    CondensedTextView mTimeSavedPerLinkTextView;
    CondensedTextView mTimeSavedTotalTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashTracking.init(this);

        setContentView(R.layout.activity_home);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle("");
        setSupportActionBar(toolbar);

        Analytics.trackScreenView(HomeActivity.class.getSimpleName());

        ((MainApplication)getApplicationContext()).registerDrmTracker(this);

        mActionButtonView = (Button)findViewById(R.id.big_white_button);
        mStatsFlipView = (FlipView) findViewById(R.id.stats_flip_view);
        if (BuildConfig.DEBUG) {
            mStatsFlipView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prompt.show("Something short", "OK", 1000, null);
                }
            });
        }
        mTimeSavedPerLinkContainerView = mStatsFlipView.getDefaultView();
        mTimeSavedPerLinkTextView = (CondensedTextView) mTimeSavedPerLinkContainerView.findViewById(R.id.time_per_link);
        mTimeSavedPerLinkTextView.setText("");
        mTimeSavedTotalTextView = (CondensedTextView) mStatsFlipView.getFlippedView().findViewById(R.id.time_total);
        mTimeSavedTotalTextView.setText("");

        if (!Settings.get().getTermsAccepted()) {
            final FrameLayout rootView = (FrameLayout)findViewById(android.R.id.content);

            final View acceptTermsView = getLayoutInflater().inflate(R.layout.view_accept_terms, null);
            TextView acceptTermsTextView = (TextView)acceptTermsView.findViewById(R.id.accept_terms_and_privacy_text);
            acceptTermsTextView.setText(Html.fromHtml(getString(R.string.accept_terms_and_privacy)));
            acceptTermsTextView.setMovementMethod(LinkMovementMethod.getInstance());
            Button acceptTermsButton = (Button)acceptTermsView.findViewById(R.id.accept_terms_and_privacy_button);
            acceptTermsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Settings.get().setTermsAccepted(true);
                    if (rootView != null) {
                        rootView.removeView(acceptTermsView);
                    }
                }
            });
            acceptTermsView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // do nothing, but prevent clicks from flowing to item underneath
                }
            });

            if (rootView != null) {
                rootView.addView(acceptTermsView);
            }
        }

        if (!Settings.get().getWelcomeMessageDisplayed()) {
            boolean showWelcomeUrl = true;
            if (MainController.get() != null && MainController.get().isUrlActive(Constant.WELCOME_MESSAGE_URL)) {
                showWelcomeUrl = false;
            }
            if (showWelcomeUrl) {
                MainApplication.openLink(this, Constant.WELCOME_MESSAGE_URL, null);
            }
        }

        if (Settings.get().debugAutoLoadUrl()) {
            MainApplication.openLink(this, "http://linkbubble.com/device/test.html", null);
            //MainApplication.openLink(getActivity(), "https://twitter.com/lokibartleby/status/412160702707539968", false);
        }

        configureForDrmState();

        mActionButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (DRM.isLicensed()) {
                    startActivity(new Intent(HomeActivity.this, HistoryActivity.class), v);
                } else {
                    Intent intent = MainApplication.getStoreIntent(HomeActivity.this, BuildConfig.STORE_PRO_URL);
                    if (intent != null) {
                        startActivity(intent);
                    }
                }
            }
        });

        MainApplication.registerForBus(this, this);

        Settings.get().getBrowsers();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_home, menu);
        if (DRM.isLicensed()) {
            menu.removeItem(R.id.action_history);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                return true;
            }

            case R.id.action_settings:
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class), item.getActionView());
                return true;

            case R.id.action_history:
                startActivity(new Intent(HomeActivity.this, HistoryActivity.class), item.getActionView());
                return true;
        }

        return false;
    }

    private void configureForDrmState() {
        if (DRM.isLicensed()) {
            mActionButtonView.setText(R.string.history);
        } else {
            mActionButtonView.setText(R.string.action_upgrade_to_pro);
        }
    }

    @Override
    public void onDestroy() {
        ((MainApplication)getApplicationContext()).unregisterDrmTracker(this);
        MainApplication.unregisterForBus(this, this);
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();

        updateLinkLoadTimeStats();

        configureForDrmState();

        Tamper.checkForTamper(getApplicationContext(), mTamListener);
        MainApplication.postEvent(getApplicationContext(), new MainApplication.CheckStateEvent());
    }

    @Override
    public void onStart() {
        super.onStart();

        MainApplication.checkRestoreCurrentTabs(this);
    }

    private void updateLinkLoadTimeStats() {
        long timeSavedPerLink = Settings.get().getTimeSavedPerLink();
        if (timeSavedPerLink > -1) {
            String prettyTimeElapsed = Util.getPrettyTimeElapsed(getResources(), timeSavedPerLink, "\n");
            mTimeSavedPerLinkTextView.setText(prettyTimeElapsed);
            Log.d(Settings.LOAD_TIME_TAG, "*** " + (prettyTimeElapsed.replace("\n", " ")));
        } else {
            String prettyTimeElapsed = Util.getPrettyTimeElapsed(getResources(), 0, "\n");
            mTimeSavedPerLinkTextView.setText(prettyTimeElapsed);
            // The "time saved so far == 0" link is a better one to display when there's no data yet
            if (mStatsFlipView.getDefaultView() == mTimeSavedPerLinkContainerView) {
                mStatsFlipView.toggleFlip(false);
            }
        }

        long totalTimeSaved = Settings.get().getTotalTimeSaved();
        if (totalTimeSaved > -1) {
            String prettyTimeElapsed = Util.getPrettyTimeElapsed(getResources(), totalTimeSaved, "\n");
            mTimeSavedTotalTextView.setText(prettyTimeElapsed);
            Log.d(Settings.LOAD_TIME_TAG, "*** " + (prettyTimeElapsed.replace("\n", " ")));
        }
    }

    void startActivity(Intent intent, View view) {

        boolean useLaunchAnimation = (view != null) &&
                !intent.hasExtra(Constant.INTENT_EXTRA_IGNORE_LAUNCH_ANIMATION);

        if (useLaunchAnimation) {
            ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(view, 0, 0,
                    view.getMeasuredWidth(), view.getMeasuredHeight());

            startActivity(intent, opts.toBundle());
        } else {
            startActivity(intent);
        }
    }

    private Tamper.Listener mTamListener = new Tamper.Listener() {
        @Override
        public void onTweaked() {
            finish();
        }
    };

    @SuppressWarnings("unused")
    @Subscribe
    public void onLinkLoadTimeStatsUpdatedEvent(Settings.LinkLoadTimeStatsUpdatedEvent event) {
        updateLinkLoadTimeStats();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onStateChangedEvent(MainApplication.StateChangedEvent event) {
        configureForDrmState();

        if (event.mOldState != DRM.LICENSE_VALID
                && event.mState == DRM.LICENSE_VALID
                && event.mDisplayToast
                && event.mDisplayedToast == false) {
            Toast.makeText(this, R.string.valid_license_detected, Toast.LENGTH_LONG).show();
            event.mDisplayedToast = true;
        }
    }
}
