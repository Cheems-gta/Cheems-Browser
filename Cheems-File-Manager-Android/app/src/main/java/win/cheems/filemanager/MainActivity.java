package win.cheems.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACK = Color.rgb(11, 22, 43);
    private static final int PANEL = Color.argb(107, 38, 64, 85);
    private static final int LIGHT = Color.rgb(232, 232, 232);
    private static final int MUTED = Color.rgb(185, 207, 225);
    private final File root = Environment.getExternalStorageDirectory();
    private final Pane left = new Pane("左", "left");
    private final Pane right = new Pane("右", "right");
    private Pane active = left;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat stamp = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());

    private LinearLayout host, fileArea, notice;
    private TextView pathView, counts;
    private boolean hadAccess = false;

    private static final class Pane {
        final String title, pref;
        File folder;
        File selected;
        LinearLayout rows;
        LinearLayout tabs;
        final ArrayList<File> openFolders = new ArrayList<>();
        TextView dirLabel;
        Pane(String title, String pref) { this.title = title; this.pref = pref; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(Color.rgb(35,35,35));
        left.folder = restore(left);
        right.folder = restore(right);
        left.openFolders.add(left.folder);
        right.openFolders.add(right.folder);
        createUi();
        renderPermissionState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (host != null) renderPermissionState();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable color(int c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c); d.setCornerRadius(dp(12));
        return d;
    }
    private GradientDrawable glassPanel(boolean selected) {
        GradientDrawable d = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            selected ? new int[]{0xB95B839F,0xA9284567,0x9D172D4D} :
                       new int[]{0xAD41627C,0x96304D6B,0x911B314F});
        d.setCornerRadius(dp(18));
        d.setStroke(dp(1), selected ? 0xB7B5E4FF : 0x7375AECF);
        return d;
    }
    private GradientDrawable glassCard(boolean selected) {
        GradientDrawable d = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            selected ? new int[]{0xD27AACCB,0xB84E799A,0xB6396084} :
                       new int[]{0xAE567B98,0x9B365473,0x8D29415C});
        d.setCornerRadius(dp(13));
        d.setStroke(dp(1), selected ? 0xDDE0F7FF : 0x7389C0DA);
        return d;
    }
    private static final class GlassBackdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            float w = getBounds().width(), h = getBounds().height();
            if (w < 1 || h < 1) return;
            paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF091524,0xFF204B66,0xFF0C1E36},null,
                Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(w * .12f, h * .13f,
                Math.max(w,h) * .65f,
                new int[]{0x875CB0D3,0x355490B3,0x003C89D0},
                new float[]{0f,.55f,1f},Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,w,h,paint);
            paint.setShader(new RadialGradient(w * .92f, h * .89f,
                Math.max(w,h) * .55f,
                new int[]{0x506880DC,0x255E91CC,0x003C89D0},
                new float[]{0f,.5f,1f},Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,w,h,paint);
            paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
    private TextView text(String s, int size, int color) {
        TextView view = new TextView(this);
        view.setText(s); view.setTextColor(color); view.setTextSize(size);
        return view;
    }
    private Button btn(String label, Runnable click) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setBackground(glassCard(false));
        b.setOnClickListener(v -> {
            try { click.run(); } catch (Exception e) { fail(e); }
        });
        return b;
    }
    private void createUi() {
        host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setBackground(new GlassBackdrop());
        setContentView(host);
        // Android 15+ enforces edge-to-edge for target SDK 35: keep our header
        // and file rows away from the system status/navigation bars.
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                android.view.WindowInsets.Type.statusBars() |
                android.view.WindowInsets.Type.navigationBars());
            host.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(12),dp(8),dp(10),dp(10));
        heading.setBackground(glassPanel(false));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1,-2);
        headerLp.setMargins(dp(7),dp(7),dp(7),dp(7));
        host.addView(heading,headerLp);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        heading.addView(topBar);

        pathView = text(root.getAbsolutePath() + "/", 18, LIGHT);
        pathView.setSingleLine(true);
        pathView.setGravity(Gravity.CENTER_VERTICAL);
        pathView.setEllipsize(TextUtils.TruncateAt.END);
        pathView.setOnClickListener(v -> promptPath());
        topBar.addView(pathView, new LinearLayout.LayoutParams(0, dp(45), 1));
        Button search = btn("⌕", this::searchDialog);
        search.setTextSize(24);
        topBar.addView(search, new LinearLayout.LayoutParams(dp(44), dp(42)));
        Button menu = btn("⋮", this::moreMenu);
        menu.setTextSize(23);
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(44), dp(42));
        menuParams.setMargins(dp(5), 0, 0, 0);
        topBar.addView(menu, menuParams);

        counts = text("Cheems File Manager · v0.4", 12, MUTED);
        heading.addView(counts);

        fileArea = new LinearLayout(this);
        fileArea.setOrientation(LinearLayout.HORIZONTAL);
        host.addView(fileArea, new LinearLayout.LayoutParams(-1,0,1));
        createPane(left);
        createPane(right);

        notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setPadding(dp(24),dp(30),dp(24),dp(10));
        host.addView(notice, new LinearLayout.LayoutParams(-1,0,1));

    }

    private void createPane(Pane pane) {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackground(glassPanel(false));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0,-1,1);
        fp.setMargins(dp(5),dp(2),dp(5),dp(5));
        fileArea.addView(frame,fp);

        // Each open directory has a compact glass tab and its own overflow menu.
        HorizontalScrollView tabScroller = new HorizontalScrollView(this);
        tabScroller.setHorizontalScrollBarEnabled(false);
        pane.tabs = new LinearLayout(this);
        pane.tabs.setOrientation(LinearLayout.HORIZONTAL);
        pane.tabs.setPadding(dp(4),dp(5),dp(4),dp(3));
        tabScroller.addView(pane.tabs);
        frame.addView(tabScroller,new LinearLayout.LayoutParams(-1,dp(42)));

        pane.dirLabel = text(pane.title + " · /",11, MUTED);
        pane.dirLabel.setSingleLine(true);
        pane.dirLabel.setEllipsize(TextUtils.TruncateAt.END);
        pane.dirLabel.setPadding(dp(10),dp(3),dp(5),dp(8));
        pane.dirLabel.setOnClickListener(v -> selectPane(pane));
        frame.addView(pane.dirLabel);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        pane.rows = new LinearLayout(this);
        pane.rows.setOrientation(LinearLayout.VERTICAL);
        pane.rows.setPadding(dp(2),dp(1),dp(2),dp(4));
        scroll.addView(pane.rows);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
    }

    private boolean canAccess() {
        return android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    private void renderPermissionState() {
        boolean granted = canAccess();
        fileArea.setVisibility(granted ? View.VISIBLE : View.GONE);
        notice.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (granted) {
            if (!hadAccess) {
                left.folder = restore(left);
                right.folder = restore(right);
            }
            refreshBoth();
        } else {
            notice.removeAllViews();
            TextView title = text("允许访问共享存储文件",22,LIGHT);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            notice.addView(title);
            TextView desc = text(
                "\nCFM 是本地文件管理器，需要访问你自己手机的共享存储。\n\n"
                + "只需要通过 Android 系统设置授权一次，之后打开直接显示文件；"
                + "授权也可以随时在系统设置里撤销。\n\n"
                + "注意：这不是 ROOT，Android/data 等受保护目录仍可能无法访问。\n",
                15, MUTED);
            notice.addView(desc);
            Button allow = btn("前往系统设置 · 授权一次",this::requestFilesPermission);
            notice.addView(allow,new LinearLayout.LayoutParams(-1,dp(54)));
        }
        hadAccess = granted;
    }

    private void requestFilesPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        try { startActivity(intent); }
        catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void permissionInfo() {
        new AlertDialog.Builder(this).setTitle("CFM v0.4 · 文件权限")
            .setMessage("本版使用 Android 官方“所有文件访问”授权。\n"
                + "只需授权一次，除非你卸载应用、清除数据或在系统设置中撤销权限。\n\n"
                + "无需 ROOT，不依赖 Shizuku。Android 13 及以上的 Shizuku 兼容方案会另行开发。"
                + "\n\n注意：授权不等于拥有访问其他应用私有目录的权限。")
            .setNegativeButton("取消",null)
            .setPositiveButton("打开权限设置",(d,w)->requestFilesPermission()).show();
    }

    private File restore(Pane pane) {
        String path = getPreferences(MODE_PRIVATE).getString(pane.pref,root.getAbsolutePath());
        File candidate = new File(path);
        return insideRoot(candidate) && candidate.isDirectory() ? candidate : root;
    }

    private boolean insideRoot(File file) {
        try {
            String base = root.getCanonicalPath();
            String test = file.getCanonicalPath();
            return test.equals(base) || test.startsWith(base + File.separator);
        } catch (IOException e) { return false; }
    }

    private void selectPane(Pane p) {
        active = p;
        if (pathView == null || counts == null) return;
        pathView.setText(active.folder.getAbsolutePath().replaceAll("/+$","") + "/");
        int dirs=0, files=0;
        File[] entries=active.folder.listFiles();
        if (entries != null) for (File f: entries) { if(f.isDirectory()) dirs++; else files++; }
        try {
            StatFs stat = new StatFs(root.getAbsolutePath());
            double used=(double)(stat.getTotalBytes()-stat.getAvailableBytes())/1073741824.0;
            double total=(double)stat.getTotalBytes()/1073741824.0;
            counts.setText("文件夹: "+dirs+"  文件: "+files+"  存储: "
                +String.format(Locale.getDefault(),"%.1f/%.1fG",used,total));
        } catch (Exception e) {
            counts.setText("文件夹: "+dirs+"  文件: "+files+"  · CFM v0.4");
        }
        left.dirLabel.setText((active==left?"● ":"  ")+left.folder.getAbsolutePath());
        right.dirLabel.setText((active==right?"● ":"  ")+right.folder.getAbsolutePath());
        renderTabs(left);
        renderTabs(right);
    }

    private void refreshBoth() {
        if (!canAccess()) return;
        refresh(left);
        refresh(right);
        selectPane(active);
    }

    private void refresh(Pane p) {
        p.rows.removeAllViews();
        p.selected=null;
        addParentRow(p);
        File[] arr=p.folder.listFiles();
        if (arr==null) {
            rowInfo(p,"该目录不可访问（系统权限限制）");
            return;
        }
        Arrays.sort(arr,Comparator.comparing((File f)->!f.isDirectory())
            .thenComparing(f->f.getName().toLowerCase(Locale.ROOT)));
        if(arr.length==0) rowInfo(p,"文件夹为空");
        for(int i=0;i<Math.min(arr.length,400);i++) addFileRow(p,arr[i]);
        if(arr.length>400) rowInfo(p,"仅显示前 400 项");
    }

    private void rowInfo(Pane p,String s) {
        TextView text=text(s,11,MUTED);
        text.setPadding(dp(10),dp(15),dp(4),dp(10));
        p.rows.addView(text);
    }

    private void addParentRow(Pane p) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(glassCard(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,dp(57));
        lp.setMargins(dp(4),dp(4),dp(4),dp(3));
        p.rows.addView(line,lp);
        TextView icon = text("📁",19,LIGHT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(color(0x9A283A55));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40),dp(40));
        iconLp.setMargins(dp(6),0,dp(9),0);
        line.addView(icon,iconLp);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(words,new LinearLayout.LayoutParams(0,-1,1));
        TextView top = text("..",15,Color.WHITE);
        top.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        top.setSingleLine(true);
        words.addView(top);
        TextView sub = text(p.folder.equals(root)?"已到共享存储根目录":"返回上一级",10,MUTED);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(sub);
        line.setOnClickListener(v-> {
            File parent=p.folder.getParentFile();
            if(parent!=null && insideRoot(parent)) navigate(p,parent);
            else say("已经在根目录");
        });
    }

    private void addFileRow(Pane p,File file) {
        boolean folder=file.isDirectory();
        LinearLayout line=new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackground(glassCard(false));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(62));
        lp.setMargins(dp(4),dp(4),dp(4),dp(3));
        p.rows.addView(line,lp);

        TextView icon=text(folder ? "📁" :
            ArchiveExtractor.supported(file) ? "🗜" : "📄",20,LIGHT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(color(folder ? 0x976B542A : 0x8B345B84));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42),dp(43));
        iconLp.setMargins(dp(6),0,dp(9),0);
        line.addView(icon,iconLp);

        LinearLayout words=new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(words,new LinearLayout.LayoutParams(0,-1,1));
        TextView name=text(file.getName(),13,Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxLines(1);
        words.addView(name,new LinearLayout.LayoutParams(-1,dp(23)));

        TextView subtitle=text(stamp.format(new Date(file.lastModified())),11,MUTED);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(18)));
        line.setPadding(0,0,dp(5),0);
        line.setOnClickListener(v -> {
            active=p;
            if(folder) {
                if(!file.canRead()) {
                    selectPane(p);
                    new AlertDialog.Builder(this).setMessage("此目录可能受到 Android 系统限制，无法读取。")
                        .setPositiveButton("确定",null).show();
                    return;
                }
                navigate(p,file);
            } else {
                p.selected=file;
                mark(p,line);
                selectPane(p);
            }
        });
        line.setOnLongClickListener(v -> {
            active=p;
            p.selected=file;
            mark(p,line);
            selectPane(p);
            fileContextMenu(p, file);
            return true;
        });
    }

    private void mark(Pane p,View chosen) {
        for(int i=0;i<p.rows.getChildCount();i++) {
            View v=p.rows.getChildAt(i);
            if(v instanceof LinearLayout) v.setBackground(glassCard(v==chosen));
        }
    }

    private void renderTabs(Pane pane) {
        if(pane.tabs==null) return;
        pane.tabs.removeAllViews();
        for(File folder: new ArrayList<>(pane.openFolders)) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(glassCard(folder.equals(pane.folder)));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2,dp(34));
            cp.setMargins(dp(2),0,dp(4),0);
            pane.tabs.addView(chip,cp);

            String tabName = folder.equals(root) ? "根目录" : folder.getName();
            TextView label = text("📁 "+tabName,10,LIGHT);
            label.setSingleLine(true);
            label.setMaxWidth(dp(90));
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(8),0,dp(2),0);
            chip.addView(label,new LinearLayout.LayoutParams(-2,dp(32)));
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setOnClickListener(v->switchTab(pane,folder));

            TextView more = text("⋮",18,Color.WHITE);
            more.setGravity(Gravity.CENTER);
            chip.addView(more,new LinearLayout.LayoutParams(dp(25),dp(34)));
            more.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(tabName)
                .setItems(new String[]{"切换到此文件夹","关闭此标签","关闭其他标签"},(d,which)->{
                    if(which==0) switchTab(pane,folder);
                    else if(which==1) closeTab(pane,folder);
                    else {
                        pane.openFolders.clear();
                        pane.openFolders.add(folder);
                        switchTab(pane,folder);
                    }
                }).show());
        }
    }

    private void switchTab(Pane p,File folder) {
        if(!folder.isDirectory() || !insideRoot(folder)){say("文件夹不可访问");return;}
        active=p;
        p.folder=folder;
        getPreferences(MODE_PRIVATE).edit().putString(p.pref,folder.getAbsolutePath()).apply();
        refresh(p);selectPane(p);
    }

    private void closeTab(Pane p,File folder) {
        p.openFolders.remove(folder);
        if(p.openFolders.isEmpty()) p.openFolders.add(root);
        if(p.folder.equals(folder)) switchTab(p,p.openFolders.get(p.openFolders.size()-1));
        else renderTabs(p);
    }

    private void navigate(Pane p, File folder) {
        if (!insideRoot(folder) || !folder.isDirectory() || !folder.canRead()) {
            new AlertDialog.Builder(this).setMessage("不能访问这个路径。")
                .setPositiveButton("确定",null).show();
            return;
        }
        p.folder=folder;
        if(!p.openFolders.contains(folder)) {
            if(p.openFolders.size() >= 12) p.openFolders.remove(0);
            p.openFolders.add(folder);
        }
        getPreferences(MODE_PRIVATE).edit().putString(p.pref,folder.getAbsolutePath()).apply();
        refresh(p);
        selectPane(p);
    }
    private void up() { File parent=active.folder.getParentFile(); if(parent!=null)navigate(active,parent); }
    private void goBack() { up(); }
    @Override public void onBackPressed() {
        if(!active.folder.equals(root)) up(); else super.onBackPressed();
    }
    private void promptPath() {
        if(!canAccess()) return;
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setText(active.folder.getAbsolutePath());
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("打开路径")
            .setMessage("可输入共享存储中的完整文件夹路径")
            .setView(field).setNegativeButton("取消",null)
            .setPositiveButton("进入",(d,w)->navigate(active,new File(field.getText().toString().trim())))
            .show();
    }

    private String safeName(String proposed) {
        String name=proposed.trim();
        if(name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.contains("/") || name.contains("\\") || name.indexOf('\0')>=0)
            throw new IllegalArgumentException("文件名不合法");
        return name;
    }
    private void newFolder() {
        if(!canAccess()) return;
        final Pane current=active;
        EditText field=new EditText(this); field.setSingleLine(true);field.setText("新建文件夹");
        new AlertDialog.Builder(this).setTitle("新建文件夹").setView(field)
            .setNegativeButton("取消",null)
            .setPositiveButton("创建",(d,w)->{
                try {
                    File dest=new File(current.folder,safeName(field.getText().toString()));
                    if(dest.exists() || !dest.mkdir()) throw new IOException("文件夹存在或创建失败");
                    refresh(current);
                    selectPane(current);
                }catch(Exception e){fail(e);}
            }).show();
    }
    private void renameSelected() {
        if(active.selected==null){say("长按文件夹或点击文件后才能重命名");return;}
        final Pane p=active; final File original=p.selected;
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(original.getName());
        new AlertDialog.Builder(this).setTitle("重命名").setView(field)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                try {
                    File target=new File(original.getParentFile(),safeName(field.getText().toString()));
                    if(target.exists() || !original.renameTo(target))throw new IOException("目标已存在或重命名失败");
                    refresh(p);selectPane(p);
                }catch(Exception e){fail(e);}
            }).show();
    }
    private File unique(File parent,String original) {
        File candidate=new File(parent,original);
        if(!candidate.exists())return candidate;
        int dot=original.lastIndexOf(".");
        String stem=dot>0?original.substring(0,dot):original;
        String ext=dot>0?original.substring(dot):"";
        for(int i=2;i<9999;i++){
            candidate=new File(parent,stem+" ("+i+")"+ext);
            if(!candidate.exists())return candidate;
        }
        throw new IllegalStateException("重名文件太多");
    }
    private void copySelected() {
        if(active.selected==null){say("先选择一个文件（或长按文件夹）");return;}
        final Pane origin=active;
        final Pane dest=active==left?right:left;
        final File source=origin.selected;
        if(source.isDirectory()){
            try{
                if(dest.folder.getCanonicalPath().equals(source.getCanonicalPath())||
                    dest.folder.getCanonicalPath().startsWith(source.getCanonicalPath()+File.separator)){
                    say("不能复制文件夹到它自己内部");return;
                }
            }catch(IOException e){fail(e);return;}
        }
        new AlertDialog.Builder(this).setTitle("复制到另一栏")
            .setMessage(source.getName()+"\n→ "+dest.folder.getAbsolutePath()+"\n\n建议先用测试文件。")
            .setNegativeButton("取消",null).setPositiveButton("复制",(d,w)->{
                say("正在复制……");
                io.execute(()->{
                    try {
                        if(!insideRoot(source)||!insideRoot(dest.folder))throw new IOException("越出共享存储范围");
                        copy(source,unique(dest.folder,source.getName()),0);
                        runOnUiThread(()->{
                            refresh(origin);refresh(dest);selectPane(origin);
                            say("复制完成："+source.getName());
                        });
                    } catch(Exception e){runOnUiThread(()->fail(e));}
                });
            }).show();
    }
    private void copy(File source,File target,int depth)throws Exception{
        if(depth>25)throw new IOException("文件夹嵌套过深");
        if(source.isDirectory()){
            if(!target.mkdir()) throw new IOException("无法创建："+target.getName());
            File[] entries=source.listFiles();
            if(entries==null)throw new IOException("无法读取："+source.getName());
            for(File item:entries)copy(item,new File(target,item.getName()),depth+1);
            return;
        }
        if(!source.isFile())throw new IOException("暂不支持这种文件类型");
        try(InputStream in=new FileInputStream(source);
            OutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[32768];int size;
            while((size=in.read(buffer))!=-1)out.write(buffer,0,size);
        }
    }
    private void editSelected(){
        if(active.selected==null||!active.selected.isFile()){say("先选中一个文本文件");return;}
        File file=active.selected;
        String lower=file.getName().toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".txt")||lower.endsWith(".json")||lower.endsWith(".md")||
             lower.endsWith(".js")||lower.endsWith(".css")||lower.endsWith(".html")||
             lower.endsWith(".xml")||lower.endsWith(".ini")||lower.endsWith(".csv")||
             lower.endsWith(".java")||lower.endsWith(".kt"))){say("只支持常见纯文本文件");return;}
        if(file.length()>256*1024){say("文本编辑只支持 256KB 以内");return;}
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=new FileInputStream(file)){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
            EditText editor=new EditText(this);
            editor.setTypeface(Typeface.MONOSPACE);
            editor.setTextSize(13);
            editor.setGravity(Gravity.TOP|Gravity.START);
            editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editor.setMinLines(10);editor.setMaxLines(15);
            editor.setText(out.toString("UTF-8"));
            ScrollView scroll=new ScrollView(this);scroll.addView(editor);
            new AlertDialog.Builder(this).setTitle("编辑："+file.getName()).setView(scroll)
                .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                    try(OutputStream stream=new FileOutputStream(file)){
                        stream.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
                        say("已保存："+file.getName());
                    }catch(Exception e){fail(e);}
                }).show();
        }catch(Exception e){fail(e);}
    }

    private void moreMenu() {
        final String[] items = {"搜索文件", "新建文件夹", "返回上一级", "刷新",
            "授权设置与说明", "支持的解压格式"};
        new AlertDialog.Builder(this).setTitle("CFM v0.3").setItems(items, (d, which) -> {
            switch (which) {
                case 0: searchDialog(); break;
                case 1: newFolder(); break;
                case 2: up(); break;
                case 3: refreshBoth(); break;
                case 4: permissionInfo(); break;
                case 5: archiveInfo(); break;
                default: break;
            }
        }).show();
    }

    private void fileContextMenu(Pane p, File file) {
        final boolean archive = ArchiveExtractor.supported(file);
        final ArrayList<String> actions = new ArrayList<>();
        if (file.isDirectory()) actions.add("进入文件夹");
        actions.add("复制到另一栏");
        if (archive) actions.add("解压到当前目录");
        if (file.isFile()) actions.add("文本编辑");
        actions.add("重命名");
        actions.add("删除（永久删除）");
        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(actions.toArray(new String[0]), (d, index) -> {
                String action = actions.get(index);
                active = p;
                p.selected = file;
                switch (action) {
                    case "进入文件夹": navigate(p, file); break;
                    case "复制到另一栏": copySelected(); break;
                    case "解压到当前目录": extractArchive(file); break;
                    case "文本编辑": editSelected(); break;
                    case "重命名": renameSelected(); break;
                    case "删除（永久删除）": confirmDelete(p, file); break;
                    default: break;
                }
            })
            .setNegativeButton("取消", null).show();
    }

    private void confirmDelete(Pane pane, File selectedFile) {
        if (selectedFile.equals(root) || !insideRoot(selectedFile)) {
            say("无法删除共享存储根目录或无权限路径");
            return;
        }
        new AlertDialog.Builder(this).setTitle("确认永久删除")
            .setMessage("确定永久删除「" + selectedFile.getName() + "」？\n\n"
                + "若是文件夹，会连同其中所有内容一起删除。"
                + " 此操作不会进入回收站，无法撤销，请先备份重要文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("永久删除", (d,w) -> {
                io.execute(() -> {
                    try {
                        deleteRecursively(selectedFile, 0, new int[]{0});
                        runOnUiThread(() -> {
                            refresh(pane);
                            selectPane(pane);
                            say("已删除："+selectedFile.getName());
                        });
                    } catch (Exception e) { runOnUiThread(() -> fail(e)); }
                });
            }).show();
    }

    private void deleteRecursively(File file, int depth, int[] count) throws Exception {
        if (depth > 30 || ++count[0] > 10000)
            throw new IOException("安全限制：删除的文件过多或层级过深");
        if (file.equals(root) || !insideRoot(file))
            throw new IOException("拒绝删除共享存储之外的路径");
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            // Remove link itself; never follow it.
            if (!file.delete()) throw new IOException("删除失败：" + file.getName());
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取文件夹：" + file.getName());
            for (File child : children) deleteRecursively(child, depth+1, count);
        }
        if (!file.delete()) throw new IOException("删除失败：" + file.getName());
    }

    private void extractArchive(File archive) {
        if (!ArchiveExtractor.supported(archive)) {
            say("不支持该压缩格式");
            return;
        }
        new AlertDialog.Builder(this).setTitle("解压文件")
            .setMessage("解压「" + archive.getName() + "」到同一目录下的新文件夹？\n"
                + "需要足够剩余空间；分卷请放在同一目录并选第一卷。"
                + "\n\n陌生压缩包可能包含异常内容，建议先在测试目录使用。")
            .setNegativeButton("取消",null)
            .setPositiveButton("开始解压",(d,w) -> {
                say("正在解压，请勿重复操作……");
                io.execute(() -> {
                    try {
                        File extracted = ArchiveExtractor.extract(archive, archive.getParentFile(), getCacheDir());
                        runOnUiThread(() -> {
                            refreshBoth();
                            say("解压完成：" + extracted.getName());
                        });
                    } catch (Exception ex) {
                        runOnUiThread(() -> fail(ex));
                    }
                });
            }).show();
    }

    private void archiveInfo() {
        new AlertDialog.Builder(this).setTitle("CFM v0.3 · 解压工具箱")
            .setMessage("支持：ZIP、JAR、APK、XAPK、APKS、APKM、7z、RAR、"
                + "TAR、TAR.GZ/TGZ、TAR.BZ2/TBZ2、TAR.XZ/TXZ、TAR.LZMA、"
                + "GZ、BZ2、XZ、LZMA、LZ4、Unix Z、AR、DEB、CPIO、ARJ、DUMP。\n\n"
                + "分卷：ZIP 的 .z01+.zip；7z.001/ZIP.001 顺序拼接；"
                + "RAR 常见分卷（依赖 Junrar）。需要所有分卷放在同一目录。\n\n"
                + "不保证每种加密算法和稀有子格式都能解压；"
                + "CAB、ISO、WIM、DMG、ACE 等暂未支持。\n"
                + "常见格式限制：单文件 512MB，总量 1GB，最多 4000 项；"
                + "RAR 由独立解码库处理，不适用同一套配额。")
            .setPositiveButton("确定",null).show();
    }

    private void searchDialog() {
        if (!canAccess()) { requestFilesPermission(); return; }
        final Pane pane = active;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("输入文件或文件夹名称");
        field.setPadding(dp(16), dp(9), dp(16), dp(9));
        new AlertDialog.Builder(this).setTitle("搜索当前目录及子目录")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索", (d,w) -> {
                String term=field.getText().toString().trim().toLowerCase(Locale.ROOT);
                if(term.length()<2){say("请输入至少两个字符");return;}
                say("正在搜索 " + pane.folder.getName() + "……");
                io.execute(() -> {
                    List<File> found = new ArrayList<>();
                    ArrayDeque<File> pending = new ArrayDeque<>();
                    pending.add(pane.folder);
                    int scanned=0;
                    while(!pending.isEmpty() && scanned<35000 && found.size()<120){
                        File folder=pending.removeFirst();
                        if(!insideRoot(folder))continue;
                        File[] entries=folder.listFiles();
                        if(entries==null)continue;
                        for(File item:entries) {
                            if(++scanned>=35000 || found.size()>=120)break;
                            if(item.getName().toLowerCase(Locale.ROOT).contains(term))found.add(item);
                            if(item.isDirectory() && !java.nio.file.Files.isSymbolicLink(item.toPath())
                                && insideRoot(item))pending.addLast(item);
                        }
                    }
                    final int checked=scanned;
                    runOnUiThread(() -> showSearchResults(pane, term, found, checked));
                });
            }).show();
    }

    private void showSearchResults(Pane pane, String query, List<File> files, int checked) {
        if (files.isEmpty()) { say("没有找到：" + query); return; }
        String[] names = new String[files.size()];
        for (int i=0;i<files.size();i++) {
            File f=files.get(i);
            names[i]=(f.isDirectory()?"📁 ":"📄 ")+f.getName()+"\n"+f.getParent();
        }
        new AlertDialog.Builder(this)
            .setTitle("搜索“"+query+"” · "+files.size()+" 项（扫描 "+checked+"）")
            .setItems(names, (d,w) -> {
                File selected=files.get(w);
                if(selected.isDirectory()) navigate(pane, selected);
                else {
                    navigate(pane,selected.getParentFile());
                    pane.selected=selected;
                    say("已定位：" + selected.getName() + "；长按文件可操作");
                }
            })
            .setNegativeButton("关闭",null).show();
    }

    private void say(String message){
        android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();
    }
    private void fail(Exception e){
        new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(e.getMessage())
            .setPositiveButton("知道了",null).show();
    }
}
