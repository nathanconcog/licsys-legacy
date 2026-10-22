import { useEffect, useState } from 'react'
import { displayName } from './domain'
import type { ActivityEvent } from './types'

type D = Record<string, unknown>

export function summarise(e: ActivityEvent): string {
  const d = e.detail as D
  const who = (d.name ? displayName(d.name as string) : undefined) ?? (d.driverId as string) ?? ''
  switch (e.detailType) {
    case 'DriverRegistered':
      return `${who} registered on ${d.licenceClass} with ${d.demeritPoints ?? 0} points`
    case 'LicenceProgressed':
      return `${who} progressed ${d.fromClass} → ${d.toClass}`
    case 'OffenceRecorded':
      return `${d.driverId} · ${d.code} recorded, +${d.appliedPoints} pts${d.doubled ? ' (doubled)' : ''}`
    case 'DemeritPointsChanged':
      return `${d.driverId} now on ${d.points}/${d.limit} points`
    case 'SuspensionThresholdReached':
      return `${who} reached ${d.points}/${d.limit} points`
    case 'RegistrationRenewed':
      return `${d.plate} · ${String(d.vehicleType).toLowerCase()} renewed for ${d.term} months, $${d.total}`
    case 'NoticeIssued':
      return `${String(d.type).toLowerCase() === 'suspension' ? 'Suspension' : 'Upgrade'} notice issued to ${d.driverId}`
    default:
      return e.detailType
  }
}

export function useNow(intervalMs = 5000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}
