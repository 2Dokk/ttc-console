export type Iso = string

export interface SubPoint {
  time: Iso
  latDeg: number
  lonDeg: number
  altKm: number
}

export interface LookAngles {
  azimuthDeg: number
  elevationDeg: number
  rangeKm: number
}

export interface Pass {
  aos: Iso
  los: Iso
  tca: Iso
  maxElevationDeg: number
  aosAzimuthDeg: number
  losAzimuthDeg: number
}

export interface TrackingSnapshot {
  missionTime: Iso
  speed: number
  position: SubPoint
  look: LookAngles
  visible: boolean
  sunlit: boolean
  currentPass: Pass | null
  nextPass: Pass | null
}

export interface GroundStation {
  id: number
  name: string
  latDeg: number
  lonDeg: number
  altM: number
  minElevationDeg: number
}

export interface MissionConfig {
  satellite: { id: number; name: string; noradId: number; tleEpoch: Iso }
  station: GroundStation
  fopWindowSize: number
  fopAckTimeoutMs: number
}

export interface TelemetryPoint {
  seqCount: number
  source: 'REALTIME' | 'PLAYBACK'
  recordedAt: Iso
  receivedAt: Iso
  batteryPct: number
  tempC: number
  rollDeg: number
  pitchDeg: number
  yawDeg: number
  mode: 'NOMINAL' | 'MISSION' | 'SAFE'
  heaterOn: boolean
  payloadOn: boolean
  sunlit: boolean
}

export type CommandType = 'PING' | 'SET_MODE' | 'HEATER' | 'PAYLOAD_POWER'

export type CommandStatus =
  | 'PENDING'
  | 'SENT'
  | 'ACKED'
  | 'EXECUTED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'FAILED'

export interface Command {
  id: number
  satelliteId: number
  type: CommandType
  args: Record<string, unknown>
  status: CommandStatus
  seq: number | null
  attempts: number
  createdAt: Iso
  firstSentAt: Iso | null
  lastSentAt: Iso | null
  ackedAt: Iso | null
  executedAt: Iso | null
  expiresAt: Iso | null
  resultMessage: string | null
}

export interface OpsEvent {
  id: number
  missionTime: Iso
  level: 'INFO' | 'WARN'
  category: string
  message: string
}

export interface LinkView {
  channel: {
    uplinkLoss: number
    downlinkLoss: number
    oneWayDelayMs: number
    tcFrames: number
    tcLost: number
    tmFrames: number
    tmLost: number
  }
  reception: { realtimeFrames: number; framesMissing: number; playbackSamples: number }
}

export interface SkyTrack {
  pass: Pass
  points: LookAngles[]
}

export interface NewCommand {
  type: CommandType
  args: Record<string, unknown>
  expiresInMinutes?: number
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    let message = `요청 실패 (${response.status})`
    try {
      message = (await response.json()).error ?? message
    } catch {
      // body was not JSON; keep the status line
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

const post = (body?: unknown): RequestInit => ({
  method: 'POST',
  body: body === undefined ? undefined : JSON.stringify(body),
})

export const api = {
  config: () => request<MissionConfig>('/api/config'),
  passes: (count = 6) => request<Pass[]>(`/api/passes?count=${count}`),
  skyTrack: () => request<SkyTrack>('/api/passes/active/skytrack'),
  groundTrack: () => request<SubPoint[]>('/api/orbit/track?behindMin=45&aheadMin=95&stepSec=30'),
  telemetry: (minutes: number) => request<TelemetryPoint[]>(`/api/telemetry?minutes=${minutes}&limit=8000`),
  commands: () => request<Command[]>('/api/commands?limit=100'),
  sendCommand: (command: NewCommand) => request<Command>('/api/commands', post(command)),
  cancelCommand: (id: number) => request<Command>(`/api/commands/${id}/cancel`, post()),
  events: () => request<OpsEvent[]>('/api/events?limit=200'),
  link: () => request<LinkView>('/api/link'),
  setLinkLoss: (uplinkLoss: number, downlinkLoss: number) =>
    request<LinkView>('/api/link', { method: 'PUT', body: JSON.stringify({ uplinkLoss, downlinkLoss }) }),
  setSpeed: (speed: number) => request<TrackingSnapshot>('/api/clock/speed', post({ speed })),
  skipToNextPass: () => request<TrackingSnapshot>('/api/clock/skip-to-next-pass', post()),
}
