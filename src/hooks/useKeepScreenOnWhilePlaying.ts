import { useEffect } from 'react';
import { PlayerState } from '../types';
import { selectDisplayPlayerState, usePlaybackStore } from '../stores/usePlaybackStore';
import { useAudioSettingsStore } from '../stores/useAudioSettingsStore';

// src/hooks/useKeepScreenOnWhilePlaying.ts
// 播放时不让屏幕熄掉。设置里打开后，只要在放歌且页面可见，就向系统要一个屏幕唤醒锁；
// 暂停、关掉设置、或者应用退到后台时放开——系统本来也会在页面不可见时自动收走，这里显式放一次，
// 回到前台再重新要。桌面版有 Electron 的 powerSaveBlocker，不走这里，所以那边这个开关不显示。
//
// Wake Lock 在 WebView 里也能用（Chrome 84+），页面必须是安全上下文；127.0.0.1 算安全上下文。
// 拿不到（旧内核、省电模式拒绝）就安静地什么都不做：熄屏是系统的默认行为，不该因此报错。

type WakeLockSentinelLike = { release: () => Promise<void> };
type WakeLockLike = { request: (type: 'screen') => Promise<WakeLockSentinelLike> };

const wakeLockOf = (): WakeLockLike | null => (
    typeof navigator !== 'undefined'
        ? (navigator as Navigator & { wakeLock?: WakeLockLike }).wakeLock ?? null
        : null
);

/** 挂在应用根上，读设置和播放状态，自己管唤醒锁的取与放。 */
export const useKeepScreenOnWhilePlaying = (): void => {
    const enabled = useAudioSettingsStore(state => state.keepScreenOn);
    const isPlaying = usePlaybackStore(selectDisplayPlayerState) === PlayerState.PLAYING;

    useEffect(() => {
        if (!enabled || !isPlaying) return undefined;
        const wakeLock = wakeLockOf();
        if (!wakeLock) return undefined;

        let sentinel: WakeLockSentinelLike | null = null;
        let disposed = false;

        const acquire = () => {
            if (disposed || document.visibilityState !== 'visible') return;
            wakeLock.request('screen').then(next => {
                if (disposed) {
                    void next.release();
                    return;
                }
                sentinel = next;
            }).catch(() => {
                // 省电模式或系统策略拒绝：保持熄灭，不影响播放。
            });
        };

        // 从后台回来时系统已经收走了锁，重新要一个。
        const onVisibilityChange = () => {
            if (document.visibilityState === 'visible') acquire();
        };

        acquire();
        document.addEventListener('visibilitychange', onVisibilityChange);
        return () => {
            disposed = true;
            document.removeEventListener('visibilitychange', onVisibilityChange);
            const held = sentinel;
            sentinel = null;
            void held?.release().catch(() => {});
        };
    }, [enabled, isPlaying]);
};

export default useKeepScreenOnWhilePlaying;
