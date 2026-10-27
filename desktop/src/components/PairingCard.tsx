import { useEffect, useRef, useState } from 'react';
import { Check, Copy, QrCode, RefreshCw, ScanLine, Smartphone } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { useAppStore } from '../core/store';
import { persistPasscode } from '../core/storage';
import { requestPairCode } from '../core/sync';
import { Button, Spinner, cx } from './ui';

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
  const openScanner = state.openScanner;
  const pushToast = state.pushToast;

  /**
   * 配对码应由这台电脑主动生成：手机只负责确认。
   *
   * 地址已经记住时（刷新、回到这一页、从同步面板打开），不用再让用户点一次
   * 「生成配对码」；进页面就把码画出来。请求失败会落到 error 状态，
   * 不会在这里循环重试。
   */
  useEffect(() => {
    if (autoRequested.current) return;
    if (!state.base || state.secret) return;
    if (pair.status !== 'idle') return;
    autoRequested.current = true;
    void request(false, pair.mode ?? 'scan');
  }, [state.base, state.secret, pair.status, pair.mode, request]);

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
  const mode = pair.mode ?? 'scan';
  /**
   * 二维码里带的是**公钥**，不是密钥。
   *
   * 密钥是两端各自算出来的（DH 协商），从不过网；公钥本来就是公开的东西，
   * 摆在屏幕上给人扫不泄密。手机上扫到之后会算出同一把密钥。
   */
  const payload = !waiting
    ? ''
    : pair.publicKey
      ? `miulist://pair?v=4&k=${encodeURIComponent(pair.publicKey)}&r=${encodeURIComponent(
          pair.requestId!,
        )}`
      : `miulist://pair?v=3&s=${encodeURIComponent(pair.secret ?? '')}&r=${encodeURIComponent(
          pair.requestId!,
        )}`;
  const code = pair.code ?? '';

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
      <div className="pair-intro">
        <span className="pair-step">02</span>
        <div>
          <h3>{mode === 'scan' ? '用手机扫这张配对码' : '让手机确认一下就能连上'}</h3>
          <p>
            {mode === 'scan' ? (
              <>
                手机相机扫码 → 在 MiuList 里点「允许」。配对方向是手机扫电脑，
                电脑不需要摄像头 —— <strong>全程只扫这一次码</strong>。
              </>
            ) : (
              <>
                不用摄像头：电脑已经把配对请求发给手机了，
                手机上会弹出<strong>一张确认卡</strong>，点「允许」就好。
              </>
            )}
          </p>
        </div>
      </div>

      {waiting ? (
        <>
          {mode === 'scan' ? (
            <div className="pair-qr">
              <QRCodeSVG
                value={payload}
                size={compact ? 184 : 220}
                marginSize={2}
                level="M"
                bgColor="#FFFFFF"
                fgColor="#1E2320"
                title="MiuList 配对二维码"
              />
            </div>
          ) : (
            <div className="pair-waiting">
              <Spinner size={22} />
              <p>等手机上点「允许」…</p>
              <p className="pair-sub">手机上会弹出一张确认卡，上面有同一个配对码。</p>
            </div>
          )}
          <div className="pair-code-row">
            <span>配对码</span>
            <strong>{code}</strong>
            <span className="pair-code-hint">
              {mode === 'scan' ? '手机扫电脑 · 同一 Wi-Fi' : '和手机上显示的一致即可'}
            </span>
          </div>
          {!state.secret ? (
            <label className="field pair-passcode">
              <span>手机上设了同步密码？在这里填同样的 4 位数字</span>
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
            <Button variant="ghost" icon={RefreshCw} onClick={() => void request(true, mode)}>
              换一个配对码
            </Button>
            <Button
              variant="ghost"
              icon={mode === 'scan' ? Smartphone : ScanLine}
              onClick={() => void request(true, mode === 'scan' ? 'link' : 'scan')}
            >
              {mode === 'scan' ? '不扫码，直接让手机确认' : '改用手机扫码'}
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
          <p>正在向手机申请配对码…</p>
        </div>
      ) : pair.status === 'error' || pair.status === 'expired' || pair.status === 'denied' ? (
        <div className="pair-message is-error">
          <Smartphone size={20} />
          <div>
            <p>{pair.message || '还没有配对成功。'}</p>
            <p className="pair-sub">
              确认手机上的「更多 → 网页同步 → 局域网服务」已经打开，再试一次。
            </p>
          </div>
        </div>
      ) : (
        <div className="pair-message">
          <QrCode size={20} />
          <div>
            <p>地址已填好，这台电脑会生成配对码。</p>
            <p className="pair-sub">配对由手机确认，电脑不需要摄像头。</p>
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
            <Button variant="primary" icon={RefreshCw} onClick={() => void request(true, mode)}>
              生成配对码
            </Button>
          ) : (
            <Button variant="primary" icon={ScanLine} onClick={openScanner}>
              扫描手机地址
            </Button>
          )}
          {onChangeAddress ? (
            <Button variant="ghost" onClick={onChangeAddress}>
              换手机地址
            </Button>
          ) : null}
        </div>
      ) : null}

      <p className="pair-footnote">
        配对密钥由两端<strong>各自协商出来，从不过网</strong>；二维码里只有公钥。
        快照文件不会包含密钥。
      </p>
    </div>
  );
}
