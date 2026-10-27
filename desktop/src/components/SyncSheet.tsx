import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  AlertTriangle,
  Check,
  ClipboardPaste,
  Copy,
  Download,
  FileJson2,
  GraduationCap,
  Info,
  Landmark,
  Link2,
  Palette,
  RefreshCw,
  ScanLine,
  ShieldCheck,
  Smartphone,
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
  pasteAddress,
  pullConfigAfterZju,
  requestPairCode,
  syncConfigPull,
  syncConfigPush,
  syncMerge,
  syncPull,
  syncPush,
  zjuSync,
  zjuTerms,
  type ZjuTerm,
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
  { id: 'snapshot', label: '本机文件', icon: FileJson2 },
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
      subtitle="手机、本机数据与毛玻璃外观。"
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
  const pushToast = useAppStore((s) => s.pushToast);
  const addressRef = useRef<HTMLInputElement | null>(null);

  const submit = async (event?: FormEvent) => {
    event?.preventDefault();
    await connectWith('scan');
  };

  /**
   * 连上手机，并**选定走哪条配对路**。
   *
   * 两条路各只做一个动作：扫码，或者只填地址。
   */
  const connectWith = async (mode: 'scan' | 'link') => {
    if (!normalizeBase(address)) {
      return;
    }
    setBusy(true);
    const ok = await connectBase(address, { pairMode: mode });
    setBusy(false);
    if (ok) onDone?.();
  };

  /**
   * 从剪贴板把地址搬过来。读不到就把输入框聚焦好，让用户按 Ctrl+V。
   */
  const paste = async () => {
    setBusy(true);
    const ok = await pasteAddress();
    setBusy(false);
    if (ok) {
      onDone?.();
      return;
    }
    addressRef.current?.focus();
    pushToast('剪贴板里没读到地址 —— 在输入框里按 Ctrl+V 粘贴。', 'error');
  };

  return (
    <form className="connect-form" onSubmit={submit}>
      <div className="connect-row">
        <label className="field grow">
          <span>手机地址（手机上「网页同步」里复制）</span>
          <input
            ref={addressRef}
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            placeholder="http://192.168.1.20:8765"
            inputMode="url"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
          />
        </label>
        <Button variant="ghost" icon={ClipboardPaste} onClick={() => void paste()}>
          粘贴
        </Button>
        <Button variant="primary" type="submit" loading={busy}>
          继续
        </Button>
      </div>
      <p className="field-hint">
        地址在手机的「更多 → 网页同步」里，点「复制连接链接」；
        粘贴到这里之后，两条路挑一条 —— <strong>手机扫电脑的码</strong>
        （电脑出二维码，手机扫一次）或 <strong>只填地址直接连</strong>
        （手机弹确认卡，点「允许」，全程不用摄像头）。
        手机和电脑要在同一个 Wi-Fi。
      </p>
      <button type="button" className="scan-tile" onClick={openScanner}>
        <span className="scan-tile-icon">
          <ScanLine size={24} />
        </span>
        <span>
          <strong>让电脑扫手机地址码</strong>
          <em>扫完地址码，手机上点一下「允许」就行 —— 也只扫这一次</em>
        </span>
        <span className="scan-tile-arrow">→</span>
      </button>
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
              <h3>找到手机</h3>
              <p>第一次只需告诉电脑手机地址 —— 从手机复制粘贴过来，或手打一次。</p>
            </div>
          </div>
          <ConnectForm
            initial={state.base}
            onDone={() => setEditing(false)}
            onCancel={state.base ? () => setEditing(false) : undefined}
          />
        </div>
      ) : !state.secret ? (
        <div className="panel-card glass-soft">
          <div className="panel-card-head">
            <span className="panel-icon">
              <Smartphone size={18} />
            </span>
            <div>
              <h3>手机扫码配对</h3>
              <p>电脑已记住手机地址。配对由手机扫下面的码完成 —— 只扫这一次码。</p>
            </div>
            <span className={cx('connection-chip', state.phoneReachable && 'is-online')}>
              {state.phoneReachable ? '手机在线' : '手机离线'}
            </span>
          </div>
          {!state.phoneReachable ? (
            <p className="inline-warn">
              这个地址暂时连不上。确认手机上的「网页同步」已打开，或者换一个地址。
            </p>
          ) : null}
          <PairingCard compact onChangeAddress={() => setEditing(true)} />
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
          </div>
        </div>
      )}

      {state.base && state.secret ? <SchedulePanel /> : null}
      {state.base && state.secret ? <ZjuPanel /> : null}

      <div className="panel-note">
        <ShieldCheck size={16} />
        <p>
          待办在两端之间自动同步；<strong>课表与考试各留一份</strong>，不会跟着同步跑。
          配对后可在「课表与考试」里手动搬运。
        </p>
      </div>
    </div>
  );
}

