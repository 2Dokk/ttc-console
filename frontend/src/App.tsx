import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import type {
  Command,
  LinkView,
  MissionConfig,
  OpsEvent,
  Pass,
  SkyTrack,
  SubPoint,
  TelemetryPoint,
  TrackingSnapshot,
} from './api'
import { ChannelPanel } from './components/ChannelPanel'
import { CommandPanel } from './components/CommandPanel'
import { EventLog } from './components/EventLog'
import { Header } from './components/Header'
import { LinkPanel } from './components/LinkPanel'
import { WorldMap } from './components/WorldMap'
import { TelemetryPanel } from './components/TelemetryPanel'
import { ms } from './format'
import { useStomp } from './useStomp'

const HISTORY_MS = 24 * 3_600_000
const MAX_POINTS = 8000

/** Merges new samples into the history, keeping it ordered by spacecraft time. */
function mergeTelemetry(history: TelemetryPoint[], incoming: TelemetryPoint[]): TelemetryPoint[] {
  if (incoming.length === 0) return history
  const last = history[history.length - 1]
  const ordered = !last || incoming.every((p) => p.recordedAt >= last.recordedAt)
  const merged = ordered
    ? history.concat(incoming)
    : history.concat(incoming).sort((a, b) => ms(a.recordedAt) - ms(b.recordedAt))
  const newest = ms(merged[merged.length - 1].recordedAt)
  const trimmed = merged.filter((p) => ms(p.recordedAt) >= newest - HISTORY_MS)
  return trimmed.slice(-MAX_POINTS)
}

function upsert(list: Command[], command: Command): Command[] {
  const index = list.findIndex((c) => c.id === command.id)
  if (index === -1) return [command, ...list].slice(0, 100)
  const copy = list.slice()
  copy[index] = command
  return copy
}

export default function App() {
  const [config, setConfig] = useState<MissionConfig | null>(null)
  const [snapshot, setSnapshot] = useState<TrackingSnapshot | null>(null)
  const [telemetry, setTelemetry] = useState<TelemetryPoint[]>([])
  const [playbackRemaining, setPlaybackRemaining] = useState(0)
  const [commands, setCommands] = useState<Command[]>([])
  const [events, setEvents] = useState<OpsEvent[]>([])
  const [link, setLink] = useState<LinkView | null>(null)
  const [passes, setPasses] = useState<Pass[]>([])
  const [track, setTrack] = useState<SubPoint[]>([])
  const [sky, setSky] = useState<SkyTrack | null>(null)

  const connected = useStomp({
    '/topic/state': (s) => setSnapshot(s as TrackingSnapshot),
    '/topic/telemetry': (p) => setTelemetry((t) => mergeTelemetry(t, [p as TelemetryPoint])),
    '/topic/telemetry/playback': (body) => {
      const batch = body as { samples: TelemetryPoint[]; remaining: number }
      setTelemetry((t) => mergeTelemetry(t, batch.samples))
      setPlaybackRemaining(batch.remaining)
    },
    '/topic/commands': (c) => setCommands((list) => upsert(list, c as Command)),
    '/topic/events': (e) => setEvents((list) => [...list, e as OpsEvent].slice(-300)),
  })

  // (Re)load everything whenever the socket comes up, so a backend restart resyncs the console.
  useEffect(() => {
    if (!connected) return
    void api.config().then(setConfig)
    void api.telemetry(24 * 60).then((points) => setTelemetry(mergeTelemetry([], points)))
    void api.commands().then(setCommands)
    void api.events().then(setEvents)
  }, [connected])

  const poll = useCallback(() => {
    void api.passes().then(setPasses).catch(() => {})
    void api.groundTrack().then(setTrack).catch(() => {})
    void api.link().then(setLink).catch(() => {})
  }, [])

  useEffect(() => {
    if (!connected) return
    poll()
    const timer = window.setInterval(poll, 3000)
    return () => window.clearInterval(timer)
  }, [connected, poll])

  // Refresh the sky track when the pass of interest changes, and on skips: the ground track goes stale too.
  const passKey = snapshot?.currentPass?.aos ?? snapshot?.nextPass?.aos
  useEffect(() => {
    if (!passKey) return
    void api.skyTrack().then(setSky).catch(() => {})
    poll()
  }, [passKey, poll])

  return (
    <div className="app">
      <Header config={config} snapshot={snapshot} connected={connected} />
      {config ? (
        <main className="grid">
          <div className="column">
            <section className="panel map-panel">
              <WorldMap
                station={config.station}
                satelliteName={config.satellite.name}
                snapshot={snapshot}
                track={track}
              />
            </section>
            <TelemetryPanel snapshot={snapshot} telemetry={telemetry} playbackRemaining={playbackRemaining} />
            <EventLog events={events} />
          </div>
          <div className="column">
            <LinkPanel config={config} snapshot={snapshot} passes={passes} sky={sky} />
            <CommandPanel
              commands={commands}
              visible={snapshot?.visible ?? false}
              windowSize={config.fopWindowSize}
            />
            <ChannelPanel link={link} />
          </div>
        </main>
      ) : (
        <main className="loading">
          <p>지상 소프트웨어(:8080) 연결을 기다리는 중…</p>
        </main>
      )}
    </div>
  )
}
