import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { ArrowRight, Bell, ChevronDown, Gavel, IdCard, Inbox, Radio, Car } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, EmptyState, PageHeader, RuleTag, StatusDot } from '../components/ui'
import { CONTEXTS, SERVICES, routesFor, serviceOf } from '../lib/domain'
import { relativeTime } from '../lib/dates'
import { summarise, useNow } from '../lib/events'
import { useLive } from '../lib/live'
import type { ActivityEvent, ServiceName } from '../lib/types'

const CONTEXT_ICONS: Record<ServiceName, typeof IdCard> = { licence: IdCard, demerit: Gavel, registration: Car, notification: Bell }

export function ActivityPage() {
  const { events, fresh, status } = useLive()
  const [filter, setFilter] = useState<ServiceName | 'all'>('all')
  const now = useNow(5000)
  const counts = useMemo(() => {
    const c = Object.fromEntries(SERVICES.map((s) => [s, 0])) as Record<ServiceName, number>
    events.forEach((e) => c[serviceOf(e.source)]++)
    return c
  }, [events])
  const shown = events.filter((e) => filter === 'all' || serviceOf(e.source) === filter).slice(0, 80)
  const live = status === 'live' || status === 'mock'

  return (
    <div>
      <PageHeader
        title="Live activity"
        description="Every domain event on the licsys-bus, as it happens. Streamed over SSE from the notification service's activity inbox."
        actions={
          <span className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-600 shadow-sm">
            <StatusDot status={live ? 'UP' : 'DOWN'} />
            {live ? (status === 'mock' ? 'Live (mock bus)' : 'Live') : 'Stream disconnected'}
            <span className="font-mono text-slate-400">/notification/api/activity/stream</span>
          </span>
        }
      />

      <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-4">
        {SERVICES.map((s) => {
          const Icon = CONTEXT_ICONS[s]
          const active = filter === s
          return (
            <button
              key={s}
              onClick={() => setFilter(active ? 'all' : s)}
              className={clsx('card group flex items-center gap-3 p-4 text-left transition hover:-translate-y-px hover:shadow-pop', active && 'ring-2 ring-offset-1', active && CONTEXTS[s].ring)}
            >
              <div className="flex h-9 w-9 items-center justify-center rounded-lg" style={{ background: `${CONTEXTS[s].color}14`, color: CONTEXTS[s].color }}>
                <Icon className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <div className="truncate text-xs text-slate-500">{CONTEXTS[s].title}</div>
                <motion.div key={counts[s]} initial={{ y: -4, opacity: 0.4 }} animate={{ y: 0, opacity: 1 }} className="text-xl font-semibold tabular-nums text-slate-900">
                  {counts[s]}
                </motion.div>
              </div>
              <span className="ml-auto text-[10px] font-medium uppercase tracking-wider text-slate-400 opacity-0 transition group-hover:opacity-100">{active ? 'clear' : 'filter'}</span>
            </button>
          )
        })}
      </div>

      <Card className="overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3">
          <div className="flex items-center gap-2 text-[13px] font-semibold text-slate-900">
            <Radio className="h-4 w-4 text-slate-400" /> Event stream
            {filter !== 'all' && (
              <button onClick={() => setFilter('all')} className="ml-1 rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium capitalize text-slate-600 hover:bg-slate-200">
                {filter} ×
              </button>
            )}
          </div>
          <span className="font-mono text-[11px] text-slate-400">{events.length} events · newest first</span>
        </div>
        {!shown.length && (
          <EmptyState icon={<Inbox className="h-5 w-5" />} title="Waiting for events">
            Record an offence on the <Link className="text-indigo-600" to="/drivers">Driver 360</Link> page or quote a renewal to see events flow.
          </EmptyState>
        )}
        <ul className="divide-y divide-slate-100">
          <AnimatePresence initial={false}>
            {shown.map((e) => (
              <EventRow key={e.eventId} event={e} isNew={fresh.has(e.eventId)} now={now} />
            ))}
          </AnimatePresence>
        </ul>
      </Card>
    </div>
  )
}

