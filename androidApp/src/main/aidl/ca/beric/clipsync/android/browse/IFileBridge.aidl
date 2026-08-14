package ca.beric.clipsync.android.browse;

interface IFileBridge {
    /**
     * Tab-separated "size\tdir\tmtime\tname" rows; empty array for a missing directory.
     * name is last because it is the only user-controlled field — a tab inside a filename
     * stays intact in the final part instead of shifting the fields ahead of it.
     */
    String[] list(String dir);
    /** One "size\tdir\tmtime\tname" row (see list()), or null when the path does not exist. */
    String stat(String path);
    boolean exists(String path);
    String canonical(String path);
    ParcelFileDescriptor open(String path);
    ParcelFileDescriptor create(String path);
    boolean move(String from, String to);
    boolean delete(String path);
    boolean mkdirs(String path);
}
