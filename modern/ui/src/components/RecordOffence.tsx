import { AnimatePresence, motion } from 'framer-motion'
import { Gavel } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '../lib/api'
import { OFFENCE_CLASSES, OFFENCE_CODES } from '../lib/domain'
import { todayIso } from '../lib/dates'
import type { OffenceResult } from '../lib/types'
import { useToast } from './Toast'
import { Button, Card, CardHeader, Pill, Why } from './ui'

export function RecordOffence({ driverId, onRecorded }: { driverId: string; onRecorded: (r: OffenceResult) => void }) {
  const toast = useToast()
  const [code, setCode] = useState(OFFENCE_CODES[0].code)
  const preset = OFFENCE_CODES.find((o) => o.code === code) ?? OFFENCE_CODES[0]
  const [date, setDate] = useState(todayIso())
  const [basePoints, setBasePoints] = useState(preset.basePoints)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<OffenceResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const r = await api.recordOffence({ driverId, date, code, offenceClass: preset.offenceClass, basePoints })
      setResult(r)
      onRecorded(r)
      toast({
        tone: r.suspensionNotice ? 'error' : 'success',
        title: `${r.appliedPoints} point${r.appliedPoints === 1 ? '' : 's'} applied to ${driverId}`,
        body: r.suspensionNotice ? `Now ${r.totalPoints}/${r.limit}. Suspension threshold reached.` : `Now ${r.totalPoints}/${r.limit} points.`,
      })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader title="Record offence" subtitle="POST /demerit/api/offences" icon={<Gavel className="h-4 w-4" />} />
      <form onSubmit={submit} className="space-y-3 p-5">
        <div>
          <label className="label mb-1.5 block">Offence</label>
          <select
            className="input"
            value={code}
            onChange={(e) => {
              setCode(e.target.value)
              setBasePoints(OFFENCE_CODES.find((o) => o.code === e.target.value)?.basePoints ?? 1)
            }}
          >
            {OFFENCE_CODES.map((o) => (
              <option key={o.code} value={o.code}>
                {o.code} · {o.label}
              </option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label mb-1.5 block">Date</label>
            <input type="date" className="input" value={date} onChange={(e) => setDate(e.target.value)} required />
          </div>
          <div>
            <label className="label mb-1.5 block">Base points</label>
            <input type="number" min={1} max={12} className="input" value={basePoints} onChange={(e) => setBasePoints(Number(e.target.value))} required />
          </div>
        </div>
        <div className="flex items-center justify-between pt-1">
          <span className="text-xs text-slate-500">
            Class <span className="font-mono font-medium text-slate-700">{preset.offenceClass}</span> · {OFFENCE_CLASSES[preset.offenceClass]}
          </span>
          <Button type="submit" variant="primary" loading={busy}>
            Record
          </Button>
        </div>
        <AnimatePresence>
          {error && (
            <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700">
              {error}
            </motion.p>
          )}
          {result && !error && (
            <motion.div key={result.offenceId} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2.5 text-xs">
              <div className="flex items-center justify-between gap-2">
                <span className="text-slate-700">
                  <span className="font-semibold">{result.appliedPoints} pts</span> applied
                  {result.doubled && (
                    <Pill tone="red" className="ml-1.5">
                      doubled
                    </Pill>
                  )}
                </span>
                <Why rule={result.rule} reason={result.reason} align="right" />
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </form>
    </Card>
  )
}
