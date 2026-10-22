import { DOUBLE_ELIGIBLE, NEXT_CLASS, POINT_LIMITS } from '../lib/domain'
import { daysBetween } from '../lib/dates'
import type { Driver, LicenceClass, Progression, Quote, QuoteInput, RuleError } from '../lib/types'

const CLASS_LABEL: Record<string, string> = { SP: 'speeding', MP: 'mobile phone', SB: 'seatbelt', HL: 'helmet', GN: 'general' }

/** BR-02 / BR-03 progression test, counted from the current class issue date. */
export function evaluateProgression(d: Driver, asOf: string): Progression {
  const toClass = NEXT_CLASS[d.licenceClass]
  if (d.licenceClass === 'OP' || !toClass) {
    return {
      eligible: false,
      toClass: null,
      tenureDays: Math.max(0, daysBetween(d.classIssuedDate, asOf) - d.suspendedDays),
      requiredDays: null,
      rule: 'BR-01',
      reason: 'Open is the highest licence class, so there is no further progression.',
    }
  }
  if (d.licenceClass === 'LN') {
    return {
      eligible: false,
      toClass,
      tenureDays: Math.max(0, daysBetween(d.classIssuedDate, asOf) - d.suspendedDays),
      requiredDays: null,
      rule: 'BR-01',
      reason: 'Learners progress to P1 by passing a driving test, not on the nightly run.',
    }
  }
  const rule = d.licenceClass === 'P1' ? 'BR-02' : 'BR-03'
  const requiredDays = d.licenceClass === 'P1' ? 365 : 730
  const tenureDays = Math.max(0, daysBetween(d.classIssuedDate, asOf) - d.suspendedDays)
  const susp = d.suspendedDays ? `, less ${d.suspendedDays} suspended days` : ''
  const from = `counted from the ${d.licenceClass} issue date ${d.classIssuedDate}${susp}`
  const p2Note = d.licenceClass === 'P2' ? ' Time spent on P1 does not count.' : ''
  if (tenureDays < requiredDays) {
    return {
      eligible: false,
      toClass,
      tenureDays,
      requiredDays,
      rule,
      reason: `Held ${d.licenceClass} for ${tenureDays} of the ${requiredDays} days required, ${from}.${p2Note} ${requiredDays - tenureDays} days to go.`,
    }
  }
  if (d.demeritPoints >= 4) {
    return {
      eligible: false,
      toClass,
      tenureDays,
      requiredDays,
      rule,
      reason: `Tenure is met (${tenureDays} days, ${from}) but the driver has ${d.demeritPoints} demerit points. Progression needs fewer than 4.`,
    }
  }
  return {
    eligible: true,
    toClass,
    tenureDays,
    requiredDays,
    rule,
    reason: `Held ${d.licenceClass} for ${tenureDays} days (${from}), at least ${requiredDays}, with ${d.demeritPoints} demerit points (fewer than 4). Eligible for ${toClass}.${p2Note}`,
  }
}

export function limitFor(cls: LicenceClass) {
  return POINT_LIMITS[cls] ?? 4
}

