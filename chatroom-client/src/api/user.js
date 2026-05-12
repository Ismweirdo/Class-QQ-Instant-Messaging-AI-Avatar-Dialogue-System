import request from './request'

export function updateProfile(data) {
  return request.put('/users/profile', data)
}

export function uploadAvatar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/users/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function deleteAccount() {
  return request.delete('/users/me')
}
