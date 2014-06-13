package com.linkbubble.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import com.linkbubble.BuildConfig;
import com.linkbubble.Config;
import com.linkbubble.Constant;
import com.linkbubble.DRM;
import com.linkbubble.MainApplication;
import com.linkbubble.MainController;
import com.linkbubble.R;
import com.linkbubble.Settings;
import com.linkbubble.util.ActionItem;
import com.linkbubble.util.Analytics;
import com.linkbubble.util.PageInspector;
import com.linkbubble.util.Util;
import com.linkbubble.webrender.WebRenderer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/**
 * Created by gw on 19/08/13.
 */
public class ContentView extends FrameLayout {

    private static final String TAG = "UrlLoad";

    private WebRenderer mWebRenderer;
    private TabView mOwnerTabView;

    private CondensedTextView mTitleTextView;
    private CondensedTextView mUrlTextView;
    private ContentViewButton mShareButton;
    private ContentViewButton mReloadButton;
    private ArticleModeButton mArticleModeButton;
    private OpenInAppButton mOpenInAppButton;
    private OpenEmbedButton mOpenEmbedButton;
    private ContentViewButton mOverflowButton;
    private View mRequestLocationShadow;
    private View mRequestLocationContainer;
    private CondensedTextView mRequestLocationTextView;
    private Button mRequestLocationYesButton;
    private LinearLayout mToolbarLayout;
    private EventHandler mEventHandler;
    private int mCurrentProgress = 0;

    private boolean mPageFinishedLoading;
    private Boolean mIsDestroyed = false;
    private Set<String> mAppPickersUrls = new HashSet<String>();

    private List<AppForUrl> mAppsForUrl = new ArrayList<AppForUrl>();
    private List<ResolveInfo> mTempAppsForUrl = new ArrayList<ResolveInfo>();

    private PopupMenu mOverflowPopupMenu;
    private AlertDialog mLongPressAlertDialog;
    private long mInitialUrlLoadStartTime;
    private String mInitialUrlAsString;
    private int mHeaderHeight;
    private Path mTempPath = new Path();

    private Stack<URL> mUrlStack = new Stack<URL>();
    // We only want to handle this once per link. This prevents 3+ dialogs appearing for some links, which is a bad experience. #224
    private boolean mHandledAppPickerForCurrentUrl = false;
    private boolean mUsingLinkBubbleAsDefaultForCurrentUrl = false;

    private static Paint sIndicatorPaint;
    private static Paint sBorderPaint;

    public ContentView(Context context) {
        this(context, null);
    }

    public ContentView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ContentView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (sIndicatorPaint == null) {
            sIndicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sIndicatorPaint.setColor(getResources().getColor(R.color.content_toolbar_background));
        }

