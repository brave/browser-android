package com.linkbubble.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.graphics.Canvas;
import android.widget.TextView;
import android.widget.Toast;
import com.linkbubble.Constant;
import com.linkbubble.util.ActionItem;
import com.linkbubble.Config;
import com.linkbubble.MainApplication;
import com.linkbubble.MainController;
import com.linkbubble.R;
import com.linkbubble.Settings;
import com.linkbubble.util.PageInspector;
import com.linkbubble.util.Util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * Created by gw on 19/08/13.
 */
public class ContentView extends FrameLayout {

    private static final String TAG = "UrlLoad";

    private WebView mWebView;
    private View mTouchInterceptorView;
    private CondensedTextView mTitleTextView;
    private CondensedTextView mUrlTextView;
    private ContentViewButton mShareButton;
    private ContentViewButton mReloadButton;
    private OpenInAppButton mOpenInAppButton;
    private OpenEmbedButton mOpenEmbedButton;
    private ContentViewButton mOverflowButton;
    private LinearLayout mToolbarLayout;
    private EventHandler mEventHandler;
    private URL mUrl;
    private int mCurrentProgress = 0;
    private long mLastWebViewTouchUpTime = -1;
    private String mLastWebViewTouchDownUrl;
    private boolean mPageFinishedLoading;
    private boolean mShowingDefaultAppPicker = false;

    private List<AppForUrl> mAppsForUrl = new ArrayList<AppForUrl>();
    private List<ResolveInfo> mTempAppsForUrl = new ArrayList<ResolveInfo>();

