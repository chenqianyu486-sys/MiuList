import { AlertTriangle, CheckCircle2, Info, X } from 'lucide-react';
import { useAppStore } from '../core/store';

export function Toast() {
  const toast = useAppStore((state) => state.toast);
  const dismiss = useAppStore((state) => state.dismissToast);
  if (!toast) return null;
  const Icon =
    toast.kind === 'error' ? AlertTriangle : toast.kind === 'success' ? CheckCircle2 : Info;
  return (
    <div className={`toast toast--${toast.kind}`} role={toast.kind === 'error' ? 'alert' : 'status'}>
      <Icon size={18} className="toast-icon" />
      <span className="toast-message">{toast.message}</span>
      <button
        type="button"
        className="toast-close"
        aria-label="关闭提示"
        onClick={() => dismiss(toast.id)}
      >
        <X size={14} />
      </button>
    </div>
  );
}
