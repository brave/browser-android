package com.linkbubble;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.net.Uri;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.widget.TextView;
import android.widget.Toast;
import com.linkbubble.physics.Draggable;
import com.linkbubble.ui.BubbleDraggable;
import com.linkbubble.ui.BubbleFlowDraggable;
import com.linkbubble.ui.BubbleFlowView;
import com.linkbubble.ui.CanvasView;
import com.linkbubble.ui.Prompt;
import com.linkbubble.ui.SettingsFragment;
import com.linkbubble.ui.TabView;
import com.linkbubble.util.ActionItem;
import com.linkbubble.util.Analytics;
import com.linkbubble.util.AppPoller;
import com.linkbubble.util.Util;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * Created by gw on 2/10/13.
 */
public class MainController implements Choreographer.FrameCallback {

    private static final String TAG = "MainController";

    protected static MainController sInstance;

    // Simple event classes used for the event bus
    public static class BeginBubbleDragEvent {
    }

    public static class EndBubbleDragEvent {
    }

    public static class BeginAnimateFinalTabAwayEvent {
        public TabView mTab;
    }

    public static class BeginExpandTransitionEvent {
        public float mPeriod;
    }

    public static class BeginCollapseTransitionEvent {
        public float mPeriod;
    }

    public static class EndCollapseTransitionEvent {
    }

    public static class OrientationChangedEvent {
    }

    public static class CurrentTabChangedEvent {
        public TabView mTab;
    }

    public static class DraggableBubbleMovedEvent {
        public int mX, mY;
    }

    public static void addRootWindow(View v, WindowManager.LayoutParams lp) {
        MainController mc = get();
        if (!mc.mRootViews.contains(v)) {
            mc.mRootViews.add(v);
            if (mc.mRootWindowsVisible) {
                mc.mWindowManager.addView(v, lp);
            }
        }
    }

    public static void removeRootWindow(View v) {
        MainController mc = get();
        if (mc.mRootViews.contains(v)) {
            mc.mRootViews.remove(v);
            if (mc.mRootWindowsVisible) {
                mc.mWindowManager.removeView(v);
            }
        }
    }

    public static void updateRootWindowLayout(View v, WindowManager.LayoutParams lp) {
        MainController mc = get();
        if (mc.mRootWindowsVisible && mc.mRootViews.contains(v)) {
            mc.mWindowManager.updateViewLayout(v, lp);
        }
    }

