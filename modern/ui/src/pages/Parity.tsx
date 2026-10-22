import clsx from 'clsx'
import { motion } from 'framer-motion'
import { ArrowRight, CheckCircle2, CircleAlert, FileCheck2, Sparkles, XCircle } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardHeader, ClassBadge, PageHeader, Pill, RuleTag, Skeleton } from '../components/ui'
import { displayName } from '../lib/domain'
import { useParity } from '../lib/useParity'
import type { ParityDriver } from '../lib/types'

function Stat({ label, value, tone, sub, delay }: { label: string; value: number; tone: 'slate' | 'green' | 'indigo' | 'red'; sub: string; delay: number }) {
  const tones = { slate: 'text-slate-900', green: 'text-emerald-600', indigo: 'text-indigo-600', red: 'text-rose-600' }
  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay }} className="card p-5">
      <div className="label">{label}</div>
      <div className={clsx('mt-2 text-3xl font-semibold tabular-nums tracking-tight', tones[tone])}>{value}</div>
      <div className="mt-1 text-xs text-slate-500">{sub}</div>
    </motion.div>
  )
}

function Outcome({ o, muted }: { o: ParityDriver['legacy']; muted?: boolean }) {
  return (
    <div className={clsx('flex items-center gap-2', muted && 'opacity-60')}>
      <ClassBadge cls={o.licenceClass} />
      <span className="w-12 text-xs tabular-nums text-slate-700">{o.points} pts</span>
      {o.suspensionNotice ? <Pill tone="red">notice</Pill> : <span className="text-xs text-slate-300">—</span>}
    </div>
  )
}

