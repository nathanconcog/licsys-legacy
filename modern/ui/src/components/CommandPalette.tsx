import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { CornerDownLeft, Search } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { displayName } from '../lib/domain'
import { useDrivers } from '../lib/useDrivers'
import { ClassBadge } from './ui'

export function CommandPalette({ open, setOpen, onPick }: { open: boolean; setOpen: (v: boolean) => void; onPick: (id: string) => void }) {
  const { drivers } = useDrivers()
  const [q, setQ] = useState('')
  const [idx, setIdx] = useState(0)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen(!open)
      }
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, setOpen])

  const results = useMemo(() => {
    const term = q.trim().toLowerCase()
    return (drivers ?? []).filter((d) => !term || d.driverId.toLowerCase().includes(term) || d.name.toLowerCase().includes(term)).slice(0, 8)
  }, [drivers, q])

  const pick = (id: string) => {
    onPick(id)
    setOpen(false)
    setQ('')
  }

  return (
    <AnimatePresence>
      {open && (
        <motion.div className="fixed inset-0 z-50 flex items-start justify-center bg-slate-900/30 p-4 pt-[14vh] backdrop-blur-sm" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onMouseDown={() => setOpen(false)}>
          <motion.div
            initial={{ opacity: 0, scale: 0.97, y: -8 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.97 }}
            transition={{ duration: 0.15 }}
            className="w-full max-w-lg overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-pop"
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 border-b border-slate-100 px-4">
              <Search className="h-4 w-4 text-slate-400" />
              <input
                autoFocus
                value={q}
                onChange={(e) => {
                  setQ(e.target.value)
                  setIdx(0)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'ArrowDown') setIdx((i) => Math.min(i + 1, results.length - 1))
                  if (e.key === 'ArrowUp') setIdx((i) => Math.max(i - 1, 0))
                  if (e.key === 'Enter' && results[idx]) pick(results[idx].driverId)
                }}
                placeholder="Search drivers by number or name…"
                className="h-12 flex-1 bg-transparent text-sm outline-none placeholder:text-slate-400"
              />
              <span className="kbd">esc</span>
            </div>
            <ul className="max-h-80 overflow-y-auto p-2">
              {results.map((d, i) => (
                <li key={d.driverId}>
                  <button
                    onMouseEnter={() => setIdx(i)}
                    onClick={() => pick(d.driverId)}
                    className={clsx('flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left text-sm', i === idx ? 'bg-slate-100' : '')}
                  >
                    <span className="font-mono text-xs text-slate-400">{d.driverId}</span>
                    <span className="font-medium text-slate-800">{displayName(d.name)}</span>
                    <span className="ml-auto flex items-center gap-2">
                      <ClassBadge cls={d.licenceClass} />
                      {i === idx && <CornerDownLeft className="h-3.5 w-3.5 text-slate-400" />}
                    </span>
                  </button>
                </li>
              ))}
              {!results.length && <li className="px-3 py-6 text-center text-sm text-slate-500">No drivers match “{q}”.</li>}
            </ul>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  )
}
