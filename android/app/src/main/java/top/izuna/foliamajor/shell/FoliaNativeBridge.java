package top.izuna.foliamajor.shell;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

// src/main/java/.../FoliaNativeBridge.java
// 注入页面的原生接口（window.foliaNative）。页面只在 Android 上碰到它。
// 所有方法都跑在 WebView 的桥线程上，慢活（扫描 MediaStore）直接同步返回——
// 一次扫描百来毫秒，比异步回调加胶水简单。

public class FoliaNativeBridge {
    private final Context context;

    FoliaNativeBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    /** 页面把播放状态与歌曲信息推过来；由 MediaControlService 画通知与锁屏卡。 */
    @JavascriptInterface
    public String mediaUpdate(String json) {
        try {
            MediaControlService.updateState(context, new JSONObject(json));
            return "ok";
        } catch (Exception error) {
            return "error: " + error.getMessage();
        }
    }

    /** 页面通知播放停了：撤通知、让出焦点。 */
    @JavascriptInterface
    public void mediaStop() {
        MediaControlService.stopPresenting(context);
    }

    /** MediaStore 里的音频清单，JSON 数组。 */
    @JavascriptInterface
    public String scanLocalAudio() {
        return LocalAudioServer.scanJson(context.getContentResolver());
    }

    /** 本地音频流服务器的基础地址（保证已启动）。 */
    @JavascriptInterface
    public String localAudioBaseUrl() {
        return LocalAudioServer.ensureStarted(context);
    }

    /** 打开系统目录选择器；结果以 folia-folder-picked 事件回到页面（空串表示取消）。 */
    @JavascriptInterface
    public void pickMusicFolder() {
        MainActivity.pickMusicFolder();
    }

    /** 上次选中的目录（SAF 树 URI），没有就是空串。 */
    @JavascriptInterface
    public String savedMusicFolder() {
        return FolderAccess.savedTree(context);
    }

    /** 目录内容：[{path,name,uri,mime,size,lyric}]。 */
    @JavascriptInterface
    public String listMusicFolder(String treeUri) {
        return FolderAccess.listTree(context.getContentResolver(), android.net.Uri.parse(treeUri));
    }

    /** 某个文档的音频流地址（本地 127.0.0.1）。 */
    @JavascriptInterface
    public String documentAudioUrl(String documentUri) {
        return LocalAudioServer.ensureStarted(context) + "/doc?uri=" + android.net.Uri.encode(documentUri);
    }

    /** 内嵌歌词（ID3 USLT），没有就是空串。 */
    @JavascriptInterface
    public String embeddedLyrics(String documentUri) {
        return FolderAccess.readLyrics(context.getContentResolver(), android.net.Uri.parse(documentUri));
    }

    /** 应用显示模式：landscape / portrait / system；壳存下来，下次启动也按它来。 */
    @JavascriptInterface
    public void setOrientation(String mode) {
        MainActivity.applyDisplayMode(mode);
    }
}
