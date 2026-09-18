import { useMemo } from 'react'
import { feature } from 'topojson-client'
import type { FeatureCollection, MultiPolygon, Polygon } from 'geojson'
import type { GeometryCollection, Topology } from 'topojson-specification'
import countries110m from 'world-atlas/countries-110m.json'
import type { GroundStation, SubPoint, TrackingSnapshot } from '../api'
import { ms, visibilityRadiusKm } from '../format'
import { circle, terminator } from '../geo'
import type { LonLat } from '../geo'

interface Props {
  station: GroundStation
  satelliteName: string
  snapshot: TrackingSnapshot | null
  track: SubPoint[]
}

// Equirectangular projection, cropped to the latitudes where anything happens.
const W = 1000
const LAT_TOP = 82
const LAT_BOTTOM = -62
const H = ((LAT_TOP - LAT_BOTTOM) / 360) * W
const px = (lon: number) => ((lon + 180) / 360) * W
const py = (lat: number) => ((LAT_TOP - lat) / 360) * W

/** SVG path through the points, lifting the pen wherever a segment wraps across the antimeridian. */
function polyline(points: LonLat[]): string {
  let d = ''
  points.forEach(([lon, lat], i) => {
    const jump = i === 0 || Math.abs(lon - points[i - 1][0]) > 180
    d += `${jump ? 'M' : 'L'}${px(lon).toFixed(1)},${py(lat).toFixed(1)}`
  })
  return d
}

function landPath(): string {
  const topo = countries110m as unknown as Topology<{ countries: GeometryCollection }>
  const fc = feature(topo, topo.objects.countries) as FeatureCollection<Polygon | MultiPolygon>
  const rings: LonLat[][] = []
  for (const f of fc.features) {
    const polys = f.geometry.type === 'Polygon' ? [f.geometry.coordinates] : f.geometry.coordinates
    for (const poly of polys) for (const ring of poly) rings.push(ring as LonLat[])
  }
  return rings.map((ring) => `${polyline(ring)}Z`).join('')
}

const LAND = landPath()

const GRATICULE = (() => {
  let d = ''
  for (let lon = -150; lon <= 150; lon += 30) d += `M${px(lon)},0V${H}`
  for (let lat = -60; lat <= 60; lat += 30) d += `M0,${py(lat)}H${W}`
  return d
})()

export function WorldMap({ station, satelliteName, snapshot, track }: Props) {
  const now = snapshot ? ms(snapshot.missionTime) : 0
  const visible = snapshot?.visible ?? false

  const toLonLat = (points: SubPoint[]): LonLat[] => points.map((p) => [p.lonDeg, p.latDeg])
  const past = toLonLat(track.filter((p) => ms(p.time) <= now))
  const future = toLonLat(track.filter((p) => ms(p.time) >= now))

  // Night side: terminator closed off along the dark pole's edge of the map. The terminator moves
  // 0.25 deg per minute, so recomputing once per mission minute is plenty.
  const minute = Math.floor(now / 60_000)
  const night = useMemo(() => {
    if (!minute) return ''
    const { line, darkPole } = terminator(minute * 60_000)
    const edge = darkPole === 'S' ? LAT_BOTTOM - 5 : LAT_TOP + 5
    return `${polyline(line)}L${px(180)},${py(edge)}L${px(-180)},${py(edge)}Z`
  }, [minute])

  const sat = snapshot?.position
  const footprint = sat ? circle(sat.latDeg, sat.lonDeg, visibilityRadiusKm(sat.altKm, station.minElevationDeg)) : []

  return (
    <svg className="worldmap" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="지상궤적 지도">
      <rect width={W} height={H} className="ocean" />
      <path d={GRATICULE} className="graticule" />
      <path d={LAND} className="land" />
      {night && <path d={night} className="night" />}
      <path d={polyline(past)} className="track-past" />
      <path d={polyline(future)} className="track-future" />
      {sat && <path d={polyline(footprint)} className={`footprint ${visible ? 'in-contact' : ''}`} />}

      <g transform={`translate(${px(station.lonDeg)},${py(station.latDeg)})`}>
        <path d="M0,-7 L6,5 L-6,5 Z" className={`station ${visible ? 'in-contact' : ''}`} />
        <text y={19} textAnchor="middle" className="map-text">
          {station.name}
        </text>
      </g>
      {sat && visible && (
        <line
          x1={px(station.lonDeg)}
          y1={py(station.latDeg)}
          x2={px(sat.lonDeg)}
          y2={py(sat.latDeg)}
          className="contact-line"
        />
      )}
      {sat && (
        <g transform={`translate(${px(sat.lonDeg)},${py(sat.latDeg)})`}>
          <circle r={11} className={`sat-halo ${visible ? 'in-contact' : ''}`} />
          <circle r={5} className={`sat ${visible ? 'in-contact' : ''}`} />
          <text y={-16} textAnchor="middle" className="map-text">
            {satelliteName}
          </text>
        </g>
      )}
    </svg>
  )
}
