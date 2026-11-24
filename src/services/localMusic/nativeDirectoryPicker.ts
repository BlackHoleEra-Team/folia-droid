// src/services/localMusic/nativeDirectoryPicker.ts
// Android 壳里的「导入文件夹」兜底：WebView 没有 File System Access API，
// showDirectoryPicker 不存在，本地库的两个导入按钮点了没反应。
//
// 这里把一个虚拟目录接到 MediaStore 上：目录叫「本机音乐」，里面一首歌一个文件，
// getFile() 时从壳里的本地流（127.0.0.1:32104）取字节，包成真正的 File。
// 本地库的导入、元数据读取、封面提取因此不用改一行——它看到的还是标准句柄。

type NativeTrack = {
    id: number;
    title: string;
    artist: string;
    album: string;
    durationSec: number;
    albumId: number;
    mime?: string;
};

type NativeAudioApi = {
    scanLocalAudio: () => string;
    localAudioBaseUrl: () => string;
};

const FOLDER_NAME = '本机音乐';

const extensionFor = (mime: string | undefined, name: string): string => {
    if (name.includes('.')) return name.slice(name.lastIndexOf('.') + 1).toLowerCase();
    if (mime === 'audio/flac') return 'flac';
    if (mime === 'audio/mp4' || mime === 'audio/aac' || mime === 'audio/x-m4a') return 'm4a';
    if (mime === 'audio/ogg') return 'ogg';
    if (mime === 'audio/wav' || mime === 'audio/x-wav') return 'wav';
    return 'mp3';
};

const sanitize = (raw: string): string => raw.replace(/[/\\:*?"<>|]/g, '_').trim() || '未命名';

const buildVirtualRoot = (api: NativeAudioApi): FileSystemDirectoryHandle => {
    const tracks = JSON.parse(api.scanLocalAudio() || '[]') as NativeTrack[];
    const base = api.localAudioBaseUrl();
    const usedNames = new Set<string>();

    const files = tracks.map((track) => {
        const mime = track.mime || 'audio/mpeg';
        const baseName = sanitize(`${track.title} - ${track.artist}`);
        let name = `${baseName}.${extensionFor(mime, track.title)}`;
        let index = 2;
        while (usedNames.has(name)) {
            name = `${baseName} (${index}).${extensionFor(mime, track.title)}`;
            index += 1;
        }
        usedNames.add(name);

        const handle = {
            kind: 'file' as const,
            name,
            async getFile(): Promise<File> {
                const response = await fetch(`${base}/local/${track.id}`);
                if (!response.ok) throw new Error(`本机音乐读取失败：${response.status}`);
                const blob = await response.blob();
                return new File([blob], name, { type: mime, lastModified: Date.now() });
            },
            async isSameEntry(other: unknown) {
                return other === handle;
            },
            async queryPermission() {
                return 'granted' as PermissionState;
            },
            async requestPermission() {
                return 'granted' as PermissionState;
            },
        };
        return handle;
    });

    const root = {
        kind: 'directory' as const,
        name: FOLDER_NAME,
        async *values(): AsyncGenerator<(typeof files)[number]> {
            for (const file of files) yield file;
        },
        async *entries(): AsyncGenerator<[string, (typeof files)[number]]> {
            for (const file of files) yield [file.name, file] as [string, (typeof files)[number]];
        },
        async *keys(): AsyncGenerator<string> {
            for (const file of files) yield file.name;
        },
        async getFileHandle(name: string) {
            const found = files.find(file => file.name === name);
            if (!found) throw new DOMException(`没有这首歌：${name}`, 'NotFoundError');
            return found;
        },
        async getDirectoryHandle(name: string) {
            throw new DOMException(`本机音乐不分目录：${name}`, 'NotFoundError');
        },
        async isSameEntry(other: unknown) {
            return other === root;
        },
        async queryPermission() {
            return 'granted' as PermissionState;
        },
        async requestPermission() {
            return 'granted' as PermissionState;
        },
    };
    return root as unknown as FileSystemDirectoryHandle;
};

/** 装一次；没有壳（桌面/浏览器）或已有原生选择器时什么都不做。 */
export const installNativeDirectoryPicker = (): void => {
    if (typeof window === 'undefined') return;
    const api = (window as Window & { foliaNative?: Partial<NativeAudioApi> }).foliaNative;
    if (!api?.scanLocalAudio || !api.localAudioBaseUrl) return;
    if ('showDirectoryPicker' in window) return;

    Object.defineProperty(window, 'showDirectoryPicker', {
        configurable: true,
        writable: true,
        value: async () => buildVirtualRoot(api as NativeAudioApi),
    });
};
