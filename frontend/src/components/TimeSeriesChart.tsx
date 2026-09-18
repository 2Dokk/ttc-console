import { useLayoutEffect, useRef, useState } from 'react'
import type { TelemetryPoint } from '../api'
import { ms, utcTime } from '../format'

interface Limit {
  value: number
  label: string
}

interface Props {
  title: string
  unit: string
  points: TelemetryPoint[]
  value: (p: TelemetryPoint) => number
  from: number
  to: number
  domain: [number, number]
  limits?: Limit[]
  /** Samples further apart than this are drawn as a gap, not joined. */
  gapMs: number
}

const HEIGHT = 132
const M = { top: 10, right: 10, bottom: 20, left: 38 }

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [width, setWidth] = useState(280)
  useLayoutEffect(() => {
    if (!ref.current) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(200, entry.contentRect.width)))
    observer.observe(ref.current)
    return () => observer.disconnect()
  }, [])
  return [ref, width] as const
}

/** Consecutive samples of one source, split wherever the spacing exceeds gapMs. */
function runs(points: TelemetryPoint[], gapMs: number): TelemetryPoint[][] {
  const out: TelemetryPoint[][] = []
  let run: TelemetryPoint[] = []
  for (const p of points) {
    if (run.length > 0 && ms(p.recordedAt) - ms(run[run.length - 1].recordedAt) > gapMs) {
      out.push(run)
      run = []
    }
    run.push(p)
  }
  if (run.length > 0) out.push(run)
  return out
}

export function TimeSeriesChart({ title, unit, points, value, from, to, domain, limits = [], gapMs }: Props) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const plotW = width - M.left - M.right
  const plotH = HEIGHT - M.top - M.bottom
  const x = (t: number) => M.left + ((t - from) / (to - from)) * plotW
  const y = (v: number) => M.top + (1 - (v - domain[0]) / (domain[1] - domain[0])) * plotH

  const visible = points.filter((p) => ms(p.recordedAt) >= from && ms(p.recordedAt) <= to)
  const realtime = runs(visible.filter((p) => p.source === 'REALTIME'), gapMs)
  const playback = runs(visible.filter((p) => p.source === 'PLAYBACK'), gapMs)
  const line = (run: TelemetryPoint[]) =>
    run.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(ms(p.recordedAt)).toFixed(1)},${y(value(p)).toFixed(1)}`).join(' ')

  const latest = visible[visible.length - 1]
  const yTicks = [domain[0], (domain[0] + domain[1]) / 2, domain[1]]
  const xTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => from + f * (to - from))

  return (
    <div className="chart" ref={ref}>
      <div className="chart-head">
        <span>{title}</span>
        <span className="mono">{latest ? `${value(latest).toFixed(1)} ${unit}` : '-'}</span>
      </div>
      <svg width={width} height={HEIGHT} role="img" aria-label={`${title} 이력`}>
        {realtime.map((run) => (
          <rect
            key={`band${run[0].recordedAt}`}
            className="contact-band"
            x={x(ms(run[0].recordedAt))}
            y={M.top}
            width={Math.max(2, x(ms(run[run.length - 1].recordedAt)) - x(ms(run[0].recordedAt)))}
            height={plotH}
          />
        ))}
        {yTicks.map((v) => (
          <g key={v}>
            <line className="gridline" x1={M.left} x2={M.left + plotW} y1={y(v)} y2={y(v)} />
            <text className="axis" x={M.left - 6} y={y(v)} textAnchor="end" dominantBaseline="central">
              {v}
            </text>
          </g>
        ))}
        {xTicks.map((t, i) => (
          <text
            key={t}
            className="axis"
            x={x(t)}
            y={HEIGHT - 5}
            textAnchor={i === 0 ? 'start' : i === xTicks.length - 1 ? 'end' : 'middle'}
          >
            {utcTime(t).slice(0, 5)}
          </text>
        ))}
        {limits.map((l) => (
          <g key={l.label}>
            <line className="limit" x1={M.left} x2={M.left + plotW} y1={y(l.value)} y2={y(l.value)} />
            <text className="limit-label" x={M.left + plotW - 2} y={y(l.value) - 3} textAnchor="end">
              {l.label}
            </text>
          </g>
        ))}
        {playback.map((run) => (
          <path key={`pb${run[0].recordedAt}`} d={line(run)} className="series playback" />
        ))}
        {realtime.map((run) => (
          <path key={`rt${run[0].recordedAt}`} d={line(run)} className="series realtime" />
        ))}
      </svg>
    </div>
  )
}
