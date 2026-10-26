import type { ButtonHTMLAttributes, ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import { Loader2 } from 'lucide-react';

export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}

export function Spinner({ size = 16, className }: { size?: number; className?: string }) {
  return <Loader2 className={cx('spinner', className)} size={size} aria-hidden="true" />;
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'soft' | 'ghost' | 'danger';
  icon?: LucideIcon;
  loading?: boolean;
};

export function Button({
  variant = 'soft',
  icon: Icon,
  loading = false,
  className,
  children,
  disabled,
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      className={cx('btn', `btn--${variant}`, className)}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Spinner size={16} /> : Icon ? <Icon size={16} /> : null}
      {children ? <span>{children}</span> : null}
    </button>
  );
}

export function IconButton({
  icon: Icon,
  label,
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: LucideIcon;
  label: string;
}) {
  return (
    <button
      type="button"
      className={cx('icon-btn', className)}
      title={label}
      aria-label={label}
      {...rest}
    >
      <Icon size={19} />
      {children}
    </button>
  );
}

export function SectionHead({
  icon: Icon,
  title,
  count,
  action,
}: {
  icon?: LucideIcon;
  title: string;
  count?: number;
  action?: ReactNode;
}) {
  return (
    <div className="section-head">
      <div className="section-title">
        {Icon ? (
          <span className="section-icon">
            <Icon size={16} />
          </span>
        ) : null}
        <h2>{title}</h2>
        {typeof count === 'number' ? <span className="count-badge">{count}</span> : null}
      </div>
      {action ? <div className="section-actions">{action}</div> : null}
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  text,
  action,
  className,
}: {
  icon?: LucideIcon;
  title: string;
  text?: string;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cx('empty', className)}>
      {Icon ? (
        <span className="empty-icon">
          <Icon size={22} />
        </span>
      ) : null}
      <p className="empty-title">{title}</p>
      {text ? <p className="empty-text">{text}</p> : null}
      {action ? <div className="empty-action">{action}</div> : null}
    </div>
  );
}

export function Chip({
  children,
  color,
  ink,
  className,
}: {
  children: ReactNode;
  color?: string;
  ink?: string;
  className?: string;
}) {
  return (
    <span
      className={cx('chip', className)}
      style={color ? ({ '--chip-bg': color, '--chip-ink': ink || '#4A524D' } as never) : undefined}
    >
      {children}
    </span>
  );
}
