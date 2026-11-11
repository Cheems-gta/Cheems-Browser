package win.cheems.filemanager;

// User explicitly grants CFM access through Shizuku.
// The service only supports read-only listings and copying protected
// Android/data or Android/obb items OUT into normal user storage.
interface IRestrictedFiles {
    String[] listEntries(String path) = 1;
    void copyOut(String sourcePath, String destinationDirectory) = 2;
    void destroy() = 16777114;
}
