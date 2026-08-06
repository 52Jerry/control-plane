<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Activity, Clipboard, CloudCog, Copy, Database, Eye, EyeOff, Gauge, Link, LogOut, Plus, Power,
  RefreshCw, RotateCw, Search, Server, Settings2, ShieldCheck, Trash2, UserCog, Users,
  Wrench, X,
} from 'lucide-vue-next'
import { ApiError, api, setUnauthorizedHandler } from './api'

const meta = ref({ version: '0.1.0', authRequired: false, passwordLoginEnabled: false })
const dashboard = ref({ nodeCount: 0, onlineNodeCount: 0, degradedNodeCount: 0, userCount: 0, connections: 0, totalTraffic: 0 })
const nodes = ref([])
const users = ref([])
const userLoadError = ref('')
const allocations = ref([])
const allocationPage = reactive({ page: 1, pageSize: 20, total: 0, totalPages: 1 })
const auditLogs = ref([])
const auditPage = reactive({ page: 0, pageSize: 50, total: 0, totalPages: 1 })
const controlAccounts = ref([])
const activeView = ref('overview')
const userPage = reactive({ page: 1, pageSize: 20, total: 0, keyword: '' })
const selectedNodeId = ref(localStorage.getItem('selected-node-id') || '')
const loading = reactive({ app: true, nodes: false, users: false, allocations: false, audit: false, action: false })
const modal = reactive({ login: false, accounts: false, node: false, installation: false, user: false, provision: false, connection: false, proxy: false, proxyDetails: false, settings: false })
const toast = reactive({ visible: false, type: 'success', message: '' })
const loginForm = reactive({ username: '', password: '' })
const accountForm = reactive({ username: '', password: '', role: 'PROVISIONER' })
const accountPasswordForm = reactive({ accountId: '', username: '', password: '' })
const sessionUsername = ref('')
const sessionRole = ref('')
const loginConfigurationError = ref('')
const connectionData = ref(null)
const connectionUser = ref('')
const connectionContext = ref(null)
const revealConnectionSecrets = ref(false)
const proxyCredentialData = ref(null)
const revealProxyCredentials = ref(false)
const proxyBatchForm = reactive({
  input: '',
  preferredNodeId: '',
})
const proxyBatchResults = ref([])
const proxyBatchSummary = reactive({ total: 0, succeeded: 0, failed: 0 })
const revealBatchSecrets = ref(false)
const installCommand = ref('')
const installExpiresAt = ref('')
const installNow = ref(Date.now())
let refreshTimer
let installTimer
let toastTimer
let installRequestVersion = 0

const nodeForm = reactive({ name: '服务器节点', baseUrl: 'http://server:8088', token: '', maxUsers: 500 })
const nodeSettingsForm = reactive({ enabled: true, maintenance: false, maxUsers: 500 })
const provisionForm = reactive({ userId: '', protocols: ['vless', 'vmess', 'socks'], preferredNodeId: '' })
const userForm = reactive({
  userId: '',
  protocols: ['vless', 'vmess', 'socks'],
  socksUsername: '',
  socksPassword: '',
  useProxy: false,
  proxyServer: '',
  proxyPort: 1080,
  proxyUsername: '',
  proxyPassword: '',
})
const proxyForm = reactive({ userId: '', server: '', port: 1080, username: '', password: '' })

const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value) || null)
const totalPages = computed(() => Math.max(1, Math.ceil(userPage.total / userPage.pageSize)))
const effectiveRole = computed(() => meta.value.authRequired ? sessionRole.value : 'ADMIN')
const canManageAccounts = computed(() => effectiveRole.value === 'ADMIN')
const canViewAudit = computed(() => effectiveRole.value === 'ADMIN')
const canOperateNodes = computed(() => ['ADMIN', 'NODE_OPS'].includes(effectiveRole.value))
const canProvision = computed(() => ['ADMIN', 'NODE_OPS', 'PROVISIONER'].includes(effectiveRole.value))
const canManageUsers = computed(() => ['ADMIN', 'PROVISIONER'].includes(effectiveRole.value))
const canViewSensitive = computed(() => ['ADMIN', 'NODE_OPS', 'PROVISIONER'].includes(effectiveRole.value))
const pageTitle = computed(() => ({
  overview: '总览',
  nodes: '受管节点',
  allocations: '自动生成记录',
  users: selectedNode.value ? `${selectedNode.value.name} · 节点用户` : '节点用户管理',
  'node-management': '节点管理',
  audit: '审计日志',
}[activeView.value] || '节点控制中心'))
const allocatableNodes = computed(() => nodes.value.filter((node) => node.enabled && !node.maintenance && ['online', 'degraded'].includes(node.status) && node.userCount < node.maxUsers))
const batchConnectionCount = computed(() => proxyBatchResults.value.reduce(
  (total, result) => total + batchConnectionLinks(result).length,
  0,
))
const installRemainingSeconds = computed(() => {
  if (!installExpiresAt.value) return 0
  return Math.max(0, Math.ceil((new Date(installExpiresAt.value).getTime() - installNow.value) / 1000))
})
const installExpired = computed(() => Boolean(installExpiresAt.value) && installRemainingSeconds.value <= 0)

function notify(message, type = 'success') {
  clearTimeout(toastTimer)
  Object.assign(toast, { visible: true, message, type })
  toastTimer = setTimeout(() => { toast.visible = false }, 3200)
}

function errorMessage(error) {
  let message
  if (error instanceof ApiError && error.fields) {
    message = `${error.message}：${Object.values(error.fields).join('，')}`
  } else {
    message = error.message || '操作失败'
  }
  if (/Could not decrypt secret|CONTROL_PLANE_ENCRYPTION_KEY|敏感数据解密失败/i.test(message)) {
    message = '敏感数据解密失败，请检查本地与服务器的加密密钥是否一致。'
  }
  const knownMessages = new Map([
    ['Could not create allocation', '创建节点分配记录失败'],
    ['Could not create proxy allocation', '创建 SOCKS 节点分配记录失败'],
    ['Could not prepare allocation', '准备节点开通任务失败'],
    ['Could not complete allocation', '完成节点开通记录失败'],
    ['Could not hash provisioning request', '计算节点开通请求摘要失败'],
    ['Could not hash provisioning key', '计算节点开通幂等键摘要失败'],
    ['Could not hash operation request', '计算节点操作请求摘要失败'],
    ['Could not persist operation response', '保存节点操作响应失败'],
    ['Could not read persisted operation response', '读取已保存的节点操作响应失败'],
    ['Unauthorized', '未授权'],
    ['Forbidden', '无权执行此操作'],
    ['Internal Server Error', '服务器内部错误'],
  ])
  knownMessages.forEach((localized, source) => {
    message = message.replaceAll(source, localized)
  })
  message = message.replaceAll('Node Manager', '节点管理器')
  return redactKnownSecrets(message)
}

function localizedErrorMessage(value) {
  return errorMessage({ message: value })
}

function redactKnownSecrets(value) {
  let result = String(value || '')
  const secrets = [
    loginForm.password,
    accountForm.password,
    accountPasswordForm.password,
    nodeForm.token,
    userForm.socksPassword,
    userForm.proxyPassword,
    proxyForm.password,
    installCommand.value.match(/niusu_[A-Za-z0-9_-]+/)?.[0],
  ].filter((secret) => secret && secret.length >= 3)
  secrets.forEach((secret) => { result = result.split(secret).join('***') })
  return result
}

function clearConnectionDetails() {
  connectionData.value = null
  connectionUser.value = ''
  connectionContext.value = null
  revealConnectionSecrets.value = false
}

function closeConnectionModal() {
  modal.connection = false
  clearConnectionDetails()
}

function clearBatchDetails({ clearInput = true } = {}) {
  if (clearInput) proxyBatchForm.input = ''
  proxyBatchResults.value = []
  Object.assign(proxyBatchSummary, { total: 0, succeeded: 0, failed: 0 })
  revealBatchSecrets.value = false
}

function clearFormSecrets() {
  loginForm.password = ''
  accountForm.password = ''
  accountPasswordForm.password = ''
  nodeForm.token = ''
  userForm.socksPassword = ''
  userForm.proxyPassword = ''
  proxyForm.password = ''
}

