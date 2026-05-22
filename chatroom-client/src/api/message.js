import request from './request'

export function getPrivateHistory(friendId, page = 1, size = 20) {
  return request.get(`/messages/private/${friendId}`, { params: { page, size } })
}

export function getChatHistory(friendId, page = 1, size = 20) {
  return request.get(`/messages/private/${friendId}`, { params: { page, size } })
}

export function getGroupHistory(groupId, page = 1, size = 20) {
  return request.get(`/messages/group/${groupId}`, { params: { page, size } })
}

export function recallMessage(messageId) {
  return request.delete(`/messages/${messageId}`)
}

export function clearChatHistory(friendId) {
  return request.delete(`/messages/private/${friendId}`)
}

export function deleteMessagePermanently(messageId) {
  return request.delete(`/messages/${messageId}/permanent`)
}
