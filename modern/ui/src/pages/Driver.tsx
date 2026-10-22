import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { AlertTriangle, ArrowRight, Bell, CalendarClock, CheckCircle2, Flame, History, IdCard, Search, ShieldCheck, Sparkles, UserX } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { RecordOffence } from '../components/RecordOffence'
import { PointsGauge } from '../components/PointsGauge'
import { Card, CardHeader, ClassBadge, EmptyState, Pill, RuleTag, Skeleton, Why } from '../components/ui'
import { api } from '../lib/api'
import { CLASS_NAMES, NEXT_CLASS, OFFENCE_CLASSES, OFFENCE_LABELS, displayName } from '../lib/domain'
import { addDays, age, formatDate, todayIso } from '../lib/dates'
import { useLive } from '../lib/live'
import { useDrivers } from '../lib/useDrivers'
import { useParity } from '../lib/useParity'
import type { Demerits, Driver, Notice, OffenceResult, Progression } from '../lib/types'

const DEFAULT_DRIVER = 'D0000009'

const initials = (name: string) =>
  name
    .split(/\s+/)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()

const AVATAR_TONES = ['from-indigo-400 to-violet-500', 'from-sky-400 to-indigo-500', 'from-emerald-400 to-teal-500', 'from-amber-400 to-orange-500', 'from-rose-400 to-pink-500']
const tone = (id: string) => AVATAR_TONES[Number(id.replace(/\D/g, '')) % AVATAR_TONES.length]

function Avatar({ driver, size = 'sm' }: { driver: Pick<Driver, 'driverId' | 'name'>; size?: 'sm' | 'lg' }) {
  return (
    <div
      className={clsx(
        'flex shrink-0 items-center justify-center rounded-full bg-gradient-to-br font-semibold text-white',
        tone(driver.driverId),
        size === 'lg' ? 'h-14 w-14 text-lg shadow-lg shadow-indigo-500/20' : 'h-8 w-8 text-[11px]',
      )}
    >
      {initials(driver.name)}
    </div>
  )
}

interface Profile {
  driver?: Driver
  progression?: Progression
  demerits?: Demerits
  notices?: Notice[]
  errors: Partial<Record<'driver' | 'progression' | 'demerits' | 'notices', string>>
}

function useProfile(driverId: string) {
  const { version, subscribe } = useLive()
  const [profile, setProfile] = useState<Profile | null>(null)
  const [tick, setTick] = useState(0)
  const refresh = useCallback(() => setTick((t) => t + 1), [])

  useEffect(() => {
    let cancelled = false
    const asOf = todayIso()
    Promise.allSettled([api.driver(driverId), api.progression(driverId, asOf), api.demerits(driverId), api.notices(driverId)]).then(([d, p, m, n]) => {
      if (cancelled) return
      const err = (r: PromiseSettledResult<unknown>) => (r.status === 'rejected' ? (r.reason as Error).message : undefined)
      setProfile({
        driver: d.status === 'fulfilled' ? d.value : undefined,
        progression: p.status === 'fulfilled' ? p.value : undefined,
        demerits: m.status === 'fulfilled' ? m.value : undefined,
        notices: n.status === 'fulfilled' ? n.value : undefined,
        errors: { driver: err(d), progression: err(p), demerits: err(m), notices: err(n) },
      })
    })
    return () => {
      cancelled = true
    }
  }, [driverId, version, tick])

  useEffect(() => setProfile(null), [driverId])

  useEffect(
    () =>
      subscribe((e) => {
        if ((e.detail as { driverId?: string }).driverId === driverId) setTimeout(refresh, 150)
      }),
    [subscribe, driverId, refresh],
  )

  return { profile, setProfile, refresh }
}

