import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Inter Variable"', 'Inter', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono Variable"', 'ui-monospace', 'monospace'],
      },
      colors: {
        ink: { 950: '#0b0d12', 900: '#11141b', 850: '#161a23', 800: '#1d2230', 700: '#2a3142' },
        licence: { DEFAULT: '#6366f1', soft: '#eef2ff' },
        demerit: { DEFAULT: '#f43f5e', soft: '#fff1f2' },
        registration: { DEFAULT: '#0ea5e9', soft: '#f0f9ff' },
        notification: { DEFAULT: '#f59e0b', soft: '#fffbeb' },
      },
      boxShadow: {
        card: '0 1px 2px rgba(16,24,40,.04), 0 1px 3px rgba(16,24,40,.06)',
        pop: '0 12px 32px -8px rgba(16,24,40,.18), 0 4px 8px -4px rgba(16,24,40,.08)',
      },
      keyframes: {
        'pulse-ring': { '0%': { transform: 'scale(.8)', opacity: '.7' }, '100%': { transform: 'scale(2.4)', opacity: '0' } },
        shimmer: { '100%': { transform: 'translateX(100%)' } },
      },
      animation: {
        'pulse-ring': 'pulse-ring 1.8s cubic-bezier(.2,.6,.4,1) infinite',
        shimmer: 'shimmer 1.4s infinite',
      },
    },
  },
  plugins: [],
} satisfies Config
