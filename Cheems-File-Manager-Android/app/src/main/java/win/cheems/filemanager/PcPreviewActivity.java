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
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.TypedValue;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.widget.EditText;
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
    private ProtectedAccess protectedAccess;
    private String pendingProtectedPath;
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

        // Keep the original scaled PC interface inside the WebView, but put
        // the navigation bar below it as REAL Android buttons at touch size.
        // These are not affected by WebView zoom or the 1200px PC viewport.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        container.addView(column, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(25,25,27));
        column.addView(web, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(Color.rgb(46,46,48));
        column.addView(bottom, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        addNavigationButton(bottom, "‹", "后退", () -> callNavigation("back"));
        addNavigationButton(bottom, "›", "前进", () -> callNavigation("forward"));
        addNavigationButton(bottom, "+", "新建文件或文件夹", this::showCreateMenu);
        addNavigationButton(bottom, "⇄", "同步左右两栏路径", () -> callNavigation("sync"));
        addNavigationButton(bottom, "↑", "返回上一级目录", () -> callNavigation("up"));

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
        // The PC UI also uses alert()/confirm()/prompt() for file operations.
        // Android WebView needs WebChromeClient for these to actually appear.
        web.setWebChromeClient(new WebChromeClient() {
            private boolean trusted(String url) {
                return url != null && url.startsWith(BASE);
            }
            @Override public boolean onJsAlert(WebView view,String url,String message,
                                                JsResult result) {
                if (!trusted(url)) {result.cancel(); return true;}
                new AlertDialog.Builder(PcPreviewActivity.this)
                    .setTitle("Cheems File Manager")
                    .setMessage(message)
                    .setPositiveButton("确定",(dialog,which)->result.confirm())
                    .setOnCancelListener(dialog->result.cancel())
                    .show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view,String url,String message,
                                                  JsResult result) {
                if (!trusted(url)) {result.cancel(); return true;}
                new AlertDialog.Builder(PcPreviewActivity.this)
                    .setTitle("确认操作")
                    .setMessage(message)
                    .setNegativeButton("取消",(dialog,which)->result.cancel())
                    .setPositiveButton("确定",(dialog,which)->result.confirm())
                    .setOnCancelListener(dialog->result.cancel())
                    .show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view,String url,String message,
                                                 String defaultValue,JsPromptResult result) {
                if (!trusted(url)) {result.cancel(); return true;}
                EditText entry=new EditText(PcPreviewActivity.this);
                entry.setSingleLine(true);
                entry.setText(defaultValue==null?"":defaultValue);
                int space=(int)(16*getResources().getDisplayMetrics().density);
                entry.setPadding(space,space,space,space);
                new AlertDialog.Builder(PcPreviewActivity.this)
                    .setTitle("输入内容")
                    .setMessage(message)
                    .setView(entry)
                    .setNegativeButton("取消",(dialog,which)->result.cancel())
                    .setPositiveButton("确定",(dialog,which)->
                        result.confirm(entry.getText().toString()))
                    .setOnCancelListener(dialog->result.cancel())
                    .show();
                return true;
            }
        });
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
                    case "icon.webp": mime="image/webp";break;
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
        protectedAccess=new ProtectedAccess(this,()->{
            if(web!=null && pendingProtectedPath!=null){
                String path=pendingProtectedPath;pendingProtectedPath=null;
                web.evaluateJavascript("guarded(()=>navigate(state.active,"+
                    JSONObject.quote(path)+"))",null);
            }
        });
        addNavigationButton(bottom,"⋮","Shizuku 连接与目录授权",()->protectedAccess.show());
        web.loadUrl(BASE+"index.html");

        authorizedOnLastResume = canAccess();
        if (!authorizedOnLastResume) main.postDelayed(this::requestFilesPermission,500);
    }

    private int dp(int px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    private void addNavigationButton(LinearLayout bar, String symbol, String purpose,
                                     Runnable action) {
        TextView button = new TextView(this);
        button.setText(symbol);
        button.setContentDescription(purpose);
        button.setTextColor(Color.rgb(207,207,210));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 31);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        android.util.TypedValue attr = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                attr, true)) button.setBackgroundResource(attr.resourceId);
        bar.addView(button, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        button.setOnClickListener(v -> action.run());
    }

    private void callNavigation(String action) {
        if(web == null) return;
        // Only fixed literal action names reach evaluateJavascript.
        if (!("back".equals(action) || "forward".equals(action) ||
              "sync".equals(action) || "up".equals(action) ||
              "newFolder".equals(action) || "newFile".equals(action) ||
              "newHtml".equals(action))) return;
        web.evaluateJavascript("window.CFMNav && window.CFMNav."+action+"()", null);
    }

    private void showCreateMenu() {
        final String[] choices={"📁 新建文件夹","▤ 新建空文件","〈/〉 新建 HTML 文件"};
        final String[] actions={"newFolder","newFile","newHtml"};
        new AlertDialog.Builder(this)
            .setTitle("＋ 新建")
            .setItems(choices,(dialog,which)->callNavigation(actions[which]))
            .setNegativeButton("取消",null)
            .show();
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
                    if ("openWith".equals(action)) {
                        final int replyId=id;
                        // Resolve/copy protected previews off the UI thread.
                        final Intent view=PcNativeFileApi.openIntent(PcPreviewActivity.this,arguments);
                        main.post(()->{
                            try {
                                if(view.resolveActivity(getPackageManager())==null)
                                    throw new java.io.IOException("没有能打开此类型文件的应用，请安装对应查看器，或长按选择其他操作");
                                startActivity(Intent.createChooser(view,"选择打开方式"));
                                send(new JSONObject().put("id",replyId).put("ok",true)
                                    .put("data",new JSONObject().put("message","已打开系统打开方式菜单")));
                            }catch(Exception e){
                                try{send(new JSONObject().put("id",replyId).put("ok",false)
                                    .put("error",e.getMessage()==null?"无法打开文件":e.getMessage()));}
                                catch(Exception ignored){}
                            }
                        });
                        return;
                    }
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
                    JSONObject data;
                    try {
                        data=PcNativeFileApi.execute(PcPreviewActivity.this,action,arguments);
                    }catch(Exception failure){
                        if("list".equals(action) && PcNativeFileApi.isProtectedPath(arguments.optString("path"))
                                && PcNativeFileApi.protectedService==null){
                            final String target=arguments.optString("path");
                            main.post(()->{pendingProtectedPath=target;protectedAccess.show();});
                        }
                        throw failure;
                    }
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
        if(protectedAccess!=null)protectedAccess.close();
        worker.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("AndroidFileApi");
            web.destroy();
            web=null;
        }
        super.onDestroy();
    }
}
