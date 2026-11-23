import { useEffect } from 'react';
import type { RefObject } from 'react';
import { PlayerState } from '../types';
import type { SongResult } from '../types';
import { getSongAlbumLabel, getSongArtistLabel, getSongCoverUrl } from '../services/onlineMusic/songMetadata';
import { getObservedMedia } from '../services/hifi/hifiMediaShim';

// src/hooks/useNativeMediaBridge.ts
// Android 壳的媒体卡数据面：页面把「现在放什么、放到哪」推给原生（window.foliaNative），
// 原生把它画进通知栏与锁屏（MediaControlService）。命令（播放/暂停/上下首）由原生以
// `folia-native-media` 事件发回来，这里路由到与 MediaSession 同一套处理器。
// 桌面与浏览器不装这个桥：window.foliaNative 不存在时整个钩子不动。

type NativeMediaApi = {
    mediaUpdate: (json: string) => string;
    mediaStop: () => void;
};

type FoliaWindow = Window & { foliaNative?: NativeMediaApi };

const nativeApi = (): NativeMediaApi | null => (
    typeof window === 'undefined' ? null : (window as FoliaWindow).foliaNative ?? null
);

export type UseNativeMediaBridgeOptions = {
    audioRef: RefObject<HTMLAudioElement | null>;
    getDisplayAudioElement?: () => HTMLAudioElement | null;
    currentSong: SongResult | null;
    cachedCoverUrl: string | null;
    playerState: PlayerState;
    isNowPlayingStageActive: boolean;
    unknownArtistLabel: string;
    mediaSessionPlayRef: RefObject<() => Promise<void>>;
    mediaSessionPauseRef: RefObject<() => void>;
    mediaSessionPrevRef: RefObject<() => void>;
    mediaSessionNextRef: RefObject<() => Promise<void> | void>;
    isNowPlayingControlDisabledRef: RefObject<boolean>;
};

type NativeMediaState = {
    playing: boolean;
    title?: string;
    artist?: string;
    album?: string;
    coverUrl?: string;
    durationSec?: number;
    positionSec?: number;
};

export const useNativeMediaBridge = ({
    audioRef,
    getDisplayAudioElement,
    currentSong,
    cachedCoverUrl,
    playerState,
    isNowPlayingStageActive,
    unknownArtistLabel,
    mediaSessionPlayRef,
    mediaSessionPauseRef,
    mediaSessionPrevRef,
    mediaSessionNextRef,
    isNowPlayingControlDisabledRef,
}: UseNativeMediaBridgeOptions) => {
    const playing = playerState === PlayerState.PLAYING;

    // 元数据与播放状态：歌一换、播放一停就走一次。位置由 timeupdate 补。
    useEffect(() => {
        const api = nativeApi();
        if (!api) return undefined;

        if (!currentSong || isNowPlayingStageActive) {
            api.mediaStop();
            return undefined;
        }

        const audio = getObservedMedia(getDisplayAudioElement?.() ?? audioRef.current);
        const state: NativeMediaState = {
            playing,
            title: currentSong.name,
            artist: getSongArtistLabel(currentSong) || unknownArtistLabel,
            album: getSongAlbumLabel(currentSong),
            // 只给原生的 http(s) 地址；blob: 这类页面内的地址原生拿不到，封面就让上一张留着。
            coverUrl: cachedCoverUrl || getSongCoverUrl(currentSong) || undefined,
            durationSec: audio && Number.isFinite(audio.duration) ? audio.duration : undefined,
            positionSec: audio ? audio.currentTime : undefined,
        };
        api.mediaUpdate(JSON.stringify(state));
        return undefined;
    }, [audioRef, cachedCoverUrl, currentSong, getDisplayAudioElement, isNowPlayingStageActive, playing, unknownArtistLabel]);

    // 进度：1 秒一次的 timeupdate 才推，别把通知刷坏。
    useEffect(() => {
        const api = nativeApi();
        if (!api || !currentSong) return undefined;
        let lastPush = 0;
        const audio = getObservedMedia(getDisplayAudioElement?.() ?? audioRef.current);
        if (!audio) return undefined;
        const onTimeUpdate = () => {
            const now = Date.now();
            if (now - lastPush < 1000) return;
            lastPush = now;
            api.mediaUpdate(JSON.stringify({
                playing,
                title: currentSong.name,
                artist: getSongArtistLabel(currentSong) || unknownArtistLabel,
                album: getSongAlbumLabel(currentSong),
                durationSec: Number.isFinite(audio.duration) ? audio.duration : undefined,
                positionSec: audio.currentTime,
            }));
        };
        audio.addEventListener('timeupdate', onTimeUpdate);
        return () => audio.removeEventListener('timeupdate', onTimeUpdate);
    }, [audioRef, currentSong, getDisplayAudioElement, playing, unknownArtistLabel]);

    // 原生命令 → 与 MediaSession 相同的处理器。
    useEffect(() => {
        if (nativeApi() === null) return undefined;
        const onCommand = (event: Event) => {
            const detail = (event as CustomEvent<string>).detail;
            if (isNowPlayingControlDisabledRef.current) return;
            if (detail === 'play') {
                void mediaSessionPlayRef.current();
            } else if (detail === 'pause') {
                mediaSessionPauseRef.current();
            } else if (detail === 'prev') {
                mediaSessionPrevRef.current();
            } else if (detail === 'next') {
                void mediaSessionNextRef.current();
            }
        };
        window.addEventListener('folia-native-media', onCommand);
        return () => window.removeEventListener('folia-native-media', onCommand);
    }, [isNowPlayingControlDisabledRef, mediaSessionNextRef, mediaSessionPauseRef, mediaSessionPlayRef, mediaSessionPrevRef]);

    // 页面卸载（理论上不会）时撤卡。
    useEffect(() => () => {
        nativeApi()?.mediaStop();
    }, []);
};

export default useNativeMediaBridge;
