import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { AlertCircle, Bike, Car, CheckCircle2, Container, Receipt } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useToast } from '../components/Toast'
import { Button, Card, CardHeader, PageHeader, RuleTag, Skeleton } from '../components/ui'
import { api, ApiError } from '../lib/api'
import type { Quote, QuoteInput, RuleError, VehicleType } from '../lib/types'

const SAMPLES: (QuoteInput & { owner: string })[] = [
  { plate: '123ABC', owner: 'Farid Hassan', vehicleType: 'CAR', cylinders: 4, termMonths: 12, concession: false },
  { plate: '456DEF', owner: 'Isla Morgan', vehicleType: 'CAR', cylinders: 6, termMonths: 6, concession: false },
  { plate: '567MNO', owner: 'Maya Rossi', vehicleType: 'MOTORCYCLE', cylinders: 1, termMonths: 6, concession: true },
  { plate: '890PQR', owner: 'Maya Rossi', vehicleType: 'TRAILER', cylinders: 0, termMonths: 12, concession: true },
  { plate: '678VWX', owner: 'Grace Liu', vehicleType: 'CAR', cylinders: 5, termMonths: 3, concession: true },
  { plate: '112BCD', owner: 'Farid Hassan', vehicleType: 'MOTORCYCLE', cylinders: 1, termMonths: 9, concession: false },
]

const TYPES: { value: VehicleType; label: string; icon: typeof Car }[] = [
  { value: 'CAR', label: 'Car', icon: Car },
  { value: 'MOTORCYCLE', label: 'Motorcycle', icon: Bike },
  { value: 'TRAILER', label: 'Trailer', icon: Container },
]

