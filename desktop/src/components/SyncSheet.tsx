import { useRef, useState, type FormEvent } from 'react';
import {
  AlertTriangle,
  Check,
  Copy,
  FileJson2,
  Info,
  Link2,
  Palette,
  RefreshCw,
  ScanLine,
  ShieldCheck,
  Unplug,
  Upload,
  Wifi,
  WifiOff,
} from 'lucide-react';
import appIcon from '../assets/app-icon.png';
import {
  formatSyncTime,
} from '../core/format';
import { useAppStore } from '../core/store';
import {
  afterImport,
  connectBase,
  disconnectPair,
  manualSync,
  normalizeBase,
  requestPairCode,
  syncMerge,
  syncPull,
  syncPush,
} from '../core/sync';
import {
  createSnapshotFile,
  disconnectSnapshotFile,
  exportSnapshotJson,
  importSnapshotJsonFile,
  pickSnapshotFile,
  requestSnapshotPermission,
  writeSnapshotNow,
} from '../core/snapshotFile';
import type { SyncTab, WallpaperId } from '../core/types';
import { PairingCard } from './PairingCard';
import { Sheet } from './Sheet';
import { Button, cx } from './ui';

const TABS: { id: SyncTab; label: string; icon: typeof Link2 }[] = [
  { id: 'connection', label: '连接', icon: Link2 },
  { id: 'snapshot', label: '快照', icon: FileJson2 },
  { id: 'appearance', label: '外观', icon: Palette },
  { id: 'about', label: '关于', icon: Info },
];

export function SyncSheet() {
  const open = useAppStore((s) => s.syncOpen);
  const tab = useAppStore((s) => s.syncTab);
  const close = useAppStore((s) => s.closeSync);
  const setTab = useAppStore((s) => s.setSyncTab);
  return (
    <Sheet
      open={open}
      onClose={close}
      title="同步与设置"
      subtitle="手机、本机快照与毛玻璃外观。"
      wide
    >
      <div className="sheet-tabs" role="tablist" aria-label="设置分区">
        {TABS.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              role="tab"
              aria-selected={tab === item.id}
              className={cx('sheet-tab', tab === item.id && 'is-active')}
              onClick={() => setTab(item.id)}
            >
              <Icon size={16} />
              {item.label}
            </button>
          );
        })}
      </div>
      {tab === 'connection' ? <ConnectionPanel /> : null}
      {tab === 'snapshot' ? <SnapshotPanel /> : null}
      {tab === 'appearance' ? <AppearancePanel /> : null}
      {tab === 'about' ? <AboutPanel /> : null}
    </Sheet>
  );
}

function ConnectForm({
  initial = '',
  onDone,
  onCancel,
}: {
  initial?: string;
  onDone?: () => void;
  onCancel?: () => void;
}) {
  const [address, setAddress] = useState(initial);
  const [busy, setBusy] = useState(false);
  const openScanner = useAppStore((s) => s.openScanner);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    if (!normalizeBase(address)) {
      return;
    }
    setBusy(true);
    const ok = await connectBase(address);
    setBusy(false);
    if (ok) onDone?.();
  };

  return (
    <form className="connect-form" onSubmit={submit}>
      <button type="button" className="scan-tile" onClick={openScanner}>
        <span className="scan-tile-icon">
          <ScanLine size={24} />
        </span>
        <span>
          <strong>扫描手机二维码</strong>
          <em>手机：更多 → 网页同步 → 地址码</em>
        </span>
        <span className="scan-tile-arrow">→</span>
      </button>
      <div className="connect-row">
        <label className="field grow">
          <span>也可以手动填地址</span>
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
        <Button variant="primary" type="submit" loading={busy}>
          连接
        </Button>
      </div>
      <p className="field-hint">
        地址在手机的「更多 → 网页同步」里，带复制按钮；手机和电脑要在同一个 Wi-Fi。
      </p>
      {onCancel ? (
        <Button variant="ghost" onClick={onCancel}>
          取消
        </Button>
      ) : null}
    </form>
  );
}

