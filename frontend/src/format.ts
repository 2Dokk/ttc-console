const ms = (iso: string) => new Date(iso).getTime()

export { ms }

/** HH:MM:SS in UTC, the ops convention. */
export function utcTime(iso: string | number): string {
  return new Date(iso).toISOString().slice(11, 19)
}

export function utcDateTime(iso: string): string {
  return new Date(iso).toISOString().slice(0, 19).replace('T', ' ')
}

/** 1:05:09 / 05:09 */
export function duration(msValue: number): string {
  const total = Math.max(0, Math.round(msValue / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mmss = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return h > 0 ? `${h}:${mmss}` : mmss
}

/** "3분 20초", "2시간 4분": coarse age for "last contact" style labels. */
export function age(msValue: number): string {
  const total = Math.max(0, Math.round(msValue / 1000))
  if (total < 60) return `${total}초`
  if (total < 3600) return `${Math.floor(total / 60)}분 ${total % 60}초`
  return `${Math.floor(total / 3600)}시간 ${Math.floor((total % 3600) / 60)}분`
}

export function describeArgs(args: Record<string, unknown>): string {
  const entries = Object.entries(args)
  if (entries.length === 0) return ''
  return entries.map(([k, v]) => `${k}=${typeof v === 'boolean' ? (v ? 'ON' : 'OFF') : String(v)}`).join(' ')
}

const EARTH_RADIUS_KM = 6371

/** Great-circle radius around the station inside which the satellite is above the mask. */
export function visibilityRadiusKm(altKm: number, minElevationDeg: number): number {
  const e = (minElevationDeg * Math.PI) / 180
  const centralAngle = Math.acos((EARTH_RADIUS_KM / (EARTH_RADIUS_KM + altKm)) * Math.cos(e)) - e
  return centralAngle * EARTH_RADIUS_KM
}
