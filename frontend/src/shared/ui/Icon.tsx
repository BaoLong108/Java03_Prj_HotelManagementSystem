const paths = {
  home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
  bed: 'M3 18v-8m18 8v-8M3 14h18M5 10V5h14v5M7 10V8h4v2m2 0V8h4v2M3 18v3m18-3v3',
  calendar: 'M8 2v4m8-4v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14H3V6a2 2 0 0 1 2-2m2 10h3m4 0h3m-10 4h3',
  user: 'M20 21v-2a6 6 0 0 0-6-6h-4a6 6 0 0 0-6 6v2m12-14a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  shield: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7Zm-4 9 3 3 5-6',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  chevron: 'm9 5 7 7-7 7',
  check: 'm5 12 4 4L19 6',
  close: 'm6 6 12 12M6 18 18 6',
  menu: 'M4 6h16M4 12h16M4 18h16',
  logout: 'M9 3H4v18h5m5-14 5 5-5 5m-5-5h10',
  key: 'M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0m-4 4v10m0-4h4m-4-3h3',
  building: 'M5 21V3h14v18M2 21h20M9 7h1m4 0h1M9 11h1m4 0h1M10 21v-6h4v6',
  clock: 'M12 8v5l3 2m7-3a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  refresh: 'M20 7v5h-5M4 17v-5h5m-4-4a8 8 0 0 1 13-3l2 3M4 16l2 3a8 8 0 0 0 13-3',
  info: 'M12 11v6m0-10h.01m10 5a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12m13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  mail: 'M3 5h18v14H3Zm0 0 9 8 9-8',
} as const

export type IconName = keyof typeof paths
export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
