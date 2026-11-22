package top.izuna.foliamajor.shell;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// android/app/src/main/java/top/izuna/foliamajor/shell/NodeRuntime.java
// 应用内的 Node 运行时：首次启动把 assets 里的后端压缩包解开，然后在一个后台线程里跑 main.js。
// Node 是进程级单例，退出就没了；上层用健康检查判断它到底起来了没有。
public final class NodeRuntime {

    public interface Progress {
        void onProgress(String message);
    }

    private static final String TAG = "FoliaNode";
    private static final String PROJECT_DIR = "nodejs-project";
    private static final String PROJECT_ZIP = "nodejs-project.zip";
    private static final String STAMP_FILE = ".stamp";

    private static boolean started = false;
    private static volatile int exitCode = Integer.MIN_VALUE;
    /** 这一次进程里重新解过包（APK 更新过）：页面可能还留着旧版 Service Worker 的缓存。 */
    private static volatile boolean justExtracted = false;

    static {
        System.loadLibrary("folia_node");
    }

    private NodeRuntime() {
    }

    private static native int startNode(String[] arguments, String modulesPath, boolean redirectOutput);

    public static File projectDir(Context context) {
        return new File(context.getFilesDir(), PROJECT_DIR);
    }

    public static boolean hasExited() {
        return exitCode != Integer.MIN_VALUE;
    }

    public static int exitCode() {
        return exitCode;
    }

    /** True once this process has unpacked a new build, so the page should drop any older cache. */
    public static boolean justExtracted() {
        return justExtracted;
    }

    /** 解开后端包。APK 变了才重解，之后每次启动都直接用已经解开的目录。 */
    public static synchronized void ensureProject(Context context, Progress progress) throws IOException {
        final File target = projectDir(context);
        final String stamp = String.valueOf(new File(context.getPackageCodePath()).lastModified());
        final File stampFile = new File(target, STAMP_FILE);

        if (stampFile.isFile() && stamp.equals(readText(stampFile)) && new File(target, "main.js").isFile()) {
            return;
        }

        progress.onProgress(context.getString(R.string.preparing_components));
        deleteRecursively(target);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("无法创建 " + target);
        }

        int entries = 0;
        final byte[] buffer = new byte[1 << 16];
        try (InputStream raw = context.getAssets().open(PROJECT_ZIP);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                final File destination = new File(target, entry.getName());
                if (!destination.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator)) {
                    throw new IOException("压缩包里有越界路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    destination.mkdirs();
                    continue;
                }
                final File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录 " + parent);
                }
                try (OutputStream output = new FileOutputStream(destination)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                entries++;
                if (entries % 500 == 0) {
                    progress.onProgress(context.getString(R.string.preparing_components) + " " + entries);
                }
            }
        }

        writeText(stampFile, stamp);
        justExtracted = true;
        Log.i(TAG, "[project] extracted " + entries + " entries to " + target);
    }

    /** 起 Node；重复调用只生效一次。返回后 Node 在后台线程里继续跑。 */
    public static synchronized void start(Context context, Progress progress) throws IOException {
        if (started) return;
        started = true;

        final File directory = projectDir(context);
        final File entry = new File(directory, "main.js");
        if (!entry.isFile()) {
            throw new IOException("缺少 " + entry);
        }

        final String[] arguments = { "node", entry.getAbsolutePath() };
        final String modulesPath = new File(directory, "node_modules").getAbsolutePath();

        progress.onProgress(context.getString(R.string.starting_local_service));
        Thread thread = new Thread(() -> {
            Log.i(TAG, "[node] starting " + entry);
            final int code = startNode(arguments, modulesPath, false);
            exitCode = code;
            Log.e(TAG, "[node] exited with " + code);
            final String tail = readLogTail(context, 4000);
            if (!tail.isEmpty()) {
                Log.e(TAG, "[node] log tail:\n" + tail);
            }
        }, "folia-node");
        thread.start();
    }

    /** 读 main.js 写的日志末尾，用来在启动失败时把原因带到界面上。 */
    public static String readLogTail(Context context, int maxChars) {
        final File file = new File(projectDir(context), "folia-node.log");
        if (!file.isFile()) return "";
        try (java.io.RandomAccessFile access = new java.io.RandomAccessFile(file, "r")) {
            final long length = access.length();
            final int size = (int) Math.min(length, maxChars);
            final byte[] buffer = new byte[size];
            access.seek(length - size);
            access.readFully(buffer);
            return new String(buffer, "UTF-8");
        } catch (IOException error) {
            return "";
        }
    }

    private static String readText(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 256)];
            int read = input.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8");
        } catch (IOException error) {
            return "";
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes("UTF-8"));
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("无法删除 " + file);
        }
    }
}