function ConnectionPanel() {
  const state = useAppStore();
  const [editing, setEditing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [disconnectArmed, setDisconnectArmed] = useState(false);
  const openScanner = state.openScanner;
  const conflict = state.conflict;

  const copy = async () => {
    if (!state.base) return;
    try {
      await navigator.clipboard.writeText(state.base);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      state.pushToast('复制失败，请手动选中地址。', 'error');
    }
  };

  const statusText = state.conflict
    ? '需要手动选方向'
    : state.syncing
      ? '同步中…'
      : state.phoneReachable
        ? '手机在线'
        : state.base
          ? '未连接'
          : '还没有连接手机';

  return (
    <div className="panel-stack">
      {conflict ? <ConflictCard /> : null}

      {!state.base || editing ? (
        <div className="panel-card glass-soft">
          <div className="panel-card-head">
            <span className="panel-icon">
              <Wifi size={18} />
            </span>
            <div>
              <h3>连接手机</h3>
              <p>扫描手机上的地址二维码，或手动填局域网地址。</p>
            </div>
          </div>
          <ConnectForm
            initial={state.base}
            onDone={() => setEditing(false)}
            onCancel={state.base ? () => setEditing(false) : undefined}
          />
        </div>
      ) : (
        <div className="panel-card glass-soft">
          <div className="connection-head">
            <span className={cx('connection-orb', state.phoneReachable && 'is-online')}>
              {state.phoneReachable ? <Wifi size={20} /> : <WifiOff size={20} />}
            </span>
            <div className="connection-title">
              <h3>{state.phoneName || state.snapshot.deviceName || '手机'}</h3>
              <p>{statusText}</p>
            </div>
            <span className={cx('connection-chip', state.phoneReachable && 'is-online')}>
              {state.phoneReachable ? '在线' : '离线'}
            </span>
          </div>

          <div className="address-line">
            <span>地址</span>
            <code>{state.base}</code>
            <button type="button" className="copy-btn" onClick={() => void copy()} aria-label="复制手机地址">
              {copied ? <Check size={14} /> : <Copy size={14} />}
            </button>
          </div>

          <div className="connection-meta">
            <span>上次同步</span>
            <strong>
              {state.meta.lastSyncAt
                ? formatSyncTime(state.meta.lastSyncAt, state.snapshot)
                : '还没有同步过'}
            </strong>
          </div>
          {state.syncError ? <p className="inline-error">{state.syncError}</p> : null}

          <div className="connection-actions">
            <Button
              variant="primary"
              icon={RefreshCw}
              loading={state.syncing}
              disabled={!state.secret || !state.deviceId}
              onClick={() => void manualSync()}
            >
              立即同步
            </Button>
            <Button variant="soft" icon={ScanLine} onClick={openScanner}>
              扫描地址
            </Button>
            <Button variant="ghost" onClick={() => setEditing(true)}>
              换地址
            </Button>
            {state.secret ? (
              <>
                <Button variant="ghost" onClick={() => void requestPairCode()}>
                  重新配对
                </Button>
                {disconnectArmed ? (
                  <>
                    <Button
                      variant="danger"
                      icon={Unplug}
                      onClick={() => {
                        setDisconnectArmed(false);
                        disconnectPair();
                      }}
                    >
                      确认断开
                    </Button>
                    <Button variant="ghost" onClick={() => setDisconnectArmed(false)}>
                      取消
                    </Button>
                  </>
                ) : (
                  <Button variant="ghost" icon={Unplug} onClick={() => setDisconnectArmed(true)}>
                    断开配对
                  </Button>
                )}
              </>
            ) : null}
          </div>

          {!state.secret ? (
            <>
              {!state.phoneReachable ? (
                <p className="inline-warn">
                  这个地址暂时连不上。确认手机上的「局域网服务」已打开，或者换一个地址。
                </p>
              ) : null}
              <PairingCard compact />
            </>
          ) : null}
        </div>
      )}

      <div className="panel-note">
        <ShieldCheck size={16} />
        <p>
          网页端与手机是对等快照；配对密钥只在本机，快照文件不含它。手机 App 继续当接口服务端，协议不变。
        </p>
      </div>
    </div>
  );
}

function ConflictCard() {
  const state = useAppStore();
  const conflict = state.conflict;
  if (!conflict) return null;
  const localTime = formatSyncTime(state.snapshot.latestEditAt, state.snapshot);
  const remoteTime = formatSyncTime(conflict.remote.latestEditAt, conflict.remote);
  const titles = conflict.conflicts.map((item) => item.title).filter(Boolean).slice(0, 4);
  return (
    <div className="conflict-card">
      <div className="conflict-head">
        <span className="conflict-icon">
          <AlertTriangle size={20} />
        </span>
        <div>
          <h3>两台设备改了同一条待办</h3>
          <p>自动同步已经停下。选一个方向，或者合并。</p>
        </div>
      </div>
      <div className="conflict-titles">
        {titles.map((title) => (
          <span key={title}>「{title}」</span>
        ))}
        {conflict.conflicts.length > titles.length ? (
          <span>等 {conflict.conflicts.length} 条</span>
        ) : null}
      </div>
      <div className="conflict-times">
        <span>本页 <strong>{localTime}</strong></span>
        <span>手机 <strong>{remoteTime}</strong></span>
      </div>
      <div className="conflict-actions">
        <Button
          variant="primary"
          disabled={state.syncing}
          onClick={() => void syncPush()}
        >
          以本页为准
        </Button>
        <Button variant="soft" disabled={state.syncing} onClick={() => void syncPull()}>
          以手机为准
        </Button>
        <Button variant="ghost" disabled={state.syncing} onClick={() => void syncMerge()}>
          合并
        </Button>
      </div>
    </div>
  );
}

function SnapshotPanel() {
  const state = useAppStore();
  const file = state.snapshotFile;
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<boolean | void>) => {
    setBusy(true);
    try {
      await fn();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel-stack">
      <div className="panel-card glass-soft">
        <div className="panel-card-head">
          <span className="panel-icon">
            <FileJson2 size={18} />
          </span>
          <div>
            <h3>本机 JSON 快照</h3>
            <p>课程、待办与考试落在本机文件里；不含配对密钥。</p>
          </div>
        </div>

        {file.active ? (
          <div className="file-card">
            <div className="file-name-line">
              <FileJson2 size={17} />
              <strong>{file.name || 'miulist-snapshot.json'}</strong>
              <span className="file-state">已连接</span>
            </div>
            <p>
              {file.lastSavedAt
                ? `最近写入：${formatSyncTime(file.lastSavedAt, state.snapshot)}`
                : '编辑后会自动写入'}
              {file.permission === 'prompt'
                ? ' · 浏览器需要重新授权一次'
                : file.permission === 'denied'
                  ? ' · 文件权限被拒绝'
                  : ''}
            </p>
            {file.error ? <p className="inline-error">{file.error}</p> : null}
            <div className="file-actions">
              <Button
                variant="primary"
                loading={busy}
                onClick={() => void run(() => writeSnapshotNow(true))}
              >
                立即保存
              </Button>
              {file.permission !== 'granted' ? (
                <Button variant="soft" onClick={() => void requestSnapshotPermission()}>
                  重新授权
                </Button>
              ) : null}
              <Button
                variant="ghost"
                onClick={() => {
                  void disconnectSnapshotFile();
                }}
              >
                断开文件
              </Button>
            </div>
            <p className="field-hint">
              自动保存有 0.8 秒防抖；关闭浏览器前最后一秒的改动建议点一下「立即保存」。
            </p>
          </div>
        ) : (
          <div className="file-empty">
            <p>
              还没有绑定快照文件。绑定后每次编辑会自动写入；也可以先保存到新文件。
            </p>
            {!file.supported ? (
              <p className="inline-warn">
                当前浏览器不支持直接读写本机文件，请用下面的「导出 / 导入 JSON」。Chrome / Edge 支持自动写入。
              </p>
            ) : null}
            <div className="file-actions">
              <Button
                variant="primary"
                icon={FileJson2}
                disabled={!file.supported}
                loading={busy}
                onClick={() => void run(() => pickSnapshotFile())}
              >
                绑定已有快照
              </Button>
              <Button
                variant="soft"
                disabled={!file.supported}
                onClick={() => void createSnapshotFile()}
              >
                保存到新文件
              </Button>
            </div>
          </div>
        )}
      </div>

      <div className="panel-card glass-soft">
        <div className="panel-card-head">
          <span className="panel-icon">
            <Upload size={18} />
          </span>
          <div>
            <h3>导入 / 导出</h3>
            <p>在浏览器之间搬数据，或者留一份手动备份。</p>
          </div>
        </div>
        <div className="file-actions">
          <Button variant="soft" icon={FileJson2} onClick={exportSnapshotJson}>
            导出 JSON
          </Button>
          <Button variant="ghost" icon={Upload} onClick={() => inputRef.current?.click()}>
            导入 JSON
          </Button>
          <input
            ref={inputRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={(event) => {
              const picked = event.target.files?.[0];
              event.target.value = '';
              if (!picked) return;
              void importSnapshotJsonFile(picked).then((ok) => {
                if (ok) void afterImport();
              });
            }}
          />
        </div>
        <p className="field-hint">
          导入会替换当前本地快照，但不会删除浏览器里的配对密钥；如果文件带有手机地址，也会一并记住。
        </p>
      </div>

      <div className="panel-note">
        <ShieldCheck size={16} />
        <p>
          快照文件是明文的：它包含课程与待办，但<strong>不包含</strong>配对 secret 和同步密码。密钥只存在浏览器的
          localStorage；清掉浏览器数据后需要重新扫码配对。请把快照当作个人数据文件保管。
        </p>
      </div>
    </div>
  );
}

const WALLPAPERS: { id: WallpaperId; label: string; desc: string }[] = [
  { id: 'cloud', label: '云烟', desc: '青柠 · 樱粉' },
  { id: 'mint', label: '薄荷', desc: '青竹 · 湖蓝' },
  { id: 'dusk', label: '暮色', desc: '杏橙 · 藕紫' },
  { id: 'sakura', label: '樱粉', desc: '粉樱 · 米白' },
];

function AppearancePanel() {
  const wallpaper = useAppStore((s) => s.wallpaper);
  const setWallpaper = useAppStore((s) => s.setWallpaper);
  const pinned = useAppStore((s) => s.sidebarPinned);
  const setPinned = useAppStore((s) => s.setSidebarPinned);

  return (
    <div className="panel-stack">
      <div className="panel-card glass-soft">
        <div className="panel-card-head">
          <span className="panel-icon">
            <Palette size={18} />
          </span>
          <div>
            <h3>背景与玻璃</h3>
            <p>换一层透光底色，卡片会自动模糊背景。</p>
          </div>
        </div>
        <div className="wallpaper-grid">
          {WALLPAPERS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={cx('wallpaper-swatch', `is-${item.id}`, wallpaper === item.id && 'is-active')}
              onClick={() => setWallpaper(item.id)}
              aria-pressed={wallpaper === item.id}
            >
              <span className="wallpaper-preview" />
              <span className="wallpaper-label">{item.label}</span>
              <span className="wallpaper-desc">{item.desc}</span>
            </button>
          ))}
        </div>
        <p className="field-hint">
          毛玻璃依赖浏览器的 `backdrop-filter`；Chrome / Edge 效果最佳。留一点背景色，玻璃才有东西可透。
        </p>
      </div>

      <div className="panel-card glass-soft">
        <div className="switch-row">
          <div>
            <h3>侧栏保持展开</h3>
            <p>默认鼠标靠近才展开；钉住后内容区也会让出空间。</p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={pinned}
            className={cx('switch', pinned && 'is-on')}
            onClick={() => setPinned(!pinned)}
          >
            <span />
          </button>
        </div>
      </div>
    </div>
  );
}

