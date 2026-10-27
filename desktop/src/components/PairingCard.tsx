import { useEffect, useRef, useState } from 'react';
import { Check, Copy, QrCode, RefreshCw, ScanLine, Smartphone } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { useAppStore } from '../core/store';
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
  const autoRequested = useRef(false);
  const request = requestPairCode;
  const openScanner = state.openScanner;
  const pushToast = state.pushToast;

  /**
   * 配对码应由这台电脑主动生成：手机只负责扫。
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
    void request();
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

  const waiting = pair.status === 'waiting' && pair.requestId && pair.secret;
  const payload = waiting
    ? `miulist://pair?v=3&s=${encodeURIComponent(pair.secret!)}&r=${encodeURIComponent(
        pair.requestId!,
      )}`
    : '';
  const code = waiting ? pair.requestId!.slice(0, 6).toUpperCase() : '';

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
          <h3>用手机扫这张配对码</h3>
          <p>
            手机相机扫码 → 在 MiuList 里点「允许」。配对方向是手机扫电脑，
            电脑不需要摄像头。
          </p>
        </div>
      </div>

      {waiting ? (
        <>
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
          <div className="pair-code-row">
            <span>配对码</span>
            <strong>{code}</strong>
            <span className="pair-code-hint">手机扫电脑 · 同一 Wi-Fi</span>
          </div>
          <div className="pair-actions">
            <Button variant="ghost" icon={RefreshCw} onClick={() => void request()}>
              换一个配对码
            </Button>
            <Button variant="ghost" icon={ScanLine} onClick={openScanner}>
              扫手机地址码
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
            <p className="pair-sub">配对由手机扫电脑完成，电脑不需要摄像头。</p>
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
            <Button variant="primary" icon={RefreshCw} onClick={() => void request()}>
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
        配对密钥只走手机相机扫描，不经过网络；快照文件不会包含它。手机端继续当接口服务端；配对与同步协议不变。
      </p>
    </div>
  );
}
