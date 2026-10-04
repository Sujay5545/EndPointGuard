const API_BASE = import.meta.env.VITE_API_BASE_URL ?? ''
const TOKEN_KEY = 'endpointguard.jwt'

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY)
}

export async function request(path, options = {}) {
  const token = getToken()
  const headers = new Headers(options.headers || {})
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  } catch {
    throw new Error('Cannot reach the EndpointGuard API. Check that the backend is running and reachable.')
  }

  if (response.status === 401) {
    clearToken()
    window.dispatchEvent(new Event('endpointguard:unauthorized'))
    throw new Error('Your session has expired. Sign in again to continue.')
  }

  if (!response.ok) {
    let message = `Request failed (${response.status}).`
    try {
      const body = await response.json()
      if (body.message) message = body.message
      else if (body.error) message = body.error
    } catch {
      // Keep the response message generic when the server does not return JSON.
    }
    throw new Error(message)
  }

  if (response.status === 204) return null
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export const api = {
  login: (email, password) => request('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  register: (email, password) => request('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password }) }),
  profile: () => request('/api/auth/profile'),
  changePassword: (currentPassword, newPassword) => request('/api/auth/password', {
    method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }),
  }),
  health: () => request('/actuator/health'),
  auditLogs: () => request('/api/audit-logs'),
  projects: () => request('/api/projects'),
  createProject: (name) => request('/api/projects', { method: 'POST', body: JSON.stringify({ name }) }),
  repositories: (projectId) => request(`/api/projects/${projectId}/repositories`),
  linkRepository: (projectId, githubRepoFullName, webhookSecretRef) => request(`/api/projects/${projectId}/repositories`, {
    method: 'POST', body: JSON.stringify({ githubRepoFullName, webhookSecretRef }),
  }),
  updateRepository: (projectId, repositoryId, githubRepoFullName, webhookSecretRef) => request(`/api/projects/${projectId}/repositories/${repositoryId}`, {
    method: 'PUT', body: JSON.stringify({ githubRepoFullName, webhookSecretRef }),
  }),
  deleteRepository: (projectId, repositoryId) => request(`/api/projects/${projectId}/repositories/${repositoryId}`, { method: 'DELETE' }),
  pullRequests: (projectId, page = 0, size = 25, status = '') => request(`/api/projects/${projectId}/pull-requests?page=${page}&size=${size}${status ? `&status=${encodeURIComponent(status)}` : ''}`),
  pullRequest: (projectId, pullRequestId) => request(`/api/projects/${projectId}/pull-requests/${pullRequestId}`),
  monitoring: (projectId) => request(`/api/projects/${projectId}/monitoring`),
  endpoints: (repositoryId) => request(`/api/repositories/${repositoryId}/endpoints`),
  registerEndpoint: (repositoryId, payload) => request(`/api/repositories/${repositoryId}/endpoints`, {
    method: 'POST', body: JSON.stringify(payload),
  }),
  updateEndpoint: (repositoryId, endpointId, payload) => request(`/api/repositories/${repositoryId}/endpoints/${endpointId}`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  deleteEndpoint: (repositoryId, endpointId) => request(`/api/repositories/${repositoryId}/endpoints/${endpointId}`, { method: 'DELETE' }),
  endpointMetrics: (endpointId, from, to) => request(`/api/endpoints/${endpointId}/metrics?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  evaluateRisk: (endpointId, payload) => request(`/api/endpoints/${endpointId}/risk/evaluate`, {
    method: 'POST', body: JSON.stringify(payload),
  }),
}
