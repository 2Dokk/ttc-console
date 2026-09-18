import { api } from '../api'
import type { MissionConfig, TrackingSnapshot } from '../api'
import { utcDateTime } from '../format'

const SPEEDS = [0, 1, 10, 60, 300]

interface Props {
  config: MissionConfig | null
  snapshot: TrackingSnapshot | null
  connected: boolean
}

export function Header({ config, snapshot, connected }: Props) {
  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-mark" aria-hidden />
        <div>
          <h1>지상국 관제 콘솔</h1>
          <span className="muted">
            {config
              ? `${config.satellite.name} · NORAD ${config.satellite.noradId} · ${config.station.name}`
              : '연결 중…'}
          </span>
        </div>
      </div>

      <div className="clock">
        <span className="muted">미션 시각 (UTC)</span>
        <strong className="mono">{snapshot ? utcDateTime(snapshot.missionTime) : '----'}</strong>
      </div>

      <div className="controls">
        <div className="seg" role="group" aria-label="시뮬레이션 배속">
          {SPEEDS.map((s) => (
            <button
              key={s}
              className={snapshot?.speed === s ? 'active' : ''}
              onClick={() => void api.setSpeed(s)}
              title={s === 0 ? '일시정지' : `실제 시간의 ${s}배`}
            >
              {s === 0 ? '❚❚' : `×${s}`}
            </button>
          ))}
        </div>
        <button
          onClick={() => void api.skipToNextPass()}
          disabled={snapshot?.visible}
          title="미션 시각을 다음 AOS 60초 전으로 이동"
        >
          다음 패스로 건너뛰기 ⏭
        </button>
        <span className={`conn ${connected ? 'up' : 'down'}`} title="지상 소프트웨어와의 WebSocket 연결">
          {connected ? '연결됨' : '연결 끊김'}
        </span>
      </div>
    </header>
  )
}
