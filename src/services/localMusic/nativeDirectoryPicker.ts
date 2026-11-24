// src/services/localMusic/nativeDirectoryPicker.ts
// Android 壳里的目录接入。
//
// 1) 「导入文件夹」走真正的系统目录选择器（SAF）：壳弹 ACTION_OPEN_DOCUMENT_TREE，
//    选完把树 URI 记住，下次能直接复用。
// 2) 打开应用后若本地库还是空的，自动导入一次 Music 文件夹（有选过的目录就导那个）。
// 3) 每个音频旁边挂一个虚拟 .lrc，内容取自文件内嵌的 ID3 USLT——本地库的歌词走旁挂文件。
// 4) 没有 SAF 能力时退回 MediaStore（老版本壳）。
//
// 对本地库来说，这里给出的始终是标准 FileSystemDirectoryHandle / File。

import { getLocalSongs } from '../db';
import { importDirectoryHandle } from '../localMusicService';
import { installNativeOpfsShim } from './nativeOpfsShim';

type MediaTrack = {
    id: number;
    title: string;
    artist: string;
    album: string;
    durationSec: number;
    albumId: number;
    mime?: string;
    relPath?: string;
};

type SafeEntry = {
    path: string;
    name: string;
    uri: string;
    mime: string;
    size: number;
    lyric: boolean;
};

type NativeAudioApi = {
    scanLocalAudio: () => string;
    localAudioBaseUrl: () => string;
    pickMusicFolder?: () => void;
    savedMusicFolder?: () => string;
    listMusicFolder?: (treeUri: string) => string;
    documentAudioUrl?: (documentUri: string) => string;
    embeddedLyrics?: (documentUri: string) => string;
};

const FOLDER_NAME = '本机音乐';
const MUSIC_FOLDER_NAME = 'Music';
const AUTO_IMPORT_KEY = 'folia_native_auto_import';

const nativeApi = (): NativeAudioApi | null => {
    if (typeof window === 'undefined') return null;
    const api = (window as Window & { foliaNative?: Partial<NativeAudioApi> }).foliaNative;
    return api?.scanLocalAudio && api.localAudioBaseUrl ? api as NativeAudioApi : null;
};

