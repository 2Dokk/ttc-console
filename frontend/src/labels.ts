import type { CommandStatus, CommandType, TelemetryPoint } from './api'

export const STATUS_LABEL: Record<CommandStatus, string> = {
  PENDING: '대기',
  SENT: '전송됨',
  ACKED: '수신확인',
  EXECUTED: '실행완료',
  REJECTED: '거부됨',
  EXPIRED: '만료',
  CANCELLED: '취소',
  FAILED: '실패',
}

export const STATUS_HELP: Record<CommandStatus, string> = {
  PENDING: '지상에서 대기 중 (교신 또는 전송 윈도우 여유를 기다리는 중)',
  SENT: '송신됨, 위성의 수신 확인(CLCW)을 기다리는 중',
  ACKED: '위성이 수신함, 실행 결과 보고를 기다리는 중',
  EXECUTED: '위성에서 실행 완료',
  REJECTED: '위성에 도착했지만 위성이 실행을 거부함',
  EXPIRED: '송신하기 전에 유효 기한이 지남',
  CANCELLED: '송신 전에 운용자가 취소함',
  FAILED: '실행 여부를 확인할 수 없음 (재시작)',
}

export const COMMAND_LABEL: Record<CommandType, string> = {
  SET_MODE: '운용 모드 변경',
  PAYLOAD_POWER: '탑재체 전원',
  HEATER: '히터 전원',
  PING: '링크 점검',
}

export const MODE_LABEL: Record<TelemetryPoint['mode'], string> = {
  NOMINAL: '정상',
  MISSION: '임무',
  SAFE: '안전',
}

export const CATEGORY_LABEL: Record<string, string> = {
  LINK: '링크',
  FOP: '전송',
  CMD: '명령',
  TM: '원격측정',
  LIMIT: '한계',
  CLOCK: '시계',
}
