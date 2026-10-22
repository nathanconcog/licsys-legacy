import { legacyDate, todayIso } from '../lib/dates'
import type {
  ActivityEvent,
  Demerits,
  Driver,
  LicenceClass,
  Notice,
  OffenceInput,
  OffenceRecorded,
  QuoteInput,
  ServiceName,
} from '../lib/types'
import { DRVMAST, OFFENCES, RUN_DATE } from './legacyData'
import { applyDoubleDemerits, evaluateProgression, isRuleError, limitFor, priceRenewal } from './rules'

interface Renewal {
  renewalId: string
  plate: string
  vehicleType: string
  term: number
  total: string
  rule: string
}

interface State {
  drivers: Map<string, Driver>
  offences: Map<string, OffenceRecorded[]>
  notices: Notice[]
  renewals: Renewal[]
  activity: ActivityEvent[]
}

type Listener = (e: ActivityEvent) => void

const listeners = new Set<Listener>()
let seq = 0
const id = (prefix: string) => `${prefix}-${(++seq).toString(36).padStart(4, '0')}${Math.random().toString(36).slice(2, 6)}`

let clock = 0
let state: State = { drivers: new Map(), offences: new Map(), notices: [], renewals: [], activity: [] }
seed()

function publish(service: ServiceName, detailType: string, detail: Record<string, unknown>, at?: number) {
  const event: ActivityEvent = {
    eventId: id('evt'),
    time: new Date(at ?? Date.now()).toISOString(),
    source: `licsys.${service}`,
    detailType,
    detail,
  }
  state.activity.unshift(event)
  state.activity.length = Math.min(state.activity.length, 500)
  if (!at) listeners.forEach((l) => l(event))
  return event
}

/** Simulates EventBridge -> SQS -> consumer latency so the UI can animate the hop. */
function later(fn: () => void, ms: number) {
  if (clock) {
    clock += ms
    fn()
  } else {
    setTimeout(fn, ms)
  }
}

function at() {
  return clock || undefined
}

function emit(service: ServiceName, detailType: string, detail: Record<string, unknown>) {
  return publish(service, detailType, detail, at())
}

function issueNotice(driverId: string, type: Notice['type'], text: string) {
  const notice: Notice = { noticeId: id('NTC').toUpperCase(), driverId, type, text, issuedAt: new Date(clock || Date.now()).toISOString() }
  state.notices.unshift(notice)
  emit('notification', 'NoticeIssued', { ...notice })
}

function recordOffence(input: OffenceInput) {
  const driver = state.drivers.get(input.driverId)
  if (!driver) return null
  const history = state.offences.get(input.driverId) ?? []
  const br05 = applyDoubleDemerits(input, history)
  const offence: OffenceRecorded = {
    driverId: input.driverId,
    offenceId: id('OFF').toUpperCase(),
    date: input.date,
    code: input.code,
    offenceClass: input.offenceClass,
    basePoints: input.basePoints,
    appliedPoints: br05.appliedPoints,
    doubled: br05.doubled,
    rule: br05.rule,
    reason: input.alreadyPosted
      ? `Imported from the legacy ledger as already posted, so no points were added. ${br05.reason}`
      : br05.reason,
  }
  history.push(offence)
  history.sort((a, b) => a.date.localeCompare(b.date))
  state.offences.set(input.driverId, history)
  const limit = limitFor(driver.licenceClass)
  if (input.alreadyPosted) return { ...offence, totalPoints: driver.demeritPoints, limit, suspensionNotice: false }

  const before = driver.demeritPoints
  const totalPoints = before + offence.appliedPoints
  const suspensionNotice = totalPoints >= limit
  emit('demerit', 'OffenceRecorded', { ...offence })
  later(() => {
    emit('demerit', 'DemeritPointsChanged', { driverId: driver.driverId, points: totalPoints, limit, licenceClass: driver.licenceClass })
    if (suspensionNotice) {
      later(() => {
        emit('demerit', 'SuspensionThresholdReached', {
          driverId: driver.driverId,
          name: driver.name,
          points: totalPoints,
          limit,
          licenceClass: driver.licenceClass,
          rule: 'BR-04',
          reason: `${totalPoints} points is at or over the ${driver.licenceClass} limit of ${limit}.`,
        })
        const hasOpen = state.notices.some((n) => n.driverId === driver.driverId && n.type === 'SUSPENSION')
        if (!hasOpen) {
          later(
            () =>
              issueNotice(
                driver.driverId,
                'SUSPENSION',
                `${driver.name} has ${totalPoints} demerit points against a ${driver.licenceClass} limit of ${limit}. Licence suspension notice issued under BR-04.`,
              ),
            600,
          )
        }
      }, 350)
    }
  }, 450)
  driver.demeritPoints = totalPoints
  return { ...offence, totalPoints, limit, suspensionNotice }
}

