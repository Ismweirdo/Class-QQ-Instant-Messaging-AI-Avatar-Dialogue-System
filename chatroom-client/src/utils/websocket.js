import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

let stompClient = null
let subscriptions = {}
let messageHandlers = []
let presenceHandlers = []

export function connectWebSocket(token) {
  return new Promise((resolve, reject) => {
    const socket = new SockJS(`/ws/chat?token=${token}`)
    stompClient = new Client({
      webSocketFactory: () => socket,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        console.log('WebSocket connected')
        subscribePrivateMessages()
        subscribePresence()
        subscribeBotStream()
        resolve()
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected')
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame)
        reject(new Error('WebSocket connection failed'))
      }
    })
    stompClient.activate()
  })
}

export function disconnectWebSocket() {
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
    subscriptions = {}
  }
}

function subscribePrivateMessages() {
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  if (!user.id) return

  const sub = stompClient.subscribe(`/user/queue/private/chat`, (message) => {
    const data = JSON.parse(message.body)
    messageHandlers.forEach(handler => handler(data))
  })
  subscriptions['private_chat'] = sub
}

function subscribePresence() {
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  if (!user.id) return

  const sub = stompClient.subscribe(`/user/queue/private/presence`, (message) => {
    const data = JSON.parse(message.body)
    presenceHandlers.forEach(handler => handler(data))
  })
  subscriptions['presence'] = sub
}

export function subscribeGroupMessages(groupId) {
  if (subscriptions[`group_${groupId}`]) return

  const sub = stompClient.subscribe(`/topic/group/${groupId}`, (message) => {
    const data = JSON.parse(message.body)
    messageHandlers.forEach(handler => handler(data))
  })
  subscriptions[`group_${groupId}`] = sub
}

export function unsubscribeGroupMessages(groupId) {
  const key = `group_${groupId}`
  if (subscriptions[key]) {
    subscriptions[key].unsubscribe()
    delete subscriptions[key]
  }
}

export function sendChatMessage(dto) {
  if (!stompClient || !stompClient.connected) {
    console.error('WebSocket not connected')
    return false
  }
  stompClient.publish({
    destination: '/app/chat.send',
    body: JSON.stringify(dto)
  })
  return true
}

export function addMessageHandler(handler) {
  messageHandlers.push(handler)
}

export function removeMessageHandler(handler) {
  messageHandlers = messageHandlers.filter(h => h !== handler)
}

export function addPresenceHandler(handler) {
  presenceHandlers.push(handler)
}

export function removePresenceHandler(handler) {
  presenceHandlers = presenceHandlers.filter(h => h !== handler)
}

let streamHandlers = []
export function addStreamHandler(handler) { streamHandlers.push(handler) }
export function removeStreamHandler(handler) { streamHandlers = streamHandlers.filter(h => h !== handler) }

function subscribeBotStream() {
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  if (!user.id) return
  const sub = stompClient.subscribe(`/user/queue/bot/stream`, msg => {
    streamHandlers.forEach(h => h(JSON.parse(msg.body)))
  })
  subscriptions['bot_stream'] = sub
}

export function subscribeGroupStream(groupId) {
  const key = `group_stream_${groupId}`
  if (subscriptions[key]) return
  const sub = stompClient.subscribe(`/topic/group/${groupId}/stream`, msg => {
    streamHandlers.forEach(h => h(JSON.parse(msg.body)))
  })
  subscriptions[key] = sub
}

export function unsubscribeGroupStream(groupId) {
  const key = `group_stream_${groupId}`
  if (subscriptions[key]) { subscriptions[key].unsubscribe(); delete subscriptions[key] }
}
