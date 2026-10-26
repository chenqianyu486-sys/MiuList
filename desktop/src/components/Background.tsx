import { useAppStore } from '../core/store';

export function Background() {
  const wallpaper = useAppStore((state) => state.wallpaper);
  return <div className={`bg bg--${wallpaper}`} aria-hidden="true" />;
}
