import { useEffect, useState } from 'react'
import { api } from './api'
import { useLive } from './live'
import type { Driver } from './types'

export function useDrivers() {
  const { version, subscribe } = useLive()
  const [drivers, setDrivers] = useState<Driver[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    api
      .drivers()
      .then((d) => {
        if (cancelled) return
        setDrivers(d.sort((a, b) => a.driverId.localeCompare(b.driverId)))
        setError(null)
      })
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [version, tick])

  useEffect(
    () =>
      subscribe((e) => {
        if (['DriverRegistered', 'LicenceProgressed', 'DemeritPointsChanged'].includes(e.detailType)) {
          setTimeout(() => setTick((t) => t + 1), 300)
        }
      }),
    [subscribe],
  )

  return { drivers, error }
}
