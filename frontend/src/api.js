let unauthorizedHandler = null

export class ApiError extends Error {
  constructor(message, status, fields = null) {
    super(message)
    this.status = status
    this.fields = fields
  }
}

export function setUnauthorizedHandler(handler) {
  unauthorizedHandler = handler
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  const response = await fetch(path, { credentials: 'same-origin', ...options, headers })
  if (response.status === 204) return null
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/control/auth/login') unauthorizedHandler?.()
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

function operationHeaders(prefix) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return { 'Idempotency-Key': `${prefix}-${suffix}` }
}

export const api = {
  meta: () => request('/api/control/meta'),
  defaultUserPolicy: () => request('/api/control/settings/default-user-policy'),
  session: () => request('/api/control/auth/session'),
  login: (payload) => request('/api/control/auth/login', { method: 'POST', body: JSON.stringify(payload) }),
  logout: () => request('/api/control/auth/logout', { method: 'POST' }),
  controlAccounts: () => request('/api/control/accounts'),
  createControlAccount: (payload) => request('/api/control/accounts', { method: 'POST', body: JSON.stringify(payload) }),
  updateControlAccount: (accountId, payload) => request(`/api/control/accounts/${accountId}`, {
    method: 'PATCH', body: JSON.stringify(payload),
  }),
  deleteControlAccount: (accountId) => request(`/api/control/accounts/${accountId}`, { method: 'DELETE' }),
  auditLogs: (params = {}) => request(`/api/control/audit-logs?${queryString(params)}`),
  dashboard: () => request('/api/control/dashboard'),
  nodes: () => request('/api/control/nodes'),
  nodeToken: (nodeId) => request(`/api/control/nodes/${nodeId}/token`),
  createNodeInstallCommand: () => request('/api/control/node-installation', { method: 'POST' }),
  registerNode: (payload) => request('/api/control/nodes', { method: 'POST', body: JSON.stringify(payload) }),
  updateNode: (nodeId, payload) => request(`/api/control/nodes/${nodeId}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  refreshNode: (nodeId) => request(`/api/control/nodes/${nodeId}/refresh`, { method: 'POST' }),
  reloadNode: (nodeId) => request(`/api/control/nodes/${nodeId}/reload`, { method: 'POST' }),
  deleteNode: (nodeId) => request(`/api/control/nodes/${nodeId}`, { method: 'DELETE' }),
  users: (nodeId, params, options = {}) => request(`/api/control/nodes/${nodeId}/users?${queryString(params)}`, options),
  usersForExport: (nodeId, params, options = {}) => request(`/api/control/nodes/${nodeId}/users/export?${queryString(params)}`, options),
  createUser: (nodeId, payload) => request(`/api/control/nodes/${nodeId}/users`, {
    method: 'POST', headers: operationHeaders('manual-create'), body: JSON.stringify(payload),
  }),
  connections: (nodeId, userId, options = {}) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/connections`, options),
  connectionsBatch: (nodeId, userIds, options = {}) => request(`/api/control/nodes/${nodeId}/users/connections/batch`, {
    method: 'POST', body: JSON.stringify({ userIds }), ...options,
  }),
  proxy: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/proxy`),
  traffic: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/traffic`),
  updateUserPolicy: (nodeId, userId, payload) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}/policy`, {
    method: 'PATCH', body: JSON.stringify(payload),
  }),
  bindProxy: (nodeId, payload) => request(`/api/control/nodes/${nodeId}/users/bind-proxy`, {
    method: 'POST', headers: operationHeaders('manual-bind'), body: JSON.stringify(payload),
  }),
  deleteUser: (nodeId, userId) => request(`/api/control/nodes/${nodeId}/users/${encodeURIComponent(userId)}`, {
    method: 'DELETE', headers: operationHeaders('manual-delete'),
  }),
  allocations: (params = {}) => {
    const query = queryString({ page: params.page, pageSize: params.pageSize, ip: params.ip })
    return request(`/api/control/allocations${query ? `?${query}` : ''}`)
  },
  allocation: (allocationId) => request(`/api/control/allocations/${allocationId}`),
  retryAllocation: (allocationId) => request(`/api/control/allocations/${allocationId}/retry`, { method: 'POST' }),
  deleteAllocation: (allocationId) => request(`/api/control/allocations/${allocationId}`, { method: 'DELETE' }),
  provision: (payload, idempotencyKey) => request('/api/control/allocations', {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(payload),
  }),
  provisionProxyBatch: (payload, idempotencyKey) => request('/api/control/allocations/proxy-provisions', {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(payload),
  }),
}

