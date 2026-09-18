/** Spherical-Earth helpers for the tracking map. Display accuracy only, not navigation grade. */

const DEG = Math.PI / 180

export type LonLat = [number, number]

/** Point at great-circle distance {@code distKm} from (lat, lon) along {@code bearingDeg}. */
function destination(latDeg: number, lonDeg: number, bearingDeg: number, distKm: number): LonLat {
  const d = distKm / 6371
  const lat1 = latDeg * DEG
  const lon1 = lonDeg * DEG
  const b = bearingDeg * DEG
  const lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(b))
  const lon2 =
    lon1 + Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2))
  return [((((lon2 / DEG) + 540) % 360) - 180), lat2 / DEG]
}

/** Ring of points at a fixed ground distance: a satellite's coverage footprint. */
export function circle(latDeg: number, lonDeg: number, radiusKm: number, steps = 120): LonLat[] {
  return Array.from({ length: steps + 1 }, (_, i) => destination(latDeg, lonDeg, (360 * i) / steps, radiusKm))
}

/** Sub-solar point (low-precision almanac formulae, good to ~0.1 deg). */
export function subsolarPoint(time: number): { latDeg: number; lonDeg: number } {
  const d = time / 86_400_000 + 2440587.5 - 2451545.0
  const g = (357.529 + 0.98560028 * d) * DEG
  const q = 280.459 + 0.98564736 * d
  const L = (q + 1.915 * Math.sin(g) + 0.02 * Math.sin(2 * g)) * DEG
  const e = (23.439 - 0.00000036 * d) * DEG
  const decl = Math.asin(Math.sin(e) * Math.sin(L))
  const ra = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L)) / DEG
  const gmst = 280.46061837 + 360.98564736629 * d
  const lon = ((((ra - gmst) % 360) + 540) % 360) - 180
  return { latDeg: decl / DEG, lonDeg: lon }
}

/** Terminator latitude for each longitude, and which pole is in darkness. */
export function terminator(time: number, stepDeg = 2): { line: LonLat[]; darkPole: 'N' | 'S' } {
  const sun = subsolarPoint(time)
  // Near the equinoxes tan(decl) -> 0; keep it finite so the line stays well defined.
  const decl = (Math.abs(sun.latDeg) < 0.05 ? 0.05 * Math.sign(sun.latDeg || 1) : sun.latDeg) * DEG
  const line: LonLat[] = []
  for (let lon = -180; lon <= 180; lon += stepDeg) {
    const lat = Math.atan(-Math.cos((lon - sun.lonDeg) * DEG) / Math.tan(decl)) / DEG
    line.push([lon, lat])
  }
  return { line, darkPole: decl > 0 ? 'S' : 'N' }
}
