import { useEffect } from 'react';
import { create } from 'zustand';

// src/stores/useAppDisplayStore.ts
// 应用显示模式：横屏（默认）、竖屏、跟随系统旋转。只在 Android 壳里有实际效果——
// 桌面窗口的朝向由用户拖的，页面管不着；浏览器里也没有"锁方向"这种事。
// 选「跟随系统旋转」时，应用仍以横屏打开（壳在 onCreate 里先锁一次横屏），
// 页面加载后由 useAppDisplayOrientation 通知壳切到全传感器。

export type AppDisplayMode = 'landscape' | 'portrait' | 'system';

const KEY = 'folia_app_display_mode';

const readStoredMode = (): AppDisplayMode => {
    if (typeof window === 'undefined') return 'landscape';
    const raw = window.localStorage.getItem(KEY);
    return raw === 'portrait' || raw === 'system' ? raw : 'landscape';
};

type AppDisplayState = {
    mode: AppDisplayMode;
    setMode: (mode: AppDisplayMode) => void;
};

export const useAppDisplayStore = create<AppDisplayState>((set) => ({
    mode: readStoredMode(),
    setMode: (mode) => {
        try {
            window.localStorage.setItem(KEY, mode);
        } catch {
            // 存不进去就只活在本会话。
        }
        set({ mode });
    },
}));

/** 模式变化时通知壳；壳不在（桌面/浏览器）时什么都不发生。 */
export const useAppDisplayOrientation = (): void => {
    const mode = useAppDisplayStore(state => state.mode);
    useEffect(() => {
        const api = (window as Window & { foliaNative?: { setOrientation?: (mode: AppDisplayMode) => void } }).foliaNative;
        api?.setOrientation?.(mode);
    }, [mode]);
};
