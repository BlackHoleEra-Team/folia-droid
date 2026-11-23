import type {
    AudioQualityPreference,
    MediaId,
    OnlineMusicProvider,
    OnlineProviderId,
    ProviderAudioSource,
} from '../../../types/onlineMusic';
import type { SongResult, UnifiedSong } from '../../../types';

// src/services/onlineMusic/nativeLocal/nativeLocalProvider.ts
// Android 壳的本机音乐（MediaStore）。注册在 providerRegistry 里，条件是 window.foliaNative 存在
// ——也就是说，只在 Android 壳里出现。歌曲的音频由壳里的 LocalAudioServer 在 127.0.0.1 出流，
// 拖进度条靠它的 Range 支持；搜索是清单上的标题/歌手/专辑过滤。

const PROVIDER_ID: OnlineProviderId = 'localNative';

type NativeTrack = {
    id: number;
    title: string;
    artist: string;
    album: string;
    durationSec: number;
    albumId: number;
};

type NativeApi = {
    platform: () => string;
    scanLocalAudio: () => string;
    localAudioBaseUrl: () => string;
};

const nativeApi = (): NativeApi | null => (
    typeof window === 'undefined' ? null : (window as Window & { foliaNative?: NativeApi }).foliaNative ?? null
);

let scanCache: NativeTrack[] | null = null;

const scan = async (): Promise<NativeTrack[]> => {
    const api = nativeApi();
    if (!api) return [];
    if (scanCache !== null) return scanCache;
    try {
        scanCache = JSON.parse(api.scanLocalAudio()) as NativeTrack[];
    } catch {
        scanCache = [];
    }
    return scanCache;
};

/** 本机歌单变了（删歌、加歌）时由设置或扫描入口调用；现在先手动。 */
export const invalidateNativeLocalScan = (): void => {
    scanCache = null;
};

const toUnifiedSong = (track: NativeTrack): UnifiedSong => ({
    id: `local-native-${track.id}`,
    name: track.title,
    artists: [{ id: `local-native-artist-${track.artist}`, name: track.artist || 'Unknown' }],
    album: {
        id: `local-native-album-${track.albumId}`,
        name: track.album || '',
        coverUrl: `${(window as Window & { foliaNative?: NativeApi }).foliaNative!.localAudioBaseUrl()}/art/${track.albumId}`,
    },
    durationMs: Math.max(0, Math.round(track.durationSec * 1000)),
    // 不能标 isLocal：本地库的播放分支会去读 localRef.songId 拿文件句柄，
    // 而我们的音频是壳里流服务器出的 URL，走在线平台的取声路径。
    sourceRef: { kind: 'online', providerId: PROVIDER_ID, mediaId: String(track.id) },
});

const matchesQuery = (song: UnifiedSong, query: string): boolean => {
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return song.name.toLowerCase().includes(q)
        || song.artists.some(artist => artist.name.toLowerCase().includes(q))
        || (song.album?.name ?? '').toLowerCase().includes(q);
};

const paginate = <T,>(rows: T[], limit: number, offset: number) => ({
    items: rows.slice(offset, offset + limit),
    total: rows.length,
    hasMore: offset + limit < rows.length,
    nextOffset: Math.min(offset + limit, rows.length),
});

export const nativeLocalProvider: OnlineMusicProvider = {
    id: PROVIDER_ID,
    displayName: '本机音乐',
    shortName: '本机',
    mark: { letter: '本', color: '#4f8cff' },
    getAvailability: () => ({ configured: true }),
    capabilities: {
        search: true,
        playback: true,
        lyrics: false,
        auth: false,
        userLibrary: false,
        playlists: false,
        albums: false,
        artists: false,
        recommendations: false,
        mutations: false,
        wordByWordLyrics: false,
    },
    normalizeSong: (raw: unknown) => toUnifiedSong(raw as NativeTrack),
    search: {
        searchSongs: async (query: string, limit: number, offset: number) => {
            const tracks = (await scan()).map(toUnifiedSong);
            const rows = tracks.filter(song => matchesQuery(song, query));
            return paginate(rows, limit, offset);
        },
    },
    playback: {
        getSongDetail: async (id: MediaId) => {
            const tracks = await scan();
            const track = tracks.find(row => String(row.id) === String(id));
            return track ? toUnifiedSong(track) : null;
        },
        getAudioSource: async (song: SongResult, _quality: AudioQualityPreference): Promise<ProviderAudioSource | null> => {
            const api = nativeApi();
            if (!api) return null;
            const mediaId = song.sourceRef?.kind === 'online' ? song.sourceRef.mediaId : String(song.id);
            return {
                url: `${api.localAudioBaseUrl()}/local/${mediaId}`,
                fetchedAt: Date.now(),
                quality: 'lossless',
            };
        },
    },
};
