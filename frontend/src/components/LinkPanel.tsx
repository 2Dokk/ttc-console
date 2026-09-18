import type { MissionConfig, Pass, SkyTrack, TrackingSnapshot } from '../api'
import { duration, ms, utcTime } from '../format'
import { SkyPlot } from './SkyPlot'

interface Props {
  config: MissionConfig
  snapshot: TrackingSnapshot | null
  passes: Pass[]
  sky: SkyTrack | null
}

export function LinkPanel({ config, snapshot, passes, sky }: Props) {
  const now = snapshot ? ms(snapshot.missionTime) : 0
  const visible = snapshot?.visible ?? false
  const target = visible ? snapshot?.currentPass?.los : snapshot?.nextPass?.aos
  const remaining = target ? ms(target) - now : null
  const speed = snapshot?.speed ?? 1

  return (
    <section className="panel link-panel">
      <header className="panel-head">
        <h2>링크</h2>
        <span className="muted">
          {config.station.name} · 최저 고도각 {config.station.minElevationDeg}°
        </span>
      </header>

      <div className="link-status">
        <div className={`aos-badge ${visible ? 'aos' : 'los'}`}>
          <span className="dot" />
          {visible ? 'AOS · 교신 중' : 'LOS · 교신 불가'}
        </div>
        <div className="countdown">
          <span className="muted">{visible ? 'LOS까지' : '다음 AOS까지'}</span>
          <strong className="mono">{remaining !== null ? duration(remaining) : '--:--'}</strong>
          {remaining !== null && speed > 1 && (
            <span className="muted mono">
              ×{speed} 배속 기준 실제 {duration(remaining / speed)}
            </span>
          )}
        </div>
      </div>

      <div className="link-body">
        <SkyPlot sky={sky} look={snapshot?.look ?? null} minElevationDeg={config.station.minElevationDeg} />
        <dl className="readouts">
          <div>
            <dt>방위각</dt>
            <dd className="mono">{snapshot ? `${snapshot.look.azimuthDeg.toFixed(1)}°` : '-'}</dd>
          </div>
          <div>
            <dt>고도각</dt>
            <dd className={`mono ${visible ? 'ok' : ''}`}>
              {snapshot ? `${snapshot.look.elevationDeg.toFixed(1)}°` : '-'}
            </dd>
          </div>
          <div>
            <dt>거리</dt>
            <dd className="mono">{snapshot ? `${Math.round(snapshot.look.rangeKm).toLocaleString()} km` : '-'}</dd>
          </div>
          <div>
            <dt>직하점 (위도, 경도)</dt>
            <dd className="mono">
              {snapshot
                ? `${snapshot.position.latDeg.toFixed(2)}, ${snapshot.position.lonDeg.toFixed(2)}`
                : '-'}
            </dd>
          </div>
          <div>
            <dt>고도</dt>
            <dd className="mono">{snapshot ? `${snapshot.position.altKm.toFixed(1)} km` : '-'}</dd>
          </div>
          <div>
            <dt>일조</dt>
            <dd className={snapshot?.sunlit ? '' : 'warn'}>{snapshot ? (snapshot.sunlit ? '태양광' : '일식') : '-'}</dd>
          </div>
        </dl>
      </div>

      <table className="table passes">
        <thead>
          <tr>
            <th>AOS (UTC)</th>
            <th>LOS</th>
            <th>지속</th>
            <th>최대 고도각</th>
            <th>남은 시간</th>
          </tr>
        </thead>
        <tbody>
          {passes.map((p) => {
            const current = now >= ms(p.aos) && now < ms(p.los)
            return (
              <tr key={p.aos} className={current ? 'current' : ''}>
                <td className="mono">{utcTime(p.aos)}</td>
                <td className="mono">{utcTime(p.los)}</td>
                <td className="mono">{duration(ms(p.los) - ms(p.aos))}</td>
                <td className="mono">{p.maxElevationDeg.toFixed(0)}°</td>
                <td className="mono">{!snapshot ? '-' : current ? '진행 중' : duration(ms(p.aos) - now)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </section>
  )
}
