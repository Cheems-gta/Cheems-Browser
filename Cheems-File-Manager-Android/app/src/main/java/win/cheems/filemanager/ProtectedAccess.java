package win.cheems.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/** Lifecycle-owned connection; authorization is always handled by Shizuku. */
final class ProtectedAccess implements AutoCloseable {
    private final Activity activity;
    private final Runnable connected;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs args;
    private boolean binding, closed;
    private final Shizuku.OnBinderReceivedListener received=()->main.post(this::bind);
    private final Shizuku.OnBinderDeadListener dead=()->main.post(()->{
        PcNativeFileApi.protectedService=null;binding=false;
    });
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{
        if(code!=2011)return;
        main.post(()->{
            if(result==PackageManager.PERMISSION_GRANTED)bind();
            else message("未授权 Shizuku，可在 ⋮ 中重新连接");
        });
    };
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            main.post(()->{
                if(closed)return;
                binding=false;
                PcNativeFileApi.protectedService=IProtectedDir.Stub.asInterface(binder);
                message("Shizuku 已连接，可尝试只读打开 data / obb");
                connected.run();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name){
            main.post(()->{PcNativeFileApi.protectedService=null;binding=false;});
        }
    };
    ProtectedAccess(Activity activity,Runnable connected){
        this.activity=activity;this.connected=connected;
        args=new Shizuku.UserServiceArgs(new ComponentName(activity.getPackageName(),
            ProtectedDirService.class.getName())).daemon(false)
            .processNameSuffix("cfm_readonly").version(11);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(dead);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    private void message(String text){
        if(!closed)Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
    }
    private void bind(){
        if(closed||binding||PcNativeFileApi.protectedService!=null)return;
        try {
            if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return;
            binding=true;
            Shizuku.bindUserService(args,connection);
            main.postDelayed(()->{
                if(binding&&!closed){
                    binding=false;
                    message("Shizuku 连接超时，请确认服务仍在运行后重试");
                }
            },10000);
        }catch(Exception e){binding=false;message("连接失败："+e.getMessage());}
    }
    void show(){
        if(closed||activity.isFinishing())return;
        new AlertDialog.Builder(activity).setTitle("Android/data 与 obb 访问")
            .setMessage("请先在 Shizuku 中启动服务，然后点击连接并允许授权。连接后可只读浏览和打开文件；部分系统仍可能拒绝访问。")
            .setNegativeButton("取消",null)
            .setPositiveButton("连接 / 授权",(d,w)->connect()).show();
    }
    private void connect(){
        try {
            if(!Shizuku.pingBinder()){message("Shizuku 尚未启动，请启动后返回重试");return;}
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                if(PcNativeFileApi.protectedService!=null){connected.run();message("Shizuku 已连接");}
                else bind();
            }else if(Shizuku.shouldShowRequestPermissionRationale()){
                message("授权已被拒绝，请在 Shizuku 的应用管理中允许 Cheems File Manager");
            }else Shizuku.requestPermission(2011);
        }catch(Exception e){message("无法连接："+e.getMessage());}
    }
    @Override public void close(){
        closed=true;main.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        try{Shizuku.unbindUserService(args,connection,false);}catch(Exception ignored){}
        PcNativeFileApi.protectedService=null;
    }
}
