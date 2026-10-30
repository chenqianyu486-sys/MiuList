import { useRef, useState, type FormEvent } from 'react';
import {
  ArrowRight,
  Check,
  ClipboardPaste,
  FileJson2,
  LayoutGrid,
  ListChecks,
  RefreshCw,
  ShieldCheck,
  Smartphone,
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
  pasteAddress,
} from '../core/sync';
import { importSnapshotJsonFile } from '../core/snapshotFile';

export function Welcome() {
  const state = useAppStore();
  const [address, setAddress] = useState(state.base || '');
  const [busy, setBusy] = useState(false);
  const [changing, setChanging] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const addressRef = useRef<HTMLInputElement | null>(null);

  /**
   * 连上手机。
   *
   * 地址为空或格式不对时由 [connectBase] 给出错误提示 ——
   * 这里不再预先判断一次，免得两处判断的文案各说各的。
   *
   * **连不上时不收起表单**：地址填错的话用户要就地改，
   * 收起就等于把刚输的地址藏起来，还得再点一次「换地址」。
   * （与同步面板里的连接表单同一套写法。）
   */
  const connect = async (event?: FormEvent) => {
    event?.preventDefault();
    setBusy(true);
    const ok = await connectBase(address);
    setBusy(false);
    if (ok) setChanging(false);
  };

  /**
   * 一键把剪贴板里的地址搬过来。
   *
   * 读不到剪贴板（浏览器在 file:// 下可能拒掉读权限）时**不报错**，
   * 只是把输入框聚焦好、提示按 Ctrl+V —— 那条路永远可用。
   */
  const paste = async () => {
    setBusy(true);
    const ok = await pasteAddress();
    setBusy(false);
    if (ok) {
      setChanging(false);
      return;
    }
    setAddress(state.base || '');
    addressRef.current?.focus();
    state.pushToast('剪贴板里没有地址，请手动粘贴。', 'error');
  };

  const importFile = async (file: File) => {
    const ok = await importSnapshotJsonFile(file);
    if (ok) void afterImport();
  };

  const skipSetup = () => {
    state.skipSetup();
    state.pushToast('已进入应用。需要时可在「同步与设置」里连接手机。');
  };

  if (state.base && !state.secret) {
    return (
      <div className="welcome welcome--pairing">
        <div className="welcome-orb welcome-orb--a" aria-hidden="true" />
        <div className="welcome-orb welcome-orb--b" aria-hidden="true" />
        <div className="welcome-card glass">
          <section className="welcome-pair-copy">
            <Brand />
            <div className="welcome-step">02 / 等手机确认</div>
            <h1>
              最后一步，
              <br />
              在手机上点一下「允许」。
            </h1>
            <p className="welcome-lead">
              电脑已经把配对请求发到手机。打开手机上的 MiuList → 更多 → 网页同步，
              核对 6 位配对码后点「允许」。之后待办会自动同步；课表需要手动搬一次。
            </p>
            <ol className="welcome-steps">
              <li>
                <span>1</span>
                让手机和电脑连同一个 Wi-Fi
              </li>
              <li>
                <span>2</span>
                打开手机上的「网页同步」
              </li>
              <li>
                <span>3</span>
                核对配对码并点「允许」
              </li>
            </ol>

            {changing ? (
              <form className="connect-inline" onSubmit={connect}>
                <label className="field grow">
                  <span>换一个手机地址</span>
                  <input
                    ref={addressRef}
                    value={address}
                    onChange={(event) => setAddress(event.target.value)}
                    placeholder="http://192.168.1.20:8765"
                    autoFocus
                  />
                </label>
                <Button variant="ghost" icon={ClipboardPaste} onClick={() => void paste()}>
                  粘贴
                </Button>
                <Button variant="primary" type="submit" loading={busy}>
                  连接
                </Button>
                <Button variant="ghost" onClick={() => setChanging(false)}>
                  取消
                </Button>
              </form>
            ) : (
              <div className="welcome-actions-row">
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
                  导入文件
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
              <ShieldCheck size={14} /> 数据只在你自己的设备之间流动。
            </p>
            <button type="button" className="welcome-skip-link" onClick={skipSetup}>
              手机不在身边？先进入应用
              <ArrowRight size={14} aria-hidden="true" />
            </button>
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
            <span>打开即用</span>
            <span>数据留在本机</span>
            <span className="is-accent">
              <Smartphone size={11} aria-hidden="true" /> 与手机同步
            </span>
          </div>
          <h1>
            把课程和待办，
            <br />
            带到电脑上。
          </h1>
          <p className="welcome-lead">
            打开网页就能看课表、清单和考试，手机不在也能改。
            连上手机后，待办会自动同步。
          </p>

          <form className="welcome-manual" onSubmit={connect}>
            <label className="field grow">
              <span>手机地址（在手机「更多 → 网页同步」里点「复制连接链接」）</span>
              <input
                ref={addressRef}
                value={address}
                onChange={(event) => setAddress(event.target.value)}
                placeholder="http://192.168.1.20:38780"
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
              连接
            </Button>
          </form>
          <p className="welcome-hint">
            手机和电脑要在同一个 Wi-Fi。点「连接」后，手机上会弹出确认卡。
          </p>

          <button
            type="button"
            className="welcome-skip"
            onClick={skipSetup}
            aria-label="跳过初始配置，直接开始"
          >
            <span className="welcome-skip-copy">
              <strong>跳过初始配置，直接开始</strong>
              <em>之后可以在「同步与设置」里连接手机或导入文件</em>
            </span>
            <span className="welcome-skip-arrow" aria-hidden="true">
              <ArrowRight size={18} />
            </span>
          </button>

          <div className="welcome-links">
            <button type="button" onClick={() => inputRef.current?.click()}>
              <FileJson2 size={15} /> 已有备份？导入
            </button>
            <span className="welcome-dot">·</span>
            <span>手机和电脑需在同一个 Wi-Fi</span>
          </div>

          <p className="welcome-footnote">
            <ShieldCheck size={14} /> 推荐 Chrome / Edge；数据只保存在本机，不会上传。
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
            <FileJson2 size={14} /> 本机备份
          </div>
          <div className="preview-badge preview-badge--two">
            <Sparkles size={14} /> 轻透背景
          </div>
          <p className="preview-caption">
            课表、清单、考试，一页看完。
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
