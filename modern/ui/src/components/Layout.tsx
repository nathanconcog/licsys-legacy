import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { Activity, Boxes, Car, GitCompareArrows, RotateCcw, Search, UserRound } from 'lucide-react'
import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, isMock, setMock } from '../lib/api'
import { CONTEXTS, SERVICES } from '../lib/domain'
import { useLive } from '../lib/live'
import { CommandPalette } from './CommandPalette'
import { useToast } from './Toast'
import { Button, StatusDot } from './ui'

const NAV = [
  { to: '/drivers', label: 'Driver 360', icon: UserRound },
  { to: '/activity', label: 'Live activity', icon: Activity },
  { to: '/architecture', label: 'Architecture', icon: Boxes },
  { to: '/parity', label: 'Parity', icon: GitCompareArrows },
  { to: '/renewals', label: 'Registration quote', icon: Car },
]

const TITLES: Record<string, string> = {
  drivers: 'Driver 360',
  activity: 'Live activity',
  architecture: 'Architecture',
  parity: 'Legacy parity',
  renewals: 'Registration quote',
}

function Logo() {
  return (
    <div className="flex items-center gap-2.5">
      <div className="relative flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-indigo-400 to-indigo-600 shadow-lg shadow-indigo-500/30">
        <svg viewBox="0 0 32 32" className="h-5 w-5">
          <path d="M10 8v15h12" stroke="#fff" strokeWidth="3.2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx="21.5" cy="10.5" r="2.6" fill="#fff" />
        </svg>
      </div>
      <div className="leading-tight">
        <div className="text-[14px] font-semibold tracking-tight text-white">LICSYS Next</div>
        <div className="text-[11px] text-slate-400">Driver & vehicle platform</div>
      </div>
    </div>
  )
}

