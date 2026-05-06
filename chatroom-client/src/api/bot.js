import request from './request'

export function getBotConfig() {
  return request.get('/bots/config')
}

export function getBotList() {
  return request.get('/bots/')
}

export function getActiveBots() {
  return request.get('/bots/active')
}

export function getBotCount() {
  return request.get('/bots/count')
}

export function registerBot(data) {
  return request.post('/bots/register', data)
}

export function deactivateBot(userId) {
  return request.delete(`/bots/${userId}`)
}

export function distillSkills() {
  return request.post('/bots/distill')
}

export function importChatRecords(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/bots/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// QQ Chat Exporter integration
export function checkQQCEHealth() {
  return request.get('/bots/qq/health')
}

export function getQQFriends() {
  return request.get('/bots/qq/friends')
}

export function getQQGroups() {
  return request.get('/bots/qq/groups')
}

export function qqImportBots(data) {
  return request.post('/bots/qq/import', data)
}
