import clsx from 'clsx'
import { AnimatePresence, motion, type MotionStyle } from 'framer-motion'
import { ArrowRight, Calendar, Database, Globe, Layers, MonitorSmartphone, Network, Send, Zap } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Button, Card, CardHeader, PageHeader, StatusDot } from '../components/ui'
import { useToast } from '../components/Toast'
import { api } from '../lib/api'
import { CONTEXTS, LEGACY_MAP, ROUTES, SERVICES, routesFor, serviceOf } from '../lib/domain'
import { summarise, useNow } from '../lib/events'
import { useLive } from '../lib/live'
import type { ActivityEvent, ServiceName } from '../lib/types'
import { EventRow } from './Activity'

const W = 1040
const H = 600
const SX: Record<ServiceName, number> = { licence: 340, demerit: 520, registration: 700, notification: 880 }
const Y = { top: 56, svc: 210, bus: 390, queue: 535 }
const QX: Record<string, number> = { 'licence-inbox': 340, 'demerit-inbox': 520, 'notification-inbox': 770, 'activity-inbox': 935 }
const SVC_H = 116
const Q_H = 56

const PUBLISHES: Record<ServiceName, string[]> = {
  licence: ['DriverRegistered', 'LicenceProgressed'],
  demerit: ['OffenceRecorded', 'DemeritPointsChanged', 'SuspensionThresholdReached'],
  registration: ['RegistrationRenewed'],
  notification: ['NoticeIssued'],
}

type Pt = [number, number]
type Seg = [Pt, Pt, Pt, Pt]

const line = (a: Pt, b: Pt): Seg => [a, [a[0] + (b[0] - a[0]) / 3, a[1] + (b[1] - a[1]) / 3], [a[0] + ((b[0] - a[0]) * 2) / 3, a[1] + ((b[1] - a[1]) * 2) / 3], b]
const curve = (a: Pt, b: Pt): Seg => [a, [a[0], (a[1] + b[1]) / 2], [b[0], (a[1] + b[1]) / 2], b]
const d = (s: Seg) => `M${s[0][0]},${s[0][1]} C${s[1][0]},${s[1][1]} ${s[2][0]},${s[2][1]} ${s[3][0]},${s[3][1]}`
function at(s: Seg, t: number): Pt {
  const u = 1 - t
  const k = [u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t]
  return [k.reduce((acc, w, i) => acc + w * s[i][0], 0), k.reduce((acc, w, i) => acc + w * s[i][1], 0)]
}

const publishSeg = (s: ServiceName) => line([SX[s] - 30, Y.svc + SVC_H / 2], [SX[s] - 30, Y.bus - 22])
const routeSeg = (q: string) => line([QX[q] - 28, Y.bus + 22], [QX[q] - 28, Y.queue - Q_H / 2])
const consumeSeg = (q: string, s: ServiceName) => curve([QX[q] + 28, Y.queue - Q_H / 2], [SX[s] + 30, Y.svc + SVC_H / 2])
const gatewaySeg = (s: ServiceName) => line([SX[s] - 45, Y.top + 22], [SX[s] - 45, Y.svc - SVC_H / 2])
const schedulerSeg = line([175, Y.svc], [SX.licence - 82, Y.svc])

interface Pulse {
  id: string
  seg: Seg
  delay: number
  color: string
}

