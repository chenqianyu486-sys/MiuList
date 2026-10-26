import appIcon from '../assets/app-icon.png';
import { Spinner } from './ui';

export function Splash() {
  return (
    <div className="splash">
      <div className="splash-card glass">
        <img src={appIcon} alt="" className="splash-icon" />
        <div>
          <p className="splash-brand">MiuList</p>
          <p className="splash-sub">正在准备你的电脑端…</p>
        </div>
        <Spinner size={18} />
      </div>
    </div>
  );
}
