import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import jsQR from 'jsqr';
import { CameraOff, ImagePlus, ScanLine, X } from 'lucide-react';
import { useAppStore } from '../core/store';
import { connectBase, normalizeBase } from '../core/sync';
import { cx } from './ui';

export function QrScanner() {
  const open = useAppStore((s) => s.scannerOpen);
  const close = useAppStore((s) => s.closeScanner);
  const pushToast = useAppStore((s) => s.pushToast);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<number | null>(null);
  const aliveRef = useRef(false);
  const handledRef = useRef(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  const stopCamera = () => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (streamRef.current) {
      for (const track of streamRef.current.getTracks()) track.stop();
      streamRef.current = null;
    }
    if (videoRef.current) videoRef.current.srcObject = null;
  };

  const processText = (text: string) => {
    if (handledRef.current) return;
    if (text.startsWith('miulist://pair')) {
      setMessage('这是配对码，请扫手机「更多 → 网页同步」里的地址二维码。');
      return;
    }
    const base = normalizeBase(text);
    if (!base) {
      setMessage('没认出有效地址；请对准手机上的地址二维码。');
      return;
    }
    handledRef.current = true;
    stopCamera();
    close();
    void connectBase(base);
  };

  useEffect(() => {
    if (!open) return;
    aliveRef.current = true;
    handledRef.current = false;
    setError('');
    setMessage('');
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    document.addEventListener('keydown', onKey);

    const tick = () => {
      if (!aliveRef.current) return;
      const video = videoRef.current;
      const canvas = canvasRef.current;
      if (video && canvas && video.readyState >= 2 && video.videoWidth) {
        const width = Math.min(640, video.videoWidth);
        const height = Math.round((video.videoHeight || video.videoWidth) * (width / video.videoWidth));
        if (width && height) {
          canvas.width = width;
          canvas.height = height;
          const ctx = canvas.getContext('2d', { willReadFrequently: true });
          if (ctx) {
            ctx.drawImage(video, 0, 0, width, height);
            const image = ctx.getImageData(0, 0, width, height);
            const code = jsQR(image.data, width, height, { inversionAttempts: 'attemptBoth' });
            if (code?.data) {
              processText(code.data);
              return;
            }
          }
        }
      }
      timerRef.current = window.setTimeout(tick, 140);
    };

    const start = async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) {
          throw new Error('这个浏览器没有开放摄像头');
        }
        let stream: MediaStream;
        try {
          stream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } },
            audio: false,
          });
        } catch {
          stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
        }
        if (!aliveRef.current) {
          for (const track of stream.getTracks()) track.stop();
          return;
        }
        streamRef.current = stream;
        const video = videoRef.current;
        if (!video) return;
        video.srcObject = stream;
        await video.play();
        tick();
      } catch (err: any) {
        console.warn('MiuList: 摄像头启动失败', err);
        setError(err?.message || '摄像头不可用');
      }
    };

    void start();

    return () => {
      aliveRef.current = false;
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
      stopCamera();
    };
  }, [open, close]);

  const uploadImage = async (file: File) => {
    setError('');
    setMessage('');
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      const canvas = document.createElement('canvas');
      const scale = Math.min(1, 1400 / Math.max(image.width, image.height));
      canvas.width = Math.max(1, Math.round(image.width * scale));
      canvas.height = Math.max(1, Math.round(image.height * scale));
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      if (!ctx) {
        setMessage('这张图片读不出来。');
        return;
      }
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      const data = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = jsQR(data.data, canvas.width, canvas.height, {
        inversionAttempts: 'attemptBoth',
      });
      URL.revokeObjectURL(url);
      if (code?.data) processText(code.data);
      else setMessage('图片里没有识别到二维码，换一张清晰的截图试试。');
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      setMessage('图片打不开。');
    };
    image.src = url;
  };

  if (!open) return null;

  return createPortal(
    <div className="scanner-backdrop">
      <div className="scanner-panel" role="dialog" aria-modal="true" aria-label="扫描手机二维码">
        <div className="scanner-head">
          <div>
            <h2>扫描手机二维码</h2>
            <p>对准手机「更多 → 网页同步」里的地址码。</p>
          </div>
          <button type="button" className="scanner-close" onClick={close} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        <div className={cx('scanner-stage', error && 'has-error')}>
          <video ref={videoRef} className="scanner-video" playsInline muted autoPlay />
          <canvas ref={canvasRef} className="scanner-canvas" />
          {!error ? (
            <>
              <div className="scanner-frame" aria-hidden="true">
                <span className="corner tl" />
                <span className="corner tr" />
                <span className="corner bl" />
                <span className="corner br" />
                <span className="scanner-line" />
              </div>
              <div className="scanner-hint-chip">
                <ScanLine size={14} /> 摄像头扫描中
              </div>
            </>
          ) : (
            <div className="scanner-error">
              <CameraOff size={28} />
              <strong>摄像头没打开</strong>
              <p>{error}。也可以上传手机二维码截图。</p>
            </div>
          )}
        </div>

        {message ? <p className="scanner-message">{message}</p> : null}

        <div className="scanner-actions">
          <button type="button" className="scanner-upload" onClick={() => inputRef.current?.click()}>
            <ImagePlus size={17} /> 上传二维码截图
          </button>
          <button type="button" className="scanner-cancel" onClick={close}>
            取消
          </button>
          <input
            ref={inputRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) void uploadImage(file);
            }}
          />
        </div>

        <p className="scanner-footnote">
          摄像头只在这一次扫描时使用，不会保存画面。配对码请用手机的摄像头扫电脑上的二维码。
        </p>
      </div>
    </div>,
    document.body,
  );
}