function runProgression(runDate: string) {
  const upgraded: Record<string, unknown>[] = []
  for (const d of state.drivers.values()) {
    const p = evaluateProgression(d, runDate)
    if (!p.eligible || !p.toClass) continue
    const fromClass = d.licenceClass
    const entry = { fromClass, toClass: p.toClass, effectiveDate: runDate, rule: p.rule, reason: p.reason }
    d.history.push(entry)
    d.licenceClass = p.toClass
    d.classIssuedDate = runDate
    d.suspendedDays = 0
    const evt = { driverId: d.driverId, name: d.name, ...entry, tenureDays: p.tenureDays }
    upgraded.push(evt)
    emit('licence', 'LicenceProgressed', evt)
    issueNotice(d.driverId, 'UPGRADE', `${d.name} progressed from ${fromClass} to ${p.toClass} on ${runDate}. New licence card issued.`)
  }
  return { runDate, read: state.drivers.size, upgraded }
}

function parseMaster(): Driver[] {
  return DRVMAST.split('\n').map((l) => ({
    driverId: l.slice(0, 8),
    name: l
      .slice(8, 28)
      .trim()
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase())
      .replace(/^(.*) Obrien$/, "$1 O'Brien"),
    dob: legacyDate(l.slice(28, 36)),
    licenceClass: l.slice(36, 38) as LicenceClass,
    classIssuedDate: legacyDate(l.slice(38, 46)),
    p1IssuedDate: legacyDate(l.slice(46, 54)),
    demeritPoints: Number(l.slice(54, 56)),
    suspendedDays: Number(l.slice(56, 60)),
    history: [],
  }))
}

/** Replays the strangler loader and the first nightly run, so the mock starts where the real stack would. */
function seed() {
  state = { drivers: new Map(), offences: new Map(), notices: [], renewals: [], activity: [] }
  clock = Date.now() - 26 * 60_000
  for (const d of parseMaster()) {
    state.drivers.set(d.driverId, d)
    const { history: _h, dob: _d, ...detail } = d
    void _h
    void _d
    emit('licence', 'DriverRegistered', detail)
    clock += 900
  }
  for (const l of OFFENCES.split('\n')) {
    recordOffence({
      driverId: l.slice(0, 8),
      date: legacyDate(l.slice(8, 16)),
      code: l.slice(16, 20),
      offenceClass: l.slice(20, 22),
      basePoints: Number(l.slice(22, 24)),
      alreadyPosted: l[24] === 'P',
    })
    clock += 1300
  }
  clock += 60_000
  runProgression(RUN_DATE)
  clock = 0
}

export function resetMock() {
  seed()
}

export function subscribe(l: Listener) {
  listeners.add(l)
  return () => listeners.delete(l)
}

// ---- simulated background traffic --------------------------------------------------------

const PLATE_LETTERS = 'ABCDEFGHJKLMNPRSTUVWXYZ'
const randomPlate = () =>
  `${Math.floor(100 + Math.random() * 900)}${Array.from({ length: 3 }, () => PLATE_LETTERS[Math.floor(Math.random() * PLATE_LETTERS.length)]).join('')}`

export function simulateRenewal() {
  const vehicleType = (['CAR', 'CAR', 'CAR', 'MOTORCYCLE', 'TRAILER'] as const)[Math.floor(Math.random() * 5)]
  const q: QuoteInput = {
    plate: randomPlate(),
    vehicleType,
    cylinders: vehicleType === 'CAR' ? [3, 4, 4, 4, 6, 8][Math.floor(Math.random() * 6)] : vehicleType === 'MOTORCYCLE' ? 1 : 0,
    concession: Math.random() < 0.25,
    termMonths: [12, 12, 6, 3][Math.floor(Math.random() * 4)],
  }
  storeRenewal(q)
}

