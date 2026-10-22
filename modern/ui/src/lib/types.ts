export type LicenceClass = 'LN' | 'P1' | 'P2' | 'OP'
export type OffenceClass = 'SP' | 'MP' | 'SB' | 'HL' | 'GN' | string
export type ServiceName = 'licence' | 'demerit' | 'registration' | 'notification'

export interface ProgressionHistory {
  fromClass: LicenceClass
  toClass: LicenceClass
  effectiveDate: string
  rule: string
  reason: string
}

export interface Driver {
  driverId: string
  name: string
  dob: string
  licenceClass: LicenceClass
  classIssuedDate: string
  p1IssuedDate: string
  suspendedDays: number
  demeritPoints: number
  history: ProgressionHistory[]
}

export interface Progression {
  eligible: boolean
  toClass: LicenceClass | null
  tenureDays: number
  requiredDays: number | null
  rule: string
  reason: string
}

export interface OffenceRecorded {
  driverId: string
  offenceId: string
  date: string
  code: string
  offenceClass: OffenceClass
  basePoints: number
  appliedPoints: number
  doubled: boolean
  rule: string
  reason: string
}

export interface OffenceResult extends OffenceRecorded {
  totalPoints: number
  limit: number
  suspensionNotice: boolean
}

export interface Demerits {
  driverId: string
  licenceClass: LicenceClass
  points: number
  limit: number
  offences: OffenceRecorded[]
  suspensionNotice: boolean
}

export interface OffenceInput {
  driverId: string
  date: string
  code: string
  offenceClass: OffenceClass
  basePoints: number
  alreadyPosted?: boolean
}

export interface Notice {
  noticeId: string
  driverId: string
  type: 'SUSPENSION' | 'UPGRADE'
  text: string
  issuedAt: string
}

export type VehicleType = 'CAR' | 'MOTORCYCLE' | 'TRAILER'

export interface QuoteInput {
  plate: string
  vehicleType: VehicleType | string
  cylinders: number
  concession: boolean
  termMonths: number
}

export interface QuoteLine {
  label: string
  amount: string
  rule: string
}

export interface Quote {
  total: string
  lines: QuoteLine[]
}

export interface RuleError {
  rule: string
  reason: string
}

export interface ActivityEvent {
  eventId: string
  time: string
  source: string
  detailType: string
  detail: Record<string, unknown>
}

export interface Health {
  status: 'UP' | 'DOWN' | 'UNKNOWN'
}

export interface ParityDriver {
  driverId: string
  name: string
  legacy: { licenceClass: LicenceClass; points: number; suspensionNotice: boolean }
  modern: { licenceClass: LicenceClass; points: number; suspensionNotice: boolean }
  status: 'MATCH' | 'EXPECTED_DIFFERENCE' | 'MISMATCH'
  rule: string
  reason: string
}

export interface ParityRenewal {
  plate: string
  legacyTotal: string | null
  modernTotal: string | null
  match: boolean
}

export interface ParityReport {
  generatedAt: string
  summary: {
    drivers: number
    matched: number
    expectedDifferences: number
    unexpected: number
    renewals: number
    renewalMatched: number
  }
  drivers: ParityDriver[]
  renewals: ParityRenewal[]
}
