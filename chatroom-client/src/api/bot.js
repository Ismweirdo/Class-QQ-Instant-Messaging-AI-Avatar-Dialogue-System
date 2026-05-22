import request from './request'

export function deleteBot(userId) {
  return request.delete(`/bots/${userId}`)
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

export function uploadCustomFile(botUserId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/bots/skills/${botUserId}/custom`, formData)
}

// Update existing bot's skill via MD file
export function updateBotSkill(botUserId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.put(`/bots/${botUserId}/skill`, formData)
}

// Update existing bot's skill via plain text (quick merge)
export function updateBotSkillText(botUserId, content) {
  return request.put(`/bots/${botUserId}/skill/text`, { content })
}

// Get lightweight bot list for dropdown selection
export function getBotsSimple() {
  return request.get('/bots/list-simple')
}

// AI Provider
export function listProviders() {
  return request.get('/bots/providers')
}

export function getProviderConfig(botUserId) {
  return request.get(`/bots/${botUserId}/provider-config`)
}

export function updateProviderConfig(botUserId, data) {
  return request.put(`/bots/${botUserId}/provider-config`, data)
}

// RAG Memory
export function updateRagConfig(botUserId, ragEnabled, ragTopK) {
  return request.put(`/bots/${botUserId}/rag-config`, { ragEnabled, ragTopK })
}

export function getRagStats(botUserId) {
  return request.get(`/bots/${botUserId}/rag-stats`)
}

// Active mode
export function setActiveMode(botUserId, enabled, intervalSeconds) {
  return request.put(`/bots/${botUserId}/active-mode`, { enabled, intervalSeconds })
}

export function getActiveMode(botUserId) {
  return request.get(`/bots/${botUserId}/active-mode`)
}

export function uploadBotAvatar(botUserId, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post(`/bots/${botUserId}/avatar`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// Long-Term Memory
export function getLongTermMemory(botUserId) {
  return request.get(`/bots/${botUserId}/long-term-memory`)
}

export function clearLongTermMemory(botUserId) {
  return request.delete(`/bots/${botUserId}/long-term-memory`)
}

export function consolidateMemory(botUserId) {
  return request.post(`/bots/${botUserId}/consolidate`)
}
