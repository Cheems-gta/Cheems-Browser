package win.cheems.filemanager;
interface IProtectedDir {
    String[] list(String path) = 1;
    void destroy() = 16777114;
}
