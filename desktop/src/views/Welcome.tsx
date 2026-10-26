import { useRef, useState, type FormEvent } from 'react';
import {
  ArrowRight,
  Check,
  FileJson2,
  LayoutGrid,
  ListChecks,
  RefreshCw,
  ScanLine,
  ShieldCheck,
  Sparkles,
  Wifi,
} from 'lucide-react';
import appIcon from '../assets/app-icon.png';
import { PairingCard } from '../components/PairingCard';
import { Button, Spinner, cx } from '../components/ui';
import { useAppStore } from '../core/store';
import {
  afterImport,
  connectBase,
  normalizeBase,
} from '../core/sync';
import { importSnapshotJsonFile } from '../core/snapshotFile';

export function Welcome() {
  const state = useAppStore();
  const [address, setAddress] = useState(state.base || '');
  const [busy, setBusy] = useState(false);
  const [changing, setChanging] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const openScanner = state.openScanner;

  const connect = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!normalizeBase(address)) {
      state.pushToast('地址看起来不对，应该是 http://192.168.x.x:端口', 'error');
      return;
    }
    setBusy(true);
    await connectBase(address);
    setBusy(false);
    setChanging(false);
  };

  const importFile = async (file: File) => {
    const ok = await importSnapshotJsonFile(file);
    if (ok) void afterImport();
  };

  if (state.base && !state.secret) {
    return (
      <div className="welcome welcome--pairing">
        <div className="welcome-orb welcome-orb--a" aria-hidden="true" />
        <div className="welcome-orb welcome-orb--b" aria-hidden="true" />
        <div className="welcome-card glass">
          <section className="welcome-pair-copy">
            <Brand />
            <div className="welcome-step">02 / 在手机上确认</div>
            <h1>
              最后一步，
              <br />
              在手机上点允许。
            </h1>
            <p className="welcome-lead">
              配对码只在你和手机之间走。确认后，两端的课表和待办会开始同步；手机不在时，这一端仍然能看、能改。
            </p>
            <ol className="welcome-steps">
              <li>
                <span>1</span>
                让手机和电脑连同一个 Wi-Fi
              </li>
              <li>
                <span>2</span>
                用手机相机扫右边的配对码
              </li>
              <li>
                <span>3</span>
                在 MiuList 里点「允许」
              </li>
            </ol>

            {changing ? (
              <form className="connect-inline" onSubmit={connect}>
                <label className="field grow">
                  <span>换一个手机地址</span>
                  <input
                    value={address}
                    onChange={(event) => setAddress(event.target.value)}
                    placeholder="http://192.168.1.20:8765"
                    autoFocus
                  />
                </label>
                <Button variant="primary" type="submit" loading={busy}>
                  连接
                </Button>
                <Button variant="ghost" onClick={() => setChanging(false)}>
                  取消
                </Button>
              </form>
            ) : (
              <div className="welcome-actions-row">
                <Button variant="primary" icon={ScanLine} onClick={openScanner}>
                  重新扫描地址
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => {
                    setAddress(state.base || '');
                    setChanging(true);
                  }}
                >
                  换地址
                </Button>
                <Button variant="ghost" icon={FileJson2} onClick={() => inputRef.current?.click()}>
                  导入快照
                </Button>
                {state.phoneReachable ? (
                  <span className="welcome-live">
                    <Wifi size={13} /> {state.phoneName || '手机'} 在线
                  </span>
                ) : (
                  <span className="welcome-offline">
                    <RefreshCw size={13} /> 手机暂时连不上
                  </span>
                )}
              </div>
            )}

            <p className="welcome-footnote">
              <ShieldCheck size={14} /> 配对密钥只在二维码和浏览器里；JSON 快照不包含它。
            </p>
            <input
              ref={inputRef}
              type="file"
              accept="application/json,.json"
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file) void importFile(file);
              }}
            />
          </section>
          <section className="welcome-pair-stage">
            <PairingCard />
          </section>
        </div>
      </div>
    );
  }

  return (
    <div className="welcome">
      <div className="welcome-orb welcome-orb--a" aria-hidden="true" />
      <div className="welcome-orb welcome-orb--b" aria-hidden="true" />
      <div className="welcome-card glass welcome-card--intro">
        <section className="welcome-copy">
          <Brand />
          <div className="welcome-kicker">
            <span>单文件</span>
            <span>本机快照</span>
            <span>与手机对等</span>
          </div>
          <h1>
            把课程和待办，
            <br />
            带到电脑上。
          </h1>
          <p className="welcome-lead">
            打开一个 HTML，浏览器就是你的桌面端。数据留在本机，手机不在也能看、能改；连上手机后自动同步。
          </p>

          <button type="button" className="scan-tile welcome-scan" onClick={openScanner}>
            <span className="scan-tile-icon">
              <ScanLine size={24} />
            </span>
            <span>
              <strong>扫描手机二维码</strong>
              <em>手机：更多 → 网页同步 → 地址码</em>
            </span>
            <span className="scan-tile-arrow">→</span>
          </button>

          <form className="welcome-manual" onSubmit={connect}>
            <label className="field grow">
              <span>或手动填手机地址</span>
              <input
                value={address}
                onChange={(event) => setAddress(event.target.value)}
                placeholder="http://192.168.1.20:8765"
                inputMode="url"
                autoCapitalize="off"
                autoCorrect="off"
                spellCheck={false}
              />
            </label>
            <Button variant="soft" type="submit" loading={busy}>
              连接
            </Button>
          </form>

          <div className="welcome-links">
            <button type="button" onClick={() => inputRef.current?.click()}>
              <FileJson2 size={15} /> 已有 JSON 快照？导入
            </button>
            <span className="welcome-dot">·</span>
            <span>手机和电脑需在同一个 Wi-Fi</span>
          </div>

          <p className="welcome-footnote">
            <ShieldCheck size={14} /> 推荐 Chrome / Edge；快照不含配对密钥，密钥只留在浏览器里。
          </p>
          <input
            ref={inputRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) void importFile(file);
            }}
          />
        </section>

        <section className="welcome-preview" aria-label="界面预览">
          <div className="preview-window glass">
            <div className="preview-sidebar">
              <span className="preview-brand" />
              <span className="preview-nav is-active">
                <LayoutGrid size={14} />
              </span>
              <span className="preview-nav">
                <ListChecks size={14} />
              </span>
              <span className="preview-nav">
                <Wifi size={14} />
              </span>
            </div>
            <div className="preview-main">
              <div className="preview-date">
                <span className="week-pill">第 11 周</span>
                <strong>10月26日</strong>
                <em>星期一</em>
              </div>
              <div className="preview-course" style={{ '--course': '#7aa2ff' } as never}>
                <span className="preview-course-bar" />
                <div>
                  <strong>微积分 B(下)</strong>
                  <span>08:00 - 09:35 · 教十 201</span>
                </div>
                <span className="preview-course-chip">正在上</span>
              </div>
              <div className="preview-task">
                <span className="preview-check">
                  <Check size={10} strokeWidth={3} />
                </span>
                <div>
                  <strong>大学物理预习 · 第8章</strong>
                  <span>今天 21:30 · 大学物理</span>
                </div>
                <span className="preview-type">预习</span>
              </div>
              <div className="preview-task">
                <span className="preview-check preview-check--idle" />
                <div>
                  <strong>整理这周的错题</strong>
                  <span>2 天后 · 课后作业</span>
                </div>
                <span className="preview-star">★</span>
              </div>
              <div className="preview-note">
                <Sparkles size={13} />
                手机不在，也能看、能改。
              </div>
            </div>
          </div>
          <div className="preview-badge preview-badge--one">
            <FileJson2 size={14} /> 本机 JSON
          </div>
          <div className="preview-badge preview-badge--two">
            <Sparkles size={14} /> 毛玻璃
          </div>
          <p className="preview-caption">
            课表、清单、考试都在同一块玻璃上。
            <span>界面预览</span>
          </p>
        </section>
      </div>
    </div>
  );
}

function Brand() {
  return (
    <div className="welcome-brand">
      <img src={appIcon} alt="" />
      <span>MiuList</span>
      <em>电脑网页端</em>
    </div>
  );
}
