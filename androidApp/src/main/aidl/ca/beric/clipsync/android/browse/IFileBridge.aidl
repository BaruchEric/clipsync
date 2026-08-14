package ca.beric.clipsync.android.browse;

interface IFileBridge {
    /** Tab-separated "name\tsize\tdir\tmtime" rows; empty array for a missing directory. */
    String[] list(String dir);
    /** One "name\tsize\tdir\tmtime" row, or null when the path does not exist. */
    String stat(String path);
    boolean exists(String path);
    String canonical(String path);
    ParcelFileDescriptor open(String path);
    ParcelFileDescriptor create(String path);
    boolean move(String from, String to);
    boolean delete(String path);
    boolean mkdirs(String path);
}
