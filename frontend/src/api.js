const TOKEN_KEY = 'node-control-plane-token'

export class ApiError extends Error {
  constructor(message, status, fields = null) {
    super(message)
    this.status = status
    this.fields = fields
  }
}

export function getControlToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setControlToken(token) {
  const normalized = token.trim()
  if (normalized) localStorage.setItem(TOKEN_KEY, normalized)
  else localStorage.removeItem(TOKEN_KEY)
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = getControlToken()
  if (token) headers.set('X-Control-Token', token)
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  const response = await fetch(path, { ...options, headers })
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    throw new ApiError(body.message || body.detail || `请求失败：HTTP ${response.status}`, response.status, body.fields)
  }
  return body
}

function queryString(params) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  })
  return query.toString()
}

export const api = {
  meta: () => request('/api/control/meta'),
  dashboard: () => request('/api/control/dashboard'),
  nodes: () => request('/api/control/nodes'),
  registerNode: (payload) => request('/api/control/nodes', { method: 'POST', body: JSON.stringify(payload) }),
  refreshNode: (nodeId) => request(`/api/control/nodes/${nodeId}/refresh`, { method: 'POST' }),
  reloadNode: (nodeId) => request(`/api/control/nodes/${nodeId}/reload`, { method: 'POST' }),
  deleteNode: (nodeId) => request(`/api/control/nodes/${nodeId}`, { method: 'DELETE' }),
  users: (nodeId, params) => request(`/api/control/nodes/${nodeId}/users?${queryString(params)}`),
  createUser: (nodeId, payload) => request(`/api/control/nodes/${nodeId}/users`, { method: 'POST', body: JSON.stringify(payload) }),
  connections: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/connections`),
  traffic: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/traffic`),
  bindProxy: (nodeId, payload) => request(`/api/control/nodes/${nodeId}/users/bind-proxy`, { method: 'POST', body: JSON.stringify(payload) }),
  deleteUser: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
}

