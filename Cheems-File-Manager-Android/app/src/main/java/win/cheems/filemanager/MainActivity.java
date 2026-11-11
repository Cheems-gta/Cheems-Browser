package win.cheems.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.documentfile.provider.DocumentFile;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int CHOOSE_LEFT = 101;
    private static final int CHOOSE_RIGHT = 102;
    private static final int BACK = Color.rgb(10, 19, 34);
    private static final int PANEL = Color.rgb(21, 39, 61);
    private static final int LIGHT = Color.rgb(220, 242, 255);

    private final Pane left = new Pane("左侧工作区", "left");
    private final Pane right = new Pane("右侧工作区", "right");
    private Pane active = left;
    private LinearLayout paneHost;
    private TextView status;

    private static final class Pane {
        final String label;
        final String key;
        DocumentFile root;
        DocumentFile current;
        DocumentFile selected;
        LinearLayout rows;
        TextView title;
        TextView path;
        LinearLayout panel;
        Pane(String label, String key) { this.label = label; this.key = key; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        buildUi();
        restore(left);
        restore(right);
        refresh(left);
        refresh(right);
        setStatus("先用“选目录”授权两个文件夹；点击文件进行选择。");
    }

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + 0.5f); }

    private GradientDrawable bg(int color, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private TextView text(String content, int size, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(bg(Color.rgb(40, 81, 111), Color.rgb(92, 145, 170), 12));
        button.setPadding(dp(9), dp(4), dp(9), dp(4));
        button.setOnClickListener(v -> {
            try { action.run(); } catch (Exception ex) { error(ex); }
        });
        return button;
    }

    private void addTool(LinearLayout bar, String name, Runnable action) {
        Button b = button(name, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        params.setMargins(dp(4), dp(2), dp(4), dp(2));
        bar.addView(b, params);
    }

    private void buildUi() {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(BACK);
        frame.setPadding(dp(9), dp(10), dp(9), dp(6));
        setContentView(frame);

        TextView logo = text("CHEEMS  /  FILE MANAGER", 19, Color.WHITE);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setPadding(dp(7), dp(4), dp(7), dp(7));
        frame.addView(logo);

        TextView sub = text("V0.1 · Android 原生双栏 · 离线运行", 11, Color.rgb(141, 190, 212));
        sub.setPadding(dp(7), 0, 0, dp(9));
        frame.addView(sub);

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        horizontal.addView(tools);
        frame.addView(horizontal);

        addTool(tools, "⇄ 复制到另一栏", this::copySelected);
        addTool(tools, "＋ 新建文件夹", this::newFolder);
        addTool(tools, "✎ 重命名", this::renameSelected);
        addTool(tools, "▤ 文本编辑", this::editSelected);
        addTool(tools, "↻ 刷新", () -> { refresh(left); refresh(right); });
        addTool(tools, "帮助", this::showHelp);

        paneHost = new LinearLayout(this);
        frame.addView(paneHost, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setupPane(left, CHOOSE_LEFT);
        setupPane(right, CHOOSE_RIGHT);
        useOrientation();

        status = text("", 11, Color.rgb(170, 208, 231));
        status.setPadding(dp(7), dp(6), dp(7), dp(7));
        frame.addView(status);
    }

    private void setupPane(Pane p, int requestCode) {
        p.panel = new LinearLayout(this);
        p.panel.setOrientation(LinearLayout.VERTICAL);
        p.panel.setPadding(dp(10), dp(9), dp(10), dp(9));
        p.panel.setBackground(bg(PANEL, Color.rgb(56, 97, 124), 16));
        p.panel.setOnClickListener(v -> activate(p));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        p.title = text(p.label, 14, LIGHT);
        p.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(p.title, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button choose = button("选目录", () -> chooseFolder(requestCode));
        header.addView(choose, new LinearLayout.LayoutParams(dp(78), dp(39)));
        Button up = button("↑", () -> goUp(p));
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(dp(43), dp(39));
        upParams.setMargins(dp(5), 0, 0, 0);
        header.addView(up, upParams);
        p.panel.addView(header);

        p.path = text("尚未选择文件夹", 11, Color.rgb(150, 193, 215));
        p.path.setMaxLines(2);
        p.path.setPadding(0, 0, 0, dp(7));
        p.panel.addView(p.path);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        p.rows = new LinearLayout(this);
        p.rows.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(p.rows);
        p.panel.addView(scroller, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void useOrientation() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        paneHost.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        paneHost.removeAllViews();
        for (Pane p : Arrays.asList(left, right)) {
            LinearLayout.LayoutParams lp = landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.setMargins(dp(3), dp(5), dp(3), dp(5));
            paneHost.addView(p.panel, lp);
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        useOrientation();
    }

    private void activate(Pane p) {
        active = p;
        left.title.setText(left.label + (p == left ? "  ●" : ""));
        right.title.setText(right.label + (p == right ? "  ●" : ""));
    }

    private void chooseFolder(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != CHOOSE_LEFT && requestCode != CHOOSE_RIGHT) return;
        try {
            Uri uri = data.getData();
            int flags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
            Pane pane = requestCode == CHOOSE_LEFT ? left : right;
            pane.root = DocumentFile.fromTreeUri(this, uri);
            pane.current = pane.root;
            pane.selected = null;
            getPreferences(MODE_PRIVATE).edit().putString(pane.key, uri.toString()).apply();
            activate(pane);
            refresh(pane);
            setStatus("文件夹授权成功：" + pane.current.getName());
        } catch (Exception ex) { error(ex); }
    }

    private void restore(Pane p) {
        String stored = getPreferences(MODE_PRIVATE).getString(p.key, null);
        if (stored == null) return;
        try {
            p.root = DocumentFile.fromTreeUri(this, Uri.parse(stored));
            p.current = p.root;
        } catch (Exception ex) {
            p.root = null;
            p.current = null;
        }
    }

    private void goUp(Pane p) {
        activate(p);
        if (p.current == null || p.root == null || p.current.getUri().equals(p.root.getUri())) {
            setStatus("已到授权目录顶部；可重新选择目录。");
            return;
        }
        DocumentFile parent = p.current.getParentFile();
        if (parent != null) p.current = parent;
        p.selected = null;
        refresh(p);
    }

    private void refresh(Pane p) {
        p.rows.removeAllViews();
        p.selected = null;
        if (p.current == null) {
            labelRow(p, "点击右上角“选目录”选择授权文件夹。");
            p.path.setText("未授权");
            return;
        }
        try {
            p.path.setText("📂 " + p.current.getName());
            DocumentFile[] files = p.current.listFiles();
            Arrays.sort(files, Comparator
                .comparing((DocumentFile f) -> !f.isDirectory())
                .thenComparing(f -> f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT)));
            if (files.length == 0) labelRow(p, "这个文件夹是空的。");
            int limit = Math.min(files.length, 300);
            for (int i = 0; i < limit; i++) addItem(p, files[i]);
            if (files.length > limit) labelRow(p, "已显示前 300 项，暂不支持超大目录。");
        } catch (Exception ex) {
            labelRow(p, "目录读取失败；请重新选择目录。");
            p.path.setText("读取失败");
            setStatus("文件夹读取异常：" + ex.getMessage());
        }
        activate(active);
    }

    private void labelRow(Pane p, String content) {
        TextView label = text(content, 12, Color.rgb(176, 196, 216));
        label.setPadding(dp(9), dp(13), dp(5), dp(12));
        p.rows.addView(label);
    }

    private void addItem(Pane p, DocumentFile item) {
        boolean folder = item.isDirectory();
        String name = item.getName() == null ? "(未命名)" : item.getName();
        TextView row = text((folder ? "📁 " : "📄 ") + name, 13, Color.WHITE);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), 0, dp(8), 0);
        row.setBackground(bg(Color.rgb(27, 48, 72), Color.rgb(44, 69, 94), 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.setMargins(0, 0, 0, dp(5));
        p.rows.addView(row, lp);

        row.setOnClickListener(v -> {
            activate(p);
            if (folder) {
                p.current = item;
                refresh(p);
            } else {
                p.selected = item;
                markSelected(p, row);
                setStatus("已选择 " + name + "；可复制或文本编辑。");
            }
        });
        row.setOnLongClickListener(v -> {
            activate(p);
            p.selected = item;
            markSelected(p, row);
            if (!folder) editSelected();
            else setStatus("已选择文件夹 " + name + "，可复制到另一栏。");
            return true;
        });
    }

    private void markSelected(Pane p, TextView selectedView) {
        for (int i = 0; i < p.rows.getChildCount(); i++) {
            View item = p.rows.getChildAt(i);
            if (item instanceof TextView) item.setBackground(
                bg(item == selectedView ? Color.rgb(45, 105, 139) :
                   Color.rgb(27, 48, 72), Color.rgb(44, 69, 94), 9));
        }
    }

    private void copySelected() {
        if (active.selected == null) { setStatus("先点击一个文件，或长按一个文件夹。"); return; }
        Pane destPane = active == left ? right : left;
        if (destPane.current == null) { setStatus("另一栏还没有选择目标文件夹。"); return; }
        DocumentFile source = active.selected;
        DocumentFile destination = destPane.current;
        if (source.isDirectory()) {
            // Do not recursively copy a folder into itself or one of its children.
            for (DocumentFile cursor = destination; cursor != null; cursor = cursor.getParentFile()) {
                if (cursor.getUri().equals(source.getUri())) {
                    setStatus("不能把文件夹复制到自身或子目录。");
                    return;
                }
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("确认复制")
            .setMessage("复制 “" + source.getName() + "” 到 “" + destination.getName() +
                        "”？\n首次体验请使用测试文件夹。")
            .setNegativeButton("取消", null)
            .setPositiveButton("复制", (d, which) -> {
                try {
                    recursiveCopy(source, destination, 0);
                    refresh(left); refresh(right);
                    setStatus("复制完成。");
                } catch (Exception ex) { error(ex); }
            }).show();
    }

    private String uniqueName(DocumentFile dest, String base) {
        if (dest.findFile(base) == null) return base;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (dest.findFile(candidate) == null) return candidate;
        }
        throw new IllegalStateException("目标文件夹中存在过多同名文件。");
    }

    private void recursiveCopy(DocumentFile src, DocumentFile destFolder, int depth) throws Exception {
        if (depth > 30) throw new IllegalStateException("文件夹层级过深，已停止。");
        String name = uniqueName(destFolder, src.getName() == null ? "unknown" : src.getName());
        if (src.isDirectory()) {
            DocumentFile newFolder = destFolder.createDirectory(name);
            if (newFolder == null) throw new IllegalStateException("创建目标文件夹失败：" + name);
            for (DocumentFile child : src.listFiles()) recursiveCopy(child, newFolder, depth + 1);
            return;
        }
        if (!src.isFile()) throw new IllegalStateException("暂不支持此类型：" + name);
        String mime = src.getType() == null ? "application/octet-stream" : src.getType();
        DocumentFile newFile = destFolder.createFile(mime, name);
        if (newFile == null) throw new IllegalStateException("创建目标文件失败：" + name);
        try (InputStream input = getContentResolver().openInputStream(src.getUri());
             OutputStream output = getContentResolver().openOutputStream(newFile.getUri(), "w")) {
            if (input == null || output == null) throw new IllegalStateException("无法打开文件流。");
            byte[] buffer = new byte[32768];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        }
    }

    private void newFolder() {
        if (active.current == null) { setStatus("请先选择一个文件夹。"); return; }
        EditText edit = input("新文件夹");
        new AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", (d, which) -> {
                try {
                    String name = checkedName(edit.getText().toString());
                    if (active.current.findFile(name) != null) throw new IllegalStateException("名称已经存在。");
                    if (active.current.createDirectory(name) == null) throw new IllegalStateException("创建失败。");
                    refresh(active);
                } catch (Exception ex) { error(ex); }
            }).show();
    }

    private void renameSelected() {
        if (active.selected == null) { setStatus("请先选择一个文件或文件夹。"); return; }
        DocumentFile file = active.selected;
        EditText edit = input(file.getName() == null ? "" : file.getName());
        new AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (d, which) -> {
                try {
                    String name = checkedName(edit.getText().toString());
                    if (active.current.findFile(name) != null) throw new IllegalStateException("名称已经存在。");
                    if (!file.renameTo(name)) throw new IllegalStateException("该目录提供方不支持重命名。");
                    refresh(active);
                } catch (Exception ex) { error(ex); }
            }).show();
    }

    private EditText input(String initial) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(initial);
        edit.setSelectAllOnFocus(true);
        edit.setPadding(dp(20), dp(15), dp(20), dp(15));
        return edit;
    }

    private String checkedName(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") ||
            name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("文件名不能为空，且不能包含路径分隔符。");
        return name;
    }

    private void editSelected() {
        if (active.selected == null || !active.selected.isFile()) {
            setStatus("请先选择一个文本文件。");
            return;
        }
        DocumentFile item = active.selected;
        String name = item.getName() == null ? "" : item.getName();
        String low = name.toLowerCase(Locale.ROOT);
        if (!(low.endsWith(".txt") || low.endsWith(".json") || low.endsWith(".xml") ||
              low.endsWith(".html") || low.endsWith(".css") || low.endsWith(".js") ||
              low.endsWith(".java") || low.endsWith(".kt") || low.endsWith(".md") ||
              low.endsWith(".ini") || low.endsWith(".cfg") || low.endsWith(".csv"))) {
            setStatus("V0.1 只支持常见的 UTF-8 文本文件。");
            return;
        }
        try {
            byte[] content;
            try (InputStream input = getContentResolver().openInputStream(item.getUri())) {
                if (input == null) throw new IllegalStateException("读取失败。");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    if (bytes.size() + len > 256 * 1024)
                        throw new IllegalStateException("V0.1 只编辑 256KB 以下的文本文件。");
                    bytes.write(buffer, 0, len);
                }
                content = bytes.toByteArray();
            }

            EditText field = new EditText(this);
            field.setTypeface(Typeface.MONOSPACE);
            field.setTextSize(12);
            field.setGravity(Gravity.TOP | Gravity.START);
            field.setMinLines(10);
            field.setMaxLines(18);
            field.setHorizontallyScrolling(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setText(new String(content, java.nio.charset.StandardCharsets.UTF_8));
            ScrollView wrap = new ScrollView(this);
            wrap.addView(field);
            new AlertDialog.Builder(this)
                .setTitle("编辑 · " + name)
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, which) -> {
                    try (OutputStream output = getContentResolver().openOutputStream(item.getUri(), "wt")) {
                        if (output == null) throw new IllegalStateException("文件无法写入。");
                        output.write(field.getText().toString().getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
                        output.flush();
                        setStatus("已保存：" + name);
                    } catch (Exception ex) { error(ex); }
                }).show();
        } catch (Exception ex) { error(ex); }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
            .setTitle("CFM Android V0.1")
            .setMessage("① 左右分别点击“选目录”，授权两个测试文件夹。\n\n" +
                        "② 点击文件夹进入；点击文件选中；长按文件夹可选中它。\n\n" +
                        "③ 上方横向工具栏：复制、重命名、新建文件夹、编辑文本。\n\n" +
                        "④ 竖屏上下双栏，横屏左右双栏。\n\n" +
                        "基于 Android 的文件夹授权机制，不需要 ROOT，不读取未授权目录。" +
                        "目前没有删除/移动功能，避免 V0.1 误操作重要资料。")
            .setPositiveButton("了解", null)
            .show();
    }

    private void setStatus(String message) { if (status != null) status.setText(message); }
    private void error(Exception ex) {
        new AlertDialog.Builder(this).setTitle("操作失败")
            .setMessage(ex.getMessage() == null ? ex.toString() : ex.getMessage())
            .setPositiveButton("知道了", null).show();
    }
}