export function DriverPage() {
  const { driverId: param } = useParams()
  const driverId = (param ?? DEFAULT_DRIVER).toUpperCase()
  const navigate = useNavigate()
  const { drivers, error: listError } = useDrivers()
  const [q, setQ] = useState('')
  const { profile, setProfile } = useProfile(driverId)
  const { report } = useParity()
  const parity = report?.drivers.find((d) => d.driverId === driverId)

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase()
    return (drivers ?? []).filter((d) => !t || d.driverId.toLowerCase().includes(t) || d.name.toLowerCase().includes(t))
  }, [drivers, q])

  function onRecorded(r: OffenceResult) {
    setProfile((p) =>
      p && p.demerits
        ? {
            ...p,
            driver: p.driver ? { ...p.driver, demeritPoints: r.totalPoints } : p.driver,
            demerits: {
              ...p.demerits,
              points: r.totalPoints,
              limit: r.limit,
              suspensionNotice: r.suspensionNotice || p.demerits.suspensionNotice,
              offences: [...p.demerits.offences, r],
            },
          }
        : p,
    )
  }

  const d = profile?.driver
  const notFound = profile && !d

  return (
    <div className="grid gap-6 xl:grid-cols-[280px_minmax(0,1fr)]">
      <aside className="xl:sticky xl:top-20 xl:self-start">
        <Card className="overflow-hidden">
          <div className="border-b border-slate-100 p-3">
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
              <input
                className="input pl-8"
                placeholder="Driver number or name"
                value={q}
                onChange={(e) => setQ(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key !== 'Enter') return
                  const exact = q.trim().toUpperCase()
                  navigate(`/drivers/${filtered[0]?.driverId ?? exact}`)
                }}
              />
            </div>
          </div>
          <ul className="scrollbar-thin max-h-[calc(100vh-220px)] overflow-y-auto p-1.5">
            {!drivers && !listError && Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="m-1.5 h-10" />)}
            {listError && <li className="p-4 text-xs text-rose-600">Licence service unavailable: {listError}</li>}
            {filtered.map((dr) => {
              const active = dr.driverId === driverId
              return (
                <li key={dr.driverId}>
                  <Link
                    to={`/drivers/${dr.driverId}`}
                    className={clsx('relative flex items-center gap-3 rounded-lg px-2.5 py-2 transition', active ? 'bg-indigo-50/80' : 'hover:bg-slate-50')}
                  >
                    {active && <motion.span layoutId="driver-active" className="absolute inset-y-2 left-0 w-0.5 rounded-full bg-indigo-500" />}
                    <Avatar driver={dr} />
                    <div className="min-w-0 flex-1">
                      <div className={clsx('truncate text-[13px] font-medium', active ? 'text-indigo-900' : 'text-slate-800')}>{displayName(dr.name)}</div>
                      <div className="font-mono text-[11px] text-slate-400">{dr.driverId}</div>
                    </div>
                    <div className="flex flex-col items-end gap-1">
                      <ClassBadge cls={dr.licenceClass} />
                      <span className="font-mono text-[10px] text-slate-400">{dr.demeritPoints} pts</span>
                    </div>
                  </Link>
                </li>
              )
            })}
            {drivers && !filtered.length && <li className="p-4 text-center text-xs text-slate-500">No match. Press Enter to open {q.toUpperCase()}.</li>}
          </ul>
        </Card>
      </aside>

      <section className="min-w-0">
        {!profile && <ProfileSkeleton />}
        {notFound && (
          <Card>
            <EmptyState icon={<UserX className="h-5 w-5" />} title={`No driver ${driverId}`}>
              {profile.errors.driver}
            </EmptyState>
          </Card>
        )}
        {d && profile && (
          <motion.div key={driverId} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }} className="space-y-5">
            <ProfileHeader driver={d} demerits={profile.demerits} notices={profile.notices} />
            {parity && parity.status !== 'MATCH' && <ParityCallout parity={parity} />}
            <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
              <LicenceCard driver={d} progression={profile.progression} error={profile.errors.progression} />
              <PointsCard demerits={profile.demerits} error={profile.errors.demerits} />
            </div>
            <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
              <OffenceTimeline demerits={profile.demerits} error={profile.errors.demerits} />
              <div className="space-y-5">
                <RecordOffence driverId={driverId} onRecorded={onRecorded} />
                <NoticesCard notices={profile.notices} error={profile.errors.notices} />
              </div>
            </div>
          </motion.div>
        )}
      </section>
    </div>
  )
}

function ProfileSkeleton() {
  return (
    <div className="space-y-5">
      <Skeleton className="h-24" />
      <div className="grid gap-5 lg:grid-cols-2">
        <Skeleton className="h-72" />
        <Skeleton className="h-72" />
      </div>
      <Skeleton className="h-64" />
    </div>
  )
}