/** BR-05 double demerits: same class, within 365 days before. */
export function applyDoubleDemerits(
  offence: { date: string; offenceClass: string; basePoints: number },
  history: { date: string; offenceClass: string }[],
) {
  const cls = offence.offenceClass
  const label = CLASS_LABEL[cls] ?? cls
  if (!DOUBLE_ELIGIBLE.includes(cls)) {
    return {
      appliedPoints: offence.basePoints,
      doubled: false,
      rule: 'BR-05',
      reason: `${cls} (${label}) offences are not eligible for double demerits. Normal points apply.`,
    }
  }
  const prior = history.filter((h) => {
    const gap = daysBetween(h.date, offence.date)
    return gap >= 0 && gap <= 365
  })
  const same = prior.filter((h) => h.offenceClass === cls).sort((a, b) => b.date.localeCompare(a.date))[0]
  if (same) {
    const gap = daysBetween(same.date, offence.date)
    return {
      appliedPoints: offence.basePoints * 2,
      doubled: true,
      rule: 'BR-05',
      reason: `Repeat ${cls} (${label}) offence: the previous ${cls} offence on ${same.date} was ${gap} days earlier, inside the 365-day window. Base ${offence.basePoints} points doubled to ${offence.basePoints * 2}.`,
    }
  }
  const other = prior.sort((a, b) => b.date.localeCompare(a.date))[0]
  if (other) {
    return {
      appliedPoints: offence.basePoints,
      doubled: false,
      rule: 'BR-05',
      reason: `The previous offence within 365 days was ${other.offenceClass} on ${other.date}, a different class. Double points need a repeat of the same class, so normal points apply.`,
    }
  }
  const older = history.filter((h) => h.offenceClass === cls && h.date < offence.date).sort((a, b) => b.date.localeCompare(a.date))[0]
  if (older) {
    return {
      appliedPoints: offence.basePoints,
      doubled: false,
      rule: 'BR-05',
      reason: `The previous ${cls} offence on ${older.date} was ${daysBetween(older.date, offence.date)} days earlier, outside the 365-day window. Normal points apply.`,
    }
  }
  return {
    appliedPoints: offence.basePoints,
    doubled: false,
    rule: 'BR-05',
    reason: `No previous offence in the 365 days before ${offence.date}. Normal points apply.`,
  }
}

const cents = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100
const money = (n: number) => n.toFixed(2)

const TERM_FACTORS: Record<number, number> = { 12: 1, 6: 0.52, 3: 0.27 }

/** BR-06 fees, rounded half up to the cent at each step. */
export function priceRenewal(q: QuoteInput): Quote | RuleError {
  let base: number
  let baseLabel: string
  switch (q.vehicleType) {
    case 'CAR':
      if (!Number.isInteger(q.cylinders) || q.cylinders < 1) {
        return { rule: 'BR-06', reason: 'A car must have at least 1 cylinder to be priced.' }
      }
      if (q.cylinders <= 3) [base, baseLabel] = [312.4, 'Car, 1 to 3 cylinders']
      else if (q.cylinders === 4) [base, baseLabel] = [389.15, 'Car, 4 cylinders']
      else if (q.cylinders <= 6) [base, baseLabel] = [572.8, 'Car, 5 to 6 cylinders']
      else [base, baseLabel] = [780.55, 'Car, 7 or more cylinders']
      break
    case 'MOTORCYCLE':
      ;[base, baseLabel] = [118.25, 'Motorcycle']
      break
    case 'TRAILER':
      ;[base, baseLabel] = [94.6, 'Trailer']
      break
    default:
      return { rule: 'BR-06', reason: `Unknown vehicle type "${q.vehicleType}". Only CAR, MOTORCYCLE and TRAILER can be registered.` }
  }
  const factor = TERM_FACTORS[q.termMonths]
  if (factor === undefined) {
    return { rule: 'BR-06', reason: `A ${q.termMonths}-month term is not offered. Choose 12, 6 or 3 months.` }
  }
  const lines = [{ label: `Annual base fee (${baseLabel})`, amount: money(base), rule: 'BR-06' }]
  let fee = base
  if (q.concession && q.vehicleType !== 'TRAILER') {
    fee = cents(base * 0.5)
    lines.push({ label: 'Concession 50%', amount: money(fee - base), rule: 'BR-06.1' })
  }
  const termFee = cents(fee * factor)
  if (factor !== 1) {
    lines.push({ label: `${q.termMonths}-month term (× ${factor.toFixed(2)})`, amount: money(termFee - fee), rule: 'BR-06.2' })
  }
  const tif = cents(26.35 * factor)
  lines.push({ label: `Traffic improvement fee${factor !== 1 ? ` ($26.35 × ${factor.toFixed(2)})` : ''}`, amount: money(tif), rule: 'BR-06.3' })
  const admin = q.termMonths < 12 ? 8.4 : 0
  if (admin) lines.push({ label: 'Administration fee (term under 12 months)', amount: money(admin), rule: 'BR-06.4' })
  return { total: money(cents(termFee + tif + admin)), lines }
}

export function isRuleError(x: unknown): x is RuleError {
  return typeof x === 'object' && x !== null && 'reason' in x && !('total' in x)
}