        if (sBorderPaint == null) {
            sBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sBorderPaint.setColor(getResources().getColor(R.color.bubble_border));
        }
    }

    public long getTotalTrackedLoadTime() {
        if (mInitialUrlLoadStartTime > -1) {
            return System.currentTimeMillis() - mInitialUrlLoadStartTime;
        }
        return -1;
    }

    public WebRenderer getWebRenderer() {
        return mWebRenderer;
    }

    static class AppForUrl {
        ResolveInfo mResolveInfo;
        URL mUrl;
        Drawable mIcon;

        AppForUrl(ResolveInfo resolveInfo, URL url) {
            mResolveInfo = resolveInfo;
            mUrl = url;
        }

        Drawable getIcon(Context context) {
            if (mIcon == null) {
                // TODO: Handle OutOfMemory error
                mIcon = mResolveInfo.loadIcon(context.getPackageManager());
            }

            return mIcon;
        }
    }

    public interface EventHandler {
        public void onPageLoading(URL url);
        public void onProgressChanged(int progress);
        public void onPageLoaded(boolean withError);
        public boolean onReceivedIcon(Bitmap bitmap);
        public void setDefaultFavicon();
        public void onCanGoBackChanged(boolean canGoBack);
        public boolean hasHighQualityFavicon();
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (isInEditMode()) {
            return;
        }

        float centerX = Config.mScreenCenterX;
        float indicatorEndY = 2.f;
        float indicatorStartX = centerX - mHeaderHeight + indicatorEndY;
        float indicatorEndX = centerX + mHeaderHeight - indicatorEndY;

        mTempPath.reset();
        mTempPath.moveTo(indicatorStartX, mHeaderHeight);
        mTempPath.lineTo(centerX, indicatorEndY);
        mTempPath.lineTo(indicatorEndX, mHeaderHeight);
        canvas.drawPath(mTempPath, sIndicatorPaint);

        canvas.drawLine(indicatorEndY, mHeaderHeight, indicatorStartX, mHeaderHeight, sBorderPaint);
        canvas.drawLine(indicatorStartX, mHeaderHeight, centerX, 0, sBorderPaint);
        canvas.drawLine(centerX, indicatorEndY, indicatorEndX, mHeaderHeight, sBorderPaint);
        canvas.drawLine(indicatorEndX, mHeaderHeight, Config.mScreenWidth, mHeaderHeight, sBorderPaint);
    }

    public void destroy() {
        Log.d(TAG, "*** destroy() - url" + (mWebRenderer.getUrl() != null ? mWebRenderer.getUrl().toString() : "<null>"));
        mIsDestroyed = true;
        removeView(mWebRenderer.getView());
        mWebRenderer.destroy();
        //if (mDelayedAutoContentDisplayLinkLoadedScheduled) {
        //    mDelayedAutoContentDisplayLinkLoadedScheduled = false;
        //    Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
        //}
        removeCallbacks(mDelayedAutoContentDisplayLinkLoadedRunnable);
    }

    public void updateIncognitoMode(boolean incognito) {
        mWebRenderer.updateIncognitoMode(incognito);
    }

    private void showSelectShareMethod(final String urlAsString, final boolean closeBubbleOnShare) {

        AlertDialog alertDialog = ActionItem.getShareAlert(getContext(), false, new ActionItem.OnActionItemSelectedListener() {
            @Override
            public void onSelected(ActionItem actionItem) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.setClassName(actionItem.mPackageName, actionItem.mActivityClassName);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(Intent.EXTRA_TEXT, urlAsString);
                String title = MainApplication.sTitleHashMap != null ? MainApplication.sTitleHashMap.get(urlAsString) : null;
                if (title != null) {
                    intent.putExtra(Intent.EXTRA_SUBJECT, title);
                }
                getContext().startActivity(intent);

                boolean isCopyToClipboardAction = actionItem.mPackageName.equals("com.google.android.apps.docs")
                        && actionItem.mActivityClassName.equals("com.google.android.apps.docs.app.SendTextToClipboardActivity");

                //if (closeBubbleOnShare && isCopyToClipboardAction == false && MainController.get() != null) {
                //    MainController.get().closeTab(mOwnerTabView, true);
                //}
            }
        });
        alertDialog.show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    void configure(String urlAsString, TabView ownerTabView, long urlLoadStartTime, boolean hasShownAppPicker, EventHandler eventHandler) throws MalformedURLException {
        View webRendererPlaceholder = findViewById(R.id.web_renderer_placeholder);
        mWebRenderer = WebRenderer.create(WebRenderer.Type.WebView, getContext(), mWebRendererController, webRendererPlaceholder, TAG);
        mWebRenderer.setUrl(urlAsString);

        mOwnerTabView = ownerTabView;
        mHeaderHeight = getResources().getDimensionPixelSize(R.dimen.toolbar_header);
        mHandledAppPickerForCurrentUrl = hasShownAppPicker;
        mUsingLinkBubbleAsDefaultForCurrentUrl = false;

        if (hasShownAppPicker) {
            mAppPickersUrls.add(urlAsString);
        }

        mToolbarLayout = (LinearLayout) findViewById(R.id.content_toolbar);
        mTitleTextView = (CondensedTextView) findViewById(R.id.title_text);
        mUrlTextView = (CondensedTextView) findViewById(R.id.url_text);

        findViewById(R.id.content_text_container).setOnTouchListener(mOnTextContainerTouchListener);

        mShareButton = (ContentViewButton)findViewById(R.id.share_button);
        mShareButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_share));
        mShareButton.setOnClickListener(mOnShareButtonClickListener);

        mOpenInAppButton = (OpenInAppButton)findViewById(R.id.open_in_app_button);
        mOpenInAppButton.setOnOpenInAppClickListener(mOnOpenInAppButtonClickListener);

        mOpenEmbedButton = (OpenEmbedButton)findViewById(R.id.open_embed_button);
        mOpenEmbedButton.setOnOpenEmbedClickListener(mOnOpenEmbedButtonClickListener);

        mReloadButton = (ContentViewButton)findViewById(R.id.reload_button);
        mReloadButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_reload));
        mReloadButton.setOnClickListener(mOnReloadButtonClickListener);

        mArticleModeButton = (ArticleModeButton)findViewById(R.id.article_mode_button);
        mArticleModeButton.setState(ArticleModeButton.State.Article);
        mArticleModeButton.setOnClickListener(mOnArticleModeButtonClickListener);

        mOverflowButton = (ContentViewButton)mToolbarLayout.findViewById(R.id.overflow_button);
        mOverflowButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_overflow_round));
        mOverflowButton.setOnClickListener(mOnOverflowButtonClickListener);

        mRequestLocationShadow = findViewById(R.id.request_location_shadow);
        mRequestLocationContainer = findViewById(R.id.request_location_container);
        mRequestLocationTextView = (CondensedTextView) findViewById(R.id.requesting_location_text_view);
        mRequestLocationYesButton = (Button) findViewById(R.id.access_location_yes);
        findViewById(R.id.access_location_no).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideAllowLocationDialog();
            }
        });

        mEventHandler = eventHandler;
        mEventHandler.onCanGoBackChanged(false);
        mPageFinishedLoading = false;

        updateIncognitoMode(Settings.get().isIncognitoMode());

        mInitialUrlLoadStartTime = urlLoadStartTime;
        mInitialUrlAsString = urlAsString;

        updateAndLoadUrl(urlAsString);
        updateAppsForUrl(mWebRenderer.getUrl());
        Log.d(TAG, "load url: " + urlAsString);
        updateUrlTitleAndText(urlAsString);
    }

    WebRenderer.Controller mWebRendererController = new WebRenderer.Controller() {

        @Override
        public boolean shouldOverrideUrlLoading(String urlAsString, boolean viaUserInput) {
            if (mIsDestroyed) {
                return true;
            }

            if (urlAsString.startsWith("tel:")) {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(urlAsString));
                if (MainApplication.loadIntent(getContext(), intent, urlAsString, mInitialUrlLoadStartTime)) {
                    MainController.get().switchToBubbleView();
                }
                return true;
            }

            URL updatedUrl = getUpdatedUrl(urlAsString);
            if (updatedUrl == null) {
                Log.d(TAG, "ignore unsupported URI scheme: " + urlAsString);
                showOpenInBrowserPrompt(R.string.unsupported_scheme_default_browser,
                        R.string.unsupported_scheme_no_default_browser, mWebRenderer.getUrl().toString());
                return true;        // true because we've handled the link ourselves
            }

            Log.d(TAG, "shouldOverrideUrlLoading() - url:" + urlAsString);
            if (viaUserInput) {
                URL currentUrl = mWebRenderer.getUrl();
                mUrlStack.push(currentUrl);
                mEventHandler.onCanGoBackChanged(mUrlStack.size() > 0);
                Log.d(TAG, "[urlstack] push:" + currentUrl.toString() + ", urlStack.size():" + mUrlStack.size());// + ", delta:" + touchUpTimeDelta);
                mHandledAppPickerForCurrentUrl = false;
                mUsingLinkBubbleAsDefaultForCurrentUrl = false;
            }

            //if (mDelayedAutoContentDisplayLinkLoadedScheduled) {
            //    mDelayedAutoContentDisplayLinkLoadedScheduled = false;
            //    Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
            //}
            removeCallbacks(mDelayedAutoContentDisplayLinkLoadedRunnable);

            updateAndLoadUrl(urlAsString);
            return true;
        }

        @Override
        public void onLoadUrl(String urlAsString) {
            try {
                URL url = new URL(urlAsString);
                mEventHandler.onPageLoading(url);
                updateUrlTitleAndText(urlAsString);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onReceivedError() {
            Log.d(TAG, "onReceivedError()");
            mEventHandler.onPageLoaded(true);
            mReloadButton.setVisibility(VISIBLE);
            mShareButton.setVisibility(GONE);
            mArticleModeButton.setVisibility(GONE);
        }

        @Override
        public void onPageStarted(final String urlAsString, Bitmap favIcon) {
            Log.d(TAG, "onPageStarted() - " + urlAsString);

            // Ensure that items opened in new tabs are redirected to a browser when not licensed, re #371, re #360
            if (mInitialUrlAsString.equals(Constant.NEW_TAB_URL) && DRM.allowProFeatures() == false) {
                MainApplication.showUpgradePrompt(getContext(), R.string.upgrade_incentive_one_link, Analytics.UPGRADE_PROMPT_SINGLE_TAB_REDIRECT);
                openInBrowser(urlAsString);
                return;
            }

            if (mIsDestroyed) {
                return;
            }

            hideAllowLocationDialog();

            mPageFinishedLoading = false;

            String oldUrl = mWebRenderer.getUrl().toString();

            if (updateUrl(urlAsString) == false) {
                List<ResolveInfo> apps = Settings.get().getAppsThatHandleUrl(urlAsString, getContext().getPackageManager());
                boolean openedInApp = apps != null && apps.size() > 0 ? openInApp(apps.get(0), urlAsString) : false;
                if (openedInApp == false) {
                    openInBrowser(urlAsString);
                }
                return;
            }

            if (oldUrl.equals(Constant.NEW_TAB_URL)) {
                MainController.get().saveCurrentTabs();
            }

            mWebRenderer.resetPageInspector();

            final Context context = getContext();
            PackageManager packageManager = context.getPackageManager();

            URL currentUrl = mWebRenderer.getUrl();
            updateAppsForUrl(Settings.get().getAppsThatHandleUrl(currentUrl.toString(), packageManager), currentUrl);
            if (Settings.get().redirectUrlToBrowser(urlAsString, packageManager)) {
                if (openInBrowser(urlAsString)) {
                    String title = String.format(context.getString(R.string.link_redirected), Settings.get().getDefaultBrowserLabel());
                    MainApplication.saveUrlInHistory(context, null, urlAsString, title);
                    return;
                }
            }

            if (mHandledAppPickerForCurrentUrl == false
                    && mUsingLinkBubbleAsDefaultForCurrentUrl == false
                    && mAppsForUrl != null
                    && mAppsForUrl.size() > 0
                    && Settings.get().didRecentlyRedirectToApp(urlAsString) == false) {

                AppForUrl defaultAppForUrl = getDefaultAppForUrl();
                if (defaultAppForUrl != null) {
                    if (Util.isLinkBubbleResolveInfo(defaultAppForUrl.mResolveInfo)) {
                        mUsingLinkBubbleAsDefaultForCurrentUrl = true;
                    } else {
                        if (openInApp(defaultAppForUrl.mResolveInfo, urlAsString)) {
                            return;
                        }
                    }
                } else {
                    boolean isOnlyLinkBubble = mAppsForUrl.size() == 1 ? Util.isLinkBubbleResolveInfo(mAppsForUrl.get(0).mResolveInfo) : false;
                    if (isOnlyLinkBubble == false && MainApplication.sShowingAppPickerDialog == false &&
                            mHandledAppPickerForCurrentUrl == false && mAppPickersUrls.contains(urlAsString) == false) {
                        final ArrayList<ResolveInfo> resolveInfos = new ArrayList<ResolveInfo>();
                        for (AppForUrl appForUrl : mAppsForUrl) {
                            resolveInfos.add(appForUrl.mResolveInfo);
                        }
                        AlertDialog dialog = ActionItem.getActionItemPickerAlert(context, resolveInfos, R.string.pick_default_app,
                                new ActionItem.OnActionItemDefaultSelectedListener() {
                                    @Override
                                    public void onSelected(ActionItem actionItem, boolean always) {
                                        boolean loaded = false;
                                        String appPackageName = context.getPackageName();
                                        for (ResolveInfo resolveInfo : resolveInfos) {
                                            if (resolveInfo.activityInfo.packageName.equals(actionItem.mPackageName)
                                                    && resolveInfo.activityInfo.name.equals(actionItem.mActivityClassName)) {
                                                if (always) {
                                                    Settings.get().setDefaultApp(urlAsString, resolveInfo);
                                                }

                                                // Jump out of the loop and load directly via a BubbleView below
                                                if (resolveInfo.activityInfo.packageName.equals(appPackageName)) {
                                                    break;
                                                }

                                                mInitialUrlLoadStartTime = -1;
                                                loaded = MainApplication.loadIntent(context, actionItem.mPackageName,
                                                        actionItem.mActivityClassName, urlAsString, -1, true);
                                                break;
                                            }
                                        }

                                        if (loaded) {
                                            if (MainController.get() != null) {
                                                MainController.get().closeTab(mOwnerTabView, MainController.get().contentViewShowing(), false);
                                            }
                                            Settings.get().addRedirectToApp(urlAsString);
                                        }
                                        // NOTE: no need to call loadUrl(urlAsString) or anything in the event the link is to be handled by
                                        // Link Bubble. The flow already assumes that will happen by continuing the load when the Dialog displays. #244
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
                        mHandledAppPickerForCurrentUrl = true;
                        mAppPickersUrls.add(urlAsString);
                    }
                }
            }

            configureOpenInAppButton();
            configureOpenEmbedButton();
            configureArticleModeButton();
            Log.d(TAG, "redirect to url: " + urlAsString);
            mEventHandler.onPageLoading(mWebRenderer.getUrl());
            updateUrlTitleAndText(urlAsString);

            if (mShareButton.getVisibility() == GONE) {
                mShareButton.setVisibility(VISIBLE);
            }

            if (urlAsString.equals(Constant.WELCOME_MESSAGE_URL) && MainController.get() != null) {
                MainController.get().displayTab(mOwnerTabView);
            }
        }

        @Override
        public void onPageFinished(String urlAsString) {

            if (mIsDestroyed) {
                return;
            }

            // This should not be necessary, but unfortunately is.
            // Often when pressing Back, onPageFinished() is mistakenly called when progress is 0. #245
            if (mCurrentProgress != 100) {
                mPageFinishedIgnoredUrl = urlAsString;
                return;
            }

            onPageLoadComplete(urlAsString);
        }

        @Override
        public void onDownloadStart(String urlAsString) {
            openInBrowser(urlAsString);
            MainController.get().closeTab(mOwnerTabView, true, false);
        }

        @Override
        public void onReceivedTitle(String url, String title) {
            mTitleTextView.setText(title);
            if (MainApplication.sTitleHashMap != null && url != null) {
                MainApplication.sTitleHashMap.put(url, title);
            }
        }

        @Override
        public void onReceivedIcon(Bitmap bitmap) {

            // Only pass this along if the page has finished loading (https://github.com/chrislacy/LinkBubble/issues/155).
            // This is to prevent passing a stale icon along when a redirect has already occurred. This shouldn't cause
            // too many ill-effects, because BitmapView attempts to load host/favicon.ico automatically anyway.
            if (mPageFinishedLoading) {
                if (mEventHandler.onReceivedIcon(bitmap)) {
                    String faviconUrl = Util.getDefaultFaviconUrl(mWebRenderer.getUrl());
                    MainApplication.sFavicons.putFaviconInMemCache(faviconUrl, bitmap);
                }
            }
        }

        @Override
        public void onProgressChanged(int progress, String urlAsString) {
            //Log.d(TAG, "onProgressChanged() - progress:" + progress);

            mCurrentProgress = progress;

            // Note: annoyingly, onProgressChanged() can be called with values from a previous url.
            // Eg, "http://t.co/fR9bzpvyLW" redirects to "http://on.recode.net/1eOqNVq" which redirects to
            // "http://recode.net/2014/01/20/...", and after the "on.recode.net" redirect, progress is 100 for a moment.
            mEventHandler.onProgressChanged(progress);

            if (progress == 100 && mPageFinishedIgnoredUrl != null && mPageFinishedIgnoredUrl.equals(urlAsString)) {
                onPageLoadComplete(urlAsString);
            }
        }

        @Override
        public boolean onBackPressed() {
            if (mUrlStack.size() == 0) {
                MainController.get().closeTab(mOwnerTabView, true, true);
                return true;
            } else {
                mWebRenderer.stopLoading();
                String urlBefore = mWebRenderer.getUrl().toString();

                URL previousUrl = mUrlStack.pop();
                String previousUrlAsString = previousUrl.toString();
                mEventHandler.onCanGoBackChanged(mUrlStack.size() > 0);
                mHandledAppPickerForCurrentUrl = false;
                mUsingLinkBubbleAsDefaultForCurrentUrl = false;
                updateAndLoadUrl(previousUrlAsString);
                Log.d(TAG, "[urlstack] Go back: " + urlBefore + " -> " + mWebRenderer.getUrl() + ", urlStack.size():" + mUrlStack.size());
                updateUrlTitleAndText(previousUrlAsString);

                mEventHandler.onPageLoading(mWebRenderer.getUrl());

                updateAppsForUrl(null, previousUrl);
                configureOpenInAppButton();
                configureArticleModeButton();

                mWebRenderer.resetPageInspector();
                configureOpenEmbedButton();

                return true;
            }
        }

        @Override
        public void onUrlLongClick(String url) {
            ContentView.this.onUrlLongClick(url);
        }

        @Override
        public void onShowBrowserPrompt() {
            showOpenInBrowserPrompt(R.string.long_press_unsupported_default_browser,
                    R.string.long_press_unsupported_no_default_browser, mWebRenderer.getUrl().toString());

        }

        @Override
        public void onCloseWindow() {
            MainController.get().closeTab(mOwnerTabView, true, true);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, WebRenderer.GetGeolocationCallback callback) {
            showAllowLocationDialog(origin, callback);
        }

        @Override
        public int getPageInspectFlags() {
            int flags = PageInspector.INSPECT_DROP_DOWN | PageInspector.INSPECT_YOUTUBE;
            if (mEventHandler.hasHighQualityFavicon() == false) {
                flags |= PageInspector.INSPECT_TOUCH_ICON;
            }
            return flags;
        }

        private Handler mHandler = new Handler();
        private Runnable mUpdateOpenInAppRunnable = null;

        @Override
        public void onPageInspectorYouTubeEmbedFound() {
            if (mUpdateOpenInAppRunnable == null) {
                mUpdateOpenInAppRunnable = new Runnable() {
                    @Override
                    public void run() {
                        configureOpenEmbedButton();
                    }
                };
            }

            mOpenEmbedButton.post(mUpdateOpenInAppRunnable);
        }

        @Override
        public void onPageInspectorTouchIconLoaded(final Bitmap bitmap, final String pageUrl) {
            if (bitmap == null || pageUrl == null) {
                return;
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    URL url = mWebRenderer.getUrl();
                    if (url != null && url.toString().equals(pageUrl)) {
                        mEventHandler.onReceivedIcon(bitmap);

                        String faviconUrl = Util.getDefaultFaviconUrl(url);
                        MainApplication.sFavicons.putFaviconInMemCache(faviconUrl, bitmap);
                    }
                }
            });
        }

        @Override
        public void onPageInspectorDropDownWarningClick() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    showOpenInBrowserPrompt(R.string.unsupported_drop_down_default_browser,
                            R.string.unsupported_drop_down_no_default_browser, mWebRenderer.getUrl().toString());
                }
            });
        }

    };

    private String mPageFinishedIgnoredUrl;

    void onPageLoadComplete(String urlAsString) {

        mPageFinishedLoading = true;

        // Always check again at 100%
        mWebRenderer.runPageInspector();

        // NOTE: *don't* call updateUrl() here. Turns out, this function is called after a redirect has occurred.
        // Eg, urlAsString "t.co/xyz" even after the next redirect is starting to load

        // Check exact equality first for common case to avoid an allocation.
        URL currentUrl = mWebRenderer.getUrl();
        boolean equalUrl = currentUrl.toString().equals(urlAsString);

        if (!equalUrl) {
            try {
                URL url = new URL(urlAsString);

                if (url.getProtocol().equals(currentUrl.getProtocol()) &&
                        url.getHost().equals(currentUrl.getHost()) &&
                        url.getPath().equals(currentUrl.getPath())) {
                    equalUrl = true;
                }
            } catch (MalformedURLException e) {
            }
        }

        if (equalUrl) {
            updateAppsForUrl(currentUrl);
            configureOpenInAppButton();
            configureOpenEmbedButton();
            configureArticleModeButton();

            mEventHandler.onPageLoaded(false);
            Log.e(TAG, "onPageLoadComplete() - url: " + urlAsString);

            String title = MainApplication.sTitleHashMap != null ? MainApplication.sTitleHashMap.get(urlAsString) : "";
            MainApplication.saveUrlInHistory(getContext(), null, currentUrl.toString(), currentUrl.getHost(), title);
            if (title == null) {    // if no title is set, display nothing rather than "Loading..." #265
                mTitleTextView.setText(null);
            }

            //mDelayedAutoContentDisplayLinkLoadedScheduled = true;
            //Log.d(TAG, "set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
            postDelayed(mDelayedAutoContentDisplayLinkLoadedRunnable, Constant.AUTO_CONTENT_DISPLAY_DELAY);

            mWebRenderer.onPageLoadComplete();
        }

        mPageFinishedIgnoredUrl = null;
    }

    //boolean mDelayedAutoContentDisplayLinkLoadedScheduled = false;

    // Call autoContentDisplayLinkLoaded() via a delay so as to fix #412
    Runnable mDelayedAutoContentDisplayLinkLoadedRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsDestroyed == false && MainController.get() != null) {
                //Log.e(TAG, "*** set mDelayedAutoContentDisplayLinkLoadedScheduled=" + mDelayedAutoContentDisplayLinkLoadedScheduled);
                MainController.get().autoContentDisplayLinkLoaded(mOwnerTabView);
                saveLoadTime();
            }
        }
    };

    OnClickListener mOnShareButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            showSelectShareMethod(mWebRenderer.getUrl().toString(), true);
        }
    };

    OpenInAppButton.OnOpenInAppClickListener mOnOpenInAppButtonClickListener = new OpenInAppButton.OnOpenInAppClickListener() {

        @Override
        public void onAppOpened() {
            MainController.get().closeTab(mOwnerTabView, true, false);
        }

    };

    OpenEmbedButton.OnOpenEmbedClickListener mOnOpenEmbedButtonClickListener = new OpenEmbedButton.OnOpenEmbedClickListener() {

        @Override
        public void onYouTubeEmbedOpened() {

        }
    };

    OnClickListener mOnReloadButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            mReloadButton.setVisibility(GONE);
            mWebRenderer.reload();
        }
    };

    OnClickListener mOnArticleModeButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            mArticleModeButton.toggleState();
        }
    };

    OnClickListener mOnOverflowButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final Context context = getContext();
            mOverflowPopupMenu = new PopupMenu(context, mOverflowButton);
            Resources resources = context.getResources();
            if (DRM.isLicensed() == false) {
                mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_upgrade_to_pro, Menu.NONE, resources.getString(R.string.action_upgrade_to_pro));
            }
            if (mCurrentProgress != 100) {
                mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_stop, Menu.NONE, resources.getString(R.string.action_stop));
            }
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_reload_page, Menu.NONE, resources.getString(R.string.action_reload_page));

            if (Constant.ARTICLE_MODE) {
                mOverflowPopupMenu.getMenu().add(R.id.group_article_mode, R.id.item_article_mode, Menu.NONE, resources.getString(R.string.action_article_mode));
                mOverflowPopupMenu.getMenu().setGroupCheckable(R.id.group_article_mode, true, false);
                mOverflowPopupMenu.getMenu().findItem(R.id.item_article_mode).setChecked(mWebRenderer.getMode() == WebRenderer.Mode.Article);
            }

            String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();
            if (defaultBrowserLabel != null) {
                mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_open_in_browser, Menu.NONE,
                        String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel));
            }
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_copy_link, Menu.NONE, resources.getString(R.string.action_copy_to_clipboard));
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_close_tab, Menu.NONE, resources.getString(R.string.action_close_tab));
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_settings, Menu.NONE, resources.getString(R.string.action_settings));
            mOverflowPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch (item.getItemId()) {
                        case R.id.item_upgrade_to_pro: {
                            Intent intent = MainApplication.getStoreIntent(context, BuildConfig.STORE_PRO_URL);
                            if (intent != null) {
                                context.startActivity(intent);
                                MainController.get().switchToBubbleView();
                            }
                            break;
                        }

                        case R.id.item_reload_page: {
                            mWebRenderer.resetPageInspector();
                            URL currentUrl = mWebRenderer.getUrl();
                            mEventHandler.onPageLoading(currentUrl);
                            mWebRenderer.stopLoading();
                            mWebRenderer.reload();
                            String urlAsString = currentUrl.toString();
                            updateAppsForUrl(currentUrl);
                            configureOpenInAppButton();
                            configureOpenEmbedButton();
                            configureArticleModeButton();
                            Log.d(TAG, "reload url: " + urlAsString);
                            mInitialUrlLoadStartTime = System.currentTimeMillis();
                            updateUrlTitleAndText(urlAsString);
                            break;
                        }

                        case R.id.item_open_in_browser: {
                            openInBrowser(mWebRenderer.getUrl().toString());
                            break;
                        }

                        case R.id.item_copy_link: {
                            MainApplication.copyLinkToClipboard(getContext(), mWebRenderer.getUrl().toString(), R.string.bubble_link_copied_to_clipboard);
                            break;
                        }

                        case R.id.item_stop: {
                            mWebRenderer.stopLoading();
                            break;
                        }

                        case R.id.item_close_tab: {
                            MainController.get().closeTab(mOwnerTabView, MainController.get().contentViewShowing(), true);
                            break;
                        }

                        case R.id.item_article_mode: {
                            if (Constant.ARTICLE_MODE) {
                                mWebRenderer.loadUrl(getUrl(), item.isChecked() ? WebRenderer.Mode.Web : WebRenderer.Mode.Article);
                            }
                            break;
                        }

                        case R.id.item_settings: {
                            Intent intent = new Intent(context, SettingsActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            context.startActivity(intent);
                            MainController.get().switchToBubbleView();
                            break;
                        }
                    }
                    mOverflowPopupMenu = null;
                    return false;
                }
            });
            mOverflowPopupMenu.setOnDismissListener(new PopupMenu.OnDismissListener() {
                @Override
                public void onDismiss(PopupMenu menu) {
                    if (mOverflowPopupMenu == menu) {
                        mOverflowPopupMenu = null;
                    }
                }
            });
            mOverflowPopupMenu.show();
        }
    };

    OnSwipeTouchListener mOnTextContainerTouchListener = new OnSwipeTouchListener() {
        public void onSwipeRight() {
            MainController.get().showPreviousBubble();
        }
        public void onSwipeLeft() {
            MainController.get().showNextBubble();
        }
    };

    private void onUrlLongClick(final String urlAsString) {
        Resources resources = getResources();

        final ArrayList<String> longClickSelections = new ArrayList<String>();

        final String shareLabel = resources.getString(R.string.action_share);
        longClickSelections.add(shareLabel);

        String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();

        final String leftConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeLeft);
        if (leftConsumeBubbleLabel != null) {
            if (defaultBrowserLabel == null || defaultBrowserLabel.equals(leftConsumeBubbleLabel) == false) {
                longClickSelections.add(leftConsumeBubbleLabel);
            }
        }

        final String rightConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Constant.BubbleAction.ConsumeRight);
        if (rightConsumeBubbleLabel != null) {
            if (defaultBrowserLabel == null || defaultBrowserLabel.equals(rightConsumeBubbleLabel) == false) {
                longClickSelections.add(rightConsumeBubbleLabel);
            }
        }

        // Long pressing for a link doesn't work reliably, re #279
        //final String copyLinkLabel = resources.getString(R.string.action_copy_to_clipboard);
        //longClickSelections.add(copyLinkLabel);

        Collections.sort(longClickSelections);

        final String openInNewBubbleLabel = resources.getString(R.string.action_open_in_new_bubble);
        longClickSelections.add(0, openInNewBubbleLabel);

        final String openInBrowserLabel = defaultBrowserLabel != null ?
                String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel) : null;
        if (openInBrowserLabel != null) {
            longClickSelections.add(1, openInBrowserLabel);
        }

        ListView listView = new ListView(getContext());
        listView.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1,
                longClickSelections.toArray(new String[0])));
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String string = longClickSelections.get(position);
                if (string.equals(openInNewBubbleLabel)) {
                    MainController.get().openUrl(urlAsString, System.currentTimeMillis(), false, Analytics.OPENED_URL_FROM_NEW_TAB);
                } else if (openInBrowserLabel != null && string.equals(openInBrowserLabel)) {
                    openInBrowser(urlAsString);
                } else if (string.equals(shareLabel)) {
                    showSelectShareMethod(urlAsString, false);
                } else if (leftConsumeBubbleLabel != null && string.equals(leftConsumeBubbleLabel)) {
                    MainApplication.handleBubbleAction(getContext(), Constant.BubbleAction.ConsumeLeft, urlAsString, -1);
                } else if (rightConsumeBubbleLabel != null && string.equals(rightConsumeBubbleLabel)) {
                    MainApplication.handleBubbleAction(getContext(), Constant.BubbleAction.ConsumeRight, urlAsString, -1);
                //} else if (string.equals(copyLinkLabel)) {
                //    MainApplication.copyLinkToClipboard(getContext(), urlAsString, R.string.link_copied_to_clipboard);
                }

                if (mLongPressAlertDialog != null) {
                    mLongPressAlertDialog.dismiss();
                }
            }
        });

        mLongPressAlertDialog = new AlertDialog.Builder(getContext()).create();
        mLongPressAlertDialog.setView(listView);
        mLongPressAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mLongPressAlertDialog.show();
    }

    private void configureOpenEmbedButton() {
        if (mOpenEmbedButton.configure(mWebRenderer.getPageInspectorYouTubeEmbedHelper())) {
            mOpenEmbedButton.invalidate();
        } else {
            mOpenEmbedButton.setVisibility(GONE);
        }
    }

    private void configureOpenInAppButton() {
        if (mOpenInAppButton.configure(mAppsForUrl)) {
            mOpenInAppButton.invalidate();
        } else {
            mOpenInAppButton.setVisibility(GONE);
        }
    }

    private void configureArticleModeButton() {
        if (Constant.ARTICLE_MODE_BUTTON && mCurrentProgress == 100) {
            mArticleModeButton.setVisibility(VISIBLE);
        } else {
            mArticleModeButton.setVisibility(GONE);
        }
    }

    private void updateAppsForUrl(URL url) {
        List<ResolveInfo> resolveInfos = Settings.get().getAppsThatHandleUrl(url.toString(), getContext().getPackageManager());
        updateAppsForUrl(resolveInfos, url);
    }

    private void updateAppsForUrl(List<ResolveInfo> resolveInfos, URL url) {
        if (resolveInfos != null && resolveInfos.size() > 0) {
            mTempAppsForUrl.clear();
            for (ResolveInfo resolveInfoToAdd : resolveInfos) {
                if (resolveInfoToAdd.activityInfo != null) {
                    boolean alreadyAdded = false;
                    for (int i = 0; i < mAppsForUrl.size(); i++) {
                        AppForUrl existing = mAppsForUrl.get(i);
                        if (existing.mResolveInfo.activityInfo.packageName.equals(resolveInfoToAdd.activityInfo.packageName)
                                && existing.mResolveInfo.activityInfo.name.equals(resolveInfoToAdd.activityInfo.name)) {
                            alreadyAdded = true;
                            if (existing.mUrl.equals(url) == false) {
                                if (url.getHost().contains(existing.mUrl.getHost())
                                        && url.getHost().length() > existing.mUrl.getHost().length()) {
                                    // don't update the url in this case. This means prevents, as an example, saving a host like
                                    // "mobile.twitter.com" instead of using "twitter.com". This occurs when loading
                                    // "https://twitter.com/lokibartleby/status/412160702707539968" with Tweet Lanes
                                    // and the official Twitter client installed.
                                } else {
                                    try {
                                        existing.mUrl = new URL(url.toString());   // Update the Url
                                    } catch (MalformedURLException e) {
                                        throw new RuntimeException("Malformed URL: " + url);
                                    }
                                }
                            }
                            break;
                        }
                    }

                    if (alreadyAdded == false) {
                        //if (resolveInfoToAdd.activityInfo.packageName.equals(Settings.get().mLinkBubbleEntryActivityResolveInfo.activityInfo.packageName)) {
                        //    continue;
                        //}
                        mTempAppsForUrl.add(resolveInfoToAdd);
                    }
                }
            }

            if (mTempAppsForUrl.size() > 0) {
                URL currentUrl;
                try {
                    currentUrl = new URL(url.toString());
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                    return;
                }

                // We need to handle the following case:
                //   * Load reddit.com/r/Android. The app to handle that URL might be "Reddit is Fun" or something similar.
                //   * Click on a link to play.google.com/store/, which is handled by the "Google Play" app.
                //   * The code below adds "Google Play" to the app list that contains "Reddit is Fun",
                //       even though "Reddit is Fun" is not applicable for this link.
                // Unfortunately there is no way reliable way to find out when a user has clicked on a link using the WebView.
                // http://stackoverflow.com/a/17937536/328679 is close, but doesn't work because it relies on onPageFinished()
                // being called, which will not be called if the current page is still loading when the link was clicked.
                //
                // So, in the event contains results, and these results reference a different URL that which matched the
                // resolveInfos passed in, clear mAppsForUrl.
                if (mAppsForUrl.size() > 0) {
                    URL firstUrl = mAppsForUrl.get(0).mUrl;
                    if ((currentUrl.getHost().contains(firstUrl.getHost())
                            && currentUrl.getHost().length() > firstUrl.getHost().length()) == false) {
                        mAppsForUrl.clear();    // start again
                    }
                }

                for (ResolveInfo resolveInfoToAdd : mTempAppsForUrl) {
                    mAppsForUrl.add(new AppForUrl(resolveInfoToAdd, currentUrl));
                }
            }

        } else {
            mAppsForUrl.clear();
        }

        boolean containsLinkBubble = false;
        for (AppForUrl appForUrl : mAppsForUrl) {
            if (appForUrl.mResolveInfo != null
                    && appForUrl.mResolveInfo.activityInfo != null
                    && appForUrl.mResolveInfo.activityInfo.packageName.equals(BuildConfig.PACKAGE_NAME)) {
                containsLinkBubble = true;
                break;
            }
        }

        if (containsLinkBubble == false) {
            mAppsForUrl.add(new AppForUrl(Settings.get().mLinkBubbleEntryActivityResolveInfo, url));
        }
    }

    AppForUrl getDefaultAppForUrl() {
        if (mAppsForUrl != null && mAppsForUrl.size() > 0) {
            mTempAppsForUrl.clear();
            for (AppForUrl appForUrl : mAppsForUrl) {
                mTempAppsForUrl.add(appForUrl.mResolveInfo);
            }
            if (mTempAppsForUrl.size() > 0) {
                ResolveInfo defaultApp = Settings.get().getDefaultAppForUrl(mWebRenderer.getUrl(), mTempAppsForUrl);
                if (defaultApp != null) {
                    for (AppForUrl appForUrl : mAppsForUrl) {
                        if (appForUrl.mResolveInfo == defaultApp) {
                            return appForUrl;
                        }
                    }
                }
            }
        }

        return null;
    }

    public void onAnimateOnScreen() {
        hidePopups();
        resetButtonPressedStates();
    }

    public void onAnimateOffscreen() {
        hidePopups();
        resetButtonPressedStates();
    }

    public void onBeginBubbleDrag() {
        hidePopups();
        resetButtonPressedStates();
    }

    void onCurrentContentViewChanged(boolean isCurrent) {
        hidePopups();
        resetButtonPressedStates();

        if (isCurrent && MainController.get().contentViewShowing()) {
            saveLoadTime();
        }
    }

    public void saveLoadTime() {
        if (mInitialUrlLoadStartTime > -1) {
            Settings.get().trackLinkLoadTime(System.currentTimeMillis() - mInitialUrlLoadStartTime, Settings.LinkLoadType.PageLoad, mWebRenderer.getUrl().toString());
            mInitialUrlLoadStartTime = -1;
        }
    }

    void onOrientationChanged() {
        invalidate();
    }

    private boolean updateUrl(String urlAsString) {
        if (urlAsString.equals(mWebRenderer.getUrl().toString()) == false) {
            try {
                Log.d(TAG, "change url from " + mWebRenderer.getUrl() + " to " + urlAsString);
                mWebRenderer.setUrl(urlAsString);
            } catch (MalformedURLException e) {
                return false;
            }
        }

        return true;
    }

    private void updateAndLoadUrl(String urlAsString) {
        updateUrl(urlAsString);
        URL updatedUrl = getUrl();

        WebRenderer.Mode mode = WebRenderer.Mode.Web;
        if (Settings.get().getAutoArticleMode()) {
            String path = updatedUrl.getPath();
            if (path != null && !path.equals("") && !path.equals("/")) {
                mode = WebRenderer.Mode.Article;
            }
        }

        mWebRenderer.loadUrl(updatedUrl, mode);
    }

    private URL getUpdatedUrl(String urlAsString) {
        URL currentUrl = mWebRenderer.getUrl();
        if (urlAsString.equals(currentUrl.toString()) == false) {
            try {
                Log.d(TAG, "getUpdatedUrl(): change url from " + currentUrl + " to " + urlAsString);
                return new URL(urlAsString);
            } catch (MalformedURLException e) {
                return null;
            }
        }
        return currentUrl;
    }

    URL getUrl() {
        return mWebRenderer.getUrl();
    }

    private void hidePopups() {
        if (mOverflowPopupMenu != null) {
            mOverflowPopupMenu.dismiss();
            mOverflowPopupMenu = null;
        }
        if (mLongPressAlertDialog != null) {
            mLongPressAlertDialog.dismiss();
            mLongPressAlertDialog = null;
        }
        mWebRenderer.hidePopups();
    }

    private void resetButtonPressedStates() {
        if (mShareButton != null) {
            mShareButton.setIsTouched(false);
        }
        if (mOpenEmbedButton != null) {
            mOpenEmbedButton.setIsTouched(false);
        }
        if (mOpenInAppButton != null) {
            mOpenInAppButton.setIsTouched(false);
        }
        if (mArticleModeButton != null) {
            mArticleModeButton.setIsTouched(false);
        }
        if (mOverflowButton != null) {
            mOverflowButton.setIsTouched(false);
        }
    }

    private boolean openInBrowser(String urlAsString) {
        Log.d(TAG, "openInBrowser() - url:" + urlAsString);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(urlAsString));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (MainApplication.openInBrowser(getContext(), intent, true) && MainController.get() != null && mOwnerTabView != null) {
            MainController.get().closeTab(mOwnerTabView, MainController.get().contentViewShowing(), false);
            return true;
        }

        return false;
    }

    private boolean openInApp(ResolveInfo resolveInfo, String urlAsString) {
        Context context = getContext();
        if (MainApplication.loadResolveInfoIntent(getContext(), resolveInfo, urlAsString, mInitialUrlLoadStartTime)) {
            String title = String.format(context.getString(R.string.link_loaded_with_app),
                    resolveInfo.loadLabel(context.getPackageManager()));
            MainApplication.saveUrlInHistory(context, resolveInfo, urlAsString, title);

            MainController.get().closeTab(mOwnerTabView, MainController.get().contentViewShowing(), false);
            Settings.get().addRedirectToApp(urlAsString);
            return true;
        }

        return false;
    }

    private void showOpenInBrowserPrompt(int hasBrowserStringId, int noBrowserStringId, final String urlAsString) {
        String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();
        String message;
        Drawable drawable;
        if (defaultBrowserLabel != null) {
            message = String.format(getResources().getString(hasBrowserStringId), defaultBrowserLabel);
            drawable = Settings.get().getDefaultBrowserIcon(getContext());
        } else {
            message = getResources().getString(noBrowserStringId);
            drawable = null;
        }
        Prompt.show(message, drawable, Prompt.LENGTH_LONG, new Prompt.OnPromptEventListener() {
            @Override
            public void onClick() {
                if (urlAsString != null) {
                    openInBrowser(urlAsString);
                }
            }
            @Override
            public void onClose() {
            }
        });
    }

    void updateUrlTitleAndText(String urlAsString) {
        String title = MainApplication.sTitleHashMap != null ? MainApplication.sTitleHashMap.get(urlAsString) : null;
        if (title == null) {
            title = getResources().getString(R.string.loading);
        }
        mTitleTextView.setText(title);

        if (urlAsString.equals(Constant.NEW_TAB_URL)) {
            mUrlTextView.setText(null);
        } else if (urlAsString.equals(Constant.WELCOME_MESSAGE_URL)) {
            mUrlTextView.setText(Constant.WELCOME_MESSAGE_DISPLAY_URL);
        } else {
            mUrlTextView.setText(urlAsString.replace("http://", ""));
        }
    }

    void showAllowLocationDialog(final String origin, final WebRenderer.GetGeolocationCallback callback) {

        LocationManager locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null
                || locationManager.getAllProviders() == null
                || locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER) == false) {
            return;
        }

        String originCopy = origin.replace("http://", "").replace("https://", "");
        String messageText = String.format(getResources().getString(R.string.requesting_location_message), originCopy);
        mRequestLocationTextView.setText(messageText);
        mRequestLocationContainer.setVisibility(View.VISIBLE);
        mRequestLocationShadow.setVisibility(View.VISIBLE);
        mRequestLocationYesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                callback.onAllow();
                hideAllowLocationDialog();
            }
        });
    }

    void hideAllowLocationDialog() {
        mRequestLocationContainer.setVisibility(View.GONE);
        mRequestLocationShadow.setVisibility(View.GONE);
    }
}
