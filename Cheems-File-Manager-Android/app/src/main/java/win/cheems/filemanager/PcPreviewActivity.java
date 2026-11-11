package win.cheems.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PcPreviewActivity extends Activity {
    private static final String BASE = "https://cfm.local/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean askedPermission;
    private boolean authorizedOnLastResume;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(24,24,26));
        getWindow().setNavigationBarColor(Color.rgb(24,24,26));
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.rgb(25,25,27));
        setContentView(container);
        container.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
            container.setPadding(0,bars.top,0,bars.bottom);
            return insets;
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(25,25,27));
        container.addView(web, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        web.setInitialScale(0);
        web.addJavascriptInterface(new NativeBridge(), "AndroidFileApi");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req) {
                return !req.getUrl().toString().startsWith(BASE);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                Uri uri=request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"cfm.local".equals(uri.getHost()))
                    return null;
                String name=uri.getPath().replaceFirst("^/", "");
                String mime;
                switch(name) {
                    case "index.html": mime="text/html";break;
                    case "style.css": mime="text/css";break;
                    case "app.js": case "android-bridge.js": mime="application/javascript";break;
                    default: return new WebResourceResponse("text/plain","utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }
                try {
                    InputStream stream = getAssets().open("pcui/"+name);
                    Map<String,String> headers=new HashMap<>();
                    headers.put("Cache-Control","no-store");
                    headers.put("X-Content-Type-Options","nosniff");
                    headers.put("Content-Security-Policy",
                        "default-src 'none'; script-src 'self'; " +
                        "style-src 'self' 'unsafe-inline'; img-src data: 'self'; " +
                        "frame-src 'self' about:; connect-src 'none'; object-src 'none'; " +
                        "base-uri 'none'; form-action 'none'");
                    return new WebResourceResponse(mime,"utf-8",200,"OK",headers,stream);
                } catch(Exception e) {
                    return new WebResourceResponse("text/plain","utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.loadUrl(BASE+"index.html");

        authorizedOnLastResume = canAccess();
        if (!authorizedOnLastResume) main.postDelayed(this::requestFilesPermission,500);
    }

    private boolean canAccess() {
        return android.os.Build.VERSION.SDK_INT >= 30 &&
            Environment.isExternalStorageManager();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean now=canAccess();
        if (now && !authorizedOnLastResume && web!=null)
            web.reload();
        authorizedOnLastResume=now;
    }

    private void requestFilesPermission() {
        if (askedPermission || canAccess() || isFinishing()) return;
        askedPermission=true;
        new AlertDialog.Builder(this)
            .setTitle("允许查看手机文件")
            .setMessage("这是完整 PC 版界面的 Android 预览 APK。"
                +"为了让两栏真正列出你的共享存储，需通过 Android 系统给予一次“所有文件访问”权限。"
                +"不需要 Root，也不上传文件；Android/data 仍受系统限制。")
            .setNegativeButton("暂不授权",null)
            .setPositiveButton("前往系统设置",(dialog,which)->{
                Intent intent=new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:"+getPackageName()));
                try { startActivity(intent); }
                catch(Exception ex){
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }).show();
    }

    private final class NativeBridge {
        @JavascriptInterface public void postMessage(String request) {
            worker.execute(()->{
                int id=-1;
                try {
                    JSONObject req=new JSONObject(request);
                    id=req.getInt("id");
                    String action=req.getString("action");
                    JSONObject arguments=req.optJSONObject("args");
                    if(arguments==null)arguments=new JSONObject();
                    if ("share".equals(action)) {
                        // Android chooser must be launched on the main thread.
                        final int replyId = id;
                        final JSONObject shareArgs = arguments;
                        main.post(() -> {
                            try {
                                Intent share = PcNativeFileApi.shareIntent(
                                    PcPreviewActivity.this, shareArgs);
                                startActivity(Intent.createChooser(share,
                                    "分享给微信 / QQ / 其他应用"));
                                send(new JSONObject().put("id",replyId).put("ok",true)
                                    .put("data",new JSONObject()
                                        .put("message","已打开系统分享菜单")));
                            } catch (Exception shareFailure) {
                                try {
                                    send(new JSONObject().put("id",replyId)
                                        .put("ok",false)
                                        .put("error",shareFailure.getMessage()==null ?
                                            "无法打开系统分享菜单" : shareFailure.getMessage()));
                                } catch(Exception ignored) { }
                            }
                        });
                        return;
                    }
                    JSONObject data=PcNativeFileApi.execute(
                        PcPreviewActivity.this, action, arguments);
                    JSONObject reply=new JSONObject()
                        .put("id",id).put("ok",true).put("data",data);
                    send(reply);
                } catch(Exception ex) {
                    try {
                        JSONObject fail=new JSONObject()
                            .put("id",id).put("ok",false)
                            .put("error",ex.getMessage()==null?"操作失败":ex.getMessage());
                        send(fail);
                    } catch(Exception ignored) { }
                }
            });
        }
    }

    private void send(JSONObject reply) {
        String wrapped=JSONObject.quote(reply.toString());
        main.post(()->{
            if (web!=null) web.evaluateJavascript(
                "window.__cfmNativeReceive && window.__cfmNativeReceive("+wrapped+")",null);
        });
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("AndroidFileApi");
            web.destroy();
            web=null;
        }
        super.onDestroy();
    }
}
