import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { HelpCircle, Loader2, Scale } from 'lucide-react'
import { useEffect, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { LicenceClass } from '../lib/types'

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={clsx('card', className)}>{children}</div>
}

export function CardHeader({ title, subtitle, icon, action }: { title: ReactNode; subtitle?: ReactNode; icon?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
      <div className="flex items-center gap-2.5">
        {icon && <span className="text-slate-400">{icon}</span>}
        <div>
          <h3 className="text-[13px] font-semibold text-slate-900">{title}</h3>
          {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
        </div>
      </div>
      {action}
    </div>
  )
}

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
export function Button({
  variant = 'secondary',
  size = 'md',
  loading,
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md'; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={rest.disabled || loading}
      className={clsx(
        'inline-flex select-none items-center justify-center gap-1.5 rounded-lg font-medium transition active:scale-[.98] disabled:cursor-not-allowed disabled:opacity-60',
        size === 'sm' ? 'h-7 px-2.5 text-xs' : 'h-9 px-3.5 text-sm',
        variant === 'primary' && 'bg-indigo-600 text-white shadow-sm shadow-indigo-600/20 hover:bg-indigo-500',
        variant === 'secondary' && 'border border-slate-200 bg-white text-slate-700 shadow-sm hover:bg-slate-50 hover:text-slate-900',
        variant === 'ghost' && 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
        variant === 'danger' && 'bg-rose-600 text-white hover:bg-rose-500',
        className,
      )}
    >
      {loading && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
      {children}
    </button>
  )
}

const CLASS_STYLES: Record<string, string> = {
  LN: 'bg-slate-100 text-slate-700 ring-slate-200',
  P1: 'bg-rose-50 text-rose-700 ring-rose-200',
  P2: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
  OP: 'bg-indigo-50 text-indigo-700 ring-indigo-200',
}

export function ClassBadge({ cls, size = 'sm' }: { cls: LicenceClass | string; size?: 'sm' | 'lg' }) {
  return (
    <span
      className={clsx(
        'inline-flex items-center justify-center rounded-md font-semibold ring-1 ring-inset',
        size === 'lg' ? 'h-8 min-w-10 px-2 text-sm' : 'h-5 min-w-7 px-1.5 text-[11px]',
        CLASS_STYLES[cls] ?? CLASS_STYLES.LN,
      )}
    >
      {cls}
    </span>
  )
}

export function Pill({ children, tone = 'slate', className }: { children: ReactNode; tone?: 'slate' | 'green' | 'red' | 'amber' | 'indigo' | 'sky'; className?: string }) {
  const tones = {
    slate: 'bg-slate-100 text-slate-600',
    green: 'bg-emerald-50 text-emerald-700',
    red: 'bg-rose-50 text-rose-700',
    amber: 'bg-amber-50 text-amber-700',
    indigo: 'bg-indigo-50 text-indigo-700',
    sky: 'bg-sky-50 text-sky-700',
  }
  return <span className={clsx('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium', tones[tone], className)}>{children}</span>
}

export function RuleTag({ rule }: { rule: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-slate-900 px-1.5 py-0.5 font-mono text-[10px] font-medium text-white">
      <Scale className="h-2.5 w-2.5" />
      {rule}
    </span>
  )
}

/** "Why?" affordance that explains a decision with its rule and plain-English reason from the API. */
export function Why({ rule, reason, title, align = 'left', label = 'Why?' }: { rule: string; reason: string; title?: string; align?: 'left' | 'right'; label?: string }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLSpanElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])
  return (
    <span ref={ref} className="relative inline-flex">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className={clsx(
          'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium transition',
          open ? 'bg-indigo-600 text-white' : 'text-indigo-600 hover:bg-indigo-50',
        )}
      >
        <HelpCircle className="h-3 w-3" />
        {label}
      </button>
      <AnimatePresence>
        {open && (
          <motion.div
            role="dialog"
            initial={{ opacity: 0, y: -4, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.98 }}
            transition={{ duration: 0.14 }}
            className={clsx(
              'absolute top-full z-40 mt-2 w-80 rounded-xl border border-slate-200 bg-white p-4 text-left shadow-pop',
              align === 'right' ? 'right-0' : 'left-0',
            )}
          >
            <div className="mb-2 flex items-center justify-between gap-2">
              <span className="text-xs font-semibold text-slate-900">{title ?? 'How this was decided'}</span>
              <RuleTag rule={rule} />
            </div>
            <p className="text-[13px] leading-relaxed text-slate-600">{reason}</p>
            <p className="mt-3 border-t border-slate-100 pt-2 text-[11px] text-slate-400">Source: policy/BUSINESS-RULES.md · returned by the API</p>
          </motion.div>
        )}
      </AnimatePresence>
    </span>
  )
}

export function Skeleton({ className }: { className?: string }) {
  return (
    <div className={clsx('relative overflow-hidden rounded-md bg-slate-100', className)}>
      <div className="absolute inset-0 -translate-x-full animate-shimmer bg-gradient-to-r from-transparent via-white/70 to-transparent" />
    </div>
  )
}

export function StatusDot({ status, size = 8 }: { status: 'UP' | 'DOWN' | 'UNKNOWN'; size?: number }) {
  const color = status === 'UP' ? 'bg-emerald-500' : status === 'DOWN' ? 'bg-rose-500' : 'bg-slate-400'
  return (
    <span className="relative inline-flex" style={{ width: size, height: size }}>
      {status === 'UP' && <span className={clsx('absolute inset-0 rounded-full opacity-60 animate-pulse-ring', color)} />}
      <span className={clsx('relative inline-flex rounded-full', color)} style={{ width: size, height: size }} />
    </span>
  )
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-slate-100 text-slate-400">{icon}</div>
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {children && <div className="mt-1 max-w-sm text-xs text-slate-500">{children}</div>}
    </div>
  )
}

export function PageHeader({ title, description, actions, eyebrow }: { title: string; description?: ReactNode; actions?: ReactNode; eyebrow?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        {eyebrow && <div className="mb-1.5">{eyebrow}</div>}
        <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  )
}