/**
 * 「课表与考试」——配置搬运的唯一入口。
 *
 * 两端各持一份课表：网页端是独立的一端，不是手机的镜像。
 * 自动同步只交换待办，课表要搬得明确点一下，否则用户永远不知道
 * 「手机上改了课表，为什么网页端没跟着变」。
 */
function SchedulePanel() {
  const state = useAppStore();
  const localCourses = state.snapshot.courses?.length || 0;
  const localExams = state.snapshot.exams?.length || 0;
  const remote = state.remoteSnapshot;
  const paired = Boolean(state.secret && state.deviceId);
  const busy = state.syncing || !paired;

  return (
    <div className="panel-card glass-soft">
      <div className="panel-card-head">
        <span className="panel-icon">
          <GraduationCap size={18} />
        </span>
        <div>
          <h3>课表与考试</h3>
          <p>两端各留一份。自动同步只动待办，课表不会跟着跑。</p>
        </div>
      </div>

      <div className="config-compare">
        <div className="config-side">
          <span>这台电脑</span>
          <strong>
            {localCourses} 门课 · {localExams} 场考试
          </strong>
        </div>
        <div className="config-side">
          <span>手机</span>
          <strong>
            {remote
              ? `${remote.courses?.length || 0} 门课 · ${remote.exams?.length || 0} 场考试`
              : '还没连上'}
          </strong>
        </div>
      </div>

      <div className="connection-actions">
        <Button
          variant="soft"
          icon={Download}
          loading={state.syncing}
          disabled={busy}
          onClick={() => void syncConfigPull()}
        >
          用手机的课表和考试
        </Button>
        <Button
          variant="ghost"
          icon={Upload}
          disabled={busy || !localCourses}
          onClick={() => void syncConfigPush()}
        >
          把本页课表推给手机
        </Button>
      </div>

      <p className="field-hint">
        {localCourses
          ? '「用手机的」会覆盖这台电脑上的课表与考试；「推给手机」会覆盖手机上的。两者都只动配置，不动待办。'
          : '这台电脑还没有课表：先「用手机的课表和考试」搬一份过来，或者导入一份备份文件。'}
      </p>
    </div>
  );
}

/**
 * 「从学在浙大拉课表」。
 *
 * ## 为什么是「请手机去拉」
 *
 * 浏览器连不上浙大：网页端是 `file://` 打开的单文件 HTML（Origin 为 `null`），
 * 而学在浙大的登录链路跨三个域，中间要自己读 302 的 `Location` 取 ticket、
 * 读登录页的 hidden input 拿 `execution`、手改 TGC 的 Cookie Domain ——
 * 跨源脚本一件都做不到，浏览器也拿不到浙大接口的 CORS 头。
 * 手机那边有整套能力（Keystore 里的凭据 + CAS 客户端），所以由它代劳，
 * 网页端只是发一句「去拉」。
 *
 * ## 密码的去向
 *
 * 密码只在这条请求的**密文**里存在，不落浏览器存储 —— 网页端不记密码。
 * 手机侧会把它转存进 Keystore（除非用户关掉「记住」），
 * 下次就能直接用「用手机已存的账号」。
 */
