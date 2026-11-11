package win.cheems.filemanager;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

/** Tests native APK file operations on an Android emulator shared-storage tree.
 * The test touches only its unique CFM_QA_* folder plus CFM's own trash index.
 */
@RunWith(AndroidJUnit4.class)
public final class PcNativeQaTest {
    private static JSONObject arg(String key,Object value) throws Exception {
        return new JSONObject().put(key,value);
    }
    private static JSONObject run(Context ctx,String action,JSONObject args)throws Exception {
        return PcNativeFileApi.execute(ctx,action,args);
    }
    private static JSONArray single(File file) {
        return new JSONArray().put(file.getAbsolutePath());
    }
    private static void deleteSafe(File f) {
        if(!f.exists())return;
        if(f.isDirectory() && !Files.isSymbolicLink(f.toPath())){
            File[] children=f.listFiles();
            if(children!=null)for(File child:children)deleteSafe(child);
        }
        f.delete();
    }
    @Test public void nativeFileOperationsWorkOnAndroidEmulator() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("CI emulator must grant MANAGE_EXTERNAL_STORAGE",
            Environment.isExternalStorageManager());

        File shared=Environment.getExternalStorageDirectory();
        File qa=new File(shared,"Download/CFM_QA_"+UUID.randomUUID().toString().replace("-",""));
        File left=new File(qa,"Left"),right=new File(qa,"Right");
        assertTrue(left.mkdirs());assertTrue(right.mkdirs());
        File note=new File(left,"qa_note.txt");
        File archive=null;
        try{
            assertEquals(shared.getCanonicalPath(),
                run(context,"init",new JSONObject()).getString("home"));
            JSONObject mk=run(context,"mkdir",
                new JSONObject().put("path",left.getPath()).put("name","Created"));
            assertTrue(new File(mk.getString("path")).isDirectory());

            run(context,"newFile",new JSONObject().put("path",left.getPath())
                .put("name","qa_note.txt"));
            assertTrue(note.isFile());
            String expected="CFM 测试文字";
            run(context,"save",new JSONObject().put("path",note.getPath())
                .put("text",expected).put("bom",false));
            assertEquals(expected,run(context,"read",
                arg("path",note.getPath())).getString("text"));
            assertTrue(new File(note.getPath()+".cfm.bak").isFile());

            JSONArray items=run(context,"list",arg("path",left.getPath()))
                .getJSONArray("items");
            assertTrue(items.length()>=3);
            assertTrue(run(context,"search",new JSONObject().put("path",qa.getPath())
                .put("query","qa_note")).getJSONArray("results").length()>=1);

            run(context,"copy",new JSONObject().put("paths",single(note))
                .put("target",right.getPath()));
            File copied=new File(right,note.getName());
            assertEquals(expected,Files.readString(copied.toPath()));
            run(context,"copy",new JSONObject().put("paths",single(note))
                .put("target",right.getPath()));
            assertTrue(new File(right,"qa_note (2).txt").exists());

            File moved=new File(left,"qa_note (2).txt");
            run(context,"move",new JSONObject().put("paths",single(copied))
                .put("target",left.getPath()));
            assertTrue(moved.exists());
            assertFalse(copied.exists());
            run(context,"rename",new JSONObject().put("path",moved.getPath())
                .put("name","renamed.txt"));
            assertTrue(new File(left,"renamed.txt").exists());
            run(context,"newHtml",new JSONObject().put("path",left.getPath())
                .put("name","test.html"));
            assertTrue(new File(left,"test.html").exists());

            archive=new File(run(context,"zip",new JSONObject()
                .put("paths",single(note)).put("target",right.getPath()))
                .getString("path"));
            assertTrue(archive.exists());
            JSONObject out=run(context,"extract",new JSONObject()
                .put("path",archive.getPath()).put("target",right.getPath()));
            assertEquals(expected,Files.readString(
                new File(out.getString("folder"),"qa_note.txt").toPath()));

            Intent share=PcNativeFileApi.shareIntent(context,
                new JSONObject().put("paths",single(note)));
            assertEquals(Intent.ACTION_SEND,share.getAction());
            Uri sharedUri=share.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotNull(sharedUri);
            assertEquals("content",sharedUri.getScheme());

            // This is NOT Android system recycle bin. CFM tracks original paths.
            run(context,"recycle",arg("paths",single(note)));
            assertFalse(note.exists());
            String trash=run(context,"openTrash",new JSONObject()).getString("path");
            File inTrash=new File(trash,"qa_note.txt");
            assertTrue(inTrash.exists());
            run(context,"restore",arg("paths",single(inTrash)));
            assertTrue(note.exists());
            assertEquals(expected,Files.readString(note.toPath()));

            boolean illegalNameRejected=false;
            try {run(context,"mkdir",new JSONObject()
                .put("path",left.getPath()).put("name","../wrong")); }
            catch(Exception ex){illegalNameRejected=true;}
            assertTrue(illegalNameRejected);

            boolean invalidRestoreRejected=false;
            try {run(context,"restore",arg("paths",single(note))); }
            catch(Exception ex){invalidRestoreRejected=true;}
            assertTrue(invalidRestoreRejected);

            JSONObject terminal=run(context,"shell",
                new JSONObject().put("cwd",left.getPath()).put("command","pwd"));
            assertEquals(0,terminal.getInt("exitCode"));
            assertTrue(terminal.getString("output").contains("Left"));
        }finally{
            deleteSafe(qa);
        }
    }
}
