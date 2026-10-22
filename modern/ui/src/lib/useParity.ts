import { useEffect, useState } from 'react'
import { api } from './api'
import type { ParityReport } from './types'

let cache: Promise<ParityReport> | null = null

export function useParity() {
  const [report, setReport] = useState<ParityReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    cache ??= api.parity()
    cache.then(setReport).catch((e: Error) => {
      cache = null
      setError(e.message)
    })
  }, [])
  return { report, error }
}