function ZjuPanel() {
  const state = useAppStore();
  const pushToast = state.pushToast;
  const [studentId, setStudentId] = useState('');
  const [password, setPassword] = useState('');
  const [terms, setTerms] = useState<ZjuTerm[]>([]);
  const [termKey, setTermKey] = useState('');
  const [remember, setRemember] = useState(true);
  const [busy, setBusy] = useState(false);

  // 学期表是**公共配置**，不需要账号 —— 拿不到就退回「手机上次同步的那一期」。
  useEffect(() => {
    let cancelled = false;
    void zjuTerms()
      .then((list) => {
        if (cancelled || !list.length) return;
        setTerms(list);
        setTermKey(`${list[0].year}:${list[0].term}`);
      })
      .catch(() => {
        /* 连不上手机就算了：拉取按钮会给出真正的报错 */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const selected = terms.find((t) => `${t.year}:${t.term}` === termKey);

  const run = async (useFilled: boolean) => {
    if (useFilled && (!studentId.trim() || !password)) {
      pushToast('填一下学号与密码，或者用手机已存的账号。', 'error');
      return;
    }
    setBusy(true);
    try {
      const result = await zjuSync({
        studentId: useFilled ? studentId.trim() : undefined,
        password: useFilled ? password : undefined,
        year: selected?.year,
        term: selected?.term,
        remember,
      });
      if (!result.ok) {
        pushToast(result.error || '拉取失败', 'error');
        return;
      }
      // 拉是手机那边的事，搬才是这台电脑的事 —— 中间还差一次同步。
      await pullConfigAfterZju();
      const exam = result.examError ? `，考试没拉到（${result.examError}）` : '';
      pushToast(
        `已从学在浙大拉到 ${result.courseCount} 门课 · ${result.examCount} 场考试${exam}`,
        'success',
      );
      if (useFilled) setPassword('');
    } catch (error: any) {
      pushToast(error?.message || '拉取失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel-card glass-soft">
      <div className="panel-card-head">
        <span className="panel-icon">
          <Landmark size={18} />
        </span>
        <div>
          <h3>从学在浙大拉课表</h3>
          <p>登录浙大教务系统，取回课表与考试。</p>
        </div>
      </div>

      <div className="connect-row">
        <label className="field grow">
          <span>学号</span>
          <input
            value={studentId}
            onChange={(event) => setStudentId(event.target.value)}
            placeholder="如 3220000000"
            inputMode="numeric"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
          />
        </label>
        <label className="field grow">
          <span>密码</span>
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            placeholder="教务网密码"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
          />
        </label>
      </div>

      {terms.length ? (
        <div className="connect-row">
          <label className="field grow">
            <span>学期</span>
            <select value={termKey} onChange={(event) => setTermKey(event.target.value)}>
              {terms.map((term) => (
                <option key={`${term.year}:${term.term}`} value={`${term.year}:${term.term}`}>
                  {term.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      ) : null}

      <div className="switch-row">
        <div>
          <h3>把账号记住在手机上</h3>
          <p>存进手机的 Keystore；网页端不留密码。</p>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={remember}
          className={cx('switch', remember && 'is-on')}
          onClick={() => setRemember(!remember)}
        >
          <span />
        </button>
      </div>

      <div className="connection-actions">
        <Button
          variant="primary"
          icon={Download}
          loading={busy}
          onClick={() => void run(true)}
        >
          登录并拉取课表
        </Button>
        <Button
          variant="ghost"
          icon={RefreshCw}
          disabled={busy}
          onClick={() => void run(false)}
        >
          用手机已存的账号
        </Button>
      </div>

      <p className="field-hint">
        浏览器连不上浙大，这一步由手机代跑。密码只在这条加密请求里出现一次，
        浏览器不留；勾上「记住」就转存进手机的 Keystore。
      </p>
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
          以本页待办为准
        </Button>
        <Button variant="soft" disabled={state.syncing} onClick={() => void syncPull()}>
          以手机待办为准
        </Button>
        <Button variant="ghost" disabled={state.syncing} onClick={() => void syncMerge()}>
          合并待办
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
            <h3>本机备份文件</h3>
            <p>课程、待办与考试都存在这台电脑上。</p>
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
              改动会稍等一下再自动保存；关浏览器前的改动，建议点一下「立即保存」。
            </p>
          </div>
        ) : (
          <div className="file-empty">
            <p>
              还没有绑定备份文件。绑定后每次编辑都会自动写入；也可以先存成新文件。
            </p>
            {!file.supported ? (
              <p className="inline-warn">
                当前浏览器不支持直接读写本机文件，请用下面的「导出 / 导入」。Chrome / Edge 支持自动保存。
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
                绑定已有文件
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
            导出备份
          </Button>
          <Button variant="ghost" icon={Upload} onClick={() => inputRef.current?.click()}>
            导入备份
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
          导入会替换这台电脑上的数据，已经配对的手机不受影响。
        </p>
      </div>

      <div className="panel-note">
        <ShieldCheck size={16} />
        <p>
          备份文件是明文的：它包含课程与待办，但<strong>不包含</strong>配对凭证和同步密码。
          请把它当作个人数据文件保管。
        </p>
      </div>
    </div>
  );
}

/**
 * 四组背景 = 手机端四张壁纸的 CSS 版（`WallpaperModel.kt` 里的实测三段取样）。
 * 标签与说明按**实际渲染出来的颜色**写，别让它们说出底色没有的东西。
 */
const WALLPAPERS: { id: WallpaperId; label: string; desc: string }[] = [
  { id: 'cloud', label: '云烟', desc: '近乎纯白 · 冷灰' },
  { id: 'mint', label: '草木', desc: '浅淡绿意' },
  { id: 'dusk', label: '暖钢', desc: '暖黄渐藕紫' },
  { id: 'sakura', label: '柔粉', desc: '暖粉渐深' },
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
          <p>打开网页就能用 · 数据存在本机 · 与手机同步</p>
        </div>
        <span className="about-version">1.0.9</span>
      </div>
      <div className="panel-card glass-soft">
        <h3 className="about-heading">它是什么</h3>
        <p className="about-text">
          这是 MiuList 的电脑网页端：打开网页就能看课表、清单和考试，手机不在也能改。
        </p>
        <h3 className="about-heading">数据在哪</h3>
        <p className="about-text">
          数据存在这台电脑上，绑定的备份文件是它的一份副本。两边各有一份完整数据，同步时合并。
        </p>
        <h3 className="about-heading">当前这份</h3>
        <div className="about-facts">
          <span>连接</span>
          <strong>同一 Wi-Fi 直连</strong>
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
