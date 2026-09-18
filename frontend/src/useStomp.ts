import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'

export type TopicHandlers = Record<string, (body: unknown) => void>

/**
 * One STOMP connection for the whole console. Handlers are read through a ref so callers can pass
 * a fresh object every render without resubscribing. Returns whether the socket is up.
 */
export function useStomp(handlers: TopicHandlers): boolean {
  const [connected, setConnected] = useState(false)
  const handlersRef = useRef(handlers)

  useEffect(() => {
    handlersRef.current = handlers
  })

  useEffect(() => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${scheme}://${window.location.host}/ws`,
      reconnectDelay: 2000,
      onConnect: () => {
        setConnected(true)
        for (const topic of Object.keys(handlersRef.current)) {
          client.subscribe(topic, (message) => handlersRef.current[topic]?.(JSON.parse(message.body)))
        }
      },
      onWebSocketClose: () => setConnected(false),
    })
    client.activate()
    return () => {
      void client.deactivate()
    }
  }, [])

  return connected
}
