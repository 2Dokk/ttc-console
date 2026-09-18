import { useState } from 'react'
import { api } from '../api'
import type { LinkView } from '../api'

/** Simulator control: how lossy the RF channel is, and what that did to the traffic. */
export function ChannelPanel({ link }: { link: LinkView | null }) {
  // Local values only while the operator is dragging; otherwise show what the server has.
  const [uplinkDraft, setUplinkDraft] = useState<number | null>(null)
  const [downlinkDraft, setDownlinkDraft] = useState<number | null>(null)

  const c = link?.channel
  const r = link?.reception
  const uplink = uplinkDraft ?? Math.round((c?.uplinkLoss ?? 0) * 100)
  const downlink = downlinkDraft ?? Math.round((c?.downlinkLoss ?? 0) * 100)

  const apply = () => {
    void api.setLinkLoss(uplink / 100, downlink / 100).finally(() => {
      setUplinkDraft(null)
      setDownlinkDraft(null)
    })
  }

  return (
    <section className="panel channel-panel">
      <header className="panel-head">
        <h2>RF 채널</h2>
        <span className="muted">시뮬레이션 · 단방향 지연 {c?.oneWayDelayMs ?? '-'} ms</span>
      </header>
      <div className="sliders">
        <label>
          <span>
            업링크 손실률 <b className="mono">{uplink}%</b>
          </span>
          <input
            type="range"
            min={0}
            max={60}
            value={uplink}
            onChange={(e) => setUplinkDraft(Number(e.target.value))}
            onPointerUp={apply}
            onKeyUp={apply}
          />
        </label>
        <label>
          <span>
            다운링크 손실률 <b className="mono">{downlink}%</b>
          </span>
          <input
            type="range"
            min={0}
            max={60}
            value={downlink}
            onChange={(e) => setDownlinkDraft(Number(e.target.value))}
            onPointerUp={apply}
            onKeyUp={apply}
          />
        </label>
      </div>
      <dl className="stats">
        <div>
          <dt>TC 프레임 송신</dt>
          <dd className="mono">{c?.tcFrames ?? '-'}</dd>
        </div>
        <div>
          <dt>TC 채널 유실</dt>
          <dd className="mono">{c?.tcLost ?? '-'}</dd>
        </div>
        <div>
          <dt>TM 프레임 수신</dt>
          <dd className="mono">{r?.realtimeFrames ?? '-'}</dd>
        </div>
        <div>
          <dt>TM 누락 감지</dt>
          <dd className="mono" title="프레임 카운터가 건너뛴 개수">
            {r?.framesMissing ?? '-'}
          </dd>
        </div>
        <div>
          <dt>재생 샘플</dt>
          <dd className="mono">{r?.playbackSamples ?? '-'}</dd>
        </div>
      </dl>
    </section>
  )
}