    private int mCheckForEmbedsCount;
    private PopupMenu mOverflowPopupMenu;
    private AlertDialog mLongPressAlertDialog;
    private AlertDialog mJsAlertDialog;
    private AlertDialog mJsConfirmDialog;
    private AlertDialog mJsPromptDialog;
    private long mStartTime;
    private int mHeaderHeight;
    private Path mTempPath = new Path();
    private PageInspector mPageInspector;
    private Stack<URL> mUrlStack = new Stack<URL>();
    // We only want to handle this once per link. This prevents 3+ dialogs appearing for some links, which is a bad experience. #224
    private boolean mHandledAppPickerForCurrentUrl = false;

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
    };

    public interface EventHandler {
        public void onPageLoading(URL url);
        public void onProgressChanged(int progress);
        public void onPageLoaded();
        public boolean onReceivedIcon(Bitmap bitmap);
        public void setDefaultFavicon();
        public void onBackStackSizeChanged(int size);
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
        removeView(mWebView);
        mWebView.destroy();
    }

    public void updateIncognitoMode(boolean incognito) {
        if (incognito) {
            mWebView.getSettings().setCacheMode(mWebView.getSettings().LOAD_NO_CACHE);
            mWebView.getSettings().setAppCacheEnabled(false);
            mWebView.clearHistory();
            mWebView.clearCache(true);

            mWebView.clearFormData();
            mWebView.getSettings().setSavePassword(false);
            mWebView.getSettings().setSaveFormData(false);
        } else {
            mWebView.getSettings().setCacheMode(mWebView.getSettings().LOAD_DEFAULT);
            mWebView.getSettings().setAppCacheEnabled(true);

            mWebView.getSettings().setSavePassword(true);
            mWebView.getSettings().setSaveFormData(true);
        }
    }

    private void showSelectShareMethod(final String urlAsString, final boolean closeBubbleOnShare) {

        AlertDialog alertDialog = ActionItem.getShareAlert(getContext(), new ActionItem.OnActionItemSelectedListener() {
            @Override
            public void onSelected(ActionItem actionItem) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.setClassName(actionItem.mPackageName, actionItem.mActivityClassName);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(Intent.EXTRA_TEXT, urlAsString);
                getContext().startActivity(intent);

                boolean isCopyToClipboardAction = actionItem.mPackageName.equals("com.google.android.apps.docs")
                        && actionItem.mActivityClassName.equals("com.google.android.apps.docs.app.SendTextToClipboardActivity");

                if (closeBubbleOnShare && isCopyToClipboardAction == false) {
                    MainController.get().destroyCurrentBubble(true);
                }
            }
        });
        alertDialog.show();
    }

    void configure(String urlAsString, long startTime, boolean hasShownAppPicker, EventHandler eventHandler) throws MalformedURLException {
        mUrl = new URL(urlAsString);
        mHeaderHeight = getResources().getDimensionPixelSize(R.dimen.toolbar_header);
        mHandledAppPickerForCurrentUrl = hasShownAppPicker;

        mWebView = (WebView) findViewById(R.id.webView);
        mTouchInterceptorView = findViewById(R.id.touch_interceptor);
        mTouchInterceptorView.setWillNotDraw(true);
        mTouchInterceptorView.setOnTouchListener(mWebViewOnTouchListener);
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

        mOverflowButton = (ContentViewButton)mToolbarLayout.findViewById(R.id.overflow_button);
        mOverflowButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_overflow_round));
        mOverflowButton.setOnClickListener(mOnOverflowButtonClickListener);

        mEventHandler = eventHandler;
        mEventHandler.onBackStackSizeChanged(mUrlStack.size());
        mPageFinishedLoading = false;

        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportMultipleWindows(true);

        mWebView.setLongClickable(true);
        mWebView.setOnLongClickListener(mOnWebViewLongClickListener);
        mWebView.setWebChromeClient(mWebChromeClient);
        mWebView.setOnKeyListener(mOnKeyListener);
        mWebView.setWebViewClient(mWebViewClient);
        mWebView.setDownloadListener(mDownloadListener);

        mPageInspector = new PageInspector(getContext(), mWebView, mOnPageInspectorItemFoundListener);

        updateIncognitoMode(Settings.get().isIncognitoMode());

        mStartTime = startTime;

        updateUrl(urlAsString);
        updateAppsForUrl(mUrl);
        configureOpenInAppButton();
        configureOpenEmbedButton();
        Log.d(TAG, "load url: " + urlAsString);
        mWebView.loadUrl(urlAsString);
        mEventHandler.onPageLoading(mUrl);
        mTitleTextView.setText(R.string.loading);
        mUrlTextView.setText(urlAsString.replace("http://", ""));
    }

    private OnTouchListener mWebViewOnTouchListener = new OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            final int action = event.getAction() & MotionEvent.ACTION_MASK;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastWebViewTouchDownUrl = mUrl.toString();
                    //Log.d(TAG, "[urlstack] WebView - MotionEvent.ACTION_DOWN");
                    break;

                case MotionEvent.ACTION_UP:
                    mLastWebViewTouchUpTime = System.currentTimeMillis();
                    Log.d(TAG, "[urlstack] WebView - MotionEvent.ACTION_UP");
                    break;
            }
            // Forcibly pass along to the WebView. This ensures we receive the ACTION_UP event above.
            mWebView.onTouchEvent(event);
            return true;
        }
    };

    WebViewClient mWebViewClient = new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(WebView wView, final String urlAsString) {

            URL updatedUrl = getUpdatedUrl(urlAsString);
            if (updatedUrl == null) {
                String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();
                String message;
                Drawable drawable;
                if (defaultBrowserLabel != null) {
                    message = String.format(getResources().getString(R.string.unsupported_scheme_default_browser), defaultBrowserLabel);
                    drawable = Settings.get().getDefaultBrowserIcon(getContext());
                } else {
                    message = getResources().getString(R.string.unsupported_scheme_no_default_browser);
                    drawable = null;
                }
                Prompt.show(getContext(), message, drawable, Prompt.LENGTH_LONG, null);
                Log.d(TAG, "ignore unsupported URI scheme: " + urlAsString);
                return true;        // true because we've handled the link ourselves
            }

            if (mLastWebViewTouchUpTime > -1) {
                long touchUpTimeDelta = System.currentTimeMillis() - mLastWebViewTouchUpTime;
                // this value needs to be largish
                if (touchUpTimeDelta < 1500) {
                    // If the url has changed since the use pressed their finger down, a redirect has likely occurred,
                    // in which case we don't update the Url Stack
                    if (mLastWebViewTouchDownUrl.equals(mUrl.toString())) {
                        mUrlStack.push(mUrl);
                        mEventHandler.onBackStackSizeChanged(mUrlStack.size());
                        Log.d(TAG, "[urlstack] push:" + mUrl.toString() + ", urlStack.size():" + mUrlStack.size() + ", delta:" + touchUpTimeDelta);
                        mHandledAppPickerForCurrentUrl = false;
                    } else {
                        Log.d(TAG, "[urlstack] DON'T ADD " + mUrl.toString() + " because of redirect");
                    }
                    mLastWebViewTouchUpTime = -1;

                } else {
                    Log.d(TAG, "[urlstack] IGNORED because of delta:" + touchUpTimeDelta);
                }
            }

            mUrl = updatedUrl;

            mPageInspector.reset();
            final Context context = getContext();

            updateAppsForUrl(Settings.get().getAppsThatHandleUrl(urlAsString), mUrl);
            if (Settings.get().redirectUrlToBrowser(urlAsString)) {
                if (openInBrowser(urlAsString)) {
                    String title = String.format(context.getString(R.string.link_redirected), Settings.get().getDefaultBrowserLabel());
                    MainApplication.saveUrlInHistory(context, null, urlAsString, title);
                    return false;
                }
            }

            if (Settings.get().getAutoContentDisplayAppRedirect()
                    && mHandledAppPickerForCurrentUrl == false
                    && mAppsForUrl != null
                    && mAppsForUrl.size() > 0) {
                mHandledAppPickerForCurrentUrl = true;
                if (mAppsForUrl.size() == 1) {
                    AppForUrl appForUrl = mAppsForUrl.get(0);
                    if (appForUrl.mResolveInfo != Settings.get().mLinkBubbleEntryActivityResolveInfo) {
                        if (MainApplication.loadResolveInfoIntent(context, appForUrl.mResolveInfo, urlAsString, mStartTime)) {
                            String title = String.format(context.getString(R.string.link_loaded_with_app),
                                    appForUrl.mResolveInfo.loadLabel(context.getPackageManager()));
                            MainApplication.saveUrlInHistory(context, appForUrl.mResolveInfo, urlAsString, title);

                            MainController.get().destroyCurrentBubble(MainController.get().contentViewShowing());
                            return false;
                        }
                    }
                } else {
                    if (mShowingDefaultAppPicker == false) {
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

                                                loaded = MainApplication.loadIntent(context, actionItem.mPackageName,
                                                        actionItem.mActivityClassName, urlAsString, -1);
                                                break;
                                            }
                                        }

                                        if (loaded) {
                                            MainController.get().destroyCurrentBubble(MainController.get().contentViewShowing());
                                        }
                                        // NOTE: no need to call loadUrl(urlAsString) or anything in the event the link is to be handled by
                                        // Link Bubble. The flow already assumes that will happen by continuing the load when the Dialog displays. #244
                                    }
                                });

                        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialog) {
                                mShowingDefaultAppPicker = false;
                            }
                        });

                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                        dialog.show();
                        mShowingDefaultAppPicker = true;
                    }
                }
            }

            configureOpenInAppButton();
            configureOpenEmbedButton();
            Log.d(TAG, "redirect to url: " + urlAsString);
            mWebView.loadUrl(urlAsString);
            mEventHandler.onPageLoading(mUrl);
            mTitleTextView.setText(R.string.loading);
            mUrlTextView.setText(urlAsString.replace("http://", ""));
            return true;
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            mEventHandler.onPageLoaded();
            mReloadButton.setVisibility(VISIBLE);
            mShareButton.setVisibility(GONE);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public void onPageStarted(WebView view, String urlAsString, Bitmap favIcon) {
            mPageFinishedLoading = false;

            updateUrl(urlAsString);

            if (mShareButton.getVisibility() == GONE) {
                mShareButton.setVisibility(VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView webView, String urlAsString) {
            super.onPageFinished(webView, urlAsString);

            // This should not be necessary, but unfortunately is.
            // Often when pressing Back, onPageFinished() is mistakenly called when progress is 0.
            if (mCurrentProgress != 100) {
                return;
            }

            mPageFinishedLoading = true;
            // NOTE: *don't* call updateUrl() here. Turns out, this function is called after a redirect has occurred.
            // Eg, urlAsString "t.co/xyz" even after the next redirect is starting to load

            // Check exact equality first for common case to avoid an allocation.
            boolean equalUrl = mUrl.toString().equals(urlAsString);

            if (!equalUrl) {
                try {
                    URL url = new URL(urlAsString);

                    if (url.getProtocol().equals(mUrl.getProtocol()) &&
                        url.getHost().equals(mUrl.getHost()) &&
                        url.getPath().equals(mUrl.getPath())) {
                        equalUrl = true;
                    }
                } catch (MalformedURLException e) {
                }
            }

            if (equalUrl) {
                updateAppsForUrl(mUrl);
                configureOpenInAppButton();
                configureOpenEmbedButton();

                mEventHandler.onPageLoaded();
                Log.d(TAG, "onPageFinished() - url: " + urlAsString);

                if (mStartTime > -1) {
                    Log.d("LoadTime", "Saved " + ((System.currentTimeMillis() - mStartTime) / 1000) + " seconds.");
                    mStartTime = -1;
                }

                String title = MainApplication.sTitleHashMap.get(urlAsString);
                MainApplication.saveUrlInHistory(getContext(), null, mUrl.toString(), mUrl.getHost(), title);
                if (title == null) {    // if no title is set, display nothing rather than "Loading..." #265
                    mTitleTextView.setText(null);
                }

                // Always check again at 100%
                mPageInspector.run(webView, mEventHandler.hasHighQualityFavicon() ? false : true);
            }
        }
    };

    OnKeyListener mOnKeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                WebView webView = (WebView) v;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_BACK: {
                        if (mUrlStack.size() == 0) {
                            MainController.get().destroyCurrentBubble(true);
                        } else {
                            webView.stopLoading();
                            String urlBefore = webView.getUrl();

                            URL previousUrl = mUrlStack.pop();
                            String previousUrlAsString = previousUrl.toString();
                            mEventHandler.onBackStackSizeChanged(mUrlStack.size());
                            mHandledAppPickerForCurrentUrl = false;
                            webView.loadUrl(previousUrlAsString);

                            updateUrl(previousUrlAsString);
                            updateAppsForUrl(mUrl);
                            mPageInspector.reset();
                            Log.d(TAG, "[urlstack] Go back: " + urlBefore + " -> " + webView.getUrl() + ", urlStack.size():" + mUrlStack.size());
                            configureOpenInAppButton();
                            configureOpenEmbedButton();
                            mUrlTextView.setText(previousUrlAsString.replace("http://", ""));
                            String title = MainApplication.sTitleHashMap.get(previousUrlAsString);
                            if (title == null) {
                                title = getResources().getString(R.string.loading);
                            }
                            mTitleTextView.setText(title);

                            mEventHandler.onPageLoading(mUrl);
                            return true;
                        }
                        break;
                    }
                }
            }

            return false;
        }
    };

    WebChromeClient mWebChromeClient = new WebChromeClient() {
        @Override
        public void onReceivedTitle(WebView webView, String title) {
            super.onReceivedTitle(webView, title);
            mTitleTextView.setText(title);
            MainApplication.sTitleHashMap.put(webView.getUrl(), title);
        }

        @Override
        public void onReceivedIcon(WebView webView, Bitmap bitmap) {
            super.onReceivedIcon(webView, bitmap);

            // Only pass this along if the page has finished loading (https://github.com/chrislacy/LinkBubble/issues/155).
            // This is to prevent passing a stale icon along when a redirect has already occurred. This shouldn't cause
            // too many ill-effects, because BitmapView attempts to load host/favicon.ico automatically anyway.
            if (mPageFinishedLoading) {
                if (mEventHandler.onReceivedIcon(bitmap)) {
                    String faviconUrl = Util.getDefaultFaviconUrl(mUrl);
                    MainApplication.sFavicons.putFaviconInMemCache(faviconUrl, bitmap);
                }
            }
        }

        @Override
        public void onProgressChanged(WebView webView, int progress) {
            Log.d(TAG, "onProgressChanged() - progress:" + progress);

            mCurrentProgress = progress;

            // Note: annoyingly, onProgressChanged() can be called with values from a previous url.
            // Eg, "http://t.co/fR9bzpvyLW" redirects to "http://on.recode.net/1eOqNVq" which redirects to
            // "http://recode.net/2014/01/20/...", and after the "on.recode.net" redirect, progress is 100 for a moment.
            mEventHandler.onProgressChanged(progress);

            // At 60%, the page is more often largely viewable, but waiting for background shite to finish which can
            // take many, many seconds, even on a strong connection. Thus, do a check for embeds now to prevent the button
            // not being updated until 100% is reached, which feels too slow as a user.
            if (progress >= 60) {
                if (mCheckForEmbedsCount == 0) {
                    mCheckForEmbedsCount = 1;
                    mPageInspector.reset();

                    Log.d(TAG, "onProgressChanged() - checkForYouTubeEmbeds() - progress:" + progress + ", mCheckForEmbedsCount:" + mCheckForEmbedsCount);
                    mPageInspector.run(webView, mEventHandler.hasHighQualityFavicon() ? false : true);
                } else if (mCheckForEmbedsCount == 1 && progress >= 80) {
                    mCheckForEmbedsCount = 2;
                    Log.d(TAG, "onProgressChanged() - checkForYouTubeEmbeds() - progress:" + progress + ", mCheckForEmbedsCount:" + mCheckForEmbedsCount);
                    mPageInspector.run(webView, mEventHandler.hasHighQualityFavicon() ? false : true);
                }
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            mJsAlertDialog = new AlertDialog.Builder(getContext()).create();
            mJsAlertDialog.setMessage(message);
            mJsAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mJsAlertDialog.setButton(AlertDialog.BUTTON_POSITIVE, getResources().getString(R.string.action_ok),
                    new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                        }

                    });
            mJsAlertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    mJsAlertDialog = null;
                }
            });
            mJsAlertDialog.show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            mJsConfirmDialog = new AlertDialog.Builder(getContext()).create();
            mJsConfirmDialog.setTitle(R.string.confirm_title);
            mJsConfirmDialog.setMessage(message);
            mJsConfirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mJsConfirmDialog.setButton(AlertDialog.BUTTON_POSITIVE, getResources().getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    result.confirm();
                }
            });
            mJsConfirmDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getResources().getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    result.cancel();
                }
            });
            mJsConfirmDialog.show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
            final View v = LayoutInflater.from(getContext()).inflate(R.layout.view_javascript_prompt, null);

            ((TextView)v.findViewById(R.id.prompt_message_text)).setText(message);
            ((EditText)v.findViewById(R.id.prompt_input_field)).setText(defaultValue);

            mJsPromptDialog = new AlertDialog.Builder(getContext()).create();
            mJsPromptDialog.setView(v);
            mJsPromptDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mJsPromptDialog.setButton(AlertDialog.BUTTON_POSITIVE, getResources().getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String value = ((EditText)v.findViewById(R.id.prompt_input_field)).getText().toString();
                    result.confirm(value);
                }
            });
            mJsPromptDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getResources().getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    result.cancel();
                }
            });
            mJsPromptDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    result.cancel();
                }
            });
            mJsPromptDialog.show();

            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            // Call the old version of this function for backwards compatability.
            //onConsoleMessage(consoleMessage.message(), consoleMessage.lineNumber(),
            //        consoleMessage.sourceId());
            Log.d("Console", consoleMessage.message());
            return false;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message resultMsg)
        {
            TabView tabView = MainController.get().onOpenUrl("http://yahoo.com", System.currentTimeMillis(), false);
            if (tabView != null) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tabView.getContentView().mWebView);
                resultMsg.sendToTarget();
                return true;
            }

            return false;
        }
    };

    OnLongClickListener mOnWebViewLongClickListener = new OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            WebView.HitTestResult hitTestResult = mWebView.getHitTestResult();
            Log.d(TAG, "onLongClick type: " + hitTestResult.getType());
            switch (hitTestResult.getType()) {
                case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
                    final String url = hitTestResult.getExtra();
                    if (url == null) {
                        return false;
                    }

                    onUrlLongClick(url);
                    return true;
                }

                case WebView.HitTestResult.UNKNOWN_TYPE:
                default:
                    String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();
                    String message;
                    Drawable drawable;
                    if (defaultBrowserLabel != null) {
                        message = String.format(getResources().getString(R.string.long_press_unsupported_default_browser), defaultBrowserLabel);
                        drawable = Settings.get().getDefaultBrowserIcon(getContext());
                    } else {
                        message = getResources().getString(R.string.long_press_unsupported_no_default_browser);
                        drawable = null;
                    }
                    Prompt.show(getContext(), message, drawable, Prompt.LENGTH_LONG, null);
                    return false;
            }
        }
    };

    DownloadListener mDownloadListener = new DownloadListener() {
        @Override
        public void onDownloadStart(String urlAsString, String userAgent,
                String contentDisposition, String mimetype,
        long contentLength) {
            openInBrowser(urlAsString);
            MainController.get().destroyCurrentBubble(true);
        }
    };

    OnClickListener mOnShareButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            showSelectShareMethod(mUrl.toString(), true);
        }
    };

    OpenInAppButton.OnOpenInAppClickListener mOnOpenInAppButtonClickListener = new OpenInAppButton.OnOpenInAppClickListener() {

        @Override
        public void onAppOpened() {
            MainController.get().destroyCurrentBubble(true);
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
            mWebView.reload();
        }
    };

    OnClickListener mOnOverflowButtonClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final Context context = getContext();
            mOverflowPopupMenu = new PopupMenu(context, mOverflowButton);
            Resources resources = context.getResources();
            if (Constant.IS_LICENSED == false) {
                mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_upgrade_to_pro, Menu.NONE,
                        resources.getString(R.string.action_upgrade_to_pro));
            }
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_reload_page, Menu.NONE,
                    resources.getString(R.string.action_reload_page));
            String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();
            if (defaultBrowserLabel != null) {
                mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_open_in_browser, Menu.NONE,
                        String.format(resources.getString(R.string.action_open_in_browser), defaultBrowserLabel));
            }
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_copy_link, Menu.NONE, resources.getString(R.string.action_copy_to_clipboard));
            mOverflowPopupMenu.getMenu().add(Menu.NONE, R.id.item_settings, Menu.NONE,
                    resources.getString(R.string.action_settings));
            mOverflowPopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    switch (item.getItemId()) {
                        case R.id.item_upgrade_to_pro: {
                            Intent intent = Config.getStoreIntent(context, Config.STORE_PRO_URL);
                            if (intent != null) {
                                context.startActivity(intent);
                                MainController.get().switchToBubbleView();
                            }
                            break;
                        }

                        case R.id.item_reload_page: {
                            mPageInspector.reset();
                            mEventHandler.onPageLoading(mUrl);
                            mWebView.stopLoading();
                            mWebView.reload();
                            String urlAsString = mUrl.toString();
                            updateAppsForUrl(mUrl);
                            configureOpenInAppButton();
                            configureOpenEmbedButton();
                            Log.d(TAG, "reload url: " + urlAsString);
                            mStartTime = System.currentTimeMillis();
                            mTitleTextView.setText(R.string.loading);
                            mUrlTextView.setText(urlAsString.replace("http://", ""));
                            break;
                        }

                        case R.id.item_open_in_browser: {
                            openInBrowser(mUrl.toString());
                            break;
                        }

                        case R.id.item_copy_link: {
                            MainApplication.copyLinkToClipboard(getContext(), mUrl.toString(), R.string.bubble_link_copied_to_clipboard);
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

    PageInspector.OnItemFoundListener mOnPageInspectorItemFoundListener = new PageInspector.OnItemFoundListener() {

        private Runnable mUpdateOpenInAppRunnable = null;
        private Handler mHandler = new Handler();

        @Override
        public void onYouTubeEmbeds() {
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
        public void onTouchIconLoaded(final Bitmap bitmap, final String pageUrl) {

            if (bitmap == null || pageUrl == null) {
                return;
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mUrl != null && mUrl.toString().equals(pageUrl)) {
                        mEventHandler.onReceivedIcon(bitmap);

                        String faviconUrl = Util.getDefaultFaviconUrl(mUrl);
                        MainApplication.sFavicons.putFaviconInMemCache(faviconUrl, bitmap);
                    }
                }
            });
        }
    };

    private void onUrlLongClick(final String urlAsString) {
        Resources resources = getResources();

        final ArrayList<String> longClickSelections = new ArrayList<String>();

        final String shareLabel = resources.getString(R.string.action_share);
        longClickSelections.add(shareLabel);

        String defaultBrowserLabel = Settings.get().getDefaultBrowserLabel();

        final String leftConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Config.BubbleAction.ConsumeLeft);
        if (leftConsumeBubbleLabel != null) {
            if (defaultBrowserLabel == null || defaultBrowserLabel.equals(leftConsumeBubbleLabel) == false) {
                longClickSelections.add(leftConsumeBubbleLabel);
            }
        }

        final String rightConsumeBubbleLabel = Settings.get().getConsumeBubbleLabel(Config.BubbleAction.ConsumeRight);
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
                    MainController.get().onOpenUrl(urlAsString, System.currentTimeMillis(), false);
                } else if (openInBrowserLabel != null && string.equals(openInBrowserLabel)) {
                    openInBrowser(urlAsString);
                } else if (string.equals(shareLabel)) {
                    showSelectShareMethod(urlAsString, false);
                } else if (leftConsumeBubbleLabel != null && string.equals(leftConsumeBubbleLabel)) {
                    MainApplication.handleBubbleAction(getContext(), Config.BubbleAction.ConsumeLeft, urlAsString);
                } else if (rightConsumeBubbleLabel != null && string.equals(rightConsumeBubbleLabel)) {
                    MainApplication.handleBubbleAction(getContext(), Config.BubbleAction.ConsumeRight, urlAsString);
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
        if (mOpenEmbedButton.configure(mPageInspector.getYouTubeEmbedHelper())) {
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

    private void updateAppsForUrl(URL url) {
        List<ResolveInfo> resolveInfos = Settings.get().getAppsThatHandleUrl(url.toString());
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
                        if (resolveInfoToAdd.activityInfo.packageName.equals(Settings.get().mLinkBubbleEntryActivityResolveInfo.activityInfo.packageName)) {
                            continue;
                        }
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
    }

    void onOrientationChanged() {
        invalidate();
    }

    private void updateUrl(String urlAsString) {
        if (urlAsString.equals(mUrl.toString()) == false) {
            try {
                Log.d(TAG, "change url from " + mUrl + " to " + urlAsString);
                mUrl = new URL(urlAsString);
            } catch (MalformedURLException e) {
                throw new RuntimeException("Malformed URL: " + urlAsString);
            }
        }
    }

    private URL getUpdatedUrl(String urlAsString) {
        if (urlAsString.equals(mUrl.toString()) == false) {
            try {
                //Log.d(TAG, "change url from " + mUrl + " to " + urlAsString);
                return new URL(urlAsString);
            } catch (MalformedURLException e) {
                return null;
            }
        }
        return mUrl;
    }

    URL getUrl() {
        return mUrl;
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
        if (mJsAlertDialog != null) {
            mJsAlertDialog.dismiss();
            mJsAlertDialog = null;
        }
        if (mJsConfirmDialog != null) {
            mJsConfirmDialog.dismiss();
            mJsConfirmDialog = null;
        }
    }

    private void resetButtonPressedStates() {
        mShareButton.setIsTouched(false);
        mOpenEmbedButton.setIsTouched(false);
        mOpenInAppButton.setIsTouched(false);
        mOverflowButton.setIsTouched(false);
    }

    private boolean openInBrowser(String urlAsString) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(urlAsString));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (MainApplication.loadInBrowser(getContext(), intent, true)) {
            MainController.get().destroyCurrentBubble(true);
            return true;
        }

        return false;
    }

}
