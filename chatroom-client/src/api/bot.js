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

export function deleteBot(userId) {
  return request.delete(`/bots/${userId}`)
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
  return request.post('/bots/import', formData)
}

// QQ Chat Exporter integration
function qqceHeaders(token) {
  return token ? { 'X-QQCE-Token': token } : {}
}

export function checkQQCEHealth(token) {
  return request.get('/bots/qq/health', { headers: qqceHeaders(token) })
}

export function getQQFriends(token) {
  return request.get('/bots/qq/friends', { headers: qqceHeaders(token) })
}

export function getQQGroups(token) {
  return request.get('/bots/qq/groups', { headers: qqceHeaders(token) })
}

export function qqImportBots(data, token) {
  return request.post('/bots/qq/import', data, { headers: qqceHeaders(token) })
}

// Skill import
export function importSkillFromUrl(url) {
  return request.post('/bots/skills/import-url', { url })
}

export function importSkillFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/bots/skills/import', formData)
}

export function listSkillFiles(botUserId) {
  return request.get(`/bots/skills/${botUserId}/files`)
}

export function uploadCustomFile(botUserId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/bots/skills/${botUserId}/custom`, formData)
}

// Active mode
export function setActiveMode(botUserId, enabled, intervalSeconds) {
  return request.put(`/bots/${botUserId}/active-mode`, { enabled, intervalSeconds })
}

export function getActiveMode(botUserId) {
  return request.get(`/bots/${botUserId}/active-mode`)
}