const money = (s: string) => {
  const n = Number(s)
  return `${n < 0 ? '−' : ''}$${Math.abs(n).toLocaleString('en-AU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function Segmented<T extends string | number>({ value, options, onChange }: { value: T; options: { value: T; label: string; icon?: typeof Car }[]; onChange: (v: T) => void }) {
  return (
    <div className="grid auto-cols-fr grid-flow-col gap-1 rounded-lg bg-slate-100 p-1">
      {options.map((o) => (
        <button
          type="button"
          key={String(o.value)}
          onClick={() => onChange(o.value)}
          className={clsx('relative flex h-8 items-center justify-center gap-1.5 rounded-md text-xs font-medium transition', value === o.value ? 'text-slate-900' : 'text-slate-500 hover:text-slate-700')}
        >
          {value === o.value && <motion.span layoutId={`seg-${options.map((x) => x.value).join()}`} className="absolute inset-0 rounded-md bg-white shadow-sm" transition={{ type: 'spring', stiffness: 500, damping: 35 }} />}
          {o.icon && <o.icon className="relative h-3.5 w-3.5" />}
          <span className="relative">{o.label}</span>
        </button>
      ))}
    </div>
  )
}

export function RegistrationPage() {
  const toast = useToast()
  const [form, setForm] = useState<QuoteInput>({ plate: '456DEF', vehicleType: 'CAR', cylinders: 6, concession: false, termMonths: 6 })
  const [quote, setQuote] = useState<Quote | null>(null)
  const [error, setError] = useState<RuleError | null>(null)
  const [loading, setLoading] = useState(true)
  const [renewing, setRenewing] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const t = setTimeout(async () => {
      try {
        const q = await api.quote(form)
        if (cancelled) return
        setQuote(q)
        setError(null)
      } catch (e) {
        if (cancelled) return
        const body = e instanceof ApiError ? (e.body as RuleError) : null
        setQuote(null)
        setError(body?.reason ? body : { rule: '—', reason: (e as Error).message })
      } finally {
        if (!cancelled) setLoading(false)
      }
    }, 220)
    return () => {
      cancelled = true
      clearTimeout(t)
    }
  }, [form])

  const set = <K extends keyof QuoteInput>(k: K, v: QuoteInput[K]) => setForm((f) => ({ ...f, [k]: v }))

  async function renew() {
    setRenewing(true)
    try {
      const r = await api.renew(form)
      toast({ tone: 'success', title: `${form.plate.toUpperCase()} renewed`, body: `$${r.total} · RegistrationRenewed published` })
    } catch (e) {
      toast({ tone: 'error', title: 'Renewal failed', body: (e as Error).message })
    } finally {
      setRenewing(false)
    }
  }

  return (
    <div>
      <PageHeader title="Registration quote" description="Price a renewal with the registration service. Every line carries the BR-06 clause it comes from, and amounts are rounded half-up to the cent at each step." />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <Card>
          <CardHeader title="Vehicle" subtitle="POST /registration/api/renewals/quote" icon={<Car className="h-4 w-4" />} />
          <div className="space-y-5 p-5">
            <div>
              <div className="label mb-2">From the legacy renewals file</div>
              <div className="flex flex-wrap gap-1.5">
                {SAMPLES.map((s) => (
                  <button
                    key={s.plate}
                    onClick={() => setForm({ plate: s.plate, vehicleType: s.vehicleType, cylinders: s.cylinders, concession: s.concession, termMonths: s.termMonths })}
                    className={clsx(
                      'rounded-lg border px-2.5 py-1.5 text-left transition',
                      form.plate === s.plate ? 'border-indigo-300 bg-indigo-50' : 'border-slate-200 bg-white hover:border-slate-300',
                    )}
                  >
                    <div className="font-mono text-xs font-semibold text-slate-800">{s.plate}</div>
                    <div className="text-[10.5px] text-slate-500">{s.owner}</div>
                  </button>
                ))}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="label mb-1.5 block">Plate</label>
                <input className="input font-mono uppercase" maxLength={6} value={form.plate} onChange={(e) => set('plate', e.target.value.toUpperCase())} />
              </div>
              <div>
                <label className="label mb-1.5 block">Cylinders</label>
                <input
                  type="number"
                  min={0}
                  max={16}
                  className="input disabled:bg-slate-50 disabled:text-slate-400"
                  disabled={form.vehicleType !== 'CAR'}
                  value={form.cylinders}
                  onChange={(e) => set('cylinders', Number(e.target.value))}
                />
              </div>
            </div>
            <div>
              <label className="label mb-1.5 block">Vehicle type</label>
              <Segmented value={form.vehicleType as VehicleType} options={TYPES} onChange={(v) => setForm((f) => ({ ...f, vehicleType: v, cylinders: v === 'CAR' ? Math.max(f.cylinders, 4) : v === 'MOTORCYCLE' ? 1 : 0 }))} />
            </div>
            <div>
              <label className="label mb-1.5 block">Term</label>
              <Segmented
                value={form.termMonths}
                options={[
                  { value: 12, label: '12 months' },
                  { value: 6, label: '6 months' },
                  { value: 3, label: '3 months' },
                  ...(![12, 6, 3].includes(form.termMonths) ? [{ value: form.termMonths, label: `${form.termMonths} months` }] : []),
                ]}
                onChange={(v) => set('termMonths', v)}
              />
            </div>
            <label className="flex cursor-pointer items-center justify-between rounded-lg border border-slate-200 px-3.5 py-3">
              <div>
                <div className="text-[13px] font-medium text-slate-800">Concession holder</div>
                <div className="text-xs text-slate-500">50% off the base fee. Trailers get no concession.</div>
              </div>
              <input type="checkbox" className="h-4 w-4 accent-indigo-600" checked={form.concession} onChange={(e) => set('concession', e.target.checked)} />
            </label>
          </div>
        </Card>

        <Card className="flex flex-col overflow-hidden">
          <CardHeader title="Quote" subtitle={form.plate ? `Plate ${form.plate}` : undefined} icon={<Receipt className="h-4 w-4" />} action={<RuleTag rule="BR-06" />} />
          <div className="flex flex-1 flex-col p-5">
            {loading && !quote && !error && <Skeleton className="h-48" />}
            <AnimatePresence mode="wait">
              {error && (
                <motion.div key="err" initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="rounded-xl border border-rose-200 bg-rose-50 p-4">
                  <div className="flex items-center gap-2 text-sm font-semibold text-rose-800">
                    <AlertCircle className="h-4 w-4" /> Can't be priced
                    <RuleTag rule={error.rule} />
                  </div>
                  <p className="mt-1.5 text-[13px] text-rose-700">{error.reason}</p>
                </motion.div>
              )}
              {quote && !error && (
                <motion.div key="quote" initial={{ opacity: 0 }} animate={{ opacity: loading ? 0.55 : 1 }} exit={{ opacity: 0 }} className="flex flex-1 flex-col">
                  <ul className="divide-y divide-dashed divide-slate-200">
                    {quote.lines.map((l, i) => (
                      <motion.li key={l.label} initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: i * 0.04 }} className="flex items-center gap-3 py-2.5">
                        <span className="w-16 shrink-0">
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-500">{l.rule}</span>
                        </span>
                        <span className="flex-1 text-[13px] text-slate-700">{l.label}</span>
                        <span className={clsx('font-mono text-[13px] tabular-nums', Number(l.amount) < 0 ? 'text-emerald-600' : 'text-slate-800')}>{money(l.amount)}</span>
                      </motion.li>
                    ))}
                  </ul>
                  <div className="mt-4 flex items-end justify-between rounded-xl bg-ink-950 px-5 py-4 text-white">
                    <div>
                      <div className="text-[11px] uppercase tracking-wider text-slate-400">Total due</div>
                      <div className="text-xs text-slate-500">{form.termMonths}-month registration</div>
                    </div>
                    <motion.span key={quote.total} initial={{ opacity: 0.3, y: 4 }} animate={{ opacity: 1, y: 0 }} className="font-mono text-3xl font-semibold tabular-nums tracking-tight">
                      {money(quote.total)}
                    </motion.span>
                  </div>
                  <div className="mt-4 flex items-center justify-between">
                    <span className="flex items-center gap-1.5 text-xs text-slate-500">
                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" /> BigDecimal, HALF_UP at each step
                    </span>
                    <Button variant="primary" onClick={renew} loading={renewing}>
                      Renew registration
                    </Button>
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </Card>
      </div>
    </div>
  )
}