function ProfileHeader({ driver, demerits, notices }: { driver: Driver; demerits?: Demerits; notices?: Notice[] }) {
  const suspended = demerits?.suspensionNotice || notices?.some((n) => n.type === 'SUSPENSION')
  return (
    <Card className="flex flex-wrap items-center gap-5 p-5">
      <Avatar driver={driver} size="lg" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2.5">
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">{displayName(driver.name)}</h1>
          <span className="rounded-md bg-slate-100 px-1.5 py-0.5 font-mono text-xs text-slate-600">{driver.driverId}</span>
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-[13px] text-slate-500">
          <span>
            Born {formatDate(driver.dob)} · {age(driver.dob, todayIso())} yrs
          </span>
          <span>{CLASS_NAMES[driver.licenceClass]} licence</span>
          {driver.suspendedDays > 0 && <span>{driver.suspendedDays} suspended days on record</span>}
        </div>
      </div>
      <div className="flex items-center gap-2">
        {suspended ? (
          <Pill tone="red" className="px-2.5 py-1 text-xs">
            <AlertTriangle className="h-3.5 w-3.5" /> Suspension notice
          </Pill>
        ) : (
          <Pill tone="green" className="px-2.5 py-1 text-xs">
            <ShieldCheck className="h-3.5 w-3.5" /> In good standing
          </Pill>
        )}
        <ClassBadge cls={driver.licenceClass} size="lg" />
      </div>
    </Card>
  )
}

function ParityCallout({ parity }: { parity: NonNullable<ReturnType<typeof useParity>['report']>['drivers'][number] }) {
  const { legacy, modern } = parity
  return (
    <motion.div initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} className="flex flex-wrap items-start gap-3 rounded-xl border border-indigo-200 bg-gradient-to-r from-indigo-50 to-white p-4">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-600 text-white">
        <Sparkles className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-indigo-950">Policy fix vs. the legacy batch</span>
          <RuleTag rule={parity.rule} />
        </div>
        <p className="mt-1 text-[13px] leading-relaxed text-indigo-900/80">{parity.reason}</p>
      </div>
      <div className="flex items-center gap-2 self-center rounded-lg border border-indigo-100 bg-white px-3 py-2 text-xs">
        <span className="text-slate-400 line-through">
          {legacy.licenceClass} · {legacy.points} pts{legacy.suspensionNotice ? ' · notice' : ''}
        </span>
        <ArrowRight className="h-3.5 w-3.5 text-indigo-400" />
        <span className="font-semibold text-indigo-700">
          {modern.licenceClass} · {modern.points} pts{modern.suspensionNotice ? ' · notice' : ''}
        </span>
      </div>
    </motion.div>
  )
}

