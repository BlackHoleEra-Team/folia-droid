package top.izuna.foliamajor.shell;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

// src/main/java/.../FolderAccess.java
// 真正的系统目录选择器（SAF）。选中的树用 persistable 权限记住，下次直接复用。
// 目录内容按需列成 JSON，音频字节走 LocalAudioServer 的 /doc；内嵌歌词（ID3 USLT）单独读。

public final class FolderAccess {
    static final int REQUEST_TREE = 4101;
    private static final String PREFS = "folia_shell";
    private static final String KEY_TREE = "music_tree";
    private static final int MAX_ENTRIES = 3000;

    interface Callback {
        void onResult(String uri);
    }

    private static Callback pending;

    private FolderAccess() {
    }

    static void pick(Activity activity, Callback callback) {
        if (activity == null) {
            callback.onResult("");
            return;
        }
        pending = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_TREE);
        } catch (Exception error) {
            pending = null;
            callback.onResult("");
        }
    }

    static void handleResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_TREE) return;
        Callback callback = pending;
        pending = null;
        if (activity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (callback != null) callback.onResult("");
            return;
        }
        Uri tree = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // 有的文档提供方不给持久权限：本次仍可用，下次要重选。
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TREE, tree.toString()).apply();
        if (callback != null) callback.onResult(tree.toString());
    }

    static String savedTree(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, "");
    }

    /** 递归列出音频与歌词：{path, name, dir, uri, mime, size}。 */
    static String listTree(ContentResolver resolver, Uri treeUri) {
        JSONArray list = new JSONArray();
        if (treeUri == null) return list.toString();
        Deque<String> pendingDirs = new ArrayDeque<>();
        Deque<String> pendingPaths = new ArrayDeque<>();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            pendingDirs.push(rootId);
            pendingPaths.push("");
            int guard = 0;
            while (!pendingDirs.isEmpty() && list.length() < MAX_ENTRIES && guard < 200) {
                guard += 1;
                String dirId = pendingDirs.pop();
                String dirPath = pendingPaths.pop();
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId);
                String[] projection = {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                };
                try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
                    if (cursor == null) continue;
                    while (cursor.moveToNext()) {
                        if (list.length() >= MAX_ENTRIES) break;
                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                        String path = dirPath.isEmpty() ? name : dirPath + "/" + name;
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            pendingDirs.push(docId);
                            pendingPaths.push(path);
                            continue;
                        }
                        if (mime == null) continue;
                        boolean audio = mime.startsWith("audio/");
                        boolean lyric = name != null && name.toLowerCase().endsWith(".lrc");
                        if (!audio && !lyric) continue;
                        JSONObject row = new JSONObject();
                        row.put("path", path);
                        row.put("name", name);
                        row.put("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString());
                        row.put("mime", mime);
                        row.put("size", size);
                        row.put("lyric", lyric);
                        list.put(row);
                    }
                }
            }
        } catch (Exception ignored) {
            // 权限丢了或提供方出错：返回已列到的部分。
        }
        return list.toString();
    }

    /**
     * 文件里内嵌的歌词（ID3v2 的 USLT）。没有就返回空串。
     * 只读标签区，不碰音频数据。
     */
    static String readLyrics(ContentResolver resolver, Uri documentUri) {
        if (documentUri == null) return "";
        try (InputStream in = resolver.openInputStream(documentUri)) {
            if (in == null) return "";
            byte[] head = new byte[10];
            if (readFully(in, head) < 10) return "";
            if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') return "";
            int version = head[3] & 0xff;
            if (version < 3 || version > 4) return "";
            int tagSize = ((head[6] & 0x7f) << 21) | ((head[7] & 0x7f) << 14)
                    | ((head[8] & 0x7f) << 7) | (head[9] & 0x7f);
            if (tagSize <= 0 || tagSize > 4 * 1024 * 1024) return "";
            byte[] tag = new byte[tagSize];
            int read = readFully(in, tag);
            int offset = 0;
            while (offset + 10 <= read) {
                String frameId = new String(tag, offset, 4, StandardCharsets.ISO_8859_1);
                int frameSize = version == 4
                        ? (((tag[offset + 4] & 0x7f) << 21) | ((tag[offset + 5] & 0x7f) << 14)
                            | ((tag[offset + 6] & 0x7f) << 7) | (tag[offset + 7] & 0x7f))
                        : (((tag[offset + 4] & 0xff) << 24) | ((tag[offset + 5] & 0xff) << 16)
                            | ((tag[offset + 6] & 0xff) << 8) | (tag[offset + 7] & 0xff));
                if (frameSize <= 0 || offset + 10 + frameSize > read) break;
                if ("USLT".equals(frameId)) {
                    return decodeUslt(tag, offset + 10, frameSize);
                }
                offset += 10 + frameSize;
            }
        } catch (Exception ignored) {
            // 读不出来就当没有。
        }
        return "";
    }

    private static String decodeUslt(byte[] data, int start, int length) {
        if (length < 5) return "";
        int encoding = data[start] & 0xff;
        int cursor = start + 4; // 跳过 encoding + 3 字节语言
        int end = start + length;
        // 内容描述符：按编码读到 0 终止符
        if (encoding == 1 || encoding == 2) {
            while (cursor + 1 < end && !(data[cursor] == 0 && data[cursor + 1] == 0)) cursor += 2;
            cursor += 2;
        } else {
            while (cursor < end && data[cursor] != 0) cursor += 1;
            cursor += 1;
        }
        if (cursor >= end) return "";
        int size = end - cursor;
        try {
            switch (encoding) {
                case 1:
                    return new String(data, cursor, size, StandardCharsets.UTF_16);
                case 2:
                    return new String(data, cursor, size, StandardCharsets.UTF_16BE);
                case 3:
                    return new String(data, cursor, size, StandardCharsets.UTF_8);
                default:
                    return new String(data, cursor, size, StandardCharsets.ISO_8859_1);
            }
        } catch (Exception error) {
            return "";
        }
    }

    private static int readFully(InputStream in, byte[] target) throws Exception {
        int total = 0;
        while (total < target.length) {
            int got = in.read(target, total, target.length - total);
            if (got <= 0) break;
            total += got;
        }
        return total;
    }
}
