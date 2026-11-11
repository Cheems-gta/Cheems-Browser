package win.cheems.filemanager;
interface IRestrictedFiles {
    String[] listEntries(String path) = 1;
    void copyOut(String source, String destinationFolder) = 2;
    void destroy() = 16777114;
}