function LicenceCard({ driver, progression, error }: { driver: Driver; progression?: Progression; error?: string }) {
  const next = NEXT_CLASS[driver.licenceClass]
  const required = progression?.requiredDays ?? null
  const pct = required ? Math.min(100, (progression!.tenureDays / required) * 100) : 100
  const remaining = required ? Math.max(0, required - progression!.tenureDays) : 0
  return (
    <Card className="overflow-hidden">
      <CardHeader title="Licence" subtitle="licence-service · BR-01 to BR-03" icon={<IdCard className="h-4 w-4" />} />
      <div className="p-5">
        <div className="relative overflow-hidden rounded-xl bg-gradient-to-br from-ink-900 via-ink-800 to-indigo-950 p-5 text-white shadow-lg">
          <div className="pointer-events-none absolute -right-10 -top-16 h-48 w-48 rounded-full bg-indigo-500/25 blur-3xl" />
          <div className="pointer-events-none absolute -bottom-20 left-10 h-40 w-40 rounded-full bg-sky-400/10 blur-3xl" />
          <div className="relative flex items-start justify-between">
            <div>
              <div className="text-[10px] font-medium uppercase tracking-[0.16em] text-indigo-200/70">Driver licence</div>
              <div className="mt-1 text-lg font-semibold tracking-tight">{displayName(driver.name)}</div>
              <div className="font-mono text-xs text-slate-300">{driver.driverId}</div>
            </div>
            <div className="text-right">
              <div className="text-3xl font-semibold tracking-tight">{driver.licenceClass}</div>
              <div className="text-[11px] text-indigo-200/80">{CLASS_NAMES[driver.licenceClass]}</div>
            </div>
          </div>
          <div className="relative mt-5 grid grid-cols-3 gap-3 text-[11px]">
            <div>
              <div className="text-slate-400">Class issued</div>
              <div className="mt-0.5 font-medium">{formatDate(driver.classIssuedDate)}</div>
            </div>
            <div>
              <div className="text-slate-400">P1 issued</div>
              <div className="mt-0.5 font-medium">{formatDate(driver.p1IssuedDate)}</div>
            </div>
            <div>
              <div className="text-slate-400">Suspended days</div>
              <div className="mt-0.5 font-medium">{driver.suspendedDays}</div>
            </div>
          </div>
        </div>

        <div className="mt-5">
          {error && <p className="text-xs text-rose-600">Progression unavailable: {error}</p>}
          {progression && (
            <>
              <div className="mb-2 flex items-center justify-between gap-2">
                <div className="flex items-center gap-2 text-[13px] font-medium text-slate-700">
                  {next && driver.licenceClass !== 'LN' ? (
                    <>
                      <ClassBadge cls={driver.licenceClass} />
                      <ArrowRight className="h-3 w-3 text-slate-400" />
                      <ClassBadge cls={next} />
                      <span className="ml-1">Tenure to {CLASS_NAMES[next]}</span>
                    </>
                  ) : (
                    <span>Highest class reached</span>
                  )}
                </div>
                <Why rule={progression.rule} reason={progression.reason} align="right" title="Progression check" />
              </div>
              {required ? (
                <>
                  <div className="relative h-2.5 overflow-hidden rounded-full bg-slate-100">
                    <motion.div
                      className={clsx('h-full rounded-full', progression.eligible ? 'bg-emerald-500' : 'bg-gradient-to-r from-indigo-500 to-violet-500')}
                      initial={{ width: 0 }}
                      animate={{ width: `${pct}%` }}
                      transition={{ duration: 0.9, ease: [0.2, 0.7, 0.2, 1] }}
                    />
                  </div>
                  <div className="mt-2 flex items-center justify-between text-xs text-slate-500">
                    <span className="tabular-nums">
                      <span className="font-semibold text-slate-800">{progression.tenureDays}</span> / {required} days
                    </span>
                    {progression.eligible ? (
                      <span className="inline-flex items-center gap-1 font-medium text-emerald-600">
                        <CheckCircle2 className="h-3.5 w-3.5" /> Eligible on next sweep
                      </span>
                    ) : remaining > 0 ? (
                      <span className="inline-flex items-center gap-1">
                        <CalendarClock className="h-3.5 w-3.5" /> {remaining} days to go · ~{formatDate(addDays(todayIso(), remaining))}
                      </span>
                    ) : (
                      <span className="font-medium text-amber-600">Tenure met, blocked by points</span>
                    )}
                  </div>
                </>
              ) : (
                <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">{progression.reason}</p>
              )}
            </>
          )}
        </div>

        {driver.history.length > 0 && (
          <div className="mt-5 border-t border-slate-100 pt-4">
            <div className="label mb-2 flex items-center gap-1.5">
              <History className="h-3 w-3" /> Class history
            </div>
            <ul className="space-y-1.5">
              {driver.history.map((h, i) => (
                <li key={i} className="flex items-center gap-2 text-xs text-slate-600">
                  <ClassBadge cls={h.fromClass} />
                  <ArrowRight className="h-3 w-3 text-slate-300" />
                  <ClassBadge cls={h.toClass} />
                  <span>{formatDate(h.effectiveDate)}</span>
                  <span className="ml-auto">
                    <Why rule={h.rule} reason={h.reason} align="right" title="Why it progressed" />
                  </span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Card>
  )
}

function PointsCard({ demerits, error }: { demerits?: Demerits; error?: string }) {
  return (
    <Card>
      <CardHeader title="Demerit points" subtitle="demerit-service · BR-04" icon={<Flame className="h-4 w-4" />} />
      <div className="flex flex-col items-center p-5">
        {error && <p className="text-xs text-rose-600">Demerit service unavailable: {error}</p>}
        {demerits && (
          <>
            <PointsGauge points={demerits.points} limit={demerits.limit} />
            <div className="mt-2 w-full space-y-2">
              <div className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2 text-xs">
                <span className="text-slate-500">Limit for {demerits.licenceClass}</span>
                <span className="font-semibold tabular-nums text-slate-800">{demerits.limit} points</span>
              </div>
              <AnimatePresence mode="wait">
                {demerits.points >= demerits.limit ? (
                  <motion.div key="over" initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }} className="flex items-center justify-between gap-2 rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700">
                    <span className="flex items-center gap-1.5 font-medium">
                      <AlertTriangle className="h-3.5 w-3.5" /> At or over limit
                    </span>
                    <Why rule="BR-04" reason={`${demerits.points} points is at or over the ${demerits.licenceClass} limit of ${demerits.limit}. A driver at or over their limit is issued a suspension notice.`} align="right" />
                  </motion.div>
                ) : (
                  <motion.div key="under" initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex items-center justify-between rounded-lg bg-emerald-50 px-3 py-2 text-xs text-emerald-700">
                    <span className="font-medium">{demerits.limit - demerits.points} points of headroom</span>
                    <Why rule="BR-04" reason={`The ${demerits.licenceClass} limit is ${demerits.limit} points (LN, P1 and P2: 4 · Open: 12). ${demerits.points} is below it, so no suspension notice.`} align="right" />
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </>
        )}
      </div>
    </Card>
  )
}

function OffenceTimeline({ demerits, error }: { demerits?: Demerits; error?: string }) {
  const offences = [...(demerits?.offences ?? [])].sort((a, b) => b.date.localeCompare(a.date))
  return (
    <Card>
      <CardHeader
        title="Offence timeline"
        subtitle="Each offence carries the rule and reason the demerit service applied"
        icon={<History className="h-4 w-4" />}
        action={demerits && <span className="font-mono text-[11px] text-slate-400">{offences.length} offences</span>}
      />
      {error && <p className="p-5 text-xs text-rose-600">{error}</p>}
      {demerits && !offences.length && (
        <EmptyState icon={<ShieldCheck className="h-5 w-5" />} title="Clean record">
          No offences on file for this driver.
        </EmptyState>
      )}
      <ol className="relative px-5 py-4">
        <AnimatePresence initial={false}>
          {offences.map((o, i) => (
            <motion.li
              key={o.offenceId}
              layout
              initial={{ opacity: 0, x: -8, backgroundColor: 'rgba(99,102,241,0.08)' }}
              animate={{ opacity: 1, x: 0, backgroundColor: 'rgba(99,102,241,0)' }}
              transition={{ duration: 0.5 }}
              className="relative flex gap-4 rounded-lg pb-5 last:pb-0"
            >
              {i < offences.length - 1 && <span className="absolute left-[15px] top-8 h-[calc(100%-24px)] w-px bg-slate-200" />}
              <div
                className={clsx(
                  'relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold ring-4 ring-white',
                  o.doubled ? 'bg-rose-500 text-white' : 'bg-slate-100 text-slate-600',
                )}
              >
                {o.offenceClass}
              </div>
              <div className="min-w-0 flex-1 pt-0.5">
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="text-[13px] font-medium text-slate-900">{OFFENCE_LABELS[o.code] ?? OFFENCE_CLASSES[o.offenceClass] ?? o.code}</span>
                  <span className="font-mono text-[11px] text-slate-400">{o.code}</span>
                  {o.doubled && (
                    <Pill tone="red">
                      <Flame className="h-3 w-3" /> double demerits
                    </Pill>
                  )}
                </div>
                <div className="mt-0.5 text-xs text-slate-500">{formatDate(o.date)}</div>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1 pt-0.5">
                <div className="text-sm tabular-nums">
                  {o.doubled && <span className="mr-1.5 text-xs text-slate-400 line-through">{o.basePoints}</span>}
                  <span className={clsx('font-semibold', o.doubled ? 'text-rose-600' : 'text-slate-800')}>+{o.appliedPoints}</span>
                  <span className="ml-1 text-xs text-slate-400">pts</span>
                </div>
                <Why rule={o.rule} reason={o.reason} align="right" title="Points applied" />
              </div>
            </motion.li>
          ))}
        </AnimatePresence>
      </ol>
    </Card>
  )
}

function NoticesCard({ notices, error }: { notices?: Notice[]; error?: string }) {
  return (
    <Card>
      <CardHeader title="Notices" subtitle="notification-service" icon={<Bell className="h-4 w-4" />} />
      {error && <p className="p-5 text-xs text-rose-600">{error}</p>}
      {notices && !notices.length && <p className="px-5 py-6 text-center text-xs text-slate-500">No notices issued.</p>}
      <ul className="divide-y divide-slate-100">
        <AnimatePresence initial={false}>
          {notices?.map((n) => (
            <motion.li key={n.noticeId} layout initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} className="flex gap-3 px-5 py-3.5">
              <div className={clsx('mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg', n.type === 'SUSPENSION' ? 'bg-rose-50 text-rose-600' : 'bg-emerald-50 text-emerald-600')}>
                {n.type === 'SUSPENSION' ? <AlertTriangle className="h-3.5 w-3.5" /> : <Sparkles className="h-3.5 w-3.5" />}
              </div>
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-semibold text-slate-800">{n.type === 'SUSPENSION' ? 'Suspension notice' : 'Upgrade notice'}</span>
                  <span className="text-[11px] text-slate-400">{formatDate(n.issuedAt)}</span>
                </div>
                <p className="mt-0.5 text-xs leading-relaxed text-slate-500">{n.text}</p>
              </div>
            </motion.li>
          ))}
        </AnimatePresence>
      </ul>
    </Card>
  )
}
