package top.izuna.foliamajor.shell;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

// src/main/java/.../LocalAudioServer.java
// 一个只听 127.0.0.1 的小音频流服务器：
//   GET /local/<MediaStore _id>  把 MediaStore 里的音频按 Range 吐出去（支持拖进度条）
//   GET /art/<album_id>          出专辑封面
// 页面（WebView 的 http 源）拿不到 content:// URI，所以由这层转成普通 http；声音走页面自己的播放器，
// 这个服务器只在本地，不出设备。

public final class LocalAudioServer {
    public static final int PORT = 32104;
    // 页面用 Web Audio 分析频谱，音频请求带 CORS 语义；没有这几个头，Chrome 会直接拦掉整段音频。
    private static final String CORS = "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: Range\r\n"
            + "Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n";

    private static volatile boolean started = false;
    private static String baseUrl = "";

    private LocalAudioServer() {
    }

    public static synchronized String ensureStarted(Context context) {
        if (!started) {
            started = true;
            Thread thread = new Thread(() -> serve(context.getApplicationContext()), "folia-local-audio");
            thread.setDaemon(true);
            thread.start();
        }
        baseUrl = "http://127.0.0.1:" + PORT;
        return baseUrl;
    }

    /** MediaStore 里的音频清单：id、标题、歌手、专辑、时长（秒）、专辑 id。 */
    public static String scanJson(ContentResolver resolver) {
        JSONArray list = new JSONArray();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.MIME_TYPE,
        };
        try (Cursor cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject row = new JSONObject();
                    row.put("id", cursor.getLong(0));
                    row.put("title", cursor.getString(1));
                    row.put("artist", cursor.getString(2));
                    row.put("album", cursor.getString(3));
                    row.put("durationSec", Math.round(cursor.getLong(4) / 1000.0));
                    row.put("albumId", cursor.getLong(5));
                    row.put("mime", cursor.getString(6));
                    list.put(row);
                }
            }
        } catch (Exception ignored) {
            // 权限被拒或库为空时返回空清单，页面自己提示。
        }
        return list.toString();
    }

    private static void serve(Context context) {
        try (ServerSocket server = new ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(0);
            while (started) {
                final Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException error) {
                    continue;
                }
                // 一条长音频流会占住连接几十秒；同一个线程里处理会把封面和拖动进度条的请求堵在后面。
                Thread worker = new Thread(() -> {
                    try (Socket client = socket) {
                        client.setSoTimeout(30000);
                        handle(context, client);
                    } catch (SocketTimeoutException ignored) {
                        // 读超时：这一条连接断了。
                    } catch (IOException ignored) {
                        // 单条连接失败不影响服务。
                    }
                }, "folia-local-audio-conn");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (IOException ignored) {
            started = false;
        }
    }

    private static void handle(Context context, Socket socket) throws IOException {
        byte[] buffer = new byte[8192];
        int first = socket.getInputStream().read(buffer);
        if (first <= 0) return;
        String head = new String(buffer, 0, first, java.nio.charset.StandardCharsets.ISO_8859_1);
        // 请求行一行就够；多余的头部字节直接丢弃。
        String[] lines = head.split("\r\n");
        String requestLine = lines.length > 0 ? lines[0] : "";
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            respond(socket.getOutputStream(), "400 Bad Request", "text/plain", new byte[0], null);
            return;
        }
        String path = parts[1];
        String range = header(lines, "Range:");

        if ("OPTIONS".equalsIgnoreCase(parts[0])) {
            respond(socket.getOutputStream(), "204 No Content", "text/plain", new byte[0], null);
            return;
        }
        if (path.startsWith("/local/")) {
            long id = parseId(path.substring("/local/".length()));
            streamMediaStoreAudio(context, socket.getOutputStream(), id, range);
            return;
        }
        if (path.startsWith("/art/")) {
            long albumId = parseId(path.substring("/art/".length()));
            streamAlbumArt(context, socket.getOutputStream(), albumId);
            return;
        }
        respond(socket.getOutputStream(), "404 Not Found", "text/plain", new byte[0], null);
    }

    private static String header(String[] lines, String prefix) {
        for (String line : lines) {
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static long parseId(String raw) {
        int end = raw.indexOf('?');
        String value = end >= 0 ? raw.substring(0, end) : raw;
        value = value.replace("/", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static void streamMediaStoreAudio(Context context, OutputStream out, long id, String range) throws IOException {
        if (id < 0) {
            respond(out, "404 Not Found", "text/plain", new byte[0], null);
            return;
        }
        Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
        try {
            android.content.res.AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) {
                respond(out, "404 Not Found", "text/plain", new byte[0], null);
                return;
            }
            long total = afd.getLength();
            long start = 0;
            long end = total - 1;
            boolean isRange = range != null && range.startsWith("bytes=");
            if (isRange) {
                String spec = range.substring("bytes=".length());
                int dash = spec.indexOf('-');
                start = dash == 0 ? 0 : Long.parseLong(spec.substring(0, dash));
                end = dash == spec.length() - 1 ? total - 1 : Long.parseLong(spec.substring(dash + 1));
                if (dash == 0) {
                    // bytes=-N 表示末尾 N 字节。
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                }
            }
            if (start > end || start >= total) {
                String head = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                out.flush();
                afd.close();
                return;
            }

            String contentType = context.getContentResolver().getType(uri);
            if (contentType == null) contentType = "audio/mpeg";
            String status = isRange ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK";
            StringBuilder head = new StringBuilder()
                    .append(status).append("\r\n")
                    .append("Content-Type: ").append(contentType).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n")
                    .append("Content-Length: ").append(end - start + 1).append("\r\n")
                    .append(CORS)
                    .append("Connection: close\r\n");
            if (isRange) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

            try (InputStream in = new FileInputStream(afd.getFileDescriptor())) {
                long skipped = in.skip(start);
                while (skipped < start) {
                    long more = in.skip(start - skipped);
                    if (more <= 0) break;
                    skipped += more;
                }
                byte[] chunk = new byte[16384];
                long remaining = end - start + 1;
                while (remaining > 0) {
                    int want = (int) Math.min(chunk.length, remaining);
                    int got = in.read(chunk, 0, want);
                    if (got <= 0) break;
                    out.write(chunk, 0, got);
                    remaining -= got;
                }
            }
            out.flush();
            afd.close();
        } catch (SecurityException error) {
            respond(out, "403 Forbidden", "text/plain", new byte[0], null);
        } catch (Exception error) {
            // 客户端断开（拖进度条时常见）：连接作废即可。
        }
    }

    private static void streamAlbumArt(Context context, OutputStream out, long albumId) throws IOException {
        if (albumId < 0) {
            respond(out, "404 Not Found", "text/plain", new byte[0], null);
            return;
        }
        Uri artUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
        try (InputStream in = context.getContentResolver().openInputStream(artUri)) {
            if (in == null) {
                respond(out, "404 Not Found", "text/plain", new byte[0], null);
                return;
            }
            byte[] bytes = readAll(in);
            String type = context.getContentResolver().getType(artUri);
            respond(out, "200 OK", type == null ? "image/jpeg" : type, bytes, null);
        } catch (Exception error) {
            respond(out, "404 Not Found", "text/plain", new byte[0], null);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int got;
        while ((got = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, got);
        }
        return buffer.toByteArray();
    }

    private static void respond(OutputStream out, String status, String contentType, byte[] body, String extra) throws IOException {
        StringBuilder head = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append(CORS)
                .append("Connection: close\r\n");
        if (extra != null) head.append(extra);
        head.append("\r\n");
        out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        if (body.length > 0) out.write(body);
        out.flush();
    }
}