export function Layout() {
  const { events, fresh, health, status, simulate, setSimulate, bump, reload } = useLive()
  const toast = useToast()
  const navigate = useNavigate()
  const location = useLocation()
  const [resetting, setResetting] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const mock = isMock()
  const section = location.pathname.split('/')[1] || 'drivers'
  const upCount = SERVICES.filter((s) => health[s] === 'UP').length

  async function reset() {
    setResetting(true)
    const results = await Promise.allSettled(SERVICES.map((s) => api.reset(s)))
    setResetting(false)
    const failed = SERVICES.filter((_, i) => results[i].status === 'rejected')
    if (failed.length) {
      toast({ tone: 'error', title: 'Reset partly failed', body: `No response from ${failed.join(', ')}.` })
    } else {
      toast({ tone: 'success', title: 'Demo reset', body: mock ? 'Mock data reloaded from the legacy files.' : 'All four services wiped their tables.' })
    }
    await reload()
    bump()
  }

  return (
    <div className="flex min-h-screen">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col bg-ink-950 text-slate-300 lg:flex">
        <div className="px-5 pb-6 pt-5">
          <Logo />
        </div>
        <button
          onClick={() => setPaletteOpen(true)}
          className="mx-3 mb-4 flex h-8 items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-2.5 text-xs text-slate-400 transition hover:bg-white/10"
        >
          <Search className="h-3.5 w-3.5" />
          Find a driver…
          <span className="ml-auto rounded border border-white/10 px-1 font-mono text-[10px]">⌘K</span>
        </button>
        <nav className="flex flex-col gap-0.5 px-3">
          <div className="px-2 pb-1.5 text-[10px] font-medium uppercase tracking-[0.12em] text-slate-500">Workspace</div>
          {NAV.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                clsx(
                  'group relative flex h-8 items-center gap-2.5 rounded-lg px-2.5 text-[13px] font-medium transition',
                  isActive ? 'bg-white/10 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-slate-200',
                )
              }
            >
              {({ isActive }) => (
                <>
                  {isActive && <motion.span layoutId="nav-active" className="absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-indigo-400" />}
                  <Icon className="h-4 w-4" />
                  {label}
                  {to === '/activity' && fresh.size > 0 && (
                    <span className="ml-auto rounded-full bg-indigo-500/20 px-1.5 py-px font-mono text-[10px] text-indigo-300">{fresh.size}</span>
                  )}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        <div className="mt-auto space-y-3 p-3">
          <div className="rounded-xl border border-white/10 bg-white/[0.03] p-3">
            <div className="mb-2.5 flex items-center justify-between text-[11px]">
              <span className="font-medium text-slate-300">Services</span>
              <span className="font-mono text-slate-500">{upCount}/4 up</span>
            </div>
            <div className="space-y-1.5">
              {SERVICES.map((s) => (
                <div key={s} className="flex items-center gap-2 text-[12px] text-slate-400">
                  <StatusDot status={health[s]} size={6} />
                  <span className="capitalize">{s}</span>
                  <span className="ml-auto font-mono text-[10px] text-slate-600">:{CONTEXTS[s].port}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="rounded-xl border border-white/10 bg-white/[0.03] p-3">
            <label className="flex cursor-pointer items-center justify-between text-[12px]">
              <span className="text-slate-300">Mock data</span>
              <Toggle on={mock} onChange={(v) => setMock(v)} />
            </label>
            {mock && (
              <label className="mt-2 flex cursor-pointer items-center justify-between text-[12px]">
                <span className="text-slate-400">Simulate traffic</span>
                <Toggle on={simulate} onChange={setSimulate} />
              </label>
            )}
            <p className="mt-2 text-[10.5px] leading-snug text-slate-500">
              {mock ? 'In-browser mock of the four service contracts.' : 'Talking to the services through the API proxy.'}
            </p>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col lg:pl-60">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-slate-200/80 bg-white/80 px-5 backdrop-blur-xl lg:px-8">
          <div className="flex items-center gap-2 text-sm">
            <span className="text-slate-400">LICSYS Next</span>
            <span className="text-slate-300">/</span>
            <span className="font-medium text-slate-900">{TITLES[section] ?? 'Driver 360'}</span>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <StreamBadge status={status} count={events.length} />
            {mock && <span className="hidden rounded-md bg-amber-50 px-2 py-1 text-[11px] font-medium text-amber-700 ring-1 ring-inset ring-amber-200 sm:inline">Mock mode</span>}
            <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => setPaletteOpen(true)}>
              <Search className="h-3.5 w-3.5" />
            </Button>
            <Button size="sm" onClick={reset} loading={resetting}>
              {!resetting && <RotateCcw className="h-3.5 w-3.5" />}
              Reset demo
            </Button>
          </div>
        </header>
        <nav className="flex gap-1 overflow-x-auto border-b border-slate-200 bg-white px-3 py-2 lg:hidden">
          {NAV.map(({ to, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => clsx('whitespace-nowrap rounded-md px-2.5 py-1 text-xs font-medium', isActive ? 'bg-slate-900 text-white' : 'text-slate-600')}
            >
              {label}
            </NavLink>
          ))}
        </nav>

        <main className="flex-1 px-5 py-7 lg:px-8">
          <AnimatePresence mode="wait">
            <motion.div key={section} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.18 }}>
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
        <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200/80 px-5 py-4 text-[11.5px] text-slate-400 lg:px-8">
          <span>
            <span className="font-medium text-slate-500">Fictional demo system.</span> LICSYS Next is not a real government service; all people, plates and rules are invented.
          </span>
          <span className="font-mono">policy/BUSINESS-RULES.md · modern/ARCHITECTURE.md</span>
        </footer>
      </div>
      <CommandPalette open={paletteOpen} setOpen={setPaletteOpen} onPick={(id) => navigate(`/drivers/${id}`)} />
    </div>
  )
}

function Toggle({ on, onChange }: { on: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      onClick={() => onChange(!on)}
      className={clsx('relative h-4 w-7 rounded-full transition', on ? 'bg-indigo-500' : 'bg-white/15')}
    >
      <span className={clsx('absolute top-0.5 h-3 w-3 rounded-full bg-white shadow transition-all', on ? 'left-3.5' : 'left-0.5')} />
    </button>
  )
}

function StreamBadge({ status, count }: { status: string; count: number }) {
  const live = status === 'live' || status === 'mock'
  return (
    <span className="hidden items-center gap-2 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-slate-600 md:inline-flex">
      <StatusDot status={live ? 'UP' : status === 'reconnecting' ? 'DOWN' : 'UNKNOWN'} size={6} />
      {live ? 'Event stream live' : status === 'reconnecting' ? 'Reconnecting…' : 'Connecting…'}
      <span className="font-mono text-slate-400">{count}</span>
    </span>
  )
}