function clearBusinessData() {
  nodes.value = []
  users.value = []
  userLoadError.value = ''
  allocations.value = []
  auditLogs.value = []
  controlAccounts.value = []
  dashboard.value = { nodeCount: 0, onlineNodeCount: 0, degradedNodeCount: 0, userCount: 0, connections: 0, totalTraffic: 0 }
  userPage.total = 0
  Object.assign(auditPage, { page: 0, total: 0, totalPages: 1 })
  clearConnectionDetails()
  closeProxyDetailsModal()
  clearBatchDetails()
  clearNodeInstallation()
}

function requireLogin(message = '') {
  clearFormSecrets()
  clearBusinessData()
  loginConfigurationError.value = message
  modal.installation = false
  modal.login = true
}

function closeNodeModal() {
  modal.node = false
  nodeForm.token = ''
}

function clearNodeInstallation() {
  installRequestVersion += 1
  installCommand.value = ''
  installExpiresAt.value = ''
  installNow.value = Date.now()
}

function closeNodeInstallation() {
  modal.installation = false
  clearNodeInstallation()
}

async function generateNodeInstallCommand() {
  loading.action = true
  clearNodeInstallation()
  const requestVersion = installRequestVersion
  try {
    const response = await api.createNodeInstallCommand()
    if (requestVersion !== installRequestVersion) return
    installCommand.value = response.command || ''
    installExpiresAt.value = response.expiresAt || ''
    installNow.value = Date.now()
  } catch (error) {
    if (requestVersion !== installRequestVersion) return
    notify(errorMessage(error), 'error')
  } finally {
    if (requestVersion === installRequestVersion || !modal.installation) loading.action = false
  }
}

async function openNodeInstallation() {
  modal.installation = true
  await generateNodeInstallCommand()
}

async function copyNodeInstallCommand() {
  if (!installCommand.value || installExpired.value) return
  await copy(installCommand.value)
}

function formatCountdown(seconds) {
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
}

function closeAccountModal() {
  modal.accounts = false
  accountForm.password = ''
  accountPasswordForm.password = ''
  Object.assign(accountPasswordForm, { accountId: '', username: '' })
}

function closeUserModal() {
  modal.user = false
  userForm.socksPassword = ''
  userForm.proxyPassword = ''
}

function closeProxyModal() {
  modal.proxy = false
  proxyForm.password = ''
}

function closeProxyDetailsModal() {
  modal.proxyDetails = false
  proxyCredentialData.value = null
  revealProxyCredentials.value = false
}

async function loadNodes() {
  loading.nodes = true
  try {
    const [nodeData, dashboardData] = await Promise.all([api.nodes(), api.dashboard()])
    nodes.value = nodeData
    dashboard.value = dashboardData
    if (!nodes.value.some((node) => node.id === selectedNodeId.value)) {
      selectedNodeId.value = nodes.value[0]?.id || ''
    }
    if (selectedNodeId.value) localStorage.setItem('selected-node-id', selectedNodeId.value)
  } finally {
    loading.nodes = false
  }
}

async function loadUsers(resetPage = false) {
  if (!selectedNodeId.value) {
    users.value = []
    userPage.total = 0
    userLoadError.value = ''
    return
  }
  if (resetPage) userPage.page = 1
  loading.users = true
  userLoadError.value = ''
  try {
    const data = await api.users(selectedNodeId.value, {
      page: userPage.page,
      pageSize: userPage.pageSize,
      keyword: userPage.keyword.trim(),
    })
    users.value = data.items
    userPage.total = data.total
  } catch (error) {
    if (error.status === 401) throw error
    users.value = []
    userPage.total = 0
    userLoadError.value = errorMessage(error)
  } finally {
    loading.users = false
  }
}

async function loadAllocations() {
  loading.allocations = true
  try {
    const data = await api.allocations({ page: allocationPage.page, pageSize: allocationPage.pageSize })
    if (Array.isArray(data)) {
      // Backward compatibility with an older Control Plane instance.
      allocations.value = data
      Object.assign(allocationPage, { total: data.length, totalPages: 1 })
    } else {
      allocations.value = data.items || []
      Object.assign(allocationPage, {
        page: data.page || allocationPage.page,
        pageSize: data.pageSize || allocationPage.pageSize,
        total: data.total || 0,
        totalPages: Math.max(1, data.totalPages || 1),
      })
    }
  } finally {
    loading.allocations = false
  }
}

async function loadAuditLogs() {
  if (!canViewAudit.value) {
    auditLogs.value = []
    Object.assign(auditPage, { page: 0, total: 0, totalPages: 1 })
    return
  }
  loading.audit = true
  try {
    const data = await api.auditLogs({ page: auditPage.page, pageSize: auditPage.pageSize })
    auditLogs.value = data.content || []
    Object.assign(auditPage, {
      page: Number.isInteger(data.number) ? data.number : auditPage.page,
      total: data.totalElements || 0,
      totalPages: Math.max(1, data.totalPages || 1),
    })
  } finally {
    loading.audit = false
  }
}

async function nextAuditPage(delta) {
  const target = auditPage.page + delta
  if (target < 0 || target >= auditPage.totalPages || loading.audit) return
  auditPage.page = target
  await loadAuditLogs()
}

async function nextAllocationPage(delta) {
  const target = allocationPage.page + delta
  if (target < 1 || target > allocationPage.totalPages || loading.allocations) return
  allocationPage.page = target
  await loadAllocations()
}

async function loadAll() {
  await Promise.all([loadNodes(), loadAllocations(), loadAuditLogs()])
  await loadUsers()
}

async function bootstrap() {
  loading.app = true
  try {
    meta.value = await api.meta()
    if (meta.value.authRequired) {
      if (!meta.value.passwordLoginEnabled) {
        requireLogin('后端尚未配置管理账号密码，请设置登录环境变量并重启服务。')
        return
      }
      const session = await api.session()
      if (!session.authenticated) {
        requireLogin()
        return
      }
    sessionUsername.value = session.username || ''
    sessionRole.value = session.role || 'ADMIN'
    }
    await loadAll()
  } catch (error) {
    if (error.status === 401) requireLogin()
    else notify(errorMessage(error), 'error')
  } finally {
    loading.app = false
  }
}

async function login() {
  if (!meta.value.passwordLoginEnabled || loginConfigurationError.value) return
  loading.action = true
  try {
    const session = await api.login({
      username: loginForm.username.trim(),
      password: loginForm.password,
    })
    sessionUsername.value = session.username || loginForm.username.trim()
    sessionRole.value = session.role || 'ADMIN'
    loginForm.password = ''
    modal.login = false
    notify('登录成功')
  } catch (error) {
    const message = error.status === 401 ? '账号或密码错误' : errorMessage(error)
    loginForm.password = ''
    notify(message, 'error')
    return
  } finally {
    loading.action = false
  }

  try {
    await loadAll()
  } catch (error) {
    if (error.status !== 401) notify(`登录成功，但节点数据读取失败：${errorMessage(error)}`, 'error')
  }
}

async function logout() {
  loading.action = true
  try {
    await api.logout()
  } catch (error) {
    if (error.status !== 401) notify(errorMessage(error), 'error')
  } finally {
    sessionUsername.value = ''
    sessionRole.value = ''
    requireLogin()
    loading.action = false
  }
}

async function loadControlAccounts() {
  controlAccounts.value = await api.controlAccounts()
}