export function ParityPage() {
  const { report, error } = useParity()
  const [onlyDiffs, setOnlyDiffs] = useState(false)

  if (error) {
    return (
      <Card className="p-8 text-sm text-rose-600">
        Could not load <code>/parity.json</code>: {error}
      </Card>
    )
  }
  if (!report) return <Skeleton className="h-96" />

  const s = report.summary
  const rows = report.drivers.filter((d) => !onlyDiffs || d.status !== 'MATCH')
  const fixes = report.drivers.filter((d) => d.status === 'EXPECTED_DIFFERENCE')

  return (
    <div>
      <PageHeader
        eyebrow={
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-indigo-600">
            <FileCheck2 className="h-3.5 w-3.5" /> tools/parity · generated {new Date(report.generatedAt).toLocaleString('en-AU', { dateStyle: 'medium', timeStyle: 'short' })}
          </span>
        }
        title="Legacy parity"
        description="The COBOL nightly batch and LICSYS Next ran on the same legacy data. Every difference has to be explained by a business rule the old code got wrong."
      />

      <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="relative mb-5 overflow-hidden rounded-2xl bg-ink-950 p-7 text-white">
        <div className="pointer-events-none absolute -right-20 -top-24 h-72 w-72 rounded-full bg-indigo-500/30 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-24 left-1/3 h-56 w-56 rounded-full bg-emerald-400/10 blur-3xl" />
        <div className="relative">
          <div className="flex items-center gap-2 text-xs font-medium text-emerald-300">
            <CheckCircle2 className="h-4 w-4" /> Parity run passed
          </div>
          <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-[28px]">
            {s.drivers} drivers compared <span className="text-slate-500">·</span> {s.matched} identical <span className="text-slate-500">·</span>{' '}
            <span className="text-indigo-300">{s.expectedDifferences} policy fixes</span> <span className="text-slate-500">·</span>{' '}
            <span className={s.unexpected ? 'text-rose-400' : 'text-emerald-300'}>{s.unexpected} unexpected</span>
          </h2>
          <p className="mt-2 text-sm text-slate-400">
            {s.renewals} registration renewals compared, {s.renewalMatched} matched to the cent.
          </p>
        </div>
      </motion.div>

      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Drivers compared" value={s.drivers} tone="slate" sub="Licence class, points, notices" delay={0.05} />
        <Stat label="Identical" value={s.matched} tone="green" sub="Same outcome in both systems" delay={0.1} />
        <Stat label="Policy fixes" value={s.expectedDifferences} tone="indigo" sub="Legacy bugs the new system corrects" delay={0.15} />
        <Stat label="Unexpected" value={s.unexpected} tone={s.unexpected ? 'red' : 'green'} sub="Differences with no rule behind them" delay={0.2} />
      </div>

      <div className="mb-5 grid gap-3 md:grid-cols-2 xl:grid-cols-5">
        {fixes.map((f, i) => (
          <motion.div key={f.driverId} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.25 + i * 0.05 }}>
            <Link to={`/drivers/${f.driverId}`} className="card group flex h-full flex-col p-4 transition hover:-translate-y-px hover:border-indigo-200 hover:shadow-pop">
              <div className="flex items-center justify-between">
                <span className="text-[13px] font-semibold text-slate-900">{displayName(f.name)}</span>
                <RuleTag rule={f.rule} />
              </div>
              <span className="font-mono text-[11px] text-slate-400">{f.driverId}</span>
              <div className="mt-3 flex items-center gap-2 text-xs">
                <span className="text-slate-400 line-through">
                  {f.legacy.licenceClass} · {f.legacy.points}
                  {f.legacy.suspensionNotice ? ' · notice' : ''}
                </span>
                <ArrowRight className="h-3 w-3 text-indigo-400" />
                <span className="font-semibold text-indigo-700">
                  {f.modern.licenceClass} · {f.modern.points}
                  {f.modern.suspensionNotice ? ' · notice' : ''}
                </span>
              </div>
              <span className="mt-auto pt-3 text-[11px] font-medium text-indigo-600 opacity-0 transition group-hover:opacity-100">Open Driver 360 →</span>
            </Link>
          </motion.div>
        ))}
      </div>

      <Card className="mb-5 overflow-hidden">
        <CardHeader
          title="Driver outcomes"
          subtitle="Legacy batch (DMRPTS + LICUPG) against LICSYS Next"
          action={
            <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-600">
              <input type="checkbox" className="accent-indigo-600" checked={onlyDiffs} onChange={(e) => setOnlyDiffs(e.target.checked)} />
              Only differences
            </label>
          }
        />
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-[11px] uppercase tracking-wider text-slate-400">
                <th className="px-5 py-2.5 font-medium">Driver</th>
                <th className="px-3 py-2.5 font-medium">Legacy COBOL</th>
                <th className="px-3 py-2.5 font-medium">LICSYS Next</th>
                <th className="px-3 py-2.5 font-medium">Status</th>
                <th className="px-5 py-2.5 font-medium">Explanation</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((d) => {
                const diff = d.status !== 'MATCH'
                return (
                  <tr key={d.driverId} className={clsx('align-top transition', diff ? 'bg-indigo-50/40 hover:bg-indigo-50/70' : 'hover:bg-slate-50')}>
                    <td className="whitespace-nowrap px-5 py-3">
                      <Link to={`/drivers/${d.driverId}`} className="font-medium text-slate-900 hover:text-indigo-600">
                        {displayName(d.name)}
                      </Link>
                      <div className="font-mono text-[11px] text-slate-400">{d.driverId}</div>
                    </td>
                    <td className="px-3 py-3">
                      <Outcome o={d.legacy} muted={diff} />
                    </td>
                    <td className="px-3 py-3">
                      <Outcome o={d.modern} />
                    </td>
                    <td className="whitespace-nowrap px-3 py-3">
                      {d.status === 'MATCH' && (
                        <Pill tone="green">
                          <CheckCircle2 className="h-3 w-3" /> Match
                        </Pill>
                      )}
                      {d.status === 'EXPECTED_DIFFERENCE' && (
                        <Pill tone="indigo">
                          <Sparkles className="h-3 w-3" /> Policy fix
                        </Pill>
                      )}
                      {d.status === 'MISMATCH' && (
                        <Pill tone="red">
                          <XCircle className="h-3 w-3" /> Mismatch
                        </Pill>
                      )}
                    </td>
                    <td className="max-w-xl px-5 py-3">
                      <div className="flex items-start gap-2">
                        <RuleTag rule={d.rule} />
                        <p className={clsx('text-xs leading-relaxed', diff ? 'text-slate-700' : 'text-slate-500')}>{d.reason}</p>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>

      <Card className="overflow-hidden">
        <CardHeader title="Registration renewals" subtitle="REGFEE against registration-service · BR-06" />
        <div className="grid grid-cols-2 gap-px bg-slate-100 sm:grid-cols-5">
          {report.renewals.map((r) => (
            <div key={r.plate} className="bg-white px-4 py-3">
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs font-semibold text-slate-800">{r.plate}</span>
                {r.match ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" /> : <CircleAlert className="h-3.5 w-3.5 text-rose-500" />}
              </div>
              <div className="mt-1 text-sm tabular-nums text-slate-700">{r.modernTotal ? `$${r.modernTotal}` : <span className="text-xs text-slate-400">Rejected by both</span>}</div>
              {r.legacyTotal && <div className="text-[11px] tabular-nums text-slate-400">legacy ${r.legacyTotal}</div>}
            </div>
          ))}
        </div>
      </Card>
    </div>
  )
}
