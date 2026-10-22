import { motion } from 'framer-motion'

/** 240° arc gauge of demerit points against the licence limit. */
export function PointsGauge({ points, limit }: { points: number; limit: number }) {
  const size = 200
  const stroke = 14
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const arc = (240 / 360) * c
  const ratio = Math.min(points / limit, 1)
  const over = points >= limit
  const warn = !over && points >= limit * 0.75
  const color = over ? '#e11d48' : warn ? '#f59e0b' : '#10b981'
  const ticks = Array.from({ length: limit + 1 }, (_, i) => i)
  return (
    <div className="relative mx-auto" style={{ width: size, height: size * 0.84 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="absolute inset-0">
        <g transform={`rotate(150 ${size / 2} ${size / 2})`}>
          <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#eef0f4" strokeWidth={stroke} strokeDasharray={`${arc} ${c}`} strokeLinecap="round" />
          <motion.circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke={color}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={`${arc} ${c}`}
            initial={{ strokeDashoffset: arc }}
            animate={{ strokeDashoffset: arc - arc * ratio, stroke: color }}
            transition={{ type: 'spring', stiffness: 70, damping: 16 }}
          />
          {ticks.map((t) => {
            const a = (t / limit) * (240 / 360) * 2 * Math.PI
            const ri = r - stroke / 2 - 6
            const ro = r - stroke / 2 - (t === limit ? 14 : 10)
            return (
              <line
                key={t}
                x1={size / 2 + ri * Math.cos(a)}
                y1={size / 2 + ri * Math.sin(a)}
                x2={size / 2 + ro * Math.cos(a)}
                y2={size / 2 + ro * Math.sin(a)}
                stroke={t === limit ? '#e11d48' : '#cbd5e1'}
                strokeWidth={t === limit ? 2 : 1}
              />
            )
          })}
        </g>
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center pt-2">
        <motion.span key={points} initial={{ scale: 1.25, opacity: 0.4 }} animate={{ scale: 1, opacity: 1 }} className="text-5xl font-semibold tabular-nums tracking-tight" style={{ color }}>
          {points}
        </motion.span>
        <span className="mt-0.5 text-xs font-medium text-slate-500">of {limit} point limit</span>
      </div>
    </div>
  )
}
