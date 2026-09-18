import { useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api'
import type { Command, CommandType, NewCommand } from '../api'
import { describeArgs, utcTime } from '../format'
import { COMMAND_LABEL, MODE_LABEL, STATUS_HELP, STATUS_LABEL } from '../labels'

interface Props {
  commands: Command[]
  visible: boolean
  windowSize: number
}

const TYPES: CommandType[] = ['SET_MODE', 'PAYLOAD_POWER', 'HEATER', 'PING']
const MODES = ['MISSION', 'NOMINAL', 'SAFE'] as const

export function CommandPanel({ commands, visible, windowSize }: Props) {
  const [type, setType] = useState<CommandType>('SET_MODE')
  const [mode, setMode] = useState<(typeof MODES)[number]>('MISSION')
  const [on, setOn] = useState(true)
  const [expires, setExpires] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const pending = commands.filter((c) => c.status === 'PENDING').length
  const inFlight = commands.filter((c) => c.status === 'SENT').length

  async function submit(e: FormEvent) {
    e.preventDefault()
    const args: Record<string, unknown> =
      type === 'SET_MODE' ? { mode } : type === 'PING' ? {} : { on }
    const body: NewCommand = { type, args }
    if (expires.trim()) body.expiresInMinutes = Number(expires)
    setBusy(true)
    setError(null)
    try {
      await api.sendCommand(body)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function cancel(id: number) {
    try {
      await api.cancelCommand(id)
    } catch (err) {
      setError((err as Error).message)
    }
  }

  return (
    <section className="panel cmd-panel">
      <header className="panel-head">
        <h2>원격명령</h2>
        <span className="muted mono">
          대기 {pending} · 응답 대기 {inFlight}/{windowSize}
        </span>
      </header>

      <form className="cmd-form" onSubmit={submit}>
        <label>
          명령
          <select value={type} onChange={(e) => setType(e.target.value as CommandType)}>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {t}: {COMMAND_LABEL[t]}
              </option>
            ))}
          </select>
        </label>
        {type === 'SET_MODE' && (
          <label>
            모드
            <select value={mode} onChange={(e) => setMode(e.target.value as (typeof MODES)[number])}>
              {MODES.map((m) => (
                <option key={m} value={m}>
                  {MODE_LABEL[m]} ({m})
                </option>
              ))}
            </select>
          </label>
        )}
        {(type === 'HEATER' || type === 'PAYLOAD_POWER') && (
          <label>
            상태
            <select value={on ? 'ON' : 'OFF'} onChange={(e) => setOn(e.target.value === 'ON')}>
              <option value="ON">켜기</option>
              <option value="OFF">끄기</option>
            </select>
          </label>
        )}
        <label>
          유효 기한 (분)
          <input
            type="number"
            min={1}
            placeholder="없음"
            value={expires}
            onChange={(e) => setExpires(e.target.value)}
          />
        </label>
        <button type="submit" className="primary" disabled={busy}>
          {visible ? '전송' : '다음 패스 대기열에 추가'}
        </button>
      </form>
      {error && <p className="form-error">{error}</p>}

      <div className="table-scroll">
        <table className="table cmd-log">
          <thead>
            <tr>
              <th>#</th>
              <th>순번</th>
              <th>명령</th>
              <th>상태</th>
              <th>송신</th>
              <th>등록</th>
              <th>결과</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {commands.map((c) => (
              <tr key={c.id}>
                <td className="mono muted">{c.id}</td>
                <td className="mono">{c.seq ?? '-'}</td>
                <td>
                  <span className="mono">{c.type}</span>{' '}
                  <span className="muted mono">{describeArgs(c.args)}</span>
                </td>
                <td>
                  <span className={`status s-${c.status.toLowerCase()}`} title={`${c.status}: ${STATUS_HELP[c.status]}`}>
                    {STATUS_LABEL[c.status]}
                  </span>
                </td>
                <td className={`mono ${c.attempts > 1 ? 'warn' : ''}`} title="송신 횟수 (재전송 포함)">
                  {c.attempts ? `${c.attempts}회` : '-'}
                </td>
                <td className="mono muted">{utcTime(c.createdAt)}</td>
                <td className="result">{c.resultMessage ?? ''}</td>
                <td>
                  {c.status === 'PENDING' && (
                    <button className="link-btn" onClick={() => cancel(c.id)}>
                      취소
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {commands.length === 0 && (
              <tr>
                <td colSpan={8} className="muted empty">
                  아직 명령이 없습니다. 교신 불가 상태에서 보낸 명령은 여기서 &lsquo;대기&rsquo;로 기다립니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