async function openAccountManagement() {
  loading.action = true
  try {
    await loadControlAccounts()
    modal.accounts = true
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function createControlAccount() {
  loading.action = true
  try {
    await api.createControlAccount({
      username: accountForm.username.trim(),
      password: accountForm.password,
      role: accountForm.role,
    })
    Object.assign(accountForm, { username: '', password: '', role: 'PROVISIONER' })
    await loadControlAccounts()
    notify('管理账号已创建')
  } catch (error) {
    notify(errorMessage(error), 'error')
    accountForm.password = ''
  } finally {
    loading.action = false
  }
}

async function toggleControlAccount(account) {
  loading.action = true
  try {
    await api.updateControlAccount(account.id, { enabled: !account.enabled })
    await loadControlAccounts()
    notify(account.enabled ? '管理账号已停用，旧会话已失效' : '管理账号已启用')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function changeControlAccountRole(account, role) {
  if (!role || role === account.role) return
  loading.action = true
  try {
    await api.updateControlAccount(account.id, { role })
    await loadControlAccounts()
    notify('账号角色已更新，旧会话已失效')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

function beginResetAccountPassword(account) {
  Object.assign(accountPasswordForm, { accountId: account.id, username: account.username, password: '' })
}

function cancelResetAccountPassword() {
  Object.assign(accountPasswordForm, { accountId: '', username: '', password: '' })
}

async function resetControlAccountPassword() {
  const account = controlAccounts.value.find((item) => item.id === accountPasswordForm.accountId)
  loading.action = true
  try {
    await api.updateControlAccount(accountPasswordForm.accountId, { password: accountPasswordForm.password })
    accountPasswordForm.password = ''
    if (account?.current) {
      closeAccountModal()
      sessionUsername.value = ''
      requireLogin('当前账号密码已修改，请使用新密码重新登录。')
      return
    }
    cancelResetAccountPassword()
    await loadControlAccounts()
    notify('密码已重置，该账号的旧会话已失效')
  } catch (error) {
    notify(errorMessage(error), 'error')
    accountPasswordForm.password = ''
  } finally {
    loading.action = false
  }
}

async function deleteControlAccount(account) {
  if (!window.confirm(`确认删除管理账号 ${account.username}？此操作不可撤销。`)) return
  loading.action = true
  try {
    await api.deleteControlAccount(account.id)
    if (accountPasswordForm.accountId === account.id) cancelResetAccountPassword()
    await loadControlAccounts()
    notify('管理账号已删除')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function selectNode(nodeId) {
  selectedNodeId.value = nodeId
  localStorage.setItem('selected-node-id', nodeId)
  userPage.page = 1
  activeView.value = 'users'
  await loadUsers()
}

async function registerNode() {
  loading.action = true
  try {
    const node = await api.registerNode({ ...nodeForm, maxUsers: Number(nodeForm.maxUsers) })
    closeNodeModal()
    await loadNodes()
    await selectNode(node.id)
    notify('节点注册成功')
  } catch (error) {
    notify(errorMessage(error), 'error')
    nodeForm.token = ''
  } finally {
    loading.action = false
  }
}

function openProvision() {
  Object.assign(provisionForm, {
    userId: '', protocols: ['vless', 'vmess', 'socks'], preferredNodeId: '',
  })
  modal.provision = true
}

function createIdempotencyKey(userId) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `allocation-${userId}-${suffix}`.slice(0, 128)
}

function cleanProxyInput(value) {
  return String(value || '')
    .replace(/\ufeff/g, '')
    .replace(/[\u00a0\u3000]/g, ' ')
    .replace(/[\u200b\u200c\u200d]/g, '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .join('\n')
}

function cleanProxyBatchTextarea() {
  proxyBatchForm.input = cleanProxyInput(proxyBatchForm.input)
}

function handleProxyPaste() {
  setTimeout(cleanProxyBatchTextarea, 0)
}

function batchInputCredentials(input) {
  return cleanProxyInput(input).split('\n').flatMap((line) => {
    const columns = line.split(/\s+/)
    if (![5, 6].includes(columns.length)) return []
    return columns.length === 6 ? [columns[4], columns[5]] : [columns[3], columns[4]]
  }).filter((value) => value && value.length >= 3)
}

function redactBatchError(error, input) {
  let message = errorMessage(error)
  batchInputCredentials(input).forEach((secret) => { message = message.split(secret).join('***') })
  return message
}

function socksUri(socks) {
  if (!socks) return ''
  const host = String(socks.host || '').includes(':') ? `[${socks.host}]` : socks.host
  const username = encodeURIComponent(socks.username || '')
  const password = encodeURIComponent(socks.password || '')
  return `socks5://${username}:${password}@${host}:${socks.port}`
}

function connectionLinks(connection) {
  if (!connection) return []
  const labels = {
    socks5: 'SOCKS5 原始',
    bitbrowser: 'BitBrowser',
    vless: 'VLESS 加速',
    socksAcceleration: 'SOCKS 加速',
    vmess: 'VMess 加速',
  }
  const generated = Object.entries(connection.protocolsAll || {})
    .filter(([, value]) => value)
    .map(([key, value]) => ({ protocol: labels[key] || key, value }))
  if (generated.length > 0) return generated
  return [
    connection.vless ? { protocol: 'VLESS', value: connection.vless } : null,
    connection.vmess ? { protocol: 'VMess', value: connection.vmess } : null,
    connection.socks ? { protocol: 'SOCKS', value: socksUri(connection.socks) } : null,
  ].filter(Boolean)
}

function batchConnectionLinks(result) {
  const connection = result?.allocation?.connection
  if (!connection) return []
  // Batch provisioning must expose the same three Node Manager entry points
  // as manual user creation.  `result.socksLink` is the upstream SOCKS
  // endpoint and contains the upstream credentials; it is not the generated
  // node user's routed SOCKS entry and must never be shown as a protocol link.
  return connectionLinks(connection)
}

function maskedLink(value) {
  if (!value) return ''
  return '••••••••••••••••••••••••••••••••'
}

async function provisionProxyBatch() {
  const input = cleanProxyInput(proxyBatchForm.input)
  if (!input) {
    notify('请粘贴至少一行 SOCKS 节点信息', 'error')
    return
  }
  loading.action = true
  clearBatchDetails({ clearInput: false })
  try {
    const response = await api.provisionProxyBatch({
      input,
      protocols: ['vless', 'vmess', 'socks'],
      preferredNodeId: proxyBatchForm.preferredNodeId || null,
    }, createIdempotencyKey('proxy-batch'))
    proxyBatchResults.value = response.results || []
    Object.assign(proxyBatchSummary, {
      total: response.total || 0,
      succeeded: response.succeeded || 0,
      failed: response.failed || 0,
    })
    proxyBatchForm.input = ''
    revealBatchSecrets.value = false
    await Promise.all([loadAllocations(), loadNodes()])
    if (response.failed) notify(`已生成 ${response.succeeded} 行，${response.failed} 行需要修正`, 'error')
    else notify(`已生成 ${response.succeeded} 行节点连接`)
  } catch (error) {
    notify(redactBatchError(error, input), 'error')
    proxyBatchForm.input = ''
  } finally {
    loading.action = false
  }
}

async function showAllocationProxy(allocation) {
  loading.action = true
  try {
    const detail = await api.allocation(allocation.id)
    if (detail.provisioningMode !== 'UPSTREAM_SOCKS') return
    proxyCredentialData.value = detail
    revealProxyCredentials.value = false
    modal.proxyDetails = true
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function copyAllBatchLinks() {
  const values = proxyBatchResults.value.flatMap((result) => batchConnectionLinks(result)
    .map((link) => link.value))
  if (!values.length) return
  await copy(values.join('\n'))
}

async function provisionDirect() {
  loading.action = true
  try {
    const payload = {
      userId: provisionForm.userId,
      protocols: provisionForm.protocols,
      preferredNodeId: provisionForm.preferredNodeId || null,
    }
    const allocation = await api.provision(payload, createIdempotencyKey(provisionForm.userId))
    modal.provision = false
    await Promise.all([loadAllocations(), loadNodes()])
    if (allocation.connection) showAllocationConnection(allocation)
    notify('VPS 直出节点已自动生成')
  } catch (error) {
    await loadAllocations().catch(() => {})
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function retryAllocation(allocation) {
  loading.action = true
  try {
    const result = await api.retryAllocation(allocation.id)
    await Promise.all([loadAllocations(), loadNodes()])
    if (result.connection) showAllocationConnection(result)
    notify('分配已重新开通')
  } catch (error) {
    await loadAllocations().catch(() => {})
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function showAllocationConnection(allocation) {
  loading.action = true
  try {
    const detail = allocation.connection ? allocation : await api.allocation(allocation.id)
    connectionData.value = detail.connection
    connectionUser.value = detail.userId
    connectionContext.value = detail
    modal.connection = true
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

function openNodeSettings() {
  if (!selectedNode.value) return
  Object.assign(nodeSettingsForm, {
    enabled: selectedNode.value.enabled,
    maintenance: selectedNode.value.maintenance,
    maxUsers: selectedNode.value.maxUsers,
  })
  modal.settings = true
}

async function saveNodeSettings() {
  loading.action = true
  try {
    await api.updateNode(selectedNodeId.value, {
      enabled: nodeSettingsForm.enabled,
      maintenance: nodeSettingsForm.maintenance,
      maxUsers: Number(nodeSettingsForm.maxUsers),
    })
    modal.settings = false
    await loadNodes()
    notify('节点调度设置已保存')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function refreshNode(nodeId = selectedNodeId.value) {
  if (!nodeId) return
  loading.action = true
  try {
    await api.refreshNode(nodeId)
    await loadNodes()
    notify('节点状态已刷新')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function reloadNode() {
  if (!selectedNode.value || !confirm(`确认重载 ${selectedNode.value.name} 的 sing-box？`)) return
  loading.action = true
  try {
    const result = await api.reloadNode(selectedNodeId.value)
    await loadNodes()
    notify(result.success ? 'sing-box 重载成功' : 'sing-box 重载失败', result.success ? 'success' : 'error')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function removeNode(node) {
  if (!confirm(`仅从控制中心移除节点 ${node.name}，不会卸载服务器上的节点管理器。继续？`)) return
  try {
    await api.deleteNode(node.id)
    if (selectedNodeId.value === node.id) selectedNodeId.value = ''
    await loadAll()
    notify('节点已从控制中心移除')
  } catch (error) {
    notify(errorMessage(error), 'error')
  }
}

function openCreateUser() {
  Object.assign(userForm, {
    userId: '', protocols: ['vless', 'vmess', 'socks'], socksUsername: '', socksPassword: '',
    useProxy: false, proxyServer: '', proxyPort: 1080, proxyUsername: '', proxyPassword: '',
  })
  modal.user = true
}

async function createUser() {
  if (!selectedNodeId.value) return
  const proxy = userForm.useProxy ? {
    type: 'socks5',
    server: userForm.proxyServer,
    port: Number(userForm.proxyPort),
    username: userForm.proxyUsername || null,
    password: userForm.proxyPassword || null,
  } : null
  loading.action = true
  try {
    const result = await api.createUser(selectedNodeId.value, {
      userId: userForm.userId,
      protocols: userForm.protocols,
      socksUsername: userForm.socksUsername || null,
      socksPassword: userForm.socksPassword || null,
      proxy,
    })
    closeUserModal()
    connectionData.value = result
    connectionUser.value = result.userId
    connectionContext.value = null
    modal.connection = true
    await Promise.all([loadUsers(true), loadNodes()])
    notify('用户创建成功，连接信息已生成')
  } catch (error) {
    notify(errorMessage(error), 'error')
    userForm.socksPassword = ''
    userForm.proxyPassword = ''
  } finally {
    loading.action = false
  }
}

async function showConnections(user) {
  loading.action = true
  try {
    connectionData.value = await api.connections(selectedNodeId.value, user.userId)
    connectionUser.value = user.userId
    connectionContext.value = null
    modal.connection = true
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function openProxy(user) {
  if (user.proxyBound) {
    loading.action = true
    try {
      const detail = await api.proxy(selectedNodeId.value, user.userId)
      proxyCredentialData.value = {
        ...detail,
        proxyServer: detail.server,
        proxyPort: detail.port,
        proxyUsername: detail.username,
        proxyPassword: detail.password,
      }
      revealProxyCredentials.value = false
      modal.proxyDetails = true
    } catch (error) {
      notify(errorMessage(error), 'error')
    } finally {
      loading.action = false
    }
    return
  }
  Object.assign(proxyForm, { userId: user.userId, server: '', port: 1080, username: '', password: '' })
  modal.proxy = true
}

async function bindProxy() {
  loading.action = true
  try {
    await api.bindProxy(selectedNodeId.value, {
      userId: proxyForm.userId,
      proxy: {
        type: 'socks5', server: proxyForm.server, port: Number(proxyForm.port),
        username: proxyForm.username || null, password: proxyForm.password || null,
      },
    })
    closeProxyModal()
    await loadUsers()
    notify('住宅代理绑定成功')
  } catch (error) {
    notify(errorMessage(error), 'error')
    proxyForm.password = ''
  } finally {
    loading.action = false
  }
}

async function deleteUser(user) {
  if (!confirm(`确认删除用户 ${user.userId}？该操作会重载远端 sing-box。`)) return
  try {
    await api.deleteUser(selectedNodeId.value, user.userId)
    await Promise.all([loadUsers(), loadNodes()])
    notify('用户已删除')
  } catch (error) {
    notify(errorMessage(error), 'error')
  }
}

async function copy(value) {
  if (!value) return
  await navigator.clipboard.writeText(value)
  notify('已复制到剪贴板')
}

function nextPage(offset) {
  const target = userPage.page + offset
  if (target < 1 || target > totalPages.value) return
  userPage.page = target
  loadUsers()
}

function formatBytes(value) {
  const bytes = Number(value || 0)
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = bytes / 1024
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index += 1
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[index]}`
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

function statusText(status) {
  return ({ online: '在线', degraded: '降级', offline: '离线', unknown: '待检测' })[status] || status
}

function allocationStateText(state) {
  return ({ ACTIVE: '已生成', PROVISIONING: '开通中', RETRYABLE: '待重试', FAILED: '失败', PENDING: '待分配' })[state] || state
}

onMounted(async () => {
  setUnauthorizedHandler(() => requireLogin())
  await bootstrap()
  refreshTimer = setInterval(() => {
    if (!modal.login) Promise.all([loadNodes(), loadAllocations()]).catch(() => {})
  }, 15000)
  installTimer = setInterval(() => { installNow.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  setUnauthorizedHandler(null)
  clearInterval(refreshTimer)
  clearInterval(installTimer)
  clearTimeout(toastTimer)
  clearFormSecrets()
  clearBusinessData()
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><CloudCog :size="21" /></div>
        <div><strong>牛速控制中心</strong><span>多节点统一管理</span></div>
      </div>

      <button class="nav-item" :class="{ active: activeView === 'overview' }" @click="activeView = 'overview'"><Gauge :size="17" />总览</button>
      <button v-if="canOperateNodes || effectiveRole === 'READONLY'" class="nav-item" :class="{ active: activeView === 'nodes' }" @click="activeView = 'nodes'"><Server :size="17" />受管节点</button>
      <button v-if="canProvision || effectiveRole === 'READONLY'" class="nav-item" :class="{ active: activeView === 'allocations' }" @click="activeView = 'allocations'"><Database :size="17" />自动生成记录</button>
      <button v-if="canProvision || effectiveRole === 'READONLY'" class="nav-item" :class="{ active: activeView === 'users' }" @click="activeView = 'users'"><Users :size="17" />节点用户管理</button>
      <button v-if="canProvision" class="nav-item" :class="{ active: activeView === 'node-management' }" @click="activeView = 'node-management'"><Wrench :size="17" />节点管理</button>
      <button v-if="canViewAudit" class="nav-item" :class="{ active: activeView === 'audit' }" @click="activeView = 'audit'"><Activity :size="17" />审计日志</button>
      <div class="nav-heading">已注册节点</div>
      <button
        v-for="node in nodes"
        :key="node.id"
        class="node-nav"
        :class="{ selected: node.id === selectedNodeId }"
        @click="selectNode(node.id)"
      >
        <span class="status-dot" :class="node.status"></span>
        <span><strong>{{ node.name }}</strong><small>{{ node.host || node.baseUrl }}</small></span>
      </button>
      <button v-if="canOperateNodes" class="add-node-link" @click="activeView = 'node-management'; modal.node = true"><Plus :size="15" />添加节点</button>

      <div class="sidebar-footer">
        <span>控制中心</span><strong>v{{ meta.version }}</strong>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div>
          <p class="eyebrow">控制中心 / {{ pageTitle }}</p>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="top-actions">
          <button v-if="canOperateNodes" class="button ghost icon-text" :disabled="!selectedNode || loading.action" @click="refreshNode()"><RefreshCw :size="15" />刷新状态</button>
          <button v-if="canProvision" class="button secondary icon-text" :disabled="!selectedNode" @click="openCreateUser"><Users :size="15" />手动用户</button>
          <button v-if="canProvision" class="button primary icon-text" :disabled="allocatableNodes.length === 0" @click="openProvision"><Plus :size="16" />自动生成节点</button>
          <button v-if="meta.passwordLoginEnabled && canManageAccounts" class="button ghost icon-text" :disabled="loading.action" @click="openAccountManagement"><UserCog :size="15" />账号管理</button>
          <button v-if="meta.passwordLoginEnabled" class="button ghost icon-text" :disabled="loading.action" :title="sessionUsername || '退出管理端'" @click="logout"><LogOut :size="15" />退出</button>
        </div>
      </header>

      <section v-if="activeView === 'overview'" class="metrics-grid">
        <article class="metric-card"><span>在线节点</span><strong>{{ dashboard.onlineNodeCount }}<small>/ {{ dashboard.nodeCount }}</small></strong><i class="metric-icon green"><Server :size="16" /></i></article>
        <article class="metric-card"><span>自动开通</span><strong>{{ dashboard.activeAllocationCount || 0 }}<small v-if="dashboard.retryableAllocationCount">+ {{ dashboard.retryableAllocationCount }} 待重试</small></strong><i class="metric-icon blue"><Database :size="16" /></i></article>
        <article class="metric-card"><span>活跃连接</span><strong>{{ dashboard.connections }}</strong><i class="metric-icon violet"><Activity :size="16" /></i></article>
        <article class="metric-card"><span>累计流量</span><strong>{{ formatBytes(dashboard.totalTraffic) }}</strong><i class="metric-icon orange"><Gauge :size="16" /></i></article>
      </section>

      <section v-if="activeView === 'overview' && selectedNode" class="node-hero panel">
        <div class="node-identity">
          <div class="server-glyph"><Server :size="22" /></div>
          <div>
            <div class="title-line"><h2>{{ selectedNode.name }}</h2><span class="status-pill" :class="selectedNode.status">{{ statusText(selectedNode.status) }}</span></div>
            <p>{{ selectedNode.remoteNodeId || '等待节点代理信息' }} · {{ selectedNode.host || selectedNode.baseUrl }}</p>
          </div>
        </div>
        <div class="node-stats">
          <div><span>CPU</span><strong>{{ selectedNode.cpu.toFixed(1) }}%</strong></div>
          <div><span>内存</span><strong>{{ selectedNode.memory.toFixed(1) }}%</strong></div>
            <div><span>容量</span><strong>{{ selectedNode.userCount }} / {{ selectedNode.maxUsers }}</strong></div>
            <div><span>版本</span><strong>{{ selectedNode.managerVersion || '-' }}</strong></div>
          </div>
          <div v-if="canOperateNodes" class="node-actions">
          <button class="icon-button" title="调度设置" @click="openNodeSettings"><Settings2 :size="16" /></button>
          <button class="icon-button" title="重载 sing-box" @click="reloadNode"><RotateCw :size="16" /></button>
          <button class="icon-button danger-text" title="移除节点" @click="removeNode(selectedNode)"><Trash2 :size="16" /></button>
        </div>
        <div class="node-flags">
          <span :class="selectedNode.enabled ? 'positive' : 'danger-text'"><Power :size="12" />{{ selectedNode.enabled ? '参与调度' : '已停用' }}</span>
          <span v-if="selectedNode.maintenance" class="warning"><Wrench :size="12" />维护模式</span>
        </div>
        <p v-if="selectedNode.lastError" class="node-error">{{ localizedErrorMessage(selectedNode.lastError) }}</p>
      </section>

      <section v-if="activeView === 'node-management'" class="panel proxy-batch-panel">
        <div class="panel-heading proxy-batch-heading">
          <div><p class="eyebrow">批量 SOCKS 节点生成</p><h2>节点信息输入</h2></div>
          <div class="batch-actions">
            <button v-if="canProvision" class="button ghost icon-text" :disabled="batchConnectionCount === 0" @click="copyAllBatchLinks"><Copy :size="14" />复制所有链接</button>
            <button v-if="canProvision" class="button primary icon-text" :disabled="loading.action" @click="provisionProxyBatch"><Link :size="15" />生成三协议节点</button>
          </div>
        </div>
        <div class="proxy-batch-body">
          <div class="format-guide">
            <code>四列简写：SOCKS 地址 端口 用户名 密码（需选择指定节点管理器）</code>
            <strong>支持三种格式</strong>
            <code>住宅出口IP SOCKS接入地址 端口 用户名 密码</code>
            <code>序号 住宅出口IP SOCKS接入地址 端口 用户名 密码</code>
            <span>SOCKS 接入地址支持 IP 或域名；没有独立接入地址时填写 <code>-</code>，系统将使用住宅出口 IP。使用空格或 Tab 分隔，粘贴时自动清理 WPS/Excel 表格中的字节序标记、不换行空格和全角空格。</span>
          </div>
          <label class="proxy-input-label">批量 SOCKS 节点
            <textarea
              v-model="proxyBatchForm.input"
              rows="8"
              maxlength="50000"
              spellcheck="false"
              autocomplete="off"
              placeholder="38.30.216.149 198.13.46.231 5001 示例用户名 示例密码&#10;2 198.51.100.11 proxy.example.com 1080 示例用户名 示例密码"
              @paste="handleProxyPaste"
              @blur="cleanProxyBatchTextarea"
            ></textarea>
          </label>
          <div class="batch-options">
            <label>指定节点管理器（四列简写必选）
              <select v-model="proxyBatchForm.preferredNodeId"><option value="">自动选择最空闲节点</option><option v-for="node in allocatableNodes" :key="node.id" :value="node.id">{{ node.name }} · {{ node.userCount }}/{{ node.maxUsers }}</option></select>
            </label>
            <div class="fixed-protocols"><span>按出口模式生成协议</span><strong>VLESS</strong><strong>VMess</strong><strong>SOCKS</strong><small>有住宅 SOCKS 时返回五种协议；无住宅时仅返回三种 Node Manager 直出加速链接</small></div>
          </div>
          <p class="security-note"><ShieldCheck :size="14" />输入中的上游密码提交后立即从文本框清除；批量结果只保留在当前页面内存中，完整链接默认隐藏。</p>
        </div>
        <div v-if="proxyBatchSummary.total" class="batch-results">
          <div class="batch-summary">
            <span>总计 <strong>{{ proxyBatchSummary.total }}</strong></span>
            <span class="positive">成功 <strong>{{ proxyBatchSummary.succeeded }}</strong></span>
            <span :class="proxyBatchSummary.failed ? 'danger-text' : 'muted'">失败 <strong>{{ proxyBatchSummary.failed }}</strong></span>
            <label class="reveal-toggle"><input v-model="revealBatchSecrets" type="checkbox" /><span>{{ revealBatchSecrets ? '隐藏完整链接' : '显示完整链接' }}</span></label>
          </div>
          <div class="batch-result-list">
            <article v-for="result in proxyBatchResults" :key="result.rowNumber" class="batch-result-row" :class="result.error ? 'invalid' : 'valid'">
              <div class="batch-row-status">
                <strong>第 {{ result.rowNumber }} 行</strong>
                <span v-if="result.error">校验/生成失败</span>
                <span v-else class="residential-bound"><ShieldCheck :size="12" />原生住宅 · 三协议路由已绑定</span>
              </div>
              <p v-if="result.error" class="batch-error">{{ localizedErrorMessage(result.error) }}</p>
              <template v-else>
                <div class="residential-route-summary">
                  <div><span>住宅出口 IP</span><strong>{{ result.sourceIp || '-' }}</strong></div>
                  <div><span>国家 / 代码</span><strong>{{ result.countryName || '未知' }} / {{ result.countryCode || 'ZZ' }}</strong></div>
                  <div><span>上游 SOCKS</span><strong>{{ result.sourceAddress || '-' }}:{{ result.sourcePort || '-' }}</strong></div>
                  <div><span>节点用户</span><strong>{{ result.allocation?.userId || '-' }}</strong></div>
                  <div><span>节点入口</span><strong>VLESS / VMess / SOCKS</strong></div>
                </div>
                <div class="batch-links">
                  <div v-for="linkItem in batchConnectionLinks(result)" :key="linkItem.protocol">
                    <span>{{ linkItem.protocol }}</span>
                    <code>{{ revealBatchSecrets ? linkItem.value : maskedLink(linkItem.value) }}</code>
                    <button :title="`复制 ${linkItem.protocol}`" @click="copy(linkItem.value)"><Copy :size="13" /></button>
                  </div>
                </div>
              </template>
            </article>
          </div>
        </div>
      </section>

      <section v-if="activeView === 'allocations'" class="panel allocation-panel">
        <div class="panel-heading">
          <div><p class="eyebrow">直出节点生成</p><h2>自动生成记录</h2></div>
          <button v-if="canProvision" class="button primary icon-text" :disabled="allocatableNodes.length === 0" @click="openProvision"><Plus :size="15" />生成直出节点</button>
        </div>
        <div class="table-wrap">
          <table class="allocation-table">
            <thead><tr><th>用户</th><th>状态</th><th>节点管理器</th><th>出口模式</th><th>创建时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-if="loading.allocations"><td colspan="6" class="empty-state">正在加载自动开通记录...</td></tr>
              <tr v-else-if="allocations.length === 0"><td colspan="6" class="empty-state">暂无自动开通记录</td></tr>
              <tr v-for="allocation in allocations" :key="allocation.id">
                <td><div class="user-cell"><span class="avatar">{{ allocation.userId.slice(0, 2).toUpperCase() }}</span><span><strong>{{ allocation.userId }}</strong><small>{{ allocation.protocols.join(' / ') }}</small></span></div></td>
                <td><span class="allocation-state" :class="allocation.state.toLowerCase()">{{ allocationStateText(allocation.state) }}</span><small v-if="allocation.lastError" class="error-detail" :title="localizedErrorMessage(allocation.lastError)">{{ localizedErrorMessage(allocation.lastError) }}</small></td>
                <td><strong>{{ allocation.nodeName || '-' }}</strong><small class="table-subtext">{{ allocation.nodeHost || '等待节点' }}</small></td>
                <td><button v-if="allocation.provisioningMode === 'UPSTREAM_SOCKS'" class="link-button" @click="showAllocationProxy(allocation)">上游 SOCKS</button><strong v-else>VPS 直出</strong><small class="table-subtext">{{ allocation.provisioningMode === 'UPSTREAM_SOCKS' ? `${allocation.proxyServer || '-'}:${allocation.proxyPort || '-'}` : (allocation.nodeHost || '等待选择节点') }}</small></td>
                <td>{{ formatDate(allocation.createdAt) }}</td>
                <td><div class="row-actions"><button v-if="allocation.state === 'ACTIVE' && canViewSensitive" class="icon-action" title="查看连接" @click="showAllocationConnection(allocation)"><Link :size="14" />连接</button><button v-if="['RETRYABLE','FAILED','PENDING'].includes(allocation.state) && canProvision" class="icon-action" :disabled="loading.action" title="重新开通" @click="retryAllocation(allocation)"><RefreshCw :size="14" />重试</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ allocationPage.total }} 条记录</span>
          <div><button :disabled="allocationPage.page <= 1 || loading.allocations" @click="nextAllocationPage(-1)">上一页</button><strong>{{ allocationPage.page }} / {{ allocationPage.totalPages }}</strong><button :disabled="allocationPage.page >= allocationPage.totalPages || loading.allocations" @click="nextAllocationPage(1)">下一页</button></div>
        </div>
      </section>

      <section v-if="activeView === 'audit' && canViewAudit" class="panel allocation-panel">
        <div class="panel-heading">
          <div><p class="eyebrow">安全审计</p><h2>操作日志</h2></div>
          <button class="button ghost icon-text" :disabled="loading.audit" @click="loadAuditLogs"><RefreshCw :size="14" />刷新</button>
        </div>
        <div class="table-wrap">
          <table class="allocation-table">
            <thead><tr><th>时间</th><th>事件</th><th>操作者</th><th>目标</th><th>摘要</th></tr></thead>
            <tbody>
              <tr v-if="loading.audit"><td colspan="5" class="empty-state">正在加载审计日志…</td></tr>
              <tr v-else-if="auditLogs.length === 0"><td colspan="5" class="empty-state">暂无审计记录</td></tr>
              <tr v-for="log in auditLogs" :key="log.id">
                <td>{{ formatDate(log.createdAt) }}</td>
                <td><span class="protocol-tag">{{ log.eventType }}</span></td>
                <td>{{ log.actorUsername || '兼容令牌/系统' }}</td>
                <td>{{ log.targetType || '-' }}<small class="table-subtext">{{ log.targetId || '-' }}</small></td>
                <td>{{ log.summary || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ auditPage.total }} 条记录</span>
          <div><button :disabled="auditPage.page <= 0 || loading.audit" @click="nextAuditPage(-1)">上一页</button><strong>{{ auditPage.page + 1 }} / {{ auditPage.totalPages }}</strong><button :disabled="auditPage.page + 1 >= auditPage.totalPages || loading.audit" @click="nextAuditPage(1)">下一页</button></div>
        </div>
      </section>

      <section v-if="activeView === 'users'" class="panel users-panel">
        <div class="panel-heading">
          <div><p class="eyebrow">节点用户管理</p><h2>节点用户</h2></div>
          <div class="table-tools">
            <input v-model="userPage.keyword" placeholder="搜索用户 ID" @keyup.enter="loadUsers(true)" />
            <button class="button ghost icon-text" @click="loadUsers(true)"><Search :size="14" />搜索</button>
            <button class="button ghost icon-text" :disabled="loading.users || !selectedNode" @click="loadUsers(false)"><RefreshCw :size="14" />刷新</button>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead><tr><th>用户</th><th>协议</th><th>出口模式</th><th>流量</th><th>创建时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-if="loading.users"><td colspan="6" class="empty-state">正在加载节点用户…</td></tr>
              <tr v-else-if="userLoadError"><td colspan="6" class="empty-state error-detail">节点用户加载失败：{{ userLoadError }}</td></tr>
              <tr v-else-if="!selectedNode"><td colspan="6" class="empty-state">请先添加并选择一个节点</td></tr>
              <tr v-else-if="users.length === 0"><td colspan="6" class="empty-state">当前节点暂无用户</td></tr>
              <tr v-for="user in users" :key="user.userId">
                <td><div class="user-cell"><span class="avatar">{{ user.userId.slice(0, 2).toUpperCase() }}</span><span><strong>{{ user.userId }}</strong><small>{{ user.socksUsername || '自动凭据' }}</small></span></div></td>
                <td><span v-for="protocol in user.protocols" :key="protocol" class="protocol-tag">{{ protocol }}</span></td>
                <td><span :class="user.proxyBound ? 'positive' : 'muted'">{{ user.proxyBound ? user.proxyServer : '直连出口' }}</span></td>
                <td><strong>{{ formatBytes(user.total) }}</strong><small class="traffic-split">↑ {{ formatBytes(user.upload) }} / ↓ {{ formatBytes(user.download) }}</small></td>
                <td>{{ formatDate(user.createdAt) }}</td>
                <td><div class="row-actions"><button v-if="canViewSensitive" @click="showConnections(user)">连接</button><button v-if="canManageUsers" @click="openProxy(user)">代理</button><button v-if="canManageUsers" class="danger-text" @click="deleteUser(user)">删除</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ userPage.total }} 个用户</span>
          <div><button :disabled="userPage.page <= 1" @click="nextPage(-1)">上一页</button><strong>{{ userPage.page }} / {{ totalPages }}</strong><button :disabled="userPage.page >= totalPages" @click="nextPage(1)">下一页</button></div>
        </div>
      </section>

      <section v-if="activeView === 'nodes'" class="node-list-section">
        <div class="section-title">
          <div><p class="eyebrow">节点注册管理</p><h2>全部节点</h2></div>
          <div class="node-list-actions">
            <button v-if="canOperateNodes" class="button primary icon-text" :disabled="loading.action" @click="openNodeInstallation"><Server :size="14" />一键安装 Node Manager</button>
            <button v-if="canOperateNodes" class="button ghost icon-text" @click="modal.node = true"><Plus :size="14" />手动添加节点</button>
          </div>
        </div>
        <div class="node-grid">
          <article v-for="node in nodes" :key="node.id" class="compact-node" :class="{ selected: node.id === selectedNodeId }" @click="selectNode(node.id)">
            <div><span class="status-dot" :class="node.status"></span><strong>{{ node.name }}</strong></div>
            <p>{{ node.baseUrl }}</p>
            <dl><div><dt>状态</dt><dd>{{ node.maintenance ? '维护' : (node.enabled ? statusText(node.status) : '停用') }}</dd></div><div><dt>容量</dt><dd>{{ node.userCount }} / {{ node.maxUsers }}</dd></div><div><dt>流量</dt><dd>{{ formatBytes(node.totalTraffic) }}</dd></div></dl>
          </article>
          <button v-if="nodes.length === 0 && canOperateNodes" class="empty-node" @click="modal.node = true"><Plus :size="18" />注册第一个节点管理器</button>
        </div>
      </section>
    </main>

    <div v-if="modal.login" class="modal-backdrop locked">
      <form class="modal-card login-card" @submit.prevent="login">
        <div class="brand-mark large"><ShieldCheck :size="26" /></div><p class="eyebrow">安全管理登录</p><h2>登录牛速控制中心</h2><p>使用后端配置的管理账号和密码登录。</p>
        <p v-if="loginConfigurationError" class="login-error">{{ loginConfigurationError }}</p>
        <label>账号<input v-model.trim="loginForm.username" autocomplete="username" maxlength="128" required :disabled="Boolean(loginConfigurationError)" autofocus /></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" maxlength="1024" required :disabled="Boolean(loginConfigurationError)" /></label>
        <button class="button primary wide" type="submit" :disabled="loading.action || Boolean(loginConfigurationError)">登录管理端</button>
      </form>
    </div>

    <div v-if="modal.accounts" class="modal-backdrop" @mousedown.self="closeAccountModal">
      <div class="modal-card accounts-card">
        <div class="modal-heading"><div><p class="eyebrow">管理账号</p><h2>账号与权限管理</h2></div><button class="close-button" title="关闭" @click="closeAccountModal"><X :size="17" /></button></div>
        <p class="form-note account-note">账号按角色分配权限。密码仅用于本次请求，不会写入浏览器存储。</p>

        <form class="account-create-form" @submit.prevent="createControlAccount">
          <label>新账号<input v-model.trim="accountForm.username" required minlength="3" maxlength="64" pattern="[A-Za-z0-9._-]+" autocomplete="off" placeholder="请输入管理账号" /></label>
          <label>初始密码<input v-model="accountForm.password" required type="password" minlength="10" maxlength="128" autocomplete="new-password" placeholder="至少 10 位" /></label>
          <label>角色<select v-model="accountForm.role"><option value="PROVISIONER">节点开通</option><option value="NODE_OPS">节点运维</option><option value="READONLY">只读</option><option value="ADMIN">管理员</option></select></label>
          <button class="button primary icon-text" :disabled="loading.action"><Plus :size="15" />创建账号</button>
        </form>

        <div class="account-list">
          <article v-for="account in controlAccounts" :key="account.id" class="account-row">
            <div class="account-identity">
              <div class="avatar"><UserCog :size="15" /></div>
              <div><strong>{{ account.username }}</strong><span>{{ account.current ? '当前账号' : (account.enabled ? '可登录' : '已停用') }} · {{ ({ ADMIN: '管理员', NODE_OPS: '节点运维', PROVISIONER: '节点开通', READONLY: '只读' }[account.role] || account.role || '管理员') }}</span></div>
            </div>
            <div class="account-meta"><span>最后登录</span><strong>{{ formatDate(account.lastLoginAt) }}</strong></div>
            <div class="account-actions">
              <select :value="account.role || 'ADMIN'" :disabled="loading.action" @change="changeControlAccountRole(account, $event.target.value)"><option value="ADMIN">管理员</option><option value="NODE_OPS">节点运维</option><option value="PROVISIONER">节点开通</option><option value="READONLY">只读</option></select>
              <button class="button ghost" :disabled="loading.action" @click="beginResetAccountPassword(account)">重置密码</button>
              <button class="button ghost" :disabled="loading.action || account.current" @click="toggleControlAccount(account)">{{ account.enabled ? '停用' : '启用' }}</button>
              <button class="icon-button danger-text" title="删除账号" :disabled="loading.action || account.current" @click="deleteControlAccount(account)"><Trash2 :size="14" /></button>
            </div>
          </article>
          <p v-if="controlAccounts.length === 0" class="empty-state">尚无管理账号</p>
        </div>

        <form v-if="accountPasswordForm.accountId" class="account-password-form" @submit.prevent="resetControlAccountPassword">
          <div><strong>重置 {{ accountPasswordForm.username }} 的密码</strong><span>保存后该账号现有登录会话会立即失效。</span></div>
          <label>新密码<input v-model="accountPasswordForm.password" required type="password" minlength="10" maxlength="128" autocomplete="new-password" autofocus /></label>
          <div class="account-password-actions"><button type="button" class="button ghost" @click="cancelResetAccountPassword">取消</button><button class="button primary" :disabled="loading.action">保存新密码</button></div>
        </form>
      </div>
    </div>

    <div v-if="modal.node" class="modal-backdrop" @mousedown.self="closeNodeModal">
      <form class="modal-card" @submit.prevent="registerNode">
        <div class="modal-heading"><div><p class="eyebrow">节点注册管理</p><h2>注册节点管理器</h2></div><button type="button" class="close-button" title="关闭" @click="closeNodeModal"><X :size="17" /></button></div>
        <label>节点名称<input v-model.trim="nodeForm.name" required maxlength="120" /></label>
        <label>API 地址<input v-model.trim="nodeForm.baseUrl" required placeholder="http://server:8088" /></label>
        <label>访问令牌<input v-model.trim="nodeForm.token" required type="password" autocomplete="new-password" /></label>
        <label>最大用户数<input v-model.number="nodeForm.maxUsers" required type="number" min="1" max="100000" /></label>
        <p class="form-note">控制中心会调用 <code>/api/agent/info</code> 与心跳接口验证节点，访问令牌不会返回给前端。</p>
        <div class="modal-actions"><button type="button" class="button ghost" @click="closeNodeModal">取消</button><button class="button primary" :disabled="loading.action">验证并注册</button></div>
      </form>
    </div>

    <div v-if="modal.installation" class="modal-backdrop" @mousedown.self="closeNodeInstallation">
      <div class="modal-card wide-card installation-card">
        <div class="modal-heading"><div><p class="eyebrow">自动部署与注册</p><h2>一键安装 Node Manager</h2></div><button type="button" class="close-button" title="关闭" @click="closeNodeInstallation"><X :size="17" /></button></div>
        <div class="provision-intro"><Server :size="19" /><span>把下面命令复制到目标 VPS 的 root 终端执行。脚本会从 GitHub 安装 Node Manager、获取公网 IP 和主机信息、生成节点 API Token，并自动注册到当前控制中心。</span></div>

        <div v-if="installCommand" class="install-command-wrap">
          <div class="install-command-meta">
            <span :class="installExpired ? 'danger-text' : 'positive'"><ShieldCheck :size="13" />{{ installExpired ? '安装命令已过期' : `一次性安装码剩余 ${formatCountdown(installRemainingSeconds)}` }}</span>
            <span>{{ formatDate(installExpiresAt) }} 失效</span>
          </div>
          <div class="install-command"><code>{{ installCommand }}</code><button type="button" title="复制安装命令" :disabled="installExpired" @click="copyNodeInstallCommand"><Copy :size="16" /></button></div>
        </div>
        <div v-else class="install-command-loading">{{ loading.action ? '正在生成安全的一次性安装命令…' : '安装命令生成失败，请重试。' }}</div>

        <ul class="installation-notes">
          <li>支持 Ubuntu / Debian，必须使用 root 执行，并能访问 GitHub 和当前 Control Plane。</li>
          <li>安装码只允许成功使用一次，不会保存到浏览器存储；关闭弹窗或退出登录后会立即从页面内存清除。</li>
          <li>Control Plane 必须能访问 VPS 的 TCP 8088；请同步放行云安全组和 VPS 防火墙。</li>
          <li>命令过期或已经成功使用时，点击“重新生成”即可，不需要查找长期注册令牌。</li>
        </ul>

        <div class="modal-actions"><button type="button" class="button ghost" @click="closeNodeInstallation">关闭</button><button type="button" class="button secondary" :disabled="loading.action" @click="generateNodeInstallCommand">重新生成</button><button type="button" class="button primary icon-text" :disabled="!installCommand || installExpired" @click="copyNodeInstallCommand"><Copy :size="15" />复制安装命令</button></div>
      </div>
    </div>

    <div v-if="modal.provision" class="modal-backdrop" @mousedown.self="modal.provision = false">
      <form class="modal-card wide-card" @submit.prevent="provisionDirect">
        <div class="modal-heading"><div><p class="eyebrow">自动生成节点</p><h2>生成 VPS 直出节点</h2></div><button type="button" class="close-button" title="关闭" @click="modal.provision = false"><X :size="17" /></button></div>
        <div class="provision-intro"><Server :size="19" /><span>控制中心会选择在线且有容量的节点管理器，直接生成 VLESS、VMess 和 SOCKS 三种加速连接。连接使用 VPS 自身出口，不绑定上游代理。</span></div>
        <div class="form-grid">
          <label>用户 ID<input v-model.trim="provisionForm.userId" pattern="[A-Za-z0-9._-]+" maxlength="64" placeholder="留空则自动生成" /></label>
          <label>指定节点管理器（可选）
            <select v-model="provisionForm.preferredNodeId"><option value="">自动选择最空闲节点</option><option v-for="node in allocatableNodes" :key="node.id" :value="node.id">{{ node.name }} · {{ node.userCount }}/{{ node.maxUsers }}</option></select>
          </label>
        </div>
        <fieldset><legend>返回协议</legend><div class="checkbox-row"><label v-for="protocol in ['vless','vmess','socks']" :key="protocol"><input v-model="provisionForm.protocols" type="checkbox" :value="protocol" />{{ protocol.toUpperCase() }}</label></div></fieldset>
        <div class="modal-actions"><button type="button" class="button ghost" @click="modal.provision = false">取消</button><button class="button primary icon-text" :disabled="loading.action || provisionForm.protocols.length === 0"><Plus :size="15" />生成节点</button></div>
      </form>
    </div>

    <div v-if="modal.settings" class="modal-backdrop" @mousedown.self="modal.settings = false">
      <form class="modal-card" @submit.prevent="saveNodeSettings">
        <div class="modal-heading"><div><p class="eyebrow">节点调度策略</p><h2>节点调度设置</h2></div><button type="button" class="close-button" title="关闭" @click="modal.settings = false"><X :size="17" /></button></div>
        <label class="toggle-row"><input v-model="nodeSettingsForm.enabled" type="checkbox" /><span>允许自动分配新用户</span></label>
        <label class="toggle-row"><input v-model="nodeSettingsForm.maintenance" type="checkbox" /><span>维护模式（暂停新分配）</span></label>
        <label>最大用户数<input v-model.number="nodeSettingsForm.maxUsers" type="number" min="1" max="100000" required /></label>
        <p class="form-note">现有用户不会受启用状态或维护模式影响；这些设置只控制新的自动开通请求。</p>
        <div class="modal-actions"><button type="button" class="button ghost" @click="modal.settings = false">取消</button><button class="button primary" :disabled="loading.action">保存设置</button></div>
      </form>
    </div>

    <div v-if="modal.user" class="modal-backdrop" @mousedown.self="closeUserModal">
      <form class="modal-card wide-card" @submit.prevent="createUser">
        <div class="modal-heading"><div><p class="eyebrow">手动创建用户</p><h2>手动创建节点用户</h2></div><button type="button" class="close-button" title="关闭" @click="closeUserModal"><X :size="17" /></button></div>
        <div class="form-grid"><label>用户 ID<input v-model.trim="userForm.userId" required pattern="[A-Za-z0-9._-]+" /></label><label>SOCKS 用户名（可选）<input v-model.trim="userForm.socksUsername" autocomplete="off" /></label><label>SOCKS 密码（可选）<input v-model="userForm.socksPassword" type="password" autocomplete="new-password" /></label></div>
        <fieldset><legend>启用协议</legend><div class="checkbox-row"><label v-for="protocol in ['vless','vmess','socks']" :key="protocol"><input v-model="userForm.protocols" type="checkbox" :value="protocol" />{{ protocol.toUpperCase() }}</label></div></fieldset>
        <label class="toggle-row"><input v-model="userForm.useProxy" type="checkbox" /><span>创建时绑定住宅 SOCKS5 出口</span></label>
        <div v-if="userForm.useProxy" class="form-grid proxy-grid"><label>代理服务器<input v-model.trim="userForm.proxyServer" required /></label><label>端口<input v-model.number="userForm.proxyPort" type="number" min="1" max="65535" required /></label><label>用户名<input v-model.trim="userForm.proxyUsername" autocomplete="off" /></label><label>密码<input v-model="userForm.proxyPassword" type="password" autocomplete="new-password" /></label></div>
        <div class="modal-actions"><button type="button" class="button ghost" @click="closeUserModal">取消</button><button class="button primary" :disabled="loading.action || userForm.protocols.length === 0">创建用户</button></div>
      </form>
    </div>

    <div v-if="modal.proxy" class="modal-backdrop" @mousedown.self="closeProxyModal">
      <form class="modal-card" @submit.prevent="bindProxy">
        <div class="modal-heading"><div><p class="eyebrow">出口代理绑定</p><h2>绑定住宅出口</h2></div><button type="button" class="close-button" title="关闭" @click="closeProxyModal"><X :size="17" /></button></div>
        <p class="form-note">用户：<strong>{{ proxyForm.userId }}</strong></p>
        <div class="form-grid"><label>代理服务器<input v-model.trim="proxyForm.server" required /></label><label>端口<input v-model.number="proxyForm.port" type="number" min="1" max="65535" required /></label><label>用户名<input v-model.trim="proxyForm.username" autocomplete="off" /></label><label>密码<input v-model="proxyForm.password" type="password" autocomplete="new-password" /></label></div>
        <div class="modal-actions"><button type="button" class="button ghost" @click="closeProxyModal">取消</button><button class="button primary" :disabled="loading.action">确认绑定</button></div>
      </form>
    </div>

    <div v-if="modal.connection" class="modal-backdrop" @mousedown.self="closeConnectionModal">
      <div class="modal-card connection-card">
        <div class="modal-heading"><div><p class="eyebrow">连接信息</p><h2>{{ connectionUser }}</h2></div><button class="close-button" title="关闭" @click="closeConnectionModal"><X :size="17" /></button></div>
        <div v-if="connectionContext" class="connection-context"><span><Server :size="13" />{{ connectionContext.nodeName }} · {{ connectionContext.nodeHost }}</span><span><ShieldCheck :size="13" />{{ connectionData?.proxyBound ? '已绑定上游代理' : 'VPS 直出，未绑定上游代理' }}</span></div>
        <label class="reveal-toggle connection-reveal"><input v-model="revealConnectionSecrets" type="checkbox" /><component :is="revealConnectionSecrets ? EyeOff : Eye" :size="14" /><span>{{ revealConnectionSecrets ? '隐藏完整连接' : '显示完整连接' }}</span></label>
        <div v-if="connectionData" class="connection-list">
          <div v-for="link in connectionLinks(connectionData)" :key="`${link.protocol}-${link.value}`">
            <span>{{ link.protocol }}</span>
            <code>{{ revealConnectionSecrets ? link.value : maskedLink(link.value) }}</code>
            <button :title="`复制 ${link.protocol}`" @click="copy(link.value)"><Copy :size="14" /></button>
          </div>
          <p v-if="connectionLinks(connectionData).length === 0" class="empty-state">暂无可用连接</p>
        </div>
      </div>
    </div>

    <div v-if="modal.proxyDetails" class="modal-backdrop" @mousedown.self="closeProxyDetailsModal">
      <div class="modal-card">
        <div class="modal-heading"><div><p class="eyebrow">出口代理</p><h2>上游 SOCKS 详情</h2></div><button class="close-button" title="关闭" @click="closeProxyDetailsModal"><X :size="17" /></button></div>
        <div v-if="proxyCredentialData" class="detail-list">
          <div><span>住宅出口 IP</span><strong>{{ proxyCredentialData.sourceIp || '-' }}</strong></div>
          <div><span>代理服务器</span><strong>{{ proxyCredentialData.proxyServer || proxyCredentialData.sourceAddress || '-' }}</strong></div>
          <div><span>端口</span><strong>{{ proxyCredentialData.proxyPort || '-' }}</strong></div>
          <div><span>账号</span><strong>{{ revealProxyCredentials ? (proxyCredentialData.proxyUsername || '-') : '••••••••' }}</strong></div>
          <div><span>密码</span><strong>{{ revealProxyCredentials ? (proxyCredentialData.proxyPassword || '-') : '••••••••' }}</strong></div>
          <div><span>节点用户 ID</span><strong>{{ proxyCredentialData.userId }}</strong></div>
        </div>
        <label class="reveal-toggle connection-reveal"><input v-model="revealProxyCredentials" type="checkbox" /><component :is="revealProxyCredentials ? EyeOff : Eye" :size="14" /><span>{{ revealProxyCredentials ? '隐藏账号密码' : '显示账号密码' }}</span></label>
      </div>
    </div>

    <transition name="toast"><div v-if="toast.visible" class="toast" :class="toast.type">{{ toast.message }}</div></transition>
    <div v-if="loading.app" class="loading-screen"><div class="loader"></div><span>正在连接控制中心…</span></div>
  </div>
</template>