export function EventRow({ event, isNew, now, compact }: { event: ActivityEvent; isNew?: boolean; now: number; compact?: boolean }) {
  const [open, setOpen] = useState(false)
  const svc = serviceOf(event.source)
  const ctx = CONTEXTS[svc]
  const Icon = CONTEXT_ICONS[svc]
  const routes = routesFor(event.detailType)
  const rule = (event.detail as { rule?: string }).rule
  const driverId = (event.detail as { driverId?: string }).driverId
  return (
    <motion.li
      layout
      initial={{ opacity: 0, y: -12, scale: 0.99 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={{ opacity: 0 }}
      transition={{ type: 'spring', stiffness: 380, damping: 32 }}
      className="relative"
    >
      {isNew && (
        <motion.span
          className="pointer-events-none absolute inset-0"
          initial={{ backgroundColor: `${ctx.color}1f` }}
          animate={{ backgroundColor: `${ctx.color}00` }}
          transition={{ duration: 2.2 }}
        />
      )}
      <span className="absolute inset-y-0 left-0 w-[3px]" style={{ background: ctx.color }} />
      <div className={clsx('relative flex items-start gap-3.5 pl-5 pr-4', compact ? 'py-2.5' : 'py-3.5')}>
        <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg" style={{ background: `${ctx.color}14`, color: ctx.color }}>
          <Icon className="h-4 w-4" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-[13px] font-semibold text-slate-900">{event.detailType}</span>
            {rule && <RuleTag rule={rule} />}
            {isNew && <span className="rounded-full bg-indigo-600 px-1.5 py-px text-[9px] font-semibold uppercase tracking-wider text-white">new</span>}
          </div>
          <p className="mt-0.5 truncate text-[13px] text-slate-600">
            {summarise(event)}
            {driverId && !compact && (
              <Link to={`/drivers/${driverId}`} className="ml-2 text-xs font-medium text-indigo-600 hover:underline">
                open
              </Link>
            )}
          </p>
          {!compact && (
            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px]">
              <span className="rounded-md px-1.5 py-0.5 font-mono font-medium" style={{ background: `${ctx.color}14`, color: ctx.color }}>
                {event.source}
              </span>
              <ArrowRight className="h-3 w-3 text-slate-300" />
              <span className="rounded-md bg-slate-900 px-1.5 py-0.5 font-mono font-medium text-white">licsys-bus</span>
              <ArrowRight className="h-3 w-3 text-slate-300" />
              {routes.map((r) => (
                <span key={r.queue} className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-1.5 py-0.5 font-mono text-slate-600">
                  {r.queue}
                  <ArrowRight className="h-2.5 w-2.5 text-slate-300" />
                  <span className="font-semibold" style={{ color: CONTEXTS[r.consumer].color }}>
                    {r.consumer}
                  </span>
                </span>
              ))}
            </div>
          )}
          <AnimatePresence>
            {open && (
              <motion.pre
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="scrollbar-thin mt-2.5 overflow-auto rounded-lg bg-ink-950 p-3 font-mono text-[11px] leading-relaxed text-slate-300"
              >
                {JSON.stringify({ eventId: event.eventId, source: event.source, 'detail-type': event.detailType, detail: event.detail }, null, 2)}
              </motion.pre>
            )}
          </AnimatePresence>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <span className="text-[11px] tabular-nums text-slate-400" title={event.time}>
            {relativeTime(event.time, now)}
          </span>
          {!compact && (
            <button onClick={() => setOpen((o) => !o)} className="inline-flex items-center gap-0.5 rounded px-1 text-[11px] text-slate-400 hover:bg-slate-100 hover:text-slate-700">
              JSON <ChevronDown className={clsx('h-3 w-3 transition', open && 'rotate-180')} />
            </button>
          )}
        </div>
      </div>
    </motion.li>
  )
}