const sanitize = (raw: string): string => raw.replace(/[/\\:*?"<>|]/g, '_').trim() || '未命名';

const extensionFor = (mime: string | undefined, name: string): string => {
    if (name.includes('.')) return name.slice(name.lastIndexOf('.') + 1).toLowerCase();
    if (mime === 'audio/flac' || mime === 'audio/x-flac') return 'flac';
    if (mime === 'audio/mp4' || mime === 'audio/aac' || mime === 'audio/x-m4a') return 'm4a';
    if (mime === 'audio/ogg') return 'ogg';
    if (mime === 'audio/wav' || mime === 'audio/x-wav') return 'wav';
    return 'mp3';
};

type FileEntry = {
    kind: 'file';
    name: string;
    getFile: () => Promise<File>;
    isSameEntry: (other: unknown) => Promise<boolean>;
    queryPermission: () => Promise<PermissionState>;
    requestPermission: () => Promise<PermissionState>;
};

const createFileEntry = (name: string, loader: () => Promise<Blob>, fallbackType: string): FileEntry => {
    const entry: FileEntry = {
        kind: 'file',
        name,
        async getFile(): Promise<File> {
            const blob = await loader();
            return new File([blob], name, { type: blob.type || fallbackType, lastModified: Date.now() });
        },
        async isSameEntry(other: unknown) {
            return other === entry;
        },
        async queryPermission() {
            return 'granted' as PermissionState;
        },
        async requestPermission() {
            return 'granted' as PermissionState;
        },
    };
    return entry;
};

const textFile = (name: string, loader: () => Promise<string>) => createFileEntry(
    name,
    async () => new Blob([await loader()], { type: 'text/plain' }),
    'text/plain',
);

type DirChild =
    | { kind: 'file'; entry: FileEntry }
    | { kind: 'directory'; handle: FileSystemDirectoryHandle };

const buildDirectory = (name: string, children: DirChild[]): FileSystemDirectoryHandle => {
    const files = children.filter(child => child.kind === 'file').map(child => child.entry as FileEntry);
    const directories = children.filter(child => child.kind === 'directory')
        .map(child => child as { kind: 'directory'; handle: FileSystemDirectoryHandle });

    const handle = {
        kind: 'directory' as const,
        name,
        async *values() {
            for (const child of children) {
                if (child.kind === 'file') yield { kind: 'file', name: child.entry.name };
                else yield { kind: 'directory', name: child.handle.name };
            }
        },
        async *entries() {
            for (const child of children) {
                if (child.kind === 'file') yield [child.entry.name, { kind: 'file', name: child.entry.name }];
                else yield [child.handle.name, { kind: 'directory', name: child.handle.name }];
            }
        },
        async *keys() {
            for (const child of children) {
                yield child.kind === 'file' ? child.entry.name : child.handle.name;
            }
        },
        async getFileHandle(childName: string) {
            const found = files.find(file => file.name === childName);
            if (!found) throw new DOMException(`没有这个文件：${childName}`, 'NotFoundError');
            return found;
        },
        async getDirectoryHandle(childName: string) {
            const found = directories.find(dir => dir.handle.name === childName);
            if (!found) throw new DOMException(`没有这个目录：${childName}`, 'NotFoundError');
            return found.handle;
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
    return handle as unknown as FileSystemDirectoryHandle;
};

/** MediaStore 当成一个平铺目录；musicOnly 时只取 Music 目录下的。 */
const buildMediaStoreRoot = (api: NativeAudioApi, musicOnly: boolean): FileSystemDirectoryHandle => {
    const tracks = (JSON.parse(api.scanLocalAudio() || '[]') as MediaTrack[])
        .filter(track => !musicOnly || (track.relPath || '').startsWith('Music'));
    const base = api.localAudioBaseUrl();
    const usedNames = new Set<string>();
    const children: DirChild[] = [];

    for (const track of tracks) {
        const mime = track.mime || 'audio/mpeg';
        const baseName = sanitize(`${track.title} - ${track.artist}`);
        const ext = extensionFor(mime, track.title);
        let name = `${baseName}.${ext}`;
        let index = 2;
        while (usedNames.has(name)) {
            name = `${baseName} (${index}).${ext}`;
            index += 1;
        }
        usedNames.add(name);
        children.push({
            kind: 'file',
            entry: createFileEntry(name, () => fetch(`${base}/local/${track.id}`).then(response => {
                if (!response.ok) throw new Error(`本机音乐读取失败：${response.status}`);
                return response.blob();
            }), mime),
        });
    }
    return buildDirectory(musicOnly ? MUSIC_FOLDER_NAME : FOLDER_NAME, children);
};

/** SAF 选中的目录：按 path 还原层级，歌词用旁挂的虚拟 .lrc。 */
const buildSafeRoot = (api: NativeAudioApi, treeUri: string): FileSystemDirectoryHandle => {
    const entries = JSON.parse(api.listMusicFolder?.(treeUri) || '[]') as SafeEntry[];
    const rootName = decodeURIComponent(treeUri.split('/').pop() || '音乐库').replace(/^primary:/, '') || '音乐库';

    type DirNode = { dirs: Map<string, DirNode>; files: DirChild[] };
    const root: DirNode = { dirs: new Map(), files: [] };
    const existingLyrics = new Map<string, Set<string>>();

    const dirOf = (path: string): DirNode => {
        const segments = path.split('/').slice(0, -1);
        let node = root;
        for (const segment of segments) {
            if (!node.dirs.has(segment)) node.dirs.set(segment, { dirs: new Map(), files: [] });
            node = node.dirs.get(segment)!;
        }
        return node;
    };

    for (const entry of entries) {
        if (!entry.lyric) continue;
        const dirPath = entry.path.split('/').slice(0, -1).join('/');
        if (!existingLyrics.has(dirPath)) existingLyrics.set(dirPath, new Set());
        existingLyrics.get(dirPath)!.add(entry.name.toLowerCase());
    }

    for (const entry of entries) {
        if (entry.lyric) continue;
        const node = dirOf(entry.path);
        const url = api.documentAudioUrl?.(entry.uri) || '';
        node.files.push({
            kind: 'file',
            entry: createFileEntry(entry.name, () => fetch(url).then(response => {
                if (!response.ok) throw new Error(`读取失败：${response.status}`);
                return response.blob();
            }), entry.mime || 'audio/mpeg'),
        });

        // 内嵌歌词：同目录没有同名 .lrc 时补一个虚拟文件，内容就是 ID3 里的 USLT。
        const baseName = entry.name.replace(/\.[^.]+$/, '');
        const lyricName = `${baseName}.lrc`;
        const dirPath = entry.path.split('/').slice(0, -1).join('/');
        if (!existingLyrics.get(dirPath)?.has(lyricName.toLowerCase())) {
            node.files.push({
                kind: 'file',
                entry: textFile(lyricName, async () => api.embeddedLyrics?.(entry.uri) || ''),
            });
        }
    }

    const materialize = (node: DirNode, name: string): FileSystemDirectoryHandle => {
        const children: DirChild[] = [...node.files];
        for (const [childName, child] of node.dirs) {
            children.push({ kind: 'directory', handle: materialize(child, childName) });
        }
        return buildDirectory(name, children);
    };

    return materialize(root, rootName);
};

const waitForFolderPick = (): Promise<string> => new Promise((resolve) => {
    let settled = false;
    const finish = (value: string) => {
        if (settled) return;
        settled = true;
        window.removeEventListener('folia-folder-picked', onPicked);
        resolve(value);
    };
    const onPicked = (event: Event) => finish((event as CustomEvent<string>).detail || '');
    window.addEventListener('folia-folder-picked', onPicked);
    // 系统选择器一直不回来时别永远挂着。
    setTimeout(() => finish(''), 120000);
});

const pickWithSystemUi = async (api: NativeAudioApi): Promise<FileSystemDirectoryHandle> => {
    const pending = waitForFolderPick();
    api.pickMusicFolder?.();
    const treeUri = await pending;
    if (!treeUri) throw new DOMException('用户取消了选择', 'AbortError');
    return buildSafeRoot(api, treeUri);
};

/** 打开应用后自动导入一次：选过的目录优先，否则 Music 文件夹。 */
const autoImportMusic = async (api: NativeAudioApi): Promise<void> => {
    try {
        if (window.localStorage.getItem(AUTO_IMPORT_KEY)) return;
        const existing = await getLocalSongs();
        if (existing.length > 0) {
            window.localStorage.setItem(AUTO_IMPORT_KEY, 'done');
            return;
        }
        const saved = api.savedMusicFolder?.() || '';
        const root = saved ? buildSafeRoot(api, saved) : buildMediaStoreRoot(api, true);
        await importDirectoryHandle(root);
        window.localStorage.setItem(AUTO_IMPORT_KEY, 'done');
        console.log('[NativeLocal] auto import finished from', saved ? 'saved folder' : 'Music');
    } catch (error) {
        window.localStorage.setItem(AUTO_IMPORT_KEY, 'failed');
        console.warn('[NativeLocal] auto import failed', error);
    }
};

/** 装一次；没有壳（桌面/浏览器）时什么都不做。 */
export const installNativeDirectoryPicker = (): void => {
    const api = nativeApi();
    if (!api) return;
    // 本地库的封面/歌词存储要 OPFS；WebView 没有的话先用 IndexedDB 顶上，
    // 否则可用性判定会判本地库不可用，导入按钮点了没反应。
    installNativeOpfsShim();
    // 不能因为 WebView 里已经有 showDirectoryPicker 就跳过：那是个空壳，
    // 调用只会抛错，按钮于是"点了没反应"。壳里一律换成我们的实现。

    Object.defineProperty(window, 'showDirectoryPicker', {
        configurable: true,
        writable: true,
        value: async () => (api.pickMusicFolder ? pickWithSystemUi(api) : buildMediaStoreRoot(api, false)),
    });

    // 启动后自动把 Music 铺进本地库；延迟一下，别和首屏抢时间。
    window.setTimeout(() => { void autoImportMusic(api); }, 5000);
};
