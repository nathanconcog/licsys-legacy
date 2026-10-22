import type { LicenceClass, ServiceName } from './types'

export const CLASS_NAMES: Record<LicenceClass, string> = {
  LN: 'Learner',
  P1: 'Provisional P1',
  P2: 'Provisional P2',
  OP: 'Open',
}

export const NEXT_CLASS: Record<LicenceClass, LicenceClass | null> = { LN: 'P1', P1: 'P2', P2: 'OP', OP: null }

export const POINT_LIMITS: Record<LicenceClass, number> = { LN: 4, P1: 4, P2: 4, OP: 12 }

export const DOUBLE_ELIGIBLE = ['SP', 'MP', 'SB', 'HL']

export const OFFENCE_CLASSES: Record<string, string> = {
  SP: 'Speeding > 10 km/h',
  MP: 'Mobile phone use',
  SB: 'Seatbelt',
  HL: 'Motorcycle helmet',
  GN: 'General',
}

export const OFFENCE_CODES: { code: string; offenceClass: string; basePoints: number; label: string }[] = [
  { code: 'SP11', offenceClass: 'SP', basePoints: 3, label: 'Speeding 11-20 km/h over' },
  { code: 'SP12', offenceClass: 'SP', basePoints: 4, label: 'Speeding 21-30 km/h over' },
  { code: 'MP01', offenceClass: 'MP', basePoints: 4, label: 'Mobile phone while driving' },
  { code: 'SB01', offenceClass: 'SB', basePoints: 3, label: 'Seatbelt not worn' },
  { code: 'HL01', offenceClass: 'HL', basePoints: 3, label: 'Helmet not worn' },
  { code: 'PK01', offenceClass: 'GN', basePoints: 2, label: 'Parking in a no-stopping zone' },
]

export const OFFENCE_LABELS: Record<string, string> = Object.fromEntries(OFFENCE_CODES.map((o) => [o.code, o.label]))

export interface ContextMeta {
  name: ServiceName
  title: string
  port: number
  color: string
  soft: string
  text: string
  ring: string
  table: string
  queue: string | null
  rules: string
}

export const CONTEXTS: Record<ServiceName, ContextMeta> = {
  licence: {
    name: 'licence',
    title: 'Driver Licensing',
    port: 8081,
    color: '#6366f1',
    soft: 'bg-indigo-50',
    text: 'text-indigo-600',
    ring: 'ring-indigo-200',
    table: 'licence-drivers',
    queue: 'licence-inbox',
    rules: 'BR-01 to BR-03',
  },
  demerit: {
    name: 'demerit',
    title: 'Demerits & Offences',
    port: 8082,
    color: '#f43f5e',
    soft: 'bg-rose-50',
    text: 'text-rose-600',
    ring: 'ring-rose-200',
    table: 'demerit-ledger',
    queue: 'demerit-inbox',
    rules: 'BR-04, BR-05',
  },
  registration: {
    name: 'registration',
    title: 'Vehicle Registration',
    port: 8083,
    color: '#0ea5e9',
    soft: 'bg-sky-50',
    text: 'text-sky-600',
    ring: 'ring-sky-200',
    table: 'registration-renewals',
    queue: null,
    rules: 'BR-06',
  },
  notification: {
    name: 'notification',
    title: 'Notifications',
    port: 8084,
    color: '#f59e0b',
    soft: 'bg-amber-50',
    text: 'text-amber-600',
    ring: 'ring-amber-200',
    table: 'notification-activity',
    queue: 'notification-inbox',
    rules: 'Notices & activity feed',
  },
}

export const SERVICES: ServiceName[] = ['licence', 'demerit', 'registration', 'notification']

/** EventBridge rules on licsys-bus, from modern/ARCHITECTURE.md. */
export const ROUTES: { queue: string; consumer: ServiceName; events: string[] | '*' }[] = [
  { queue: 'licence-inbox', consumer: 'licence', events: ['DemeritPointsChanged'] },
  { queue: 'demerit-inbox', consumer: 'demerit', events: ['DriverRegistered', 'LicenceProgressed'] },
  { queue: 'notification-inbox', consumer: 'notification', events: ['SuspensionThresholdReached', 'LicenceProgressed'] },
  { queue: 'activity-inbox', consumer: 'notification', events: '*' },
]

export function routesFor(detailType: string) {
  return ROUTES.filter((r) => r.events === '*' || r.events.includes(detailType))
}

export function serviceOf(source: string): ServiceName {
  const s = source.replace(/^licsys\./, '') as ServiceName
  return SERVICES.includes(s) ? s : 'notification'
}

export const LEGACY_MAP: {
  program: string
  paragraph: string
  what: string
  service: ServiceName
  replacedBy: string
  rule: string
}[] = [
  { program: 'DMRPTS', paragraph: '2200-APPLY-POINTS', what: 'Repeat-offence double points', service: 'demerit', replacedBy: 'POST /api/offences', rule: 'BR-05' },
  { program: 'DMRPTS', paragraph: '3000-CHECK-THRESHOLDS', what: 'Suspension threshold check', service: 'demerit', replacedBy: 'SuspensionThresholdReached', rule: 'BR-04' },
  { program: 'DMRPTS', paragraph: '4000-WRITE-MASTER', what: 'Write posted master', service: 'licence', replacedBy: 'DemeritPointsChanged projection', rule: 'BR-04' },
  { program: 'LICUPG', paragraph: '2000-EVALUATE-PROGRESSION', what: 'P1 → P2 → Open tenure test', service: 'licence', replacedBy: 'POST /api/progression/run', rule: 'BR-02, BR-03' },
  { program: 'LICUPG', paragraph: '2100-UPGRADE', what: 'Class change + reset', service: 'licence', replacedBy: 'LicenceProgressed', rule: 'BR-03' },
  { program: 'REGFEE', paragraph: '2000-PRICE-RENEWAL', what: 'Renewal fee calculation', service: 'registration', replacedBy: 'POST /api/renewals/quote', rule: 'BR-06' },
  { program: 'NOTICES.rpt', paragraph: 'NOTICE file', what: 'Printed suspension notices', service: 'notification', replacedBy: 'NoticeIssued', rule: 'BR-04' },
  { program: 'LICNIGHT.jcl', paragraph: 'STEP010-030', what: 'Nightly batch schedule', service: 'licence', replacedBy: 'EventBridge Scheduler', rule: '—' },
]

/** Legacy records hold names in capitals ("DANIEL OBRIEN"); show them in title case. */
export function displayName(name: string): string {
  if (name !== name.toUpperCase()) return name
  return name
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
    .replace(/\bO'?brien\b/i, "O'Brien")
}