function AboutPanel() {
  const snapshot = useAppStore((s) => s.snapshot);
  return (
    <div className="panel-stack">
      <div className="about-hero">
        <img src={appIcon} alt="" className="about-icon" />
        <div>
          <h3>MiuList · 电脑网页端</h3>
          <p>单文件静态前端 · 本机 JSON 快照 · 与手机对等同步</p>
        </div>
        <span className="about-version">1.0.6</span>
      </div>
      <div className="panel-card glass-soft">
        <h3 className="about-heading">它是什么</h3>
        <p className="about-text">
          这是 MiuList 的电脑网页端：打开 HTML 就能看课表、清单和考试，手机不在也能改。手机 App
          继续当接口服务端，配对协议、加密和冲突处理都没有变。
        </p>
        <h3 className="about-heading">数据在哪</h3>
        <p className="about-text">
          浏览器 localStorage 是实时数据；绑定的 JSON 文件是这一端的镜像。配对 secret
          仅在浏览器里，文件不含它。两边都持有一份完整快照，同步时按方向合并。
        </p>
        <h3 className="about-heading">当前这份</h3>
        <div className="about-facts">
          <span>协议</span>
          <strong>v3 · 挑战-应答签名 · ChaCha20 + HMAC</strong>
          <span>手机</span>
          <strong>{snapshot.deviceName || '未连接'}</strong>
          <span>周课表</span>
          <strong>{snapshot.timetable ? `${snapshot.timetable.totalWeeks} 周` : '暂无'}</strong>
        </div>
      </div>
      <div className="panel-note">
        <Info size={16} />
        <p>
          想改课表、考试或自动生成规则？这些仍在手机 App 上做；电脑端只同步并展示手机算好的结果。
        </p>
      </div>
    </div>
  );
}
