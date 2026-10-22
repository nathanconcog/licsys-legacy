const DAY = 86_400_000

export function toUtc(iso: string) {
  const [y, m, d] = iso.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

export function daysBetween(fromIso: string, toIso: string) {
  return Math.round((toUtc(toIso) - toUtc(fromIso)) / DAY)
}

export function todayIso() {
  const n = new Date()
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`
}

export function addDays(iso: string, days: number) {
  return new Date(toUtc(iso) + days * DAY).toISOString().slice(0, 10)
}

const fmt = new Intl.DateTimeFormat('en-AU', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

export function formatDate(iso?: string | null) {
  if (!iso) return '—'
  return fmt.format(new Date(toUtc(iso.slice(0, 10))))
}

export function legacyDate(yyyymmdd: string) {
  return `${yyyymmdd.slice(0, 4)}-${yyyymmdd.slice(4, 6)}-${yyyymmdd.slice(6, 8)}`
}

export function relativeTime(iso: string, now = Date.now()) {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  const m = Math.round(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.round(h / 24)}d ago`
}

export function age(dob: string, asOf: string) {
  const [y, m, d] = dob.split('-').map(Number)
  const [ty, tm, td] = asOf.split('-').map(Number)
  return ty - y - (tm < m || (tm === m && td < d) ? 1 : 0)
}