    private void enableRootWindows() {
        if (!mRootWindowsVisible) {
            for (View v : mRootViews) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                lp.alpha = 1.0f;
                mWindowManager.updateViewLayout(v, lp);
            }
            mRootWindowsVisible = true;
        }
    }

    private void disableRootWindows() {
        if (mRootWindowsVisible) {
            for (View v : mRootViews) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                lp.alpha = 0.0f;
                mWindowManager.updateViewLayout(v, lp);
            }
            mRootWindowsVisible = false;
        }
    }

    private Vector<View> mRootViews = new Vector<View>();
    private boolean mRootWindowsVisible = true;
    private WindowManager mWindowManager;

    private OrientationChangedEvent mOrientationChangedEvent = new OrientationChangedEvent();
    private BeginExpandTransitionEvent mBeginExpandTransitionEvent = new BeginExpandTransitionEvent();

    private static class OpenUrlInfo {
        String mUrlAsString;
        long mStartTime;

        OpenUrlInfo(String url, long startTime) {
            mUrlAsString = url;
            mStartTime = startTime;
        }
    };

    private ArrayList<OpenUrlInfo> mOpenUrlInfos = new ArrayList<OpenUrlInfo>();

    // End of event bus classes

    public static MainController get() {
        return sInstance;
    }

    public static void create(Context context, EventHandler eventHandler) {
        if (sInstance != null) {
            throw new RuntimeException("Only one instance of MainController allowed at any one time");
        }
        sInstance = new MainController(context, eventHandler);
    }

    public static void destroy() {
        if (sInstance == null) {
            throw new RuntimeException("No instance to destroy");
        }

        Settings.get().saveData();

        MainApplication app = (MainApplication) sInstance.mContext.getApplicationContext();
        Bus bus = app.getBus();
        bus.unregister(sInstance);

        if (Constant.PROFILE_FPS) {
            sInstance.mWindowManager.removeView(sInstance.mTextView);
        }
        sInstance.mBubbleDraggable.destroy();
        sInstance.mBubbleFlowDraggable.destroy();
        sInstance.mCanvasView.destroy();
        sInstance.mChoreographer.removeFrameCallback(sInstance);
        sInstance.endAppPolling();
        sInstance = null;
    }

    public interface EventHandler {
        public void onDestroy();
    }

    protected EventHandler mEventHandler;
    protected int mBubblesLoaded;
    private AppPoller mAppPoller;

    protected Context mContext;
    private String mAppPackageName;
    private Choreographer mChoreographer;
    protected boolean mUpdateScheduled;
    protected CanvasView mCanvasView;

    private BubbleFlowDraggable mBubbleFlowDraggable;
    private BubbleDraggable mBubbleDraggable;

    private long mPreviousFrameTime;

    // false if the user has forcibilty minimized the Bubbles from ContentView. Set back to true once a new link is loaded.
    private boolean mCanAutoDisplayLink;

    BubbleFlowView.AnimationEventListener mOnBubbleFlowExpandFinishedListener = new BubbleFlowView.AnimationEventListener() {

        @Override
        public void onAnimationEnd(BubbleFlowView sender) {
            TabView currentTab = ((BubbleFlowDraggable)sender).getCurrentTab();
            if (currentTab != null && currentTab.getContentView() != null) {
                currentTab.getContentView().saveLoadTime();
            }
        }
    };

    BubbleFlowView.AnimationEventListener mOnBubbleFlowCollapseFinishedListener = new BubbleFlowView.AnimationEventListener() {

        @Override
        public void onAnimationEnd(BubbleFlowView sender) {
            mBubbleDraggable.setVisibility(View.VISIBLE);
            TabView tab = mBubbleFlowDraggable.getCurrentTab();
            if (tab != null) {
                tab.setImitator(mBubbleDraggable);
            }
            mSetBubbleFlowGone = true;
            mBubbleFlowDraggable.postDelayed(mSetBubbleFlowGoneRunnable, 33);
        }
    };

    private boolean mSetBubbleFlowGone = false;
    Runnable mSetBubbleFlowGoneRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSetBubbleFlowGone) {
                mBubbleFlowDraggable.setVisibility(View.GONE);
            }
        }
    };

    Runnable mSetBubbleGoneRunnable = new Runnable() {
        @Override
        public void run() {
            mBubbleDraggable.setVisibility(View.GONE);
        }
    };

    /*
     * Pass all the input along to mBubbleDraggable
     */
    BubbleFlowView.TouchInterceptor mBubbleFlowTouchInterceptor = new BubbleFlowView.TouchInterceptor() {

        @Override
        public boolean onTouchActionDown(MotionEvent event) {
            return mBubbleDraggable.getDraggableHelper().onTouchActionDown(event);
        }

        @Override
        public boolean onTouchActionMove(MotionEvent event) {
            return mBubbleDraggable.getDraggableHelper().onTouchActionMove(event);
        }

        @Override
        public boolean onTouchActionUp(MotionEvent event) {
            boolean result = mBubbleDraggable.getDraggableHelper().onTouchActionUp(event);
            mBubbleFlowDraggable.setTouchInterceptor(null);
            return result;
        }
    };

    protected MainController(Context context, EventHandler eventHandler) {
        Util.Assert(sInstance == null, "non-null instance");
        sInstance = this;
        mContext = context;
        mAppPackageName = mContext.getPackageName();
        mEventHandler = eventHandler;

        mAppPoller = new AppPoller(context);
        mAppPoller.setListener(mAppPollerListener);

        mCanAutoDisplayLink = true;

        mCanDisplay = true;

        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        if (Constant.PROFILE_FPS) {
            mTextView = new TextView(mContext);
            mTextView.setTextColor(0xff00ffff);
            mTextView.setTextSize(32.0f);
            mWindowManagerParams.gravity = Gravity.TOP | Gravity.LEFT;
            mWindowManagerParams.x = 500;
            mWindowManagerParams.y = 16;
            mWindowManagerParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            mWindowManagerParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            mWindowManagerParams.type = WindowManager.LayoutParams.TYPE_PHONE;
            mWindowManagerParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            mWindowManagerParams.format = PixelFormat.TRANSPARENT;
            mWindowManagerParams.setTitle("LinkBubble: Debug Text");
            mWindowManager.addView(mTextView, mWindowManagerParams);
        }

        mUpdateScheduled = false;
        mChoreographer = Choreographer.getInstance();
        mCanvasView = new CanvasView(mContext);

        MainApplication app = (MainApplication) mContext.getApplicationContext();
        Bus bus = app.getBus();
        bus.register(this);

        updateIncognitoMode(Settings.get().isIncognitoMode());

        LayoutInflater inflater = LayoutInflater.from(mContext);

        mBubbleDraggable = (BubbleDraggable) inflater.inflate(R.layout.view_bubble_draggable, null);
        Point bubbleRestingPoint = Settings.get().getBubbleRestingPoint();
        float fromX;
        if (bubbleRestingPoint.x > Config.mScreenCenterX) {
            fromX = Config.mBubbleSnapRightX + Config.mBubbleWidth;
        } else {
            fromX = Config.mBubbleSnapLeftX - Config.mBubbleWidth;
        }
        mBubbleDraggable.configure((int)fromX, bubbleRestingPoint.y, bubbleRestingPoint.x, bubbleRestingPoint.y, 0.4f, mCanvasView);

        mBubbleDraggable.setOnUpdateListener(new BubbleDraggable.OnUpdateListener() {
            @Override
            public void onUpdate(Draggable draggable, float dt) {
                mBubbleFlowDraggable.syncWithBubble(draggable);
            }
        });

        mBubbleFlowDraggable = (BubbleFlowDraggable) inflater.inflate(R.layout.view_bubble_flow, null);
        mBubbleFlowDraggable.configure(null);
        mBubbleFlowDraggable.collapse(0, null);
        mBubbleFlowDraggable.setBubbleDraggable(mBubbleDraggable);
        mBubbleFlowDraggable.setVisibility(View.GONE);

        mBubbleDraggable.setBubbleFlowDraggable(mBubbleFlowDraggable);

        MainApplication.sDrm.requestLicenseStatus();
    }

    /*
     * Begin the destruction process.
     */
    public void finish() {
        mEventHandler.onDestroy();
    }

    private TextView mTextView;
    private WindowManager.LayoutParams mWindowManagerParams = new WindowManager.LayoutParams();

    public void onPageLoaded(TabView tab, boolean withError) {
        // Ensure this is not an edge case where the Tab has already been destroyed, re #254
        if (getActiveTabCount() == 0 || isTabActive(tab) == false) {
            return;
        }

        saveCurrentTabs();
    }

    public void autoContentDisplayLinkLoaded(TabView tab) {
        // Ensure this is not an edge case where the Tab has already been destroyed, re #254
        if (getActiveTabCount() == 0 || isTabActive(tab) == false) {
            return;
        }

        if (Settings.get().getAutoContentDisplayLinkLoaded()) {
            displayTab(tab);
        }
    }

    public boolean displayTab(TabView tab) {
        if (!mBubbleDraggable.isDragging() && mCanAutoDisplayLink) {
            switch (mBubbleDraggable.getCurrentMode()) {
                case BubbleView:
                    mBubbleFlowDraggable.setCenterItem(tab);
                    mBubbleDraggable.switchToExpandedView();
                    return true;
            }
        }

        return false;
    }

    public void saveCurrentTabs() {
        if (mBubbleFlowDraggable != null) {
            mBubbleFlowDraggable.saveCurrentTabs();
        }
    }

    public void updateIncognitoMode(boolean incognito) {
        CookieSyncManager.createInstance(mContext);
        CookieManager.getInstance().setAcceptCookie(!incognito);

        if (mBubbleFlowDraggable != null) {
            mBubbleFlowDraggable.updateIncognitoMode(incognito);
        }
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onIncognitoModeChanged(SettingsFragment.IncognitoModeChangedEvent event) {
        updateIncognitoMode(event.mIncognito);
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onEndCollapseTransition(EndCollapseTransitionEvent e) {
        showBadge(true);
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onBeginExpandTransition(MainController.BeginExpandTransitionEvent e) {
        showBadge(false);
    }

    public void scheduleUpdate() {
        if (!mUpdateScheduled) {
            mUpdateScheduled = true;
            mChoreographer.postFrameCallback(this);
        }
    }

    // TODO: think of a better name
    public void startDraggingFromContentView() {
        // When we start dragging, configure the BubbleFlowView to pass all its input to our TouchInterceptor so we
        // can re-route it to the BubbleDraggable. This is a bit messy, but necessary so as to cleanly using the same
        // MotionEvent chain for the BubbleFlowDraggable and BubbleDraggable so the items visually sync up.
        mBubbleFlowDraggable.setTouchInterceptor(mBubbleFlowTouchInterceptor);
        mBubbleFlowDraggable.collapse(Constant.BUBBLE_ANIM_TIME, mOnBubbleFlowCollapseFinishedListener);
        mBubbleDraggable.setVisibility(View.VISIBLE);
    }

    public BubbleDraggable getBubbleDraggable() {
        return mBubbleDraggable;
    }

    public int getActiveTabCount() {
        return mBubbleFlowDraggable != null ? mBubbleFlowDraggable.getActiveTabCount() : 0;
    }

    public boolean isUrlActive(String urlAsString) {
        return mBubbleFlowDraggable != null ? mBubbleFlowDraggable.isUrlActive(urlAsString) : false;
    }

    public boolean wasUrlRecentlyLoaded(String urlAsString, long urlLoadStartTime) {
        for (OpenUrlInfo openUrlInfo : mOpenUrlInfos) {
            long delta = urlLoadStartTime - openUrlInfo.mStartTime;
            if (openUrlInfo.mUrlAsString.equals(urlAsString) && delta < 7 * 1000) {
                //Log.d("blerg", "urlAsString:" + urlAsString + ", openUrlInfo.mUrlAsString:" + openUrlInfo.mUrlAsString + ", delta: " + delta);
                return true;
            }
        }
        return false;
    }

    public int getTabIndex(TabView tab) {
        return mBubbleFlowDraggable != null ? mBubbleFlowDraggable.getIndexOfView(tab) : -1;
    }

    public boolean isTabActive(TabView tab) {
        int index = getTabIndex(tab);
        if (index > -1) {
            return true;
        }
        return false;
    }

    private static final int MAX_SAMPLE_COUNT = 60 * 10;
    private static final float MAX_VALID_TIME = 10.0f / 60.0f;
    private float [] mSamples = new float[MAX_SAMPLE_COUNT];
    private int mSampleCount = 0;

    public void doFrame(long frameTimeNanos) {
        mUpdateScheduled = false;

        float t0 = mPreviousFrameTime / 1000000000.0f;
        float t1 = frameTimeNanos / 1000000000.0f;
        float t = t1 - t0;
        mPreviousFrameTime = frameTimeNanos;
        float dt;
        if (Constant.DYNAMIC_ANIM_STEP) {
            dt = Util.clamp(0.0f, t, 3.0f / 60.0f);
        } else {
            dt = 1.0f / 60.0f;
        }

        if (mBubbleFlowDraggable.update()) {
            scheduleUpdate();
        }

        mBubbleDraggable.update(dt);

        mCanvasView.update(dt);

        if (getActiveTabCount() == 0 && mBubblesLoaded > 0 && !mUpdateScheduled) {
            // Will be non-zero in the event a link has been dismissed by a user, but its TabView
            // instance is still animating off screen. In that case, keep triggering an update so that when the
            // item finishes, we are ready to call onDestroy().
            if (mBubbleFlowDraggable.getVisibleTabCount() == 0) {
                finish();
            } else {
                scheduleUpdate();
            }
        }

        updateKeyguardLocked();

        if (Constant.PROFILE_FPS) {
            if (t < MAX_VALID_TIME) {
                mSamples[mSampleCount % MAX_SAMPLE_COUNT] = t;
                ++mSampleCount;
            }

            float total = 0.0f;
            float worst = 0.0f;
            float best = 99999999.0f;
            int badFrames = 0;
            int frameCount = Math.min(mSampleCount, MAX_SAMPLE_COUNT);
            for (int i = 0; i < frameCount; ++i) {
                total += mSamples[i];
                worst = Math.max(worst, mSamples[i]);
                best = Math.min(best, mSamples[i]);
                if (mSamples[i] > 1.5f / 60.0f) {
                    ++badFrames;
                }
            }

            String sbest = String.format("%.2f", 1000.0f * best);
            String sworst = String.format("%.2f", 1000.0f * worst);
            String savg = String.format("%.2f", 1000.0f * total / frameCount);
            String badpc = String.format("%.2f", 100.0f * badFrames / frameCount);
            String s = "Best=" + sbest + "\nWorst=" + sworst + "\nAvg=" + savg + "\nBad=" + badFrames + "\nBad %=" + badpc + "%";

            mTextView.setSingleLine(false);
            mTextView.setText(s);
            scheduleUpdate();
        }
    }

    public void onCloseSystemDialogs() {
        switchToBubbleView();
    }

    public void onOrientationChanged() {
        Config.init(mContext);
        Settings.get().onOrientationChange();
        mBubbleDraggable.onOrientationChanged();
        mBubbleFlowDraggable.onOrientationChanged();
        MainApplication.postEvent(mContext, mOrientationChangedEvent);
    }

    private boolean handleResolveInfo(ResolveInfo resolveInfo, String urlAsString, long urlLoadStartTime) {
        if (Settings.get().didRecentlyRedirectToApp(urlAsString)) {
            return false;
        }

        boolean isLinkBubble = resolveInfo.activityInfo != null
                && resolveInfo.activityInfo.packageName.equals(mAppPackageName);
        if (isLinkBubble == false && MainApplication.loadResolveInfoIntent(mContext, resolveInfo, urlAsString, -1)) {
            if (getActiveTabCount() == 0) {
                finish();
            }

            String title = String.format(mContext.getString(R.string.link_loaded_with_app),
                    resolveInfo.loadLabel(mContext.getPackageManager()));
            MainApplication.saveUrlInHistory(mContext, resolveInfo, urlAsString, title);
            Settings.get().addRedirectToApp(urlAsString);
            Settings.get().trackLinkLoadTime(System.currentTimeMillis() - urlLoadStartTime, Settings.LinkLoadType.AppRedirectInstant, urlAsString);
            return true;
        }

        return false;
    }

    public TabView openUrl(final String urlAsString, long urlLoadStartTime, final boolean setAsCurrentTab, String openedFromAppName) {
        return openUrl(urlAsString, urlLoadStartTime, setAsCurrentTab, openedFromAppName, true);
    }

    public TabView openUrl(final String urlAsString, long urlLoadStartTime, final boolean setAsCurrentTab,
                           String openedFromAppName, boolean doLicenseCheck) {

        Analytics.trackOpenUrl(openedFromAppName);

        if (wasUrlRecentlyLoaded(urlAsString, urlLoadStartTime)) {
            Toast.makeText(mContext, R.string.duplicate_link_will_not_be_loaded, Toast.LENGTH_SHORT).show();
            return null;
        }

        if (doLicenseCheck && !DRM.allowProFeatures() && getActiveTabCount() > 0) {
            if (urlAsString.equals(Constant.NEW_TAB_URL) == false
                && urlAsString.equals(Constant.PRIVACY_POLICY_URL) == false
                && urlAsString.equals(Constant.TERMS_OF_SERVICE_URL) == false) {
                MainApplication.showUpgradePrompt(mContext, R.string.upgrade_incentive_one_link, Analytics.UPGRADE_PROMPT_SINGLE_TAB_OPEN_URL);
                MainApplication.openInBrowser(mContext, urlAsString, true);
                return null;
            }
        }

        URL url;
        try {
            url = new URL(urlAsString);
        } catch (MalformedURLException e) { // If this is not a valid scheme, back out. #271
            Toast.makeText(mContext, mContext.getString(R.string.unsupported_scheme), Toast.LENGTH_SHORT).show();
            if (getActiveTabCount() == 0) {
                finish();
            }
            return null;
        }

        PackageManager packageManager = mContext.getPackageManager();
        if (Settings.get().redirectUrlToBrowser(urlAsString, packageManager)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(urlAsString));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (MainApplication.openInBrowser(mContext, intent, false)) {
                if (getActiveTabCount() == 0) {
                    finish();
                }

                String title = String.format(mContext.getString(R.string.link_redirected), Settings.get().getDefaultBrowserLabel());
                MainApplication.saveUrlInHistory(mContext, null, urlAsString, title);
                return null;
            }
        }

        boolean showAppPicker = false;

        final List<ResolveInfo> resolveInfos = Settings.get().getAppsThatHandleUrl(url.toString(), packageManager);
        ResolveInfo defaultAppResolveInfo = Settings.get().getDefaultAppForUrl(url, resolveInfos);
        if (resolveInfos != null && resolveInfos.size() > 0) {
            if (defaultAppResolveInfo != null) {
                if (handleResolveInfo(defaultAppResolveInfo, urlAsString, urlLoadStartTime)) {
                    return null;
                }
            } else if (resolveInfos.size() == 1) {
                if (handleResolveInfo(resolveInfos.get(0), urlAsString, urlLoadStartTime)) {
                    return null;
                }
            } else {
                showAppPicker = true;
            }
        }

        mCanAutoDisplayLink = true;
        final TabView result = openUrlInTab(urlAsString, urlLoadStartTime, setAsCurrentTab, showAppPicker);

        // Show app picker after creating the tab to load so that we have the instance to close if redirecting to an app, re #292.
        if (showAppPicker && MainApplication.sShowingAppPickerDialog == false) {
            AlertDialog dialog = ActionItem.getActionItemPickerAlert(mContext, resolveInfos, R.string.pick_default_app,
                    new ActionItem.OnActionItemDefaultSelectedListener() {
                        @Override
                        public void onSelected(ActionItem actionItem, boolean always) {
                            boolean loaded = false;
                            for (ResolveInfo resolveInfo : resolveInfos) {
                                if (resolveInfo.activityInfo.packageName.equals(actionItem.mPackageName)
                                        && resolveInfo.activityInfo.name.equals(actionItem.mActivityClassName)) {
                                    if (always) {
                                        Settings.get().setDefaultApp(urlAsString, resolveInfo);
                                    }

                                    // Jump out of the loop and load directly via a BubbleView below
                                    if (resolveInfo.activityInfo.packageName.equals(mAppPackageName)) {
                                        break;
                                    }

                                    loaded = MainApplication.loadIntent(mContext, actionItem.mPackageName,
                                            actionItem.mActivityClassName, urlAsString, -1, true);
                                    break;
                                }
                            }

                            if (loaded) {
                                Settings.get().addRedirectToApp(urlAsString);
                                closeTab(result, contentViewShowing(), false);
                                if (getActiveTabCount() == 0) {
                                    finish();
                                }
                            }
                        }
                    });

            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    MainApplication.sShowingAppPickerDialog = false;
                }
            });

            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
            MainApplication.sShowingAppPickerDialog = true;
        }

        return result;
    }

    protected TabView openUrlInTab(String url, long urlLoadStartTime, boolean setAsCurrentTab, boolean hasShownAppPicker) {
        if (getActiveTabCount() == 0) {
            mBubbleDraggable.setVisibility(View.VISIBLE);
        }

        TabView result = mBubbleFlowDraggable.openUrlInTab(url, urlLoadStartTime, setAsCurrentTab, hasShownAppPicker);
        showBadge(getActiveTabCount() > 1 ? true : false);
        ++mBubblesLoaded;

        mOpenUrlInfos.add(new OpenUrlInfo(url, urlLoadStartTime));

        return result;
    }

    public void showBadge(boolean show) {
        if (mBubbleDraggable != null) {
            int tabCount = mBubbleFlowDraggable.getActiveTabCount();
            mBubbleDraggable.mBadgeView.setCount(tabCount);
            if (show) {
                if (tabCount > 1 && mBubbleDraggable.getCurrentMode() == BubbleDraggable.Mode.BubbleView) {
                    mBubbleDraggable.mBadgeView.show();
                }
            } else {
                mBubbleDraggable.mBadgeView.hide();
            }
        }
    }

    public boolean contentViewShowing() {
        return mBubbleDraggable != null && mBubbleDraggable.getCurrentMode() == BubbleDraggable.Mode.ContentView;
    }

    public boolean closeCurrentTab(Constant.BubbleAction action, boolean animateOff) {
        if (mBubbleFlowDraggable != null) {
            return closeTab(mBubbleFlowDraggable.getCurrentTab(), action, animateOff, true);
        }

        return false;
    }

    public boolean closeTab(TabView tabView, boolean animateOff, boolean canShowUndoPrompt) {
        return closeTab(tabView, Constant.BubbleAction.Close, animateOff, canShowUndoPrompt);
    }

    public boolean closeTab(TabView tabView, Constant.BubbleAction action, boolean animateOff, boolean canShowUndoPrompt) {
        if (mBubbleFlowDraggable != null) {
            mBubbleFlowDraggable.closeTab(tabView, animateOff, action, tabView != null ? tabView.getTotalTrackedLoadTime() : -1);
        }
        int activeTabCount = getActiveTabCount();
        showBadge(activeTabCount > 1 ? true : false);
        if (activeTabCount == 0) {
            hideBubbleDraggable();
            // Ensure BubbleFlowDraggable gets at least 1 update in the event items are animating off screen. See #237.
            scheduleUpdate();
        }

        if (canShowUndoPrompt) {
            String urlAsString = tabView.getUrl().toString();
            String title = MainApplication.sTitleHashMap != null ? MainApplication.sTitleHashMap.get(urlAsString) : null;
            if (title != null) {
                title = "Closed: " + title;
            } else {
                title = "Closed tab";
            }
            Prompt.show(title,
                    mContext.getResources().getString(R.string.action_undo).toUpperCase(),
                    mContext.getResources().getDrawable(R.drawable.ic_undobar_undo),
                    Prompt.LENGTH_SHORT,
                    false,
                    true,
                    new Prompt.OnPromptEventListener() {
                        @Override
                        public void onClick() {

                        }

                        @Override
                        public void onClose() {

                        }
                    }
            );
        }

        return getActiveTabCount() > 0;
    }

    public void closeAllBubbles() {
        closeAllBubbles(true);
    }

    public void closeAllBubbles(boolean removeFromCurrentTabs) {
        mBubbleFlowDraggable.closeAllBubbles(removeFromCurrentTabs);
        hideBubbleDraggable();
    }

    private void hideBubbleDraggable() {
        mBubbleDraggable.setVisibility(View.GONE);
    }

    public void expandBubbleFlow(long time, boolean hideDraggable) {
        mBeginExpandTransitionEvent.mPeriod = time / 1000.0f;
        MainApplication.postEvent(mContext, mBeginExpandTransitionEvent);

        mBubbleFlowDraggable.setVisibility(View.VISIBLE);
        mSetBubbleFlowGone = false; // cancel any pending operation to set visibility to GONE (see #190)
        mBubbleFlowDraggable.expand(time, mOnBubbleFlowExpandFinishedListener);

        if (hideDraggable) {
            mBubbleDraggable.postDelayed(mSetBubbleGoneRunnable, 33);
        }
    }

    public void collapseBubbleFlow(long time) {
        mBubbleFlowDraggable.collapse(time, mOnBubbleFlowCollapseFinishedListener);
    }

    public void switchToBubbleView() {
        mCanAutoDisplayLink = false;
        if (MainController.get().getActiveTabCount() > 0) {
            mBubbleDraggable.switchToBubbleView();
        }
    }

    public void switchToExpandedView() {
        mBubbleDraggable.switchToExpandedView();
    }

    public void beginAppPolling() {
        if (mAppPoller != null) {
            mAppPoller.beginAppPolling();
        }
    }

    public void endAppPolling() {
        if (mAppPoller != null) {
            mAppPoller.endAppPolling();
        }
    }

    AppPoller.AppPollerListener mAppPollerListener = new AppPoller.AppPollerListener() {
        @Override
        public void onAppChanged() {
            switchToBubbleView();
        }
    };

    public void showPreviousBubble() {
        mBubbleFlowDraggable.previousTab();
    }

    public void showNextBubble() {
        mBubbleFlowDraggable.nextTab();
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onStateChangedEvent(MainApplication.StateChangedEvent event) {
        closeAllBubbles(false);
        final Vector<String> urls = Settings.get().loadCurrentTabs();
        if (urls.size() > 0) {
            for (String url : urls) {
                MainApplication.openLink(mContext, url, null);
            }
        }

        if (event.mOldState != DRM.LICENSE_VALID && event.mState == DRM.LICENSE_VALID && event.mDisplayedToast == false) {
            Toast.makeText(mContext, R.string.valid_license_detected, Toast.LENGTH_LONG).show();
            event.mDisplayedToast = true;
        }
    }

    public boolean reloadAllTabs(Context context) {
        boolean reloaded = false;
        closeAllBubbles(false);
        final Vector<String> urls = Settings.get().loadCurrentTabs();
        if (urls.size() > 0) {
            for (String url : urls) {
                MainApplication.openLink(context.getApplicationContext(), url, null);
                reloaded = true;
            }
        }

        return reloaded;
    }

    private boolean mCanDisplay;
    //private static final String SCREEN_LOCK_TAG = "screenlock";

    private void setCanDisplay(boolean canDisplay) {
        if (canDisplay == mCanDisplay) {
            return;
        }
        //Log.d(SCREEN_LOCK_TAG, "*** setCanDisplay() - old:" + mCanDisplay + ", new:" + canDisplay);
        mCanDisplay = canDisplay;
        if (canDisplay) {
            enableRootWindows();
        } else {
            disableRootWindows();
        }
    }

    private void updateKeyguardLocked() {
        KeyguardManager keyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguardManager != null) {
            boolean isLocked = keyguardManager.isKeyguardLocked();
            //Log.d(SCREEN_LOCK_TAG, "keyguardManager.isKeyguardLocked():" + mCanDisplay);
            setCanDisplay(!isLocked);
        }
    }

    void updateScreenState(String action) {
        //Log.d(SCREEN_LOCK_TAG, "---" + action);

        if (action.equals(Intent.ACTION_SCREEN_OFF)) {
            setCanDisplay(false);
        } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
            updateKeyguardLocked();
        } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
            setCanDisplay(true);
        }
    }
}
