import { useState } from 'react'
import { api } from '../api'
import type { LinkView } from '../api'

/** 시뮬레이터 제어: RF 채널 품질을 조절하고, 그 결과로 생긴 유실·손상 통계를 보여 준다. */
export function ChannelPanel({ link }: { link: LinkView | null }) {
  // 조절하는 동안에만 화면 값을 쓰고, 평소에는 서버가 알려 준 값을 보여 준다.
  const [uplinkDraft, setUplinkDraft] = useState<number | null>(null)
  const [downlinkDraft, setDownlinkDraft] = useState<number | null>(null)
  const [bitErrorDraft, setBitErrorDraft] = useState<number | null>(null)

  const c = link?.channel
  const r = link?.reception
  const uplink = uplinkDraft ?? Math.round((c?.uplinkLoss ?? 0) * 100)
  const downlink = downlinkDraft ?? Math.round((c?.downlinkLoss ?? 0) * 100)
  const bitError = bitErrorDraft ?? Math.round((c?.bitErrorRate ?? 0) * 100)

  const apply = () => {
    void api.setLinkQuality(uplink / 100, downlink / 100, bitError / 100).finally(() => {
      setUplinkDraft(null)
      setDownlinkDraft(null)
      setBitErrorDraft(null)
    })
  }

  return (
    <section className="panel channel-panel">
      <header className="panel-head">
        <h2>RF 채널</h2>
        <span className="muted">
          시뮬레이션 · 지연 {c?.oneWayDelayMs ?? '-'}±{c?.jitterMs ?? 0} ms
          {c?.burstEnabled ? ' · 연속 손실 모델' : ''}
        </span>
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
        <label>
          <span>
            비트 오류율 <b className="mono">{bitError}%</b>
          </span>
          <input
            type="range"
            min={0}
            max={30}
            value={bitError}
            onChange={(e) => setBitErrorDraft(Number(e.target.value))}
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
          <dt>TC 비트 오류 주입</dt>
          <dd className="mono">{c?.tcCorrupted ?? '-'}</dd>
        </div>
        <div>
          <dt>CRC 검출·폐기</dt>
          <dd className="mono" title="위성이 CRC 불일치로 버린 프레임 수">
            {c?.tcCrcRejected ?? '-'}
          </dd>
        </div>
        <div>
          <dt>TM 프레임 수신</dt>
          <dd className="mono">{r?.realtimeFrames ?? '-'}</dd>
        </div>
        <div>
          <dt>TM 비트 오류 주입</dt>
          <dd className="mono">{c?.tmCorrupted ?? '-'}</dd>
        </div>
        <div>
          <dt>TM CRC 폐기</dt>
          <dd className="mono" title="지상이 CRC 불일치로 버린 텔레메트리 프레임 수">
            {r?.crcRejected ?? '-'}
          </dd>
        </div>
        <div>
          <dt>TM 누락 감지</dt>
          <dd className="mono" title="프레임 카운터가 건너뛴 개수 (유실 + CRC 폐기)">
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
