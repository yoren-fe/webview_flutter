// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugins.webviewflutter.view.WVJBWebView;

public class FlutterWebView implements PlatformView, MethodCallHandler {
    private static final String JS_CHANNEL_NAMES_FIELD = "javascriptChannelNames";
    private final WVJBWebView webView;
    private final MethodChannel methodChannel;
    private Context context;

    @SuppressWarnings("unchecked")
    FlutterWebView(Context context, BinaryMessenger messenger, int id, Map<String, Object> params) {
        Log.e("FlutterWebView", context + "");
        this.context = context;
        webView = new WVJBWebView(context);
        // Allow local storage.
        webView.getSettings().setDomStorageEnabled(true);
        WebView.setWebContentsDebuggingEnabled(true);
        methodChannel = new MethodChannel(messenger, "plugins.flutter.io/webview_" + id);
        methodChannel.setMethodCallHandler(this);

        applySettings((Map<String, Object>) params.get("settings"));

        if (params.containsKey(JS_CHANNEL_NAMES_FIELD)) {
            registerJavaScriptChannelNames((List<String>) params.get(JS_CHANNEL_NAMES_FIELD));
        }

        if (params.containsKey("initialUrl")) {
            String url = (String) params.get("initialUrl");
            webView.loadUrl(url);
        }
    }

    @Override
    public View getView() {
        return webView;
    }

    @Override
    public void onMethodCall(MethodCall methodCall, Result result) {
        switch (methodCall.method) {
            case "loadUrl":
                loadUrl(methodCall, result);
                break;
            case "updateSettings":
                updateSettings(methodCall, result);
                break;
            case "canGoBack":
                canGoBack(methodCall, result);
                break;
            case "canGoForward":
                canGoForward(methodCall, result);
                break;
            case "goBack":
                goBack(methodCall, result);
                break;
            case "goForward":
                goForward(methodCall, result);
                break;
            case "reload":
                reload(methodCall, result);
                break;
            case "currentUrl":
                currentUrl(methodCall, result);
                break;
            case "evaluateJavascript":
                evaluateJavaScript(methodCall, result);
                break;
            case "addJavascriptChannels":
                addJavaScriptChannels(methodCall, result);
                break;
            case "removeJavascriptChannels":
                removeJavaScriptChannels(methodCall, result);
                break;
            case "clearCache":
                clearCache(result);
                break;
            case "registerHandler":
                registerHandler(methodCall, result);
                break;
            default:
                result.notImplemented();
        }
    }

    private void loadUrl(MethodCall methodCall, Result result) {
        String url = (String) methodCall.arguments;
        webView.loadUrl(url);
        result.success(null);
    }

    private void canGoBack(MethodCall methodCall, Result result) {
        result.success(webView.canGoBack());
    }

    private void canGoForward(MethodCall methodCall, Result result) {
        result.success(webView.canGoForward());
    }

    private void goBack(MethodCall methodCall, Result result) {
        if (webView.canGoBack()) {
            webView.goBack();
        }
        result.success(null);
    }

    private void goForward(MethodCall methodCall, Result result) {
        if (webView.canGoForward()) {
            webView.goForward();
        }
        result.success(null);
    }

    private void reload(MethodCall methodCall, Result result) {
        webView.reload();
        result.success(null);
    }

    private void currentUrl(MethodCall methodCall, Result result) {
        result.success(webView.getUrl());
    }

    @SuppressWarnings("unchecked")
    private void updateSettings(MethodCall methodCall, Result result) {
        applySettings((Map<String, Object>) methodCall.arguments);
        result.success(null);
    }

    private void evaluateJavaScript(MethodCall methodCall, final Result result) {
        String jsString = (String) methodCall.arguments;
        if (jsString == null) {
            throw new UnsupportedOperationException("JavaScript string cannot be null");
        }
        webView.evaluateJavascript(
                jsString,
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        result.success(value);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private void addJavaScriptChannels(MethodCall methodCall, Result result) {
        List<String> channelNames = (List<String>) methodCall.arguments;
        registerJavaScriptChannelNames(channelNames);
        result.success(null);
    }

    @SuppressWarnings("unchecked")
    private void removeJavaScriptChannels(MethodCall methodCall, Result result) {
        List<String> channelNames = (List<String>) methodCall.arguments;
        for (String channelName : channelNames) {
            webView.removeJavascriptInterface(channelName);
        }
        result.success(null);
    }

    private void clearCache(Result result) {
        webView.clearCache(true);
        WebStorage.getInstance().deleteAllData();
        result.success(null);
    }

    private void registerHandler(final MethodCall methodCall, MethodChannel.Result result) {
        if (context != null) {
            final String handlerName = (String) methodCall.arguments;
            WVJBWebView.WVJBHandler<String, String> handler = new WVJBWebView.WVJBHandler<String, String>() {
                @Override
                public void handler(String s, final WVJBWebView.WVJBResponseCallback<String> wvjbResponseCallback) {
                    Log.e("registerHandler", s);
                    methodChannel.invokeMethod("jsBridge", s, new Result() {
                        @Override
                        public void success(Object o) {
                            Log.e("registerHandler/success", o.toString());
                            wvjbResponseCallback.onResult((String) o);
                        }

                        @Override
                        public void error(String s, String s1, Object o) {
                            Log.e("registerHandler/error", o.toString());

                            wvjbResponseCallback.onResult(s);
                        }

                        @Override
                        public void notImplemented() {
                            Log.e("registerHandler/notImplemented", "");


                        }
                    });
                }
            };
            webView.registerHandler(handlerName, handler);
            result.success(null);
        } else {
            result.error("context is null", null, null);
        }
    }

    private void applySettings(Map<String, Object> settings) {
        for (String key : settings.keySet()) {
            switch (key) {
                case "jsMode":
                    updateJsMode((Integer) settings.get(key));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown WebView setting: " + key);
            }
        }
    }

    private void updateJsMode(int mode) {
        switch (mode) {
            case 0: // disabled
                webView.getSettings().setJavaScriptEnabled(false);
                break;
            case 1: // unrestricted
                webView.getSettings().setJavaScriptEnabled(true);
                break;
            default:
                throw new IllegalArgumentException("Trying to set unknown JavaScript mode: " + mode);
        }
    }

    private void registerJavaScriptChannelNames(List<String> channelNames) {
        for (String channelName : channelNames) {
            webView.addJavascriptInterface(
                    new JavaScriptChannel(methodChannel, channelName), channelName);
        }
    }

    @Override
    public void dispose() {
        methodChannel.setMethodCallHandler(null);
    }
}
