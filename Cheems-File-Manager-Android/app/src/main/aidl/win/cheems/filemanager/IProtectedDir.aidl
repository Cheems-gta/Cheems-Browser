package win.cheems.filemanager;
import android.os.ParcelFileDescriptor;
interface IProtectedDir {
    String[] list(String path) = 1;
    String listJson(String path) = 2;
    ParcelFileDescriptor openRead(String path) = 3;
    void destroy() = 16777114;
}
