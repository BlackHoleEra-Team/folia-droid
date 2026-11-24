import { useEffect } from 'react';
import { create } from 'zustand';

// src/stores/useAppDisplayStore.ts
// 应用显示模式：横屏（默认）、竖屏、跟随系统旋转、主页竖屏 / 播放页横屏。
// 只在 Android 壳里有实际效果——桌面窗口的朝向由用户拖的，浏览器里也没有"锁方向"这种事。
// 选「跟随系统旋转」时应用仍以横屏打开（壳在 onCreate 里先锁一次横屏），页面加载后切全传感器；
// 选「主页竖屏 / 播放页横屏」时，页面按当前是不是播放页告诉壳该锁哪边。
// 页面缩放用 CSS zoom，只在壳里生效；桌面与浏览器的缩放各归各的。

export type AppDisplayMode = 'landscape' | 'portrait' | 'system' | 'player';

const MODE_KEY = 'folia_app_display_mode';
const ZOOM_KEY = 'folia_app_display_zoom';

const readStoredMode = (): AppDisplayMode => {
    if (typeof window === 'undefined') return 'landscape';
    const raw = window.localStorage.getItem(MODE_KEY);
    return raw === 'portrait' || raw === 'system' || raw === 'player' ? raw : 'landscape';
};

const readStoredZoom = (): number => {
    if (typeof window === 'undefined') return 100;
    const raw = Number(window.localStorage.getItem(ZOOM_KEY));
    return Number.isFinite(raw) && raw >= 80 && raw <= 150 ? raw : 100;
};

type AppDisplayState = {
    mode: AppDisplayMode;
    zoom: number;
    /** 当前是不是播放页；只有「主页竖屏 / 播放页横屏」用得到。App 在播放页状态变化时写进来。 */
    inPlayer: boolean;
    setMode: (mode: AppDisplayMode) => void;
    setZoom: (zoom: number) => void;
    setInPlayer: (inPlayer: boolean) => void;
};

export const useAppDisplayStore = create<AppDisplayState>((set) => ({
    mode: readStoredMode(),
    zoom: readStoredZoom(),
    inPlayer: false,
    setMode: (mode) => {
        try {
            window.localStorage.setItem(MODE_KEY, mode);
        } catch {
            // 存不进去就只活在本会话。
        }
        set({ mode });
    },
    setInPlayer: (inPlayer) => set({ inPlayer }),
    setZoom: (zoom) => {
        try {
            window.localStorage.setItem(ZOOM_KEY, String(zoom));
        } catch {
            // 同上。
        }
        set({ zoom });
    },
}));

type NativeDisplayApi = {
    setOrientation?: (mode: 'landscape' | 'portrait' | 'system') => void;
};

const nativeApi = (): NativeDisplayApi | null => (
    typeof window === 'undefined'
        ? null
        : (window as Window & { foliaNative?: NativeDisplayApi }).foliaNative ?? null
);

/**
 * 模式与缩放落到壳上。`inPlayer` 只有「主页竖屏 / 播放页横屏」用得到：
 * 进播放页锁横屏，回主页锁竖屏；其它模式直接照搬。
 */
export const useAppDisplayOrientation = (): void => {
    const mode = useAppDisplayStore(state => state.mode);
    const zoom = useAppDisplayStore(state => state.zoom);
    const inPlayer = useAppDisplayStore(state => state.inPlayer);

    useEffect(() => {
        const api = nativeApi();
        if (!api?.setOrientation) return;
        const target = mode === 'player'
            ? (inPlayer ? 'landscape' : 'portrait')
            : mode;
        api.setOrientation(target);
    }, [inPlayer, mode]);

    useEffect(() => {
        if (nativeApi() === null) return;
        // CSS zoom 缩的是内容；用 vw 的地方仍按未缩放的视口算，缩小时右侧会留白、放大时会被裁。
        // 把根元素宽度按比例补回去，两种方向都不越界。
        const root = document.documentElement;
        root.style.zoom = String(zoom / 100);
    }, [zoom]);
};
