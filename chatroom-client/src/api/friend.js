import request from './request'

export function getFriendList() {
  return request.get('/friends')
}

export function sendFriendRequest(data) {
  return request.post('/friends/add', data)
}

export function acceptFriendRequest(friendId) {
  return request.put(`/friends/${friendId}/accept`)
}

export function rejectFriendRequest(friendId) {
  return request.put(`/friends/${friendId}/reject`)
}

export function deleteFriend(friendId) {
  return request.delete(`/friends/${friendId}`)
}

export function setRemark(friendId, remark) {
  return request.put(`/friends/${friendId}/remark`, { remark })
}

export function getPendingRequests() {
  return request.get('/friends/requests')
}

export function searchUsers(keyword) {
  return request.get('/users/search', { params: { keyword } })
}