function PulseDot({ pulse, onDone }: { pulse: Pulse; onDone: (id: string) => void }) {
  const ref = useRef<SVGCircleElement>(null)
  useEffect(() => {
    let raf = 0
    const start = performance.now() + pulse.delay
    const dur = 650
    const step = (now: number) => {
      const t = Math.min(1, Math.max(0, (now - start) / dur))
      const [x, y] = at(pulse.seg, t)
      const el = ref.current
      if (el) {
        el.setAttribute('cx', String(x))
        el.setAttribute('cy', String(y))
        el.setAttribute('opacity', now < start ? '0' : '1')
      }
      if (t < 1) raf = requestAnimationFrame(step)
      else onDone(pulse.id)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [pulse, onDone])
  return <circle ref={ref} r={5} fill={pulse.color} opacity={0} style={{ filter: `drop-shadow(0 0 6px ${pulse.color})` }} />
}

type NodeId = ServiceName | 'gateway' | 'bus' | 'ui' | 'scheduler' | `q:${string}`

export function ArchitecturePage() {
  const { subscribe, health, events } = useLive()
  const toast = useToast()
  const [pulses, setPulses] = useState<Pulse[]>([])
  const [glow, setGlow] = useState<Record<string, number>>({})
  const [selected, setSelected] = useState<NodeId | null>(null)
  const [hoverSvc, setHoverSvc] = useState<ServiceName | null>(null)
  const [tab, setTab] = useState<'legacy' | 'node'>('legacy')
  const [firing, setFiring] = useState(false)
  const now = useNow(5000)

  const flash = (ids: string[], delay: number) =>
    setTimeout(() => {
      const until = Date.now() + 900
      setGlow((g) => ({ ...g, ...Object.fromEntries(ids.map((i) => [i, until])) }))
    }, delay)

  useEffect(
    () =>
      subscribe((e: ActivityEvent) => {
        const src = serviceOf(e.source)
        const color = CONTEXTS[src].color
        const routes = routesFor(e.detailType)
        const next: Pulse[] = [{ id: `${e.eventId}-p`, seg: publishSeg(src), delay: 0, color }]
        routes.forEach((r) => {
          next.push({ id: `${e.eventId}-r-${r.queue}`, seg: routeSeg(r.queue), delay: 650, color })
          next.push({ id: `${e.eventId}-c-${r.queue}`, seg: consumeSeg(r.queue, r.consumer), delay: 1300, color })
        })
        setPulses((p) => [...p.slice(-60), ...next])
        flash([src], 0)
        flash(['bus'], 600)
        flash(
          routes.map((r) => `q:${r.queue}`),
          1250,
        )
        flash(
          routes.map((r) => r.consumer),
          1900,
        )
      }),
    [subscribe],
  )

  const done = useRef((id: string) => setPulses((p) => p.filter((x) => x.id !== id))).current
  const lit = (id: string) => (glow[id] ?? 0) > Date.now()

  useEffect(() => {
    const t = setInterval(() => setGlow((g) => ({ ...g })), 300)
    return () => clearInterval(t)
  }, [])

  async function fire() {
    setFiring(true)
    try {
      const plate = `${Math.floor(100 + Math.random() * 900)}ARC`
      await api.renew({ plate, vehicleType: 'CAR', cylinders: 4, concession: false, termMonths: 12 })
    } catch (e) {
      toast({ tone: 'error', title: 'Could not publish', body: (e as Error).message })
    } finally {
      setFiring(false)
    }
  }

  const pick = (id: NodeId) => {
    setSelected(id)
    setTab('node')
  }

  const pos = (x: number, y: number) => ({ left: `${(x / W) * 100}%`, top: `${(y / H) * 100}%` })

  return (
    <div>
      <PageHeader
        title="Architecture"
        description="Four bounded contexts on AWS, talking only through domain events on EventBridge. Dots light up as real events flow through the bus."
        actions={
          <Button variant="primary" onClick={fire} loading={firing}>
            {!firing && <Send className="h-3.5 w-3.5" />} Publish a test renewal
          </Button>
        }
      />
      <div className="space-y-5">
        <Card className="overflow-hidden">
          <div className="overflow-x-auto">
          <div className="bg-grid relative w-full min-w-[960px]" style={{ aspectRatio: `${W} / ${H}` }}>
            <svg viewBox={`0 0 ${W} ${H}`} className="absolute inset-0 h-full w-full">
              <defs>
                <marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                  <path d="M0,0 L8,4 L0,8 z" fill="#94a3b8" />
                </marker>
              </defs>
              <path d={d(line([172, Y.top], [212, Y.top]))} stroke="#94a3b8" strokeWidth={1.5} fill="none" markerEnd="url(#arrow)" />
              <path d={d(schedulerSeg)} stroke="#ec4899" strokeOpacity={0.5} strokeWidth={1.4} strokeDasharray="4 4" fill="none" markerEnd="url(#arrow)" />
              {SERVICES.map((s) => (
                <g key={s}>
                  <path d={d(gatewaySeg(s))} stroke="#94a3b8" strokeWidth={1.2} fill="none" markerEnd="url(#arrow)" />
                  <path d={d(publishSeg(s))} stroke={CONTEXTS[s].color} strokeOpacity={0.55} strokeWidth={1.6} fill="none" className="flow-line" markerEnd="url(#arrow)" />
                </g>
              ))}
              {ROUTES.map((r) => (
                <g key={r.queue}>
                  <path d={d(routeSeg(r.queue))} stroke="#64748b" strokeOpacity={0.5} strokeWidth={1.4} fill="none" markerEnd="url(#arrow)" />
                  <path d={d(consumeSeg(r.queue, r.consumer))} stroke={CONTEXTS[r.consumer].color} strokeOpacity={0.35} strokeWidth={1.4} strokeDasharray="2 5" fill="none" />
                </g>
              ))}
              {pulses.map((p) => (
                <PulseDot key={p.id} pulse={p} onDone={done} />
              ))}
            </svg>

            <Node style={pos(100, Y.top)} w={150} active={selected === 'ui'} onClick={() => pick('ui')}>
              <div className="flex items-center gap-2">
                <MonitorSmartphone className="h-4 w-4 text-indigo-500" />
                <div className="text-[12px] font-semibold text-slate-900">LICSYS Next UI</div>
              </div>
            </Node>
            <Node style={pos(100, Y.svc)} w={150} active={selected === 'scheduler'} onClick={() => pick('scheduler')}>
              <div className="flex items-center gap-2">
                <Calendar className="h-4 w-4 text-pink-500" />
                <div>
                  <div className="text-[12px] font-semibold text-slate-900">Scheduler</div>
                  <div className="text-[10px] text-slate-500">nightly progression</div>
                </div>
              </div>
            </Node>

            <Bar style={{ ...pos(610, Y.top), width: `${(790 / W) * 100}%` }} active={selected === 'gateway'} onClick={() => pick('gateway')} dark={false}>
              <Globe className="h-4 w-4 text-violet-500" />
              <span className="text-[12px] font-semibold text-slate-900">Amazon API Gateway</span>
              <span className="font-mono text-[10px] text-slate-400">/licence · /demerit · /registration · /notification</span>
            </Bar>

            {SERVICES.map((s) => {
              const ctx = CONTEXTS[s]
              const isLit = lit(s)
              const hl = hoverSvc === s
              return (
                <motion.button
                  key={s}
                  onClick={() => pick(s)}
                  className={clsx(
                    'absolute rounded-xl border bg-white px-3 py-2.5 text-left shadow-card transition',
                    selected === s ? 'border-slate-900' : 'border-slate-200 hover:border-slate-300',
                  )}
                  style={{ ...pos(SX[s], Y.svc), x: '-50%', y: '-50%', width: `${(158 / W) * 100}%`, boxShadow: isLit || hl ? `0 0 0 4px ${ctx.color}26, 0 10px 30px -8px ${ctx.color}66` : undefined }}
                  animate={{ scale: isLit ? 1.04 : 1 }}
                >
                  <span className="absolute inset-x-0 top-0 h-1 rounded-t-xl" style={{ background: ctx.color }} />
                  <div className="flex items-center justify-between">
                    <span className="text-[12.5px] font-semibold capitalize text-slate-900">{s}</span>
                    <StatusDot status={health[s]} size={7} />
                  </div>
                  <div className="truncate text-[10.5px] text-slate-500">{ctx.title}</div>
                  <div className="mt-1 truncate font-mono text-[10px] text-slate-400">:{ctx.port} · {ctx.rules}</div>
                  <div className="mt-2 flex items-center gap-1.5 border-t border-slate-100 pt-1.5">
                    <Database className="h-3 w-3 shrink-0 text-blue-500" />
                    <span className="truncate font-mono text-[10px] text-slate-500">{ctx.table}</span>
                  </div>
                </motion.button>
              )
            })}

            <Bar style={{ ...pos(610, Y.bus), width: `${(790 / W) * 100}%` }} active={selected === 'bus'} lit={lit('bus')} onClick={() => pick('bus')} dark>
              <Zap className="h-4 w-4 text-amber-300" />
              <span className="text-[12px] font-semibold text-white">Amazon EventBridge</span>
              <span className="font-mono text-[10.5px] text-slate-400">licsys-bus</span>
            </Bar>

            {ROUTES.map((r) => (
              <Node key={r.queue} style={pos(QX[r.queue], Y.queue)} w={140} lit={lit(`q:${r.queue}`)} color={CONTEXTS[r.consumer].color} active={selected === `q:${r.queue}`} onClick={() => pick(`q:${r.queue}`)}>
                <div className="flex items-center gap-1.5">
                  <Layers className="h-3.5 w-3.5 text-orange-500" />
                  <span className="text-[10px] font-medium uppercase tracking-wider text-slate-400">SQS</span>
                </div>
                <div className="mt-0.5 whitespace-nowrap font-mono text-[11px] font-medium text-slate-800">{r.queue}</div>
              </Node>
            ))}
            <div className="absolute flex -translate-y-1/2 items-center gap-1.5 text-[10px] text-slate-400" style={pos(40, Y.queue)}>
              <Network className="h-3 w-3" /> EventBridge rules → SQS → consumer
            </div>
          </div>
          </div>
          <div className="flex flex-wrap items-center gap-4 border-t border-slate-100 px-5 py-3 text-[11px] text-slate-500">
            {SERVICES.map((s) => (
              <span key={s} className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full" style={{ background: CONTEXTS[s].color }} />
                <span className="capitalize">{s}</span>
              </span>
            ))}
            <span className="ml-auto">Health from GET /&lt;svc&gt;/actuator/health every 10s</span>
          </div>
        </Card>

        <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
          <Card className="overflow-hidden">
            <div className="flex gap-1 border-b border-slate-100 p-1.5">
              {(['legacy', 'node'] as const).map((t) => (
                <button key={t} onClick={() => setTab(t)} className={clsx('relative flex-1 rounded-md py-1.5 text-xs font-medium transition', tab === t ? 'text-slate-900' : 'text-slate-500 hover:text-slate-700')}>
                  {tab === t && <motion.span layoutId="arch-tab" className="absolute inset-0 rounded-md bg-slate-100" />}
                  <span className="relative">{t === 'legacy' ? 'Legacy → LICSYS Next' : 'Selected component'}</span>
                </button>
              ))}
            </div>
            <AnimatePresence mode="wait">
              {tab === 'legacy' ? (
                <motion.div key="legacy" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  <p className="px-5 pt-4 text-xs text-slate-500">Where each piece of the COBOL nightly batch lives now. Hover a row to find its service on the map.</p>
                  <ul className="space-y-1 p-3">
                    {LEGACY_MAP.map((m) => (
                      <li
                        key={m.program + m.paragraph}
                        onMouseEnter={() => setHoverSvc(m.service)}
                        onMouseLeave={() => setHoverSvc(null)}
                        className="group flex items-center gap-3 rounded-lg px-2.5 py-2 transition hover:bg-slate-50"
                      >
                        <div className="w-[170px] shrink-0">
                          <div className="font-mono text-[11px] font-semibold text-emerald-700">{m.program}</div>
                          <div className="truncate font-mono text-[10px] text-slate-400">{m.paragraph}</div>
                        </div>
                        <ArrowRight className="h-3.5 w-3.5 shrink-0 text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-slate-500" />
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-1.5">
                            <span className="h-1.5 w-1.5 rounded-full" style={{ background: CONTEXTS[m.service].color }} />
                            <span className="whitespace-nowrap text-[12px] font-semibold text-slate-800">{m.service}-service</span>
                            <span className="ml-auto shrink-0 font-mono text-[10px] text-slate-400">{m.rule}</span>
                          </div>
                          <div className="truncate text-[11px] text-slate-500">
                            {m.what} · <span className="font-mono">{m.replacedBy}</span>
                          </div>
                        </div>
                      </li>
                    ))}
                  </ul>
                </motion.div>
              ) : (
                <motion.div key="node" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="p-5">
                  <NodeDetail id={selected} health={health} events={events} />
                </motion.div>
              )}
            </AnimatePresence>
          </Card>

          <Card className="overflow-hidden">
            <CardHeader title="Latest events" subtitle="Each one animates across the map" icon={<Zap className="h-4 w-4" />} />
            <ul className="divide-y divide-slate-100">
              <AnimatePresence initial={false}>
                {events.slice(0, 5).map((e) => (
                  <EventRow key={e.eventId} event={e} now={now} compact />
                ))}
              </AnimatePresence>
            </ul>
          </Card>
        </div>
      </div>
    </div>
  )
}

function Node({
  style,
  w,
  children,
  active,
  lit,
  color,
  onClick,
}: {
  style: MotionStyle
  w: number
  children: React.ReactNode
  active?: boolean
  lit?: boolean
  color?: string
  onClick?: () => void
}) {
  return (
    <motion.button
      onClick={onClick}
      animate={{ scale: lit ? 1.06 : 1 }}
      className={clsx(
        'absolute rounded-lg border bg-white text-left shadow-card transition',
        'px-3 py-2',
        active ? 'border-slate-900' : 'border-slate-200 hover:border-slate-300',
      )}
      style={{ ...style, x: '-50%', y: '-50%', width: `${(w / W) * 100}%`,  boxShadow: lit && color ? `0 0 0 4px ${color}26, 0 8px 24px -8px ${color}88` : undefined }}
    >
      {children}
    </motion.button>
  )
}

function Bar({ style, children, active, lit, dark, onClick }: { style: MotionStyle; children: React.ReactNode; active?: boolean; lit?: boolean; dark: boolean; onClick: () => void }) {
  return (
    <motion.button
      onClick={onClick}
      animate={{ boxShadow: lit ? '0 0 0 5px rgba(251,191,36,.25), 0 12px 32px -8px rgba(251,191,36,.45)' : '0 1px 2px rgba(16,24,40,.06)' }}
      className={clsx(
        'absolute flex h-[7.4%] items-center justify-center gap-2.5 rounded-xl border px-4',
        dark ? 'border-ink-700 bg-ink-950' : 'border-slate-200 bg-white',
        active && (dark ? 'ring-2 ring-amber-300/60' : 'border-slate-900'),
      )}
      style={{ ...style, x: '-50%', y: '-50%' }}
    >
      {children}
    </motion.button>
  )
}

function NodeDetail({ id, health, events }: { id: NodeId | null; health: Record<ServiceName, string>; events: ActivityEvent[] }) {
  if (!id) return <p className="text-sm text-slate-500">Click any component on the map to see what it does.</p>
  if (id === 'ui') return <Detail title="LICSYS Next UI" kind="React 18 · Vite · TypeScript" body="This app. Calls services through the API proxy, and subscribes to the activity stream over SSE." />
  if (id === 'scheduler')
    return <Detail title="EventBridge Scheduler" kind="Replaces LICNIGHT.jcl" body="Calls POST /licence/api/progression/run every night. That replaces the LICUPG batch step, and there is no more step-order coupling." />
  if (id === 'gateway')
    return <Detail title="Amazon API Gateway" kind="Edge" body="One public entry point. It routes /licence, /demerit, /registration and /notification to their services. Locally the Vite proxy (or nginx in Docker) does the same job." />
  if (id === 'bus')
    return (
      <Detail title="Amazon EventBridge · licsys-bus" kind="Event bus" body="Every domain event is published here with source licsys.<service>. Rules fan events out to each consumer's SQS queue.">
        <div className="mt-3 text-xs text-slate-500">{events.length} events seen this session.</div>
      </Detail>
    )
  if (id.startsWith('q:')) {
    const r = ROUTES.find((x) => `q:${x.queue}` === id)!
    return (
      <Detail title={`SQS · ${r.queue}`} kind={`Consumed by ${r.consumer}-service`} body={r.events === '*' ? 'Receives every event on licsys-bus. It feeds the activity store and this SSE stream.' : `EventBridge rule matches: ${r.events.join(', ')}.`} />
    )
  }
  const s = id as ServiceName
  const ctx = CONTEXTS[s]
  const consumes = ROUTES.filter((r) => r.consumer === s)
  return (
    <div>
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-semibold capitalize text-slate-900">{s}-service</div>
          <div className="text-xs text-slate-500">
            {ctx.title} · {ctx.rules}
          </div>
        </div>
        <span className="flex items-center gap-1.5 text-xs text-slate-600">
          <StatusDot status={health[s] as 'UP'} /> {health[s]}
        </span>
      </div>
      <dl className="mt-4 space-y-3 text-xs">
        <div>
          <dt className="label mb-1">Publishes</dt>
          <dd className="flex flex-wrap gap-1">
            {PUBLISHES[s].map((e) => (
              <span key={e} className="rounded-md px-1.5 py-0.5 font-mono text-[11px]" style={{ background: `${ctx.color}14`, color: ctx.color }}>
                {e}
              </span>
            ))}
          </dd>
        </div>
        <div>
          <dt className="label mb-1">Consumes</dt>
          <dd className="font-mono text-[11px] text-slate-600">{consumes.length ? consumes.map((c) => c.queue).join(', ') : '—'}</dd>
        </div>
        <div>
          <dt className="label mb-1">Owns</dt>
          <dd className="font-mono text-[11px] text-slate-600">DynamoDB {ctx.table}</dd>
        </div>
        <div>
          <dt className="label mb-1">Recent</dt>
          <dd className="space-y-1">
            {events
              .filter((e) => serviceOf(e.source) === s)
              .slice(0, 3)
              .map((e) => (
                <div key={e.eventId} className="truncate text-[11px] text-slate-600">
                  <span className="font-medium text-slate-800">{e.detailType}</span> · {summarise(e)}
                </div>
              ))}
          </dd>
        </div>
      </dl>
    </div>
  )
}

function Detail({ title, kind, body, children }: { title: string; kind: string; body: string; children?: React.ReactNode }) {
  return (
    <div>
      <div className="text-sm font-semibold text-slate-900">{title}</div>
      <div className="text-xs text-slate-500">{kind}</div>
      <p className="mt-3 text-[13px] leading-relaxed text-slate-600">{body}</p>
      {children}
    </div>
  )
}
