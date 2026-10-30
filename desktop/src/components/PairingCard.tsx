import { useEffect, useRef, useState } from 'react';
import { Check, Copy, RefreshCw, Smartphone } from 'lucide-react';
import { useAppStore } from '../core/store';
import { persistPasscode } from '../core/storage';
import { requestPairCode } from '../core/sync';
import { Button, Spinner, cx } from './ui';

/**
 * 配对卡：**电脑发起、手机确认**。
 *
 * 地址已知时挂载即发一条带 DH 公钥的配对请求，手机那边会弹出确认卡。
 * 电脑这边把「自己算出来的」6 位配对码显示出来，用户拿它跟手机屏幕核对 ——
 * 一致才点「允许」。
 *
 * 6 位码不画成图片：它必须**两端各自算出、由人当场核对** ——
 * 画成可被机器读取的图形，等于让「对面报上来的值」冒充校验。
 */
export function PairingCard({
  compact = false,
  onChangeAddress,
}: {
  compact?: boolean;
  onChangeAddress?: () => void;
}) {
  const state = useAppStore();
  const pair = state.pair;
  const [copied, setCopied] = useState(false);
  const [passcode, setPasscode] = useState(state.passcode || '');
  const autoRequested = useRef(false);
  const request = requestPairCode;
  const pushToast = state.pushToast;

  /**
   * 配对码应由这台电脑主动生成：手机只负责确认。
   *
   * 地址已经记住时（刷新、回到这一页、从同步面板打开），不用再让用户点一次
   * 「开始配对」；进页面就把请求发出去。请求失败会落到 error 状态，
   * 不会在这里循环重试。
   */
  useEffect(() => {
    if (autoRequested.current) return;
    if (!state.base || state.secret) return;
    if (pair.status !== 'idle') return;
    autoRequested.current = true;
    void request(false);
  }, [state.base, state.secret, pair.status, request]);

  if (pair.status === 'approved') {
    return (
      <div className="pair-card pair-card--approved glass-soft">
        <span className="pair-success-icon">
          <Check size={22} strokeWidth={3} />
        </span>
        <div>
          <h3>配对成功</h3>
          <p>正在把手机上的数据同步过来…</p>
        </div>
      </div>
    );
  }

  const waiting = pair.status === 'waiting' && !!pair.requestId;
  /**
   * 配对码（协议 v3.2：双方公钥的转录哈希）。
   *
   * **一开始是没有的** —— 码要等**手机那边的公钥**到了才算得出来
   * （H(A‖B) 里 B 是手机的公钥）。所以刚发请求时只有占位文案，
   * 拿到手机公钥之后才把 6 位码亮出来让用户核对。
   */
  const code = pair.code || '';

  const copy = async () => {
    if (!state.base) return;
    try {
      await navigator.clipboard.writeText(state.base);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {
      pushToast('复制失败，请手动选中地址。', 'error');
    }
  };

  return (
    <div className={cx('pair-card', compact && 'is-compact')}>
      {waiting ? (
        <>
          <div className="pair-intro">
            <span className="pair-step">02</span>
            <div>
              <h3>在手机上点「允许」</h3>
              <p>
                打开手机上的 MiuList → 更多 → 网页同步，核对下面的配对码后点「允许」。
              </p>
            </div>
          </div>

          <div className="pair-waiting">
            <Spinner size={22} />
            <p>等手机上点「允许」…</p>
            <p className="pair-sub">手机上没有确认卡？确认手机上的「网页同步」已经打开。</p>
          </div>
          {code ? (
            <div className="pair-code-row">
              <span>配对码</span>
              <strong>{code}</strong>
              <span className="pair-code-hint">和手机上显示的一致即可</span>
            </div>
          ) : (
            // **码还没算出来**：协议 v3.2 的码是「双方公钥」的哈希，
            // 手机那边的公钥要等 `/api/pair/status` 回包才有。
            // 这时候别摆一个空框 —— 说清楚在等什么。
            <div className="pair-code-row pair-code-row--pending">
              <span>配对码</span>
              <strong>等待手机回应…</strong>
              <span className="pair-code-hint">手机回应后就会显示在这里</span>
            </div>
          )}
          {!state.secret ? (
            <label className="field pair-passcode">
              <span>同步密码（和手机上一致）</span>
              <input
                value={passcode}
                onChange={(event) => {
                  const digits = event.target.value.replace(/\D/g, '').slice(0, 4);
                  setPasscode(digits);
                  // **立刻落盘**：批准的那一刻网页端就用它派生密钥，
                  // 那时用户已经回到别的界面了，没机会再填一次。
                  persistPasscode(digits);
                }}
                inputMode="numeric"
                maxLength={4}
                placeholder="没设就留空"
                autoComplete="off"
              />
            </label>
          ) : null}
          <div className="pair-actions">
            <Button variant="ghost" icon={RefreshCw} onClick={() => void request(true)}>
              换一个配对码
            </Button>
            {onChangeAddress ? (
              <Button variant="ghost" onClick={onChangeAddress}>
                换手机地址
              </Button>
            ) : null}
          </div>
        </>
      ) : pair.status === 'requesting' ? (
        <div className="pair-waiting">
          <Spinner size={22} />
          <p>正在准备配对…</p>
        </div>
      ) : pair.status === 'error' || pair.status === 'expired' || pair.status === 'denied' ? (
        <div className="pair-message is-error">
          <Smartphone size={20} />
          <div>
            <p>{pair.message || '还没有配对成功。'}</p>
            <p className="pair-sub">
              确认手机上的「更多 → 网页同步」已经打开，再试一次。
            </p>
          </div>
        </div>
      ) : (
        <div className="pair-message">
          <Smartphone size={20} />
          <div>
            <p>还没开始配对</p>
            <p className="pair-sub">点下面的按钮，电脑会发一个配对请求到手机。</p>
          </div>
        </div>
      )}

      <div className="pair-address">
        <span className="pair-address-label">手机地址</span>
        <code>{state.base || '还没有填写'}</code>
        <button type="button" className="copy-btn" onClick={() => void copy()} aria-label="复制手机地址">
          {copied ? <Check size={14} /> : <Copy size={14} />}
        </button>
      </div>

      {!waiting && pair.status !== 'requesting' ? (
        <div className="pair-actions">
          {state.base ? (
            <Button variant="primary" icon={RefreshCw} onClick={() => void request(true)}>
              {pair.status === 'idle' ? '开始配对' : '再试一次'}
            </Button>
          ) : null}
          {onChangeAddress ? (
            <Button variant="ghost" onClick={onChangeAddress}>
              换手机地址
            </Button>
          ) : null}
        </div>
      ) : null}

      <p className="pair-footnote">
        数据只在你的设备之间直接传输，<strong>不经过任何服务器</strong>。
      </p>
    </div>
  );
}
