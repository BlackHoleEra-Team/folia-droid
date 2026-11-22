// android/nodejs-project/main.js
// 应用内的本地后端，跑在 nodejs-mobile（Node 24）里。
// 32101 是网易云 API；32100 是对外的一层：/netease/* 反代到 32101，其余路径返回打包好的前端。
// 页面和 API 同源，省掉 CORS 和跨站 Cookie 的麻烦。

const fs = require('fs');
const http = require('http');
const path = require('path');
const util = require('util');
const express = require('express');

const HOST = '127.0.0.1';
const FRONT_PORT = 32100;
const NETEASE_PORT = 32101;
const KUGOU_PORT = 32102;
const QQ_PORT = 32103;

// 应用进程的 stdout 在 Android 上没人接，而且 WebView 也在这个进程里，
// 不能把 fd 1/2 重定向走。所有输出自己写文件，Java 侧失败时读它的末尾。
const LOG_FILE = path.join(__dirname, 'folia-node.log');
const logStream = fs.createWriteStream(LOG_FILE, { flags: 'w' });

function writeLog(level, args) {
    const text = args
        .map((arg) => (typeof arg === 'string' ? arg : util.inspect(arg, { depth: 4, breakLength: 160 })))
        .join(' ');
    logStream.write(`${new Date().toISOString()} ${level} ${text}\n`);
}

for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
    console[level] = (...args) => writeLog(level.toUpperCase(), args);
}

process.on('uncaughtException', (error) => console.error('[backend] uncaught exception', error));
process.on('unhandledRejection', (error) => console.error('[backend] unhandled rejection', error));

// 网易云 API 会在 os.tmpdir() 下写 anonymous_token。Android 上没有可写的 /tmp，
// 必须在 require 之前把 TMPDIR 指到应用自己的目录。
process.env.TMPDIR = process.env.FOLIA_TMPDIR || path.join(__dirname, 'tmp');
fs.mkdirSync(process.env.TMPDIR, { recursive: true });

const log = (...args) => console.log('[backend]', ...args);

function proxyTo(port) {
    return (req, res) => {
        const upstream = http.request({
            host: HOST,
            port,
            path: req.url,
            method: req.method,
            headers: { ...req.headers, host: `${HOST}:${port}` },
        }, (upstreamRes) => {
            res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
            upstreamRes.pipe(res);
        });

        upstream.on('error', (error) => {
            console.error('[backend] proxy failed', error);
            if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ code: 502, msg: 'local backend unavailable' }));
        });

        req.pipe(upstream);
    };
}

async function startExtraSources() {
    // QQ 音乐：包在 require 时就 listen，端口从 PORT 读，所以先摆好环境再加载。
    process.env.PORT = String(QQ_PORT);
    process.env.HOST = HOST;
    try {
        require('@yakult-green-tea/qq-music-api');
        log('qq api starting on', QQ_PORT);
    } catch (error) {
        console.error('[backend] qq api failed to start', error);
    }

    // 酷狗：桌面端加载前会设 platform=lite，这里保持一致；startService 会等到端口真的绑上。
    process.env.PORT = String(KUGOU_PORT);
    process.env.platform = 'lite';
    try {
        const { startService } = require('kugoumusicapi/server');
        await startService();
        log('kugou api listening on', KUGOU_PORT);
    } catch (error) {
        console.error('[backend] kugou api failed to start', error);
    }
}

async function main() {
    // 桌面版启动网易云 API 前会做三件事，缺了 xeapi 公钥 /song/url/v1 会直接抛
    // "xeapi public key is missing"：随机国内 IP、设备 ID、拉一次 xeapi 公钥写进 TMPDIR。
    const ncmUtil = require('@neteasecloudmusicapienhanced/api/util');
    global.cnIp = ncmUtil.generateRandomChineseIP();
    global.deviceId = ncmUtil.generateDeviceId();

    const keyPath = path.join(process.env.TMPDIR, 'xeapi_public_key');
    let cachedKey = {};
    try {
        cachedKey = JSON.parse(fs.readFileSync(keyPath, 'utf-8'));
    } catch (error) {
        cachedKey = {};
    }
    try {
        const { getXeapiPublicKey } = require('@neteasecloudmusicapienhanced/api/util/xeapiKey');
        const publicKey = await getXeapiPublicKey(cachedKey, global.deviceId);
        fs.writeFileSync(keyPath, JSON.stringify(publicKey), 'utf-8');
        log('xeapi public key ready, version', publicKey.version ?? 'unknown');
    } catch (error) {
        console.error('[backend] xeapi public key refresh failed', error);
        if (!cachedKey.sk) log('warning: no xeapi public key, /song/url/v1 will fail');
    }

    await startExtraSources();

    const { serveNcmApi } = require('@neteasecloudmusicapienhanced/api').server;
    await serveNcmApi({ port: NETEASE_PORT, host: HOST, checkVersion: false });
    log('netease api listening on', NETEASE_PORT);

    const app = express();
    app.get('/health', (_req, res) => res.json({ ok: true }));
    // 壳里不需要 Service Worker：资源就在本地，SW 只会让 APK 更新后的第一次启动读到旧缓存。
    app.get('/sw.js', (_req, res) => res.status(404).type('text/plain').end('service worker disabled'));
    app.use('/netease', proxyTo(NETEASE_PORT));
    app.use('/kugou', proxyTo(KUGOU_PORT));
    app.use('/qq', proxyTo(QQ_PORT));

    const www = path.join(__dirname, 'www');
    app.use(express.static(www, { index: 'index.html' }));
    app.use((req, res, next) => {
        if (req.method !== 'GET' && req.method !== 'HEAD') return next();
        res.sendFile(path.join(www, 'index.html'));
    });

    app.listen(FRONT_PORT, HOST, () => log('front listening on', FRONT_PORT));
}

main().catch((error) => {
    console.error('[backend] failed to start', error);
    process.exit(1);
});
