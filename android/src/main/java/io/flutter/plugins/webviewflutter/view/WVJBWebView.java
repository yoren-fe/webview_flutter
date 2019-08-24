package io.flutter.plugins.webviewflutter.view;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.Keep;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;

import com.tencent.smtt.export.external.interfaces.WebResourceRequest;
import com.tencent.smtt.export.external.interfaces.WebResourceResponse;
import com.tencent.smtt.sdk.ValueCallback;
import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class WVJBWebView extends WebView {
    private static final String BRIDGE_NAME = "WVJBInterface";
    private static final int EXEC_SCRIPT = 1;
    private static final int LOAD_URL = 2;
    private static final int LOAD_URL_WITH_HEADERS = 3;
    private static final int HANDLE_MESSAGE = 4;
    MyHandler mainThreadHandler = null;
    private JavascriptCloseWindowListener javascriptCloseWindowListener = null;


    private static class MyHandler extends Handler {
        /**
         * Using WeakReference to avoid memory leak
         */
        WeakReference<Context> mContextReference;
        private WeakReference<WVJBWebView> wvjbWebViewReference;

        MyHandler(Context context, WVJBWebView wvjbWebView) {
            mContextReference = new WeakReference<>(context);
            wvjbWebViewReference = new WeakReference<>(wvjbWebView);
        }

        @Override
        public void handleMessage(Message msg) {
            final Context context = mContextReference.get();
            final WVJBWebView wvjbWebView = wvjbWebViewReference.get();
            if (context != null && wvjbWebView != null) {
                switch (msg.what) {
                    case EXEC_SCRIPT:
                        wvjbWebView._evaluateJavascript((String) msg.obj);
                        break;
                    case LOAD_URL:
                        wvjbWebView.loadSuperUrl((String) msg.obj);
                        break;
                    case LOAD_URL_WITH_HEADERS: {
                        RequestInfo info = (RequestInfo) msg.obj;
                        wvjbWebView.loadSuperUrl(info.url, info.headers);
                    }
                    break;
                    case HANDLE_MESSAGE:
                        wvjbWebView.handleMessage((String) msg.obj);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private class RequestInfo {
        String url;
        Map<String, String> headers;

        RequestInfo(String url, Map<String, String> additionalHttpHeaders) {
            this.url = url;
            this.headers = additionalHttpHeaders;
        }
    }

    private class WVJBMessage {
        Object data = null;
        String callbackId = null;
        String handlerName = null;
        String responseId = null;
        Object responseData = null;
    }


    public WVJBWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WVJBWebView(Context context) {
        super(context);
        init();
    }

    private ArrayList<WVJBMessage> startupMessageQueue = null;
    private Map<String, WVJBResponseCallback> responseCallbacks = null;
    private Map<String, WVJBHandler> messageHandlers = null;
    private long uniqueId = 0;

    /**
     * 响应接口
     *
     * @param <T>
     */
    public interface WVJBResponseCallback<T> {
        void onResult(T data);
    }

    /**
     * 方法存在接口
     */
    public interface WVJBMethodExistCallback {
        void onResult(boolean exist);
    }


    public interface JavascriptCloseWindowListener {
        /**
         * @return If true, close the current activity, otherwise, do nothing.
         */
        boolean onClose();
    }

    public interface WVJBHandler<T, R> {
        void handler(T data, WVJBResponseCallback<R> callback);
    }

    public void disableJavascriptAlertBoxSafetyTimeout(boolean disable) {
    }

    public void callHandler(String handlerName) {
        callHandler(handlerName, null, null);
    }

    public void callHandler(String handlerName, Object data) {
        callHandler(handlerName, data, null);
    }

    public <T> void callHandler(String handlerName, Object data,
                                WVJBResponseCallback<T> responseCallback) {
        sendData(data, responseCallback, handlerName);
    }

    /**
     * Test whether the handler exist in javascript
     *
     * @param handlerName
     * @param callback
     */
    public void hasJavascriptMethod(String handlerName, final WVJBMethodExistCallback callback) {
        callHandler("_hasJavascriptMethod", handlerName, new WVJBResponseCallback() {
            @Override
            public void onResult(Object data) {
                callback.onResult((boolean) data);
            }
        });
    }

    /**
     * set a listener for javascript closing the current activity.
     */
    public void setJavascriptCloseWindowListener(JavascriptCloseWindowListener listener) {
        javascriptCloseWindowListener = listener;
    }

    public <T, R> void registerHandler(String handlerName, WVJBHandler<T, R> handler) {
        if (handlerName == null || handlerName.length() == 0 || handler == null) {
            return;
        }
        messageHandlers.put(handlerName, handler);
    }

    /**
     * send the onResult message to javascript
     *
     * @param data
     * @param responseCallback
     * @param handlerName
     */
    private void sendData(Object data, WVJBResponseCallback responseCallback,
                          String handlerName) {
        if (data == null && (handlerName == null || handlerName.length() == 0)) {
            return;
        }
        WVJBMessage message = new WVJBMessage();
        if (data != null) {
            message.data = data;
        }
        if (responseCallback != null) {
            String callbackId = "java_cb_" + (++uniqueId);
            responseCallbacks.put(callbackId, responseCallback);
            message.callbackId = callbackId;
        }
        if (handlerName != null) {
            message.handlerName = handlerName;
        }
        queueMessage(message);
    }

    private synchronized void queueMessage(WVJBMessage message) {

        if (startupMessageQueue != null) {
            startupMessageQueue.add(message);
        } else {
            dispatchMessage(message);
        }
    }

    private void dispatchMessage(WVJBMessage message) {
        String messageJSON = message2JSONObject(message).toString();
        evaluateJavascript(String.format("WebViewJavascriptBridge._handleMessageFromJava(%s)", messageJSON));
    }

    // handle the onResult message from javascript
    private void handleMessage(String info) {
        try {
            JSONObject jo = new JSONObject(info);
            WVJBMessage message = JSONObject2WVJBMessage(jo);
            if (message.responseId != null) {
                WVJBResponseCallback responseCallback = responseCallbacks
                        .remove(message.responseId);
                if (responseCallback != null) {
                    responseCallback.onResult(message.responseData);
                }
            } else {
                WVJBResponseCallback responseCallback = null;
                if (message.callbackId != null) {
                    final String callbackId = message.callbackId;
                    responseCallback = new WVJBResponseCallback() {
                        @Override
                        public void onResult(Object data) {
                            WVJBMessage msg = new WVJBMessage();
                            msg.responseId = callbackId;
                            msg.responseData = data;
                            dispatchMessage(msg);
                        }
                    };
                }

                WVJBHandler handler;
                handler = messageHandlers.get(message.handlerName);
                if (handler != null) {
                    handler.handler(message.data, responseCallback);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private JSONObject message2JSONObject(WVJBMessage message) {
        JSONObject jo = new JSONObject();
        try {
            if (message.callbackId != null) {
                jo.put("callbackId", message.callbackId);
            }
            if (message.data != null) {
                jo.put("data", message.data);
            }
            if (message.handlerName != null) {
                jo.put("handlerName", message.handlerName);
            }
            if (message.responseId != null) {
                jo.put("responseId", message.responseId);
            }
            if (message.responseData != null) {
                jo.put("responseData", message.responseData);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jo;
    }

    private WVJBMessage JSONObject2WVJBMessage(JSONObject jo) {
        WVJBMessage message = new WVJBMessage();
        try {
            if (jo.has("callbackId")) {
                message.callbackId = jo.getString("callbackId");
            }
            if (jo.has("data")) {
                message.data = jo.get("data");
            }
            if (jo.has("handlerName")) {
                message.handlerName = jo.getString("handlerName");
            }
            if (jo.has("responseId")) {
                message.responseId = jo.getString("responseId");
            }
            if (jo.has("responseData")) {
                message.responseData = jo.get("responseData");
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return message;
    }

    void init() {
        mainThreadHandler = new MyHandler(getContext(), this);
        this.responseCallbacks = new HashMap<>();
        this.messageHandlers = new HashMap<>();
        this.startupMessageQueue = new ArrayList<>();
        super.setWebChromeClient(mWebChromeClient);
        super.setWebViewClient(mWebViewClient);

        registerHandler("_hasNativeMethod", new WVJBHandler() {
            @Override
            public void handler(Object data, WVJBResponseCallback callback) {
                callback.onResult(messageHandlers.get(data) != null);
            }
        });
        registerHandler("_closePage", new WVJBHandler() {
            @Override
            public void handler(Object data, WVJBResponseCallback callback) {
                if (javascriptCloseWindowListener == null
                        || javascriptCloseWindowListener.onClose()) {
                    ((Activity) getContext()).onBackPressed();
                }
            }
        });
        registerHandler("_disableJavascriptAlertBoxSafetyTimeout", new WVJBHandler() {
            @Override
            public void handler(Object data, WVJBResponseCallback callback) {
                disableJavascriptAlertBoxSafetyTimeout((boolean) data);
            }
        });
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN) {
            super.addJavascriptInterface(new Object() {
                @Keep
                @JavascriptInterface
                public void notice(String info) {
                    Message msg = new Message();
                    msg.what = HANDLE_MESSAGE;
                    msg.obj = info;
                    mainThreadHandler.sendMessage(msg);
                }

            }, BRIDGE_NAME);
        }

    }

    private void _evaluateJavascript(String script) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WVJBWebView.super.evaluateJavascript(script, null);
        } else {
            loadUrl("javascript:" + script);
        }
    }

    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param script
     */
    public void evaluateJavascript(final String script) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            _evaluateJavascript(script);
        } else {
            Message msg = new Message();
            msg.what = EXEC_SCRIPT;
            msg.obj = script;
            mainThreadHandler.sendMessage(msg);
        }
    }


    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param url
     */
    @Override
    public void loadUrl(String url) {
        Message msg = new Message();
        msg.what = LOAD_URL;
        msg.obj = url;
        mainThreadHandler.sendMessage(msg);
    }

    private void loadSuperUrl(String url) {
        super.loadUrl(url);
    }

    /**
     * This method can be called in any thread, and if it is not called in the main thread,
     * it will be automatically distributed to the main thread.
     *
     * @param url
     * @param additionalHttpHeaders
     */
    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        Message msg = new Message();
        msg.what = LOAD_URL_WITH_HEADERS;
        msg.obj = new RequestInfo(url, additionalHttpHeaders);
        mainThreadHandler.sendMessage(msg);
    }

    private void loadSuperUrl(String url, Map<String, String> headers) {
        super.loadUrl(url, headers);
    }

    // proxy client
    WebChromeClient webChromeClient;
    WebViewClient webViewClient;

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        webChromeClient = client;
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
        webViewClient = client;
    }

    private WebChromeClient mWebChromeClient = new WebChromeClient() {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress > 80) {
                injectJsBridge(view);
            }
            if (webChromeClient != null) {
                webChromeClient.onProgressChanged(view, newProgress);
            } else {
                super.onProgressChanged(view, newProgress);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (webChromeClient != null) {
                webChromeClient.onReceivedTitle(view, title);
            } else {
                super.onReceivedTitle(view, title);
            }
        }

        @Override
        public void openFileChooser(ValueCallback<Uri> valueCallback, String s, String s1) {
            if (webChromeClient != null) {
                webChromeClient.openFileChooser(valueCallback, s, s1);
            } else {
                super.openFileChooser(valueCallback, s, s1);
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (webChromeClient != null) {
                return webChromeClient.onShowFileChooser(webView, filePathCallback, fileChooserParams);
            }
            return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
    };

    private WebViewClient mWebViewClient = new WebViewClient() {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (webViewClient != null) {
                return webViewClient.shouldOverrideUrlLoading(view, url);
            } else {
                return super.shouldOverrideUrlLoading(view, url);
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (webViewClient != null) {
                webViewClient.onPageStarted(view, url, favicon);
            } else {
                super.onPageStarted(view, url, favicon);
            }

        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (webViewClient != null) {
                webViewClient.onPageFinished(view, url);
            } else {
                super.onPageFinished(view, url);
            }

        }

        @Override
        @Deprecated
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (webViewClient != null) {
                return webViewClient.shouldInterceptRequest(view, url);
            } else {
                return super.shouldInterceptRequest(view, url);
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (webViewClient != null) {
                return webViewClient.shouldInterceptRequest(view, request);
            } else {
                return super.shouldInterceptRequest(view, request);
            }
        }

        @Override
        @Deprecated
        public void onTooManyRedirects(WebView view, Message cancelMsg, Message continueMsg) {
            if (webViewClient != null) {
                webViewClient.onTooManyRedirects(view, cancelMsg, continueMsg);
            } else {
                super.onTooManyRedirects(view, cancelMsg, continueMsg);
            }
        }

        @Override
        @Deprecated
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (webViewClient != null) {
                webViewClient.onReceivedError(view, errorCode, description, failingUrl);
            } else {
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        }

        @TargetApi(Build.VERSION_CODES.M)
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (webViewClient != null) {
                webViewClient.onReceivedHttpError(view, request, errorResponse);
                ;
            } else {
                super.onReceivedHttpError(view, request, errorResponse);
            }
        }

        @Override
        public void onFormResubmission(WebView view, Message dontResend, Message resend) {
            if (webViewClient != null) {
                webViewClient.onFormResubmission(view, dontResend, resend);
            } else {
                super.onFormResubmission(view, dontResend, resend);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (webViewClient != null) {
                webViewClient.doUpdateVisitedHistory(view, url, isReload);
            } else {
                super.doUpdateVisitedHistory(view, url, isReload);
            }

        }

        @Override
        public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
            if (webViewClient != null) {
                return webViewClient.shouldOverrideKeyEvent(view, event);
            } else {
                return super.shouldOverrideKeyEvent(view, event);
            }
        }

        @Override
        @Deprecated
        public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
            if (webViewClient != null) {
                webViewClient.onUnhandledKeyEvent(view, event);
            } else {
                super.onUnhandledKeyEvent(view, event);
            }

        }

        @Override
        public void onScaleChanged(WebView view, float oldScale, float newScale) {
            if (webViewClient != null) {
                webViewClient.onScaleChanged(view, oldScale, newScale);
            } else {
                super.onScaleChanged(view, oldScale, newScale);
            }

        }

        @Override
        public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
            if (webViewClient != null) {
                webViewClient.onReceivedLoginRequest(view, realm, account, args);
            } else {
                super.onReceivedLoginRequest(view, realm, account, args);
            }
        }
    };

    private void injectJsBridge(final WebView view) {
        try {
            InputStream is = view.getContext().getAssets()
                    .open("WebViewJavascriptBridge.js");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String js = new String(buffer);
            evaluateJavascript(js);
        } catch (IOException e) {
            e.printStackTrace();
        }

        synchronized (WVJBWebView.this) {
            if (startupMessageQueue != null) {
                for (int i = 0; i < startupMessageQueue.size(); i++) {
                    dispatchMessage(startupMessageQueue.get(i));
                }
                startupMessageQueue = null;
            }
        }
    }

}