import type { OpsEvent } from '../api'
import { utcTime } from '../format'
import { CATEGORY_LABEL } from '../labels'

export function EventLog({ events }: { events: OpsEvent[] }) {
  return (
    <section className="panel events-panel">
      <header className="panel-head">
        <h2>이벤트 로그</h2>
        <span className="muted">미션 시각, UTC</span>
      </header>
      <ol className="events">
        {[...events].reverse().map((e) => (
          <li key={e.id} className={e.level === 'WARN' ? 'warn-row' : ''}>
            <span className="mono muted">{utcTime(e.missionTime)}</span>
            <span className={`cat cat-${e.category.toLowerCase()}`} title={e.category}>
              {CATEGORY_LABEL[e.category] ?? e.category}
            </span>
            <span className="msg">{e.message}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
