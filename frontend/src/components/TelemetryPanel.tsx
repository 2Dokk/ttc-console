import { useState } from 'react'
import type { TelemetryPoint, TrackingSnapshot } from '../api'
import { age, ms, utcTime } from '../format'
import { MODE_LABEL } from '../labels'
import { TimeSeriesChart } from './TimeSeriesChart'

interface Props {
  snapshot: TrackingSnapshot | null
  telemetry: TelemetryPoint[]
  playbackRemaining: number
}

const WINDOWS = [
  { label: '1시간', ms: 3_600_000 },
  { label: '6시간', ms: 6 * 3_600_000 },
  { label: '24시간', ms: 24 * 3_600_000 },
]

/** Same fixed limits the backend checks; colouring only, no inference. */
const BATTERY_LOW = 25
const TEMP_LOW = -5
const TEMP_HIGH = 35

export function TelemetryPanel({ snapshot, telemetry, playbackRemaining }: Props) {
  const [windowMs, setWindowMs] = useState(WINDOWS[1].ms)
  const newest = telemetry[telemetry.length - 1]
  const now = snapshot ? ms(snapshot.missionTime) : newest ? ms(newest.recordedAt) : 0
  const speed = snapshot?.speed ?? 1
  const visible = snapshot?.visible ?? false

  const lastRealtime = [...telemetry].reverse().find((p) => p.source === 'REALTIME')
  const staleMs = lastRealtime ? now - ms(lastRealtime.recordedAt) : Infinity
  // A live link delivers a frame every wall second; allow a few to be lost before calling it stale.
  const live = visible && staleMs < Math.max(5_000, speed * 5_000)
  const gapMs = Math.max(180_000, speed * 3_000)
  const onOff = (on: boolean) => (on ? '켜짐' : '꺼짐')

  return (
    <section className="panel tm-panel">
      <header className="panel-head">
        <h2>원격측정 (텔레메트리)</h2>
        <div className="legend">
          <span className="key realtime">실시간</span>
          <span className="key playback">레코더 재생</span>
          <span className="key contact">교신 구간</span>
        </div>
        <div className="seg">
          {WINDOWS.map((w) => (
            <button key={w.label} className={w.ms === windowMs ? 'active' : ''} onClick={() => setWindowMs(w.ms)}>
              {w.label}
            </button>
          ))}
        </div>
      </header>

      <div className={`tiles ${live ? '' : 'stale'}`}>
        {!live && (
          <div className="no-signal">
            <strong>신호 없음</strong>
            <span>
              {lastRealtime
                ? `마지막 수신 ${utcTime(lastRealtime.recordedAt)} UTC (${age(staleMs)} 전)`
                : '아직 수신한 프레임이 없습니다'}
            </span>
          </div>
        )}
        {playbackRemaining > 0 && (
          <div className="playback-note">레코더 재생 중: {playbackRemaining}개 샘플 남음</div>
        )}
        <Tile
          label="배터리"
          value={lastRealtime ? `${lastRealtime.batteryPct.toFixed(1)}%` : '-'}
          alarm={!!lastRealtime && lastRealtime.batteryPct < BATTERY_LOW}
        />
        <Tile
          label="온도"
          value={lastRealtime ? `${lastRealtime.tempC.toFixed(1)} °C` : '-'}
          alarm={!!lastRealtime && (lastRealtime.tempC < TEMP_LOW || lastRealtime.tempC > TEMP_HIGH)}
        />
        <Tile
          label="운용 모드"
          value={lastRealtime ? `${MODE_LABEL[lastRealtime.mode]} (${lastRealtime.mode})` : '-'}
          alarm={lastRealtime?.mode === 'SAFE'}
        />
        <Tile label="탑재체" value={lastRealtime ? onOff(lastRealtime.payloadOn) : '-'} />
        <Tile label="히터" value={lastRealtime ? onOff(lastRealtime.heaterOn) : '-'} />
        <Tile label="일조" value={lastRealtime ? (lastRealtime.sunlit ? '태양광' : '일식') : '-'} />
        <Tile
          label="자세 롤/피치/요"
          value={
            lastRealtime
              ? `${lastRealtime.rollDeg.toFixed(2)} / ${lastRealtime.pitchDeg.toFixed(2)} / ${lastRealtime.yawDeg.toFixed(2)}°`
              : '-'
          }
          wide
        />
      </div>

      <TimeSeriesChart
        title="배터리"
        unit="%"
        points={telemetry}
        value={(p) => p.batteryPct}
        from={now - windowMs}
        to={now}
        domain={[0, 100]}
        limits={[{ value: BATTERY_LOW, label: `하한 ${BATTERY_LOW}%` }]}
        gapMs={gapMs}
      />
      <TimeSeriesChart
        title="온도"
        unit="°C"
        points={telemetry}
        value={(p) => p.tempC}
        from={now - windowMs}
        to={now}
        domain={[-20, 50]}
        limits={[
          { value: TEMP_HIGH, label: `상한 ${TEMP_HIGH}°C` },
          { value: TEMP_LOW, label: `하한 ${TEMP_LOW}°C` },
        ]}
        gapMs={gapMs}
      />
    </section>
  )
}

function Tile({ label, value, alarm, wide }: { label: string; value: string; alarm?: boolean; wide?: boolean }) {
  return (
    <div className={`tile ${alarm ? 'alarm' : ''} ${wide ? 'wide' : ''}`}>
      <span className="tile-label">{label}</span>
      <span className="tile-value mono">{value}</span>
    </div>
  )
}
