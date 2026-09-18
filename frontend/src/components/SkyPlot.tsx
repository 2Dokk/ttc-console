import type { LookAngles, SkyTrack } from '../api'

interface Props {
  sky: SkyTrack | null
  look: LookAngles | null
  minElevationDeg: number
}

const SIZE = 220
const C = SIZE / 2
const R = C - 18

/** Polar az/el plot: zenith at the centre, horizon on the rim, north up. */
function project(azDeg: number, elDeg: number): [number, number] {
  const r = (R * (90 - Math.max(0, elDeg))) / 90
  const az = (azDeg * Math.PI) / 180
  return [C + r * Math.sin(az), C - r * Math.cos(az)]
}

export function SkyPlot({ sky, look, minElevationDeg }: Props) {
  const path = sky?.points
    .filter((p) => p.elevationDeg >= 0)
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${project(p.azimuthDeg, p.elevationDeg).join(',')}`)
    .join(' ')
  const aos = sky && project(sky.pass.aosAzimuthDeg, minElevationDeg)
  const los = sky && project(sky.pass.losAzimuthDeg, minElevationDeg)
  const sat = look && look.elevationDeg > 0 ? project(look.azimuthDeg, look.elevationDeg) : null

  return (
    <svg className="skyplot" viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label="안테나 스카이 플롯">
      {[0, 30, 60].map((el) => (
        <circle key={el} cx={C} cy={C} r={(R * (90 - el)) / 90} className="sky-ring" />
      ))}
      <circle cx={C} cy={C} r={(R * (90 - minElevationDeg)) / 90} className="sky-mask" />
      <line x1={C - R} y1={C} x2={C + R} y2={C} className="sky-ring" />
      <line x1={C} y1={C - R} x2={C} y2={C + R} className="sky-ring" />
      {(
        [
          ['북', 0],
          ['동', 90],
          ['남', 180],
          ['서', 270],
        ] as const
      ).map(([label, az]) => {
        const [x, y] = project(az, -8)
        return (
          <text key={label} x={x} y={y} className="sky-label" textAnchor="middle" dominantBaseline="central">
            {label}
          </text>
        )
      })}
      <text x={C + 3} y={C - (R * 60) / 90 + 9} className="sky-tick">
        30°
      </text>
      <text x={C + 3} y={C - (R * 30) / 90 + 9} className="sky-tick">
        60°
      </text>
      {path && <path d={path} className="sky-track" />}
      {aos && (
        <text x={aos[0]} y={aos[1] - 7} className="sky-event" textAnchor="middle">
          AOS
        </text>
      )}
      {los && (
        <text x={los[0]} y={los[1] - 7} className="sky-event" textAnchor="middle">
          LOS
        </text>
      )}
      {sat && <circle cx={sat[0]} cy={sat[1]} r={5} className="sky-sat" />}
    </svg>
  )
}