function storeRenewal(q: QuoteInput) {
  const quote = priceRenewal(q)
  if (isRuleError(quote)) return { status: 400, body: quote }
  const r: Renewal = { renewalId: id('RNW').toUpperCase(), plate: q.plate.toUpperCase(), vehicleType: q.vehicleType, term: q.termMonths, total: quote.total, rule: 'BR-06' }
  state.renewals.unshift(r)
  emit('registration', 'RegistrationRenewed', { ...r })
  return { status: 201, body: { ...r, ...quote } }
}

// ---- HTTP-ish router ----------------------------------------------------------------------

export interface MockResponse {
  status: number
  body: unknown
}

const notFound = (what: string): MockResponse => ({ status: 404, body: { error: `${what} not found` } })

function demeritsFor(d: Driver): Demerits {
  const limit = limitFor(d.licenceClass)
  return {
    driverId: d.driverId,
    licenceClass: d.licenceClass,
    points: d.demeritPoints,
    limit,
    offences: [...(state.offences.get(d.driverId) ?? [])],
    suspensionNotice: d.demeritPoints >= limit,
  }
}

export async function mockFetch(service: ServiceName, method: string, url: string, body?: unknown): Promise<MockResponse> {
  await new Promise((r) => setTimeout(r, 60 + Math.random() * 140))
  const { pathname, searchParams } = new URL(url, 'http://mock')
  const m = (re: RegExp) => pathname.match(re)
  let match: RegExpMatchArray | null

  if (pathname === '/actuator/health') return { status: 200, body: { status: 'UP' } }
  if (method === 'POST' && pathname === '/api/admin/reset') {
    resetMock()
    return { status: 200, body: { service, reset: true } }
  }

  switch (service) {
    case 'licence':
      if (method === 'GET' && pathname === '/api/drivers') return { status: 200, body: [...state.drivers.values()].map(clone) }
      if (method === 'GET' && (match = m(/^\/api\/drivers\/([^/]+)$/))) {
        const d = state.drivers.get(match[1].toUpperCase())
        return d ? { status: 200, body: clone(d) } : notFound(`Driver ${match[1]}`)
      }
      if (method === 'GET' && (match = m(/^\/api\/drivers\/([^/]+)\/progression$/))) {
        const d = state.drivers.get(match[1].toUpperCase())
        return d ? { status: 200, body: evaluateProgression(d, searchParams.get('asOf') ?? todayIso()) } : notFound(`Driver ${match[1]}`)
      }
      if (method === 'POST' && pathname === '/api/progression/run') {
        return { status: 200, body: runProgression(searchParams.get('runDate') ?? todayIso()) }
      }
      break
    case 'demerit':
      if (method === 'POST' && pathname === '/api/offences') {
        const res = recordOffence(body as OffenceInput)
        return res ? { status: 201, body: res } : notFound(`Driver ${(body as OffenceInput).driverId}`)
      }
      if (method === 'GET' && (match = m(/^\/api\/drivers\/([^/]+)\/demerits$/))) {
        const d = state.drivers.get(match[1].toUpperCase())
        return d ? { status: 200, body: demeritsFor(d) } : notFound(`Driver ${match[1]}`)
      }
      break
    case 'registration':
      if (method === 'POST' && pathname === '/api/renewals/quote') {
        const q = priceRenewal(body as QuoteInput)
        return { status: isRuleError(q) ? 400 : 200, body: q }
      }
      if (method === 'POST' && pathname === '/api/renewals') return storeRenewal(body as QuoteInput)
      if (method === 'GET' && pathname === '/api/renewals') return { status: 200, body: state.renewals }
      break
    case 'notification':
      if (method === 'GET' && pathname === '/api/notices') {
        const driverId = searchParams.get('driverId')
        return { status: 200, body: state.notices.filter((n) => !driverId || n.driverId === driverId) }
      }
      if (method === 'GET' && pathname === '/api/activity') {
        const limit = Number(searchParams.get('limit') ?? 100)
        return { status: 200, body: state.activity.slice(0, limit) }
      }
      break
  }
  return notFound(`${method} ${pathname}`)
}

function clone<T>(x: T): T {
  return JSON.parse(JSON.stringify(x))
}
