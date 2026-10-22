import { mockFetch, simulateRenewal, subscribe } from '../mock/server'
import type {
  ActivityEvent,
  Demerits,
  Driver,
  Health,
  Notice,
  OffenceInput,
  OffenceResult,
  ParityReport,
  Progression,
  Quote,
  QuoteInput,
  RuleError,
  ServiceName,
} from './types'

const MOCK_KEY = 'licsys.mock'

export function isMock(): boolean {
  const override = typeof localStorage !== 'undefined' ? localStorage.getItem(MOCK_KEY) : null
  if (override !== null) return override === '1'
  return import.meta.env.VITE_MOCK === '1' || import.meta.env.VITE_MOCK === 'true'
}

export function setMock(on: boolean) {
  localStorage.setItem(MOCK_KEY, on ? '1' : '0')
  window.location.reload()
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public body: unknown,
  ) {
    super(
      (body as RuleError)?.reason ??
        (body as { message?: string })?.message ??
        (body as { error?: string })?.error ??
        `Request failed (${status})`,
    )
  }
}

async function request<T>(service: ServiceName, method: string, path: string, body?: unknown): Promise<T> {
  if (isMock()) {
    const res = await mockFetch(service, method, path, body === undefined ? undefined : JSON.parse(JSON.stringify(body)))
    if (res.status >= 400) throw new ApiError(res.status, res.body)
    return res.body as T
  }
  const res = await fetch(`/${service}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let parsed: unknown = undefined
  try {
    parsed = text ? JSON.parse(text) : undefined
  } catch {
    parsed = text
  }
  if (!res.ok) throw new ApiError(res.status, parsed)
  return parsed as T
}

export const api = {
  drivers: () => request<Driver[]>('licence', 'GET', '/api/drivers'),
  driver: (id: string) => request<Driver>('licence', 'GET', `/api/drivers/${encodeURIComponent(id)}`),
  progression: (id: string, asOf: string) =>
    request<Progression>('licence', 'GET', `/api/drivers/${encodeURIComponent(id)}/progression?asOf=${asOf}`),
  demerits: (id: string) => request<Demerits>('demerit', 'GET', `/api/drivers/${encodeURIComponent(id)}/demerits`),
  recordOffence: (input: OffenceInput) => request<OffenceResult>('demerit', 'POST', '/api/offences', input),
  notices: (driverId: string) => request<Notice[]>('notification', 'GET', `/api/notices?driverId=${encodeURIComponent(driverId)}`),
  activity: (limit = 100) => request<ActivityEvent[]>('notification', 'GET', `/api/activity?limit=${limit}`),
  quote: (q: QuoteInput) => request<Quote>('registration', 'POST', '/api/renewals/quote', q),
  renew: (q: QuoteInput) => request<Quote & { renewalId: string }>('registration', 'POST', '/api/renewals', q),
  health: async (service: ServiceName): Promise<Health> => {
    try {
      const h = await request<Health>(service, 'GET', '/actuator/health')
      return { status: h?.status === 'UP' ? 'UP' : 'DOWN' }
    } catch {
      return { status: 'DOWN' }
    }
  },
  reset: (service: ServiceName) => request<unknown>(service, 'POST', '/api/admin/reset'),
  parity: async (): Promise<ParityReport> => {
    const res = await fetch(`${import.meta.env.BASE_URL}parity.json`, { cache: 'no-store' })
    if (!res.ok) throw new ApiError(res.status, await res.text())
    return res.json()
  },
}

const KNOWN_EVENTS = [
  'DriverRegistered',
  'LicenceProgressed',
  'OffenceRecorded',
  'DemeritPointsChanged',
  'SuspensionThresholdReached',
  'RegistrationRenewed',
  'NoticeIssued',
]

export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'mock'

/** Subscribes to the activity SSE stream (or the in-browser mock bus). Returns an unsubscribe function. */
export function streamActivity(onEvent: (e: ActivityEvent) => void, onStatus: (s: StreamStatus) => void) {
  if (isMock()) {
    onStatus('mock')
    return subscribe(onEvent)
  }
  onStatus('connecting')
  const es = new EventSource('/notification/api/activity/stream')
  const seen = new Set<string>()
  const handle = (msg: MessageEvent) => {
    try {
      const e = JSON.parse(msg.data) as ActivityEvent
      if (!e?.eventId || seen.has(e.eventId)) return
      seen.add(e.eventId)
      onEvent(e)
    } catch {
      /* ignore keep-alives and malformed frames */
    }
  }
  es.onopen = () => onStatus('live')
  es.onerror = () => onStatus('reconnecting')
  es.onmessage = handle
  for (const name of ['activity', ...KNOWN_EVENTS]) es.addEventListener(name, handle as EventListener)
  return () => es.close()
}

export { simulateRenewal }
