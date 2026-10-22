import clsx from 'clsx'
import { AnimatePresence, motion } from 'framer-motion'
import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

type Tone = 'success' | 'error' | 'info'
interface ToastItem {
  id: number
  tone: Tone
  title: string
  body?: string
}

const Ctx = createContext<(t: Omit<ToastItem, 'id'>) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const push = useCallback((t: Omit<ToastItem, 'id'>) => {
    const id = Date.now() + Math.random()
    setItems((prev) => [...prev, { ...t, id }])
    setTimeout(() => setItems((prev) => prev.filter((i) => i.id !== id)), 4200)
  }, [])
  return (
    <Ctx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-5 right-5 z-50 flex w-80 flex-col gap-2">
        <AnimatePresence>
          {items.map((t) => (
            <motion.div
              key={t.id}
              layout
              initial={{ opacity: 0, y: 12, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, x: 24 }}
              className="pointer-events-auto flex gap-3 rounded-xl border border-slate-200 bg-white p-3.5 shadow-pop"
            >
              {t.tone === 'success' && <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />}
              {t.tone === 'error' && <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-rose-500" />}
              {t.tone === 'info' && <Info className="mt-0.5 h-4 w-4 shrink-0 text-indigo-500" />}
              <div>
                <p className={clsx('text-sm font-medium text-slate-900')}>{t.title}</p>
                {t.body && <p className="mt-0.5 text-xs text-slate-500">{t.body}</p>}
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </Ctx.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export const useToast = () => useContext(Ctx)
