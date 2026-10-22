import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { api, isMock, simulateRenewal, streamActivity, type StreamStatus } from './api'
import { SERVICES } from './domain'
import type { ActivityEvent, Health, ServiceName } from './types'

interface LiveState {
  events: ActivityEvent[]
  status: StreamStatus
  /** Event ids that arrived over the stream during this page session (used to animate). */
  fresh: Set<string>
  health: Record<ServiceName, Health['status']>
  refreshHealth: () => void
  simulate: boolean
  setSimulate: (on: boolean) => void
  subscribe: (fn: (e: ActivityEvent) => void) => () => void
  version: number
  bump: () => void
  reload: () => Promise<void>
}

const Ctx = createContext<LiveState | null>(null)

export function LiveProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<ActivityEvent[]>([])
  const [status, setStatus] = useState<StreamStatus>('connecting')
  const [fresh, setFresh] = useState<Set<string>>(new Set())
  const [health, setHealth] = useState<Record<ServiceName, Health['status']>>(
    () => Object.fromEntries(SERVICES.map((s) => [s, 'UNKNOWN'])) as Record<ServiceName, Health['status']>,
  )
  const [simulate, setSimulate] = useState(() => isMock())
  const [version, setVersion] = useState(0)
  const subs = useRef(new Set<(e: ActivityEvent) => void>())

  const reload = useCallback(async () => {
    try {
      setEvents(await api.activity(100))
    } catch {
      setEvents([])
    }
  }, [])

  useEffect(() => {
    void reload()
    return streamActivity(
      (e) => {
        setEvents((prev) => (prev.some((p) => p.eventId === e.eventId) ? prev : [e, ...prev].slice(0, 300)))
        setFresh((prev) => new Set(prev).add(e.eventId))
        subs.current.forEach((fn) => fn(e))
      },
      setStatus,
    )
  }, [reload])

  const refreshHealth = useCallback(() => {
    SERVICES.forEach(async (s) => {
      const h = await api.health(s)
      setHealth((prev) => (prev[s] === h.status ? prev : { ...prev, [s]: h.status }))
    })
  }, [])

  useEffect(() => {
    refreshHealth()
    const t = setInterval(refreshHealth, 10_000)
    return () => clearInterval(t)
  }, [refreshHealth])

  useEffect(() => {
    if (!simulate || !isMock()) return
    let t: ReturnType<typeof setTimeout>
    const loop = () => {
      t = setTimeout(() => {
        simulateRenewal()
        loop()
      }, 5_000 + Math.random() * 6_000)
    }
    loop()
    return () => clearTimeout(t)
  }, [simulate])

  const subscribe = useCallback((fn: (e: ActivityEvent) => void) => {
    subs.current.add(fn)
    return () => {
      subs.current.delete(fn)
    }
  }, [])

  const bump = useCallback(() => setVersion((v) => v + 1), [])

  const value = useMemo(
    () => ({ events, status, fresh, health, refreshHealth, simulate, setSimulate, subscribe, version, bump, reload }),
    [events, status, fresh, health, refreshHealth, simulate, subscribe, version, bump, reload],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useLive() {
  const v = useContext(Ctx)
  if (!v) throw new Error('useLive outside LiveProvider')
  return v
}
