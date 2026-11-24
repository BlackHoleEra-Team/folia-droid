// src/services/localMusic/nativeOpfsShim.ts
// Android 壳里的 OPFS 兜底。本地库把封面、歌词这类二进制资源存在 navigator.storage.getDirectory()
// 下面；WebView 若不提供 OPFS，可用性判定会直接判本地库不可用，导入按钮点了没反应。
//
// 这里用 IndexedDB 实现一个够用的子集：目录句柄、文件句柄、createWritable、removeEntry。
// 桌面与支持 OPFS 的环境不会走到这里。

type StoredFile = { blob: Blob; at: number };

const DB_NAME = 'folia-native-opfs';
const STORE = 'files';

let dbPromise: Promise<IDBDatabase> | null = null;

const openDb = (): Promise<IDBDatabase> => {
    if (!dbPromise) {
        dbPromise = new Promise((resolve, reject) => {
            const request = indexedDB.open(DB_NAME, 1);
            request.onupgradeneeded = () => {
                const db = request.result;
                if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
    }
    return dbPromise;
};

const joinPath = (parent: string, name: string): string => (parent.endsWith('/') ? `${parent}${name}` : `${parent}/${name}`);

const readFile = async (path: string): Promise<StoredFile | undefined> => {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(path);
        request.onsuccess = () => resolve(request.result as StoredFile | undefined);
        request.onerror = () => reject(request.error);
    });
};

const writeFile = async (path: string, blob: Blob): Promise<void> => {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite');
        tx.objectStore(STORE).put({ blob, at: Date.now() } satisfies StoredFile, path);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
};

const deletePath = async (path: string): Promise<void> => {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite');
        tx.objectStore(STORE).delete(path);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
};

const deleteTree = async (prefix: string): Promise<void> => {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, 'readwrite');
        const store = tx.objectStore(STORE);
        const request = store.getAllKeys();
        request.onsuccess = () => {
            for (const key of request.result as string[]) {
                if (key === prefix || key.startsWith(`${prefix}/`)) store.delete(key);
            }
        };
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
    });
};

const makeWritable = (path: string) => {
    let chunks: BlobPart[] = [];
    let type = '';
    return {
        async write(data: BlobPart | { blob?: Blob }) {
            if (data instanceof Blob) {
                if (!type && data.type) type = data.type;
                chunks.push(data);
                return;
            }
            if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
                chunks.push(data as ArrayBuffer);
                return;
            }
            const maybe = data as { blob?: Blob };
            if (maybe?.blob instanceof Blob) {
                if (!type && maybe.blob.type) type = maybe.blob.type;
                chunks.push(maybe.blob);
                return;
            }
            chunks.push(String(data));
        },
        async close() {
            await writeFile(path, new Blob(chunks, type ? { type } : undefined));
            chunks = [];
        },
        async abort() {
            chunks = [];
        },
    };
};

const fileHandle = (path: string, name: string) => ({
    kind: 'file' as const,
    name,
    async getFile(): Promise<File> {
        const stored = await readFile(path);
        if (!stored) throw new DOMException(`没有这个文件：${path}`, 'NotFoundError');
        return new File([stored.blob], name, { type: stored.blob.type, lastModified: stored.at });
    },
    async createWritable() {
        return makeWritable(path);
    },
    async isSameEntry(other: unknown) {
        return other === this;
    },
    async queryPermission() {
        return 'granted' as PermissionState;
    },
    async requestPermission() {
        return 'granted' as PermissionState;
    },
});

const directoryHandle = (path: string, name: string): FileSystemDirectoryHandle => {
    const handle = {
        kind: 'directory' as const,
        name,
        async getDirectoryHandle(child: string) {
            return directoryHandle(joinPath(path, child), child);
        },
        async getFileHandle(child: string) {
            return fileHandle(joinPath(path, child), child);
        },
        async removeEntry(child: string, options?: { recursive?: boolean }) {
            const target = joinPath(path, child);
            if (options?.recursive) await deleteTree(target);
            else await deletePath(target);
        },
        async *values(): AsyncGenerator<never> {
            // 这几处存储只按名字取文件，从不遍历；空迭代器足够。
        },
        async *entries(): AsyncGenerator<never> {
            // 同上。
        },
        async *keys(): AsyncGenerator<never> {
            // 同上。
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

/** 只在没有 OPFS 的壳里装一次。 */
export const installNativeOpfsShim = (): void => {
    if (typeof navigator === 'undefined' || !navigator.storage) return;
    if (typeof navigator.storage.getDirectory === 'function') return;
    try {
        Object.defineProperty(navigator.storage, 'getDirectory', {
            configurable: true,
            writable: true,
            value: async () => directoryHandle('/', ''),
        });
    } catch {
        // 定义不上就算了：可用性判定会照常判本地库不可用。
    }
};
