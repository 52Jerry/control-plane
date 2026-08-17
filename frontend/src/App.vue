<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  Activity, AlertTriangle, Clipboard, CloudCog, Copy, Database, Download, Eye, EyeOff, FileSpreadsheet, Gauge, Link, LogOut, Plus, Power,
  RefreshCw, RotateCw, Search, Server, Settings2, ShieldCheck, Trash2, UserCog, Users,
  Upload, Wrench, X,
} from 'lucide-vue-next'
import { ApiError, api, setUnauthorizedHandler } from './api'
import {
  buildAllProtocols,
  buildConnectionExportData,
  buildConnectionLinks,
  connectionExportScenarios,
} from './protocols'
import {
  buildConnectionBatchJobs,
  chunkRows,
  connectionLookupKey,
  distributeRowsRoundRobin,
  filterImportedRowsBySequence,
  parseExcelTransferTables,
  proxyInputFromRows,
  selectedRowsForExport,
  selectSequenceRange,
} from './excelTransfer'
import ConnectionLinksPanel from './ConnectionLinksPanel.vue'
import { normalizeUserPageQuery, userPageCacheKey } from './userPageCache'

const meta = ref({ version: '0.1.0', authRequired: false, passwordLoginEnabled: false })
const dashboard = ref({ nodeCount: 0, onlineNodeCount: 0, degradedNodeCount: 0, userCount: 0, connections: 0, totalTraffic: 0 })
const nodes = ref([])
const users = ref([])
const userLoadError = ref('')
const allocations = ref([])
const allocationPage = reactive({ page: 1, pageSize: 20, total: 0, totalPages: 1, ip: '' })
const auditLogs = ref([])
const auditPage = reactive({ page: 0, pageSize: 50, total: 0, totalPages: 1 })
const controlAccounts = ref([])
const activeView = ref('overview')
const userPage = reactive({ page: 1, pageSize: 20, total: 0, keyword: '', ip: '', sort: 'createdDesc' })
const selectedNodeId = ref(localStorage.getItem('selected-node-id') || '')
const loading = reactive({ app: true, nodes: false, users: false, allocations: false, audit: false, action: false })
const modal = reactive({ login: false, accounts: false, node: false, installation: false, user: false, provision: false, connection: false, proxy: false, proxyDetails: false, policy: false, settings: false, exportUsers: false })
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
const revealListCredentials = ref(false)
const deletingUserId = ref('')
const selectedUserIds = ref([])
const selectedUserRows = ref([])
const selectedUserNodeId = ref('')
const exportingUsers = ref(false)
const importingUsers = ref(false)
const excelMode = ref('export')
const exportForm = reactive({ scenario: 'ipDirect', nodeIds: [], rangeStart: 1, rangeEnd: '' })
const importForm = reactive({ file: null, fileName: '', nodeIds: [], rangeStart: 1, rangeEnd: '' })
const importFileInput = ref(null)
const importResult = ref(null)
const exportProgress = reactive({ current: 0, total: 0, stage: '准备导出数据' })
const importProgress = reactive({ current: 0, total: 0, succeeded: 0, failed: 0 })
const nodeToken = ref('')
const nodeTokenNodeId = ref('')
const revealNodeToken = ref(false)
const loadingNodeToken = ref(false)
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
let nodeTokenRequestVersion = 0
let userLoadRequestVersion = 0
let userPageCacheGeneration = 0
const userPageCache = new Map()
const userPageRequests = new Map()

const nodeForm = reactive({ name: '服务器节点', baseUrl: 'http://server:8088', token: '', maxUsers: 500 })
const nodeSettingsForm = reactive({ enabled: true, maintenance: false, maxUsers: 500 })
const provisionForm = reactive({ userId: '', protocols: ['vless', 'vmess', 'socks'], preferredNodeId: '', trafficLimitGb: null, maxSourceIps: null })
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
  trafficLimitGb: null,
  maxSourceIps: null,
})
const proxyForm = reactive({ userId: '', server: '', port: 1080, username: '', password: '' })
const policyForm = reactive({ userId: '', trafficLimitGb: null, maxSourceIps: null })

const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value) || null)
const selectedExportScenario = computed(() => connectionExportScenarios
  .find((scenario) => scenario.key === exportForm.scenario) || connectionExportScenarios[0])
const selectedUserCount = computed(() => selectedUserNodeId.value === selectedNodeId.value
  ? selectedUserIds.value.length
  : 0)
const currentPageUserIds = computed(() => users.value.map((user) => user.userId))
const allCurrentPageUsersSelected = computed(() => currentPageUserIds.value.length > 0
  && currentPageUserIds.value.every((userId) => selectedUserIds.value.includes(userId)))
const someCurrentPageUsersSelected = computed(() => currentPageUserIds.value
  .some((userId) => selectedUserIds.value.includes(userId)))
const excelTransferBusy = computed(() => exportingUsers.value || importingUsers.value)
const transferNodes = computed(() => excelMode.value === 'import' ? allocatableNodes.value : nodes.value)
const activeTransferNodeIds = computed(() => excelMode.value === 'import' ? importForm.nodeIds : exportForm.nodeIds)
const totalPages = computed(() => Math.max(1, Math.ceil(userPage.total / userPage.pageSize)))
const effectiveRole = computed(() => meta.value.authRequired ? sessionRole.value : 'ADMIN')
const canManageAccounts = computed(() => effectiveRole.value === 'ADMIN')
const canViewAudit = computed(() => effectiveRole.value === 'ADMIN')
const canOperateNodes = computed(() => ['ADMIN', 'NODE_OPS'].includes(effectiveRole.value))
const canProvision = computed(() => ['ADMIN', 'NODE_OPS', 'PROVISIONER'].includes(effectiveRole.value))
const canManageUsers = computed(() => ['ADMIN', 'PROVISIONER'].includes(effectiveRole.value))
const canDeleteUsers = computed(() => ['ADMIN', 'NODE_OPS', 'PROVISIONER'].includes(effectiveRole.value))
const canViewSensitive = computed(() => ['ADMIN', 'NODE_OPS', 'PROVISIONER'].includes(effectiveRole.value))
const canViewNodeToken = computed(() => ['ADMIN', 'NODE_OPS'].includes(effectiveRole.value))
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
const proxyOriginalSocksLink = computed(() => {
  if (!revealProxyCredentials.value) return ''
  const d = proxyCredentialData.value
  if (!d) return ''
  // The proxy-details response keeps local node credentials and upstream
  // residential credentials in separate fields.  Build the raw SOCKS link
  // through the same protocol helper used by the connection modal so the
  // upstream endpoint and credentials are selected explicitly.
  const links = buildAllProtocols(
    d.protocolInfo || d,
    ['vless', 'vmess', 'socks'],
    true,
  )
  return links.find((item) => item.key === 'socks5')?.value || ''
})

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
    nodeToken.value,
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
  clearNodeToken()
}

function clearNodeToken() {
  nodeTokenRequestVersion += 1
  nodeToken.value = ''
  nodeTokenNodeId.value = ''
  revealNodeToken.value = false
  loadingNodeToken.value = false
}

function clearBusinessData() {
  clearUserPageCache()
  loading.users = false
  nodes.value = []
  users.value = []
  userLoadError.value = ''
  allocations.value = []
  auditLogs.value = []
  controlAccounts.value = []
  dashboard.value = { nodeCount: 0, onlineNodeCount: 0, degradedNodeCount: 0, userCount: 0, connections: 0, totalTraffic: 0 }
  userPage.total = 0
  modal.exportUsers = false
  exportingUsers.value = false
  importingUsers.value = false
  importForm.file = null
  importForm.fileName = ''
  importResult.value = null
  Object.assign(exportProgress, { current: 0, total: 0, stage: '准备导出数据' })
  Object.assign(importProgress, { current: 0, total: 0, succeeded: 0, failed: 0 })
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

function closePolicyModal() {
  modal.policy = false
  Object.assign(policyForm, { userId: '', trafficLimitGb: null, maxSourceIps: null })
}

async function loadNodes({ awaitDashboard = true } = {}) {
  loading.nodes = true
  try {
    const nodeData = await api.nodes()
    nodes.value = nodeData
    if (!nodes.value.some((node) => node.id === selectedNodeId.value)) {
      selectedNodeId.value = nodes.value[0]?.id || ''
    }
    if (nodeTokenNodeId.value && nodeTokenNodeId.value !== selectedNodeId.value) clearNodeToken()
    if (selectedNodeId.value) localStorage.setItem('selected-node-id', selectedNodeId.value)

    const dashboardRequest = api.dashboard().then((dashboardData) => {
      dashboard.value = dashboardData
      return dashboardData
    })
    if (!awaitDashboard) {
      dashboardRequest.catch((error) => notify(errorMessage(error), 'error'))
    } else {
      await dashboardRequest
    }
  } finally {
    loading.nodes = false
  }
}

function clearUserPageCache() {
  userPageCacheGeneration += 1
  userPageCache.clear()
  userPageRequests.clear()
  userLoadRequestVersion += 1
}

function currentUserPageQuery(page = userPage.page) {
  return normalizeUserPageQuery({
    nodeId: selectedNodeId.value,
    page,
    pageSize: userPage.pageSize,
    keyword: userPage.keyword,
    ip: userPage.ip,
    sort: userPage.sort,
  })
}

function applyUserPage(data, query) {
  users.value = Array.isArray(data?.items) ? data.items : []
  userPage.page = query.page
  userPage.total = Number(data?.total || 0)
  userLoadError.value = ''
}

async function fetchUserPage(query, { force = false } = {}) {
  const cacheKey = userPageCacheKey(query)
  if (!force && userPageCache.has(cacheKey)) return userPageCache.get(cacheKey)

  const generation = userPageCacheGeneration
  const requestKey = `${generation}:${cacheKey}:${force ? 'refresh' : 'cached'}`
  if (userPageRequests.has(requestKey)) return userPageRequests.get(requestKey)

  const request = api.users(query.nodeId, {
    page: query.page,
    pageSize: query.pageSize,
    keyword: query.keyword,
    ip: query.ip,
    sort: query.sort,
    refresh: force || undefined,
  }).then((data) => {
    if (generation === userPageCacheGeneration) userPageCache.set(cacheKey, data)
    return data
  }).finally(() => {
    userPageRequests.delete(requestKey)
  })
  userPageRequests.set(requestKey, request)
  return request
}

function prefetchNextUserPage(query, total) {
  const totalPageCount = Math.max(1, Math.ceil(Number(total || 0) / query.pageSize))
  if (query.page >= totalPageCount) return
  const nextQuery = { ...query, page: query.page + 1 }
  const cacheKey = userPageCacheKey(nextQuery)
  if (userPageCache.has(cacheKey)) return
  fetchUserPage(nextQuery).catch(() => {})
}

function prefetchNodeUsers(nodeId) {
  if (!nodeId || nodeId === selectedNodeId.value
    || userPage.keyword.trim() || userPage.ip.trim()) return
  const query = normalizeUserPageQuery({
    nodeId,
    page: 1,
    pageSize: userPage.pageSize,
    keyword: '',
    ip: '',
    sort: userPage.sort,
  })
  const cacheKey = userPageCacheKey(query)
  if (userPageCache.has(cacheKey)) return
  fetchUserPage(query).catch(() => {})
}

async function loadUsers(resetPage = false, options = {}) {
  if (!selectedNodeId.value) {
    userLoadRequestVersion += 1
    loading.users = false
    users.value = []
    userPage.total = 0
    userLoadError.value = ''
    clearUserSelection()
    return
  }
  const targetPage = resetPage ? 1 : (options.page || userPage.page)
  if (resetPage) {
    clearUserSelection()
  }
  const force = Boolean(options.force)
  if (force) clearUserPageCache()
  const query = currentUserPageQuery(targetPage)
  const cacheKey = userPageCacheKey(query)
  const requestVersion = ++userLoadRequestVersion
  userLoadError.value = ''
  if (!force && userPageCache.has(cacheKey)) {
    const data = userPageCache.get(cacheKey)
    loading.users = false
    applyUserPage(data, query)
    prefetchNextUserPage(query, data.total)
    return
  }

  loading.users = true
  try {
    const data = await fetchUserPage(query, { force })
    if (requestVersion !== userLoadRequestVersion
      || userPageCacheKey(currentUserPageQuery(targetPage)) !== cacheKey) return
    applyUserPage(data, query)
    prefetchNextUserPage(query, data.total)
  } catch (error) {
    if (error.status === 401) throw error
    if (requestVersion !== userLoadRequestVersion) return
    if (users.value.length === 0) userPage.total = 0
    userLoadError.value = errorMessage(error)
  } finally {
    if (requestVersion === userLoadRequestVersion) loading.users = false
  }
}

async function loadAllocations(resetPage = false) {
  if (resetPage) allocationPage.page = 1
  loading.allocations = true
  try {
    const data = await api.allocations({
      page: allocationPage.page,
      pageSize: allocationPage.pageSize,
      ip: allocationPage.ip.trim(),
    })
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
  await loadNodes({ awaitDashboard: false })
  await Promise.all([loadUsers(), loadAllocations(), loadAuditLogs()])
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
  if (selectedNodeId.value !== nodeId) {
    clearNodeToken()
    clearUserSelection()
    userLoadRequestVersion += 1
    loading.users = false
    users.value = []
    userPage.total = 0
    userLoadError.value = ''
  }
  selectedNodeId.value = nodeId
  localStorage.setItem('selected-node-id', nodeId)
  userPage.page = 1
  activeView.value = 'users'
  await loadUsers()
}

async function loadNodeToken() {
  const nodeId = selectedNodeId.value
  if (!nodeId || !canViewNodeToken.value) return
  if (nodeTokenNodeId.value === nodeId && nodeToken.value) {
    revealNodeToken.value = true
    return
  }
  clearNodeToken()
  const requestVersion = nodeTokenRequestVersion
  loadingNodeToken.value = true
  try {
    const response = await api.nodeToken(nodeId)
    if (requestVersion !== nodeTokenRequestVersion || selectedNodeId.value !== nodeId) return
    nodeToken.value = response.token || ''
    nodeTokenNodeId.value = nodeId
    revealNodeToken.value = true
  } catch (error) {
    if (requestVersion === nodeTokenRequestVersion) notify(errorMessage(error), 'error')
  } finally {
    if (requestVersion === nodeTokenRequestVersion) loadingNodeToken.value = false
  }
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
    userId: '', protocols: ['vless', 'vmess', 'socks'], preferredNodeId: '', trafficLimitGb: null, maxSourceIps: null,
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
  const rawHost = String(socks.host || '')
  const host = rawHost.includes(':') && !rawHost.startsWith('[') ? `[${rawHost}]` : rawHost
  // V2Ray/V2RayN expects the complete username:password pair as standard
  // Base64 in the SOCKS URI userinfo.  The server still stores and checks the
  // two credentials separately; this conversion is only for sharing/import.
  const bytes = new TextEncoder().encode(`${socks.username || ''}:${socks.password || ''}`)
  let binary = ''
  bytes.forEach((byte) => { binary += String.fromCharCode(byte) })
  const auth = btoa(binary)
  return `socks://${auth}@${host}:${socks.port}`
}

function connectionLinks(connection) {
  return buildConnectionLinks(connection)
}

function withDirectEndpoint(connection, endpoint) {
  if (!connection || !endpoint) return connection
  const socks = connection.socks || {}
  const host = endpoint.host || endpoint.ip || endpoint.sourceIp
  const port = endpoint.port || endpoint.sourcePort
  const countryCode = endpoint.countryCode
  const protocolInfo = {
    ...(connection.protocolInfo || {}),
    ...(countryCode && !['XX', 'ZZ'].includes(String(countryCode).toUpperCase()) ? {
      countryCode,
      countryName: endpoint.countryName,
      cityName: endpoint.cityName,
    } : {}),
  }
  if (!host || !port) return { ...connection, protocolInfo }
  return {
    ...connection,
    protocolInfo,
    directEndpoint: {
      host,
      port,
      username: endpoint.username || socks.username,
      password: endpoint.password || socks.password,
    },
  }
}

function batchConnectionLinks(result) {
  const connection = result?.allocation?.connection
  if (!connection) return []
  return connectionLinks(withDirectEndpoint(connection, {
    ip: result.sourceIp,
    port: result.sourcePort,
  }))
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
      trafficLimitBytes: gigabytesToBytes(provisionForm.trafficLimitGb),
      maxSourceIps: positiveIntegerOrNull(provisionForm.maxSourceIps),
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

async function deleteAllocation(allocation) {
  if (!confirm(`确认删除 ${allocation.userId} 的失败/待分配记录？删除后可重新生成。`)) return
  loading.action = true
  try {
    await api.deleteAllocation(allocation.id)
    if (allocations.value.length === 1 && allocationPage.page > 1) allocationPage.page -= 1
    await loadAllocations()
    notify('记录已删除，可以重新生成节点')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function showAllocationConnection(allocation) {
  loading.action = true
  try {
    const detail = allocation.connection ? allocation : await api.allocation(allocation.id)
    let connection = detail.connection
    if (detail.nodeId && detail.userId) {
      try {
        connection = await api.connections(detail.nodeId, detail.userId)
      } catch (error) {
        if (!connection) throw error
      }
    }
    connectionData.value = withDirectEndpoint(connection, {
      ip: detail.sourceIp || detail.access?.ip,
      port: detail.sourcePort || detail.access?.port,
      username: detail.proxyUsername || detail.access?.username,
      password: detail.proxyPassword || detail.access?.password,
      countryCode: detail.access?.countryCode,
      countryName: detail.access?.countryName,
      cityName: detail.access?.cityName,
    })
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
    if (selectedNodeId.value === node.id) {
      selectedNodeId.value = ''
      clearNodeToken()
    }
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
    trafficLimitGb: null, maxSourceIps: null,
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
    const directEndpoint = userForm.useProxy ? {
      host: userForm.proxyServer,
      port: Number(userForm.proxyPort),
      username: userForm.proxyUsername,
      password: userForm.proxyPassword,
    } : null
    const result = await api.createUser(selectedNodeId.value, {
      userId: userForm.userId,
      protocols: userForm.protocols,
      socksUsername: userForm.useProxy ? (userForm.proxyUsername || null) : (userForm.socksUsername || null),
      socksPassword: userForm.useProxy ? (userForm.proxyPassword || null) : (userForm.socksPassword || null),
      proxy,
      trafficLimitBytes: gigabytesToBytes(userForm.trafficLimitGb),
      maxSourceIps: positiveIntegerOrNull(userForm.maxSourceIps),
    })
    closeUserModal()
    connectionData.value = withDirectEndpoint(result, directEndpoint)
    connectionUser.value = result.userId
    connectionContext.value = null
    modal.connection = true
    await Promise.all([loadUsers(true, { force: true }), loadNodes()])
    notify('用户创建成功，连接信息已生成')
  } catch (error) {
    notify(errorMessage(error), 'error')
    userForm.socksPassword = ''
    userForm.proxyPassword = ''
  } finally {
    loading.action = false
  }
}

function openPolicy(user) {
  Object.assign(policyForm, {
    userId: user.userId,
    trafficLimitGb: user.trafficLimitBytes ? Number((user.trafficLimitBytes / (1024 ** 3)).toFixed(3)) : null,
    maxSourceIps: user.maxSourceIps || null,
  })
  modal.policy = true
}

async function savePolicy() {
  if (!selectedNodeId.value || !policyForm.userId) return
  loading.action = true
  try {
    await api.updateUserPolicy(selectedNodeId.value, policyForm.userId, {
      trafficLimitBytes: gigabytesToBytes(policyForm.trafficLimitGb),
      maxSourceIps: positiveIntegerOrNull(policyForm.maxSourceIps),
    })
    closePolicyModal()
    await loadUsers(false, { force: true })
    notify('用户限制策略已更新')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function showConnections(user) {
  loading.action = true
  try {
    const connection = await api.connections(selectedNodeId.value, user.userId)
    connectionData.value = withDirectEndpoint(connection, user.access)
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
    await loadUsers(false, { force: true })
    notify('住宅代理绑定成功')
  } catch (error) {
    notify(errorMessage(error), 'error')
    proxyForm.password = ''
  } finally {
    loading.action = false
  }
}

async function deleteUser(user) {
  if (!confirm(`确认删除节点用户 ${user.userId}？\n\n将从当前节点删除该用户、重载远端 sing-box，并释放关联的自动生成记录占用。`)) return
  deletingUserId.value = user.userId
  try {
    const result = await api.deleteUser(selectedNodeId.value, user.userId)
    if (!result?.success) throw new Error(result?.message || '节点用户删除失败')
    selectedUserIds.value = selectedUserIds.value.filter((userId) => userId !== user.userId)
    selectedUserRows.value = selectedUserRows.value
      .filter((item) => item.user.userId !== user.userId)
    if (users.value.length === 1 && userPage.page > 1) userPage.page -= 1
    await Promise.all([loadUsers(false, { force: true }), loadNodes(), loadAllocations()])
    notify('节点用户已删除，关联分配占用已释放')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    deletingUserId.value = ''
  }
}

function clearUserSelection() {
  selectedUserIds.value = []
  selectedUserRows.value = []
  selectedUserNodeId.value = ''
}

function toggleUserSelection(userId, checked) {
  if (selectedUserNodeId.value !== selectedNodeId.value) {
    selectedUserIds.value = []
    selectedUserRows.value = []
    selectedUserNodeId.value = selectedNodeId.value
  }
  const next = new Set(selectedUserIds.value)
  if (checked) {
    next.add(userId)
    const userIndex = users.value.findIndex((user) => user.userId === userId)
    const user = users.value[userIndex]
    if (user && !selectedUserRows.value.some((item) => item.user.userId === userId)) {
      selectedUserRows.value = [...selectedUserRows.value, {
        nodeId: selectedNodeId.value,
        user: { ...user },
        sequence: (userPage.page - 1) * userPage.pageSize + userIndex + 1,
      }]
    }
  } else {
    next.delete(userId)
    selectedUserRows.value = selectedUserRows.value
      .filter((item) => item.user.userId !== userId)
  }
  selectedUserIds.value = [...next]
  if (!selectedUserIds.value.length) selectedUserNodeId.value = ''
}

function toggleCurrentPageUsers(checked) {
  if (selectedUserNodeId.value !== selectedNodeId.value) {
    selectedUserIds.value = []
    selectedUserRows.value = []
    selectedUserNodeId.value = selectedNodeId.value
  }
  const next = new Set(selectedUserIds.value)
  users.value.forEach((user, userIndex) => {
    if (checked) {
      next.add(user.userId)
      if (!selectedUserRows.value.some((item) => item.user.userId === user.userId)) {
        selectedUserRows.value = [...selectedUserRows.value, {
          nodeId: selectedNodeId.value,
          user: { ...user },
          sequence: (userPage.page - 1) * userPage.pageSize + userIndex + 1,
        }]
      }
    } else {
      next.delete(user.userId)
    }
  })
  if (!checked) {
    const currentIds = new Set(currentPageUserIds.value)
    selectedUserRows.value = selectedUserRows.value
      .filter((item) => !currentIds.has(item.user.userId))
  }
  selectedUserIds.value = [...next]
  if (!selectedUserIds.value.length) selectedUserNodeId.value = ''
}

function openUserExport() {
  if (!selectedNode.value || (!canViewSensitive.value && !canProvision.value)) return
  excelMode.value = canViewSensitive.value ? 'export' : 'import'
  exportForm.nodeIds = selectedNode.value ? [selectedNode.value.id] : []
  importForm.nodeIds = allocatableNodes.value.some((node) => node.id === selectedNodeId.value)
    ? [selectedNodeId.value]
    : allocatableNodes.value.slice(0, 1).map((node) => node.id)
  Object.assign(exportForm, { rangeStart: 1, rangeEnd: '' })
  Object.assign(importForm, { file: null, fileName: '', rangeStart: 1, rangeEnd: '' })
  importResult.value = null
  Object.assign(exportProgress, { current: 0, total: 0, stage: '准备导出数据' })
  Object.assign(importProgress, { current: 0, total: 0, succeeded: 0, failed: 0 })
  modal.exportUsers = true
}

function closeUserExport() {
  if (excelTransferBusy.value) return
  modal.exportUsers = false
  importForm.file = null
  importForm.fileName = ''
  importResult.value = null
  if (importFileInput.value) importFileInput.value.value = ''
  Object.assign(exportProgress, { current: 0, total: 0, stage: '准备导出数据' })
  Object.assign(importProgress, { current: 0, total: 0, succeeded: 0, failed: 0 })
}

function changeExcelMode(mode) {
  if (excelTransferBusy.value) return
  excelMode.value = mode
  importResult.value = null
}

function setTransferNodes(action) {
  if (excelTransferBusy.value) return
  const target = excelMode.value === 'import' ? importForm : exportForm
  if (action === 'all') target.nodeIds = transferNodes.value.map((node) => node.id)
  if (action === 'clear') target.nodeIds = []
  if (action === 'current') {
    target.nodeIds = transferNodes.value.some((node) => node.id === selectedNodeId.value)
      ? [selectedNodeId.value]
      : []
  }
}

function handleImportFile(event) {
  const file = event.target.files?.[0] || null
  importForm.file = file
  importForm.fileName = file?.name || ''
  importResult.value = null
}

async function withExportRequestTimeout(requestFactory, timeoutMs, timeoutMessage) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await requestFactory(controller.signal)
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error(timeoutMessage)
    throw error
  } finally {
    clearTimeout(timer)
  }
}

async function loadUsersForExport(nodeId) {
  return await withExportRequestTimeout((signal) => api.usersForExport(nodeId, {
    keyword: userPage.keyword.trim(),
    ip: userPage.ip.trim(),
    sort: userPage.sort,
  }, { signal }), 60000, '读取节点用户超时，请稍后重试或勾选需要导出的用户')
}

async function mapWithConcurrency(items, concurrency, mapper) {
  const results = new Array(items.length)
  let cursor = 0
  async function worker() {
    while (cursor < items.length) {
      const index = cursor
      cursor += 1
      results[index] = await mapper(items[index], index)
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker))
  return results
}

function safeExportFilename(value) {
  return String(value || '节点').replace(/[\\/:*?"<>|]/g, '-').trim() || '节点'
}

function exportTimestamp(date = new Date()) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

async function exportNodeUsers() {
  if (!canViewSensitive.value || exportingUsers.value) return
  const exportingSelectedUsers = selectedUserCount.value > 0
  const requestedNodeIds = exportingSelectedUsers ? [selectedNodeId.value] : exportForm.nodeIds
  const selectedNodes = requestedNodeIds
    .map((nodeId) => nodes.value.find((node) => node.id === nodeId))
    .filter(Boolean)
  if (!selectedNodes.length) {
    notify('请至少选择一个导出节点', 'error')
    return
  }
  exportingUsers.value = true
  Object.assign(exportProgress, { current: 0, total: 0, stage: '正在读取节点用户' })
  try {
    const scenario = selectedExportScenario.value
    const modulePromise = Promise.all([import('exceljs'), import('qrcode')])
    modulePromise.catch(() => {})
    let combinedUsers
    let exportUsers
    if (exportingSelectedUsers) {
      const selectedNode = selectedNodes[0]
      exportUsers = selectedRowsForExport(selectedUserRows.value, selectedNode)
      combinedUsers = exportUsers
      Object.assign(exportProgress, {
        current: exportUsers.length,
        total: exportUsers.length,
        stage: '已读取勾选用户',
      })
    } else {
      Object.assign(exportProgress, { current: 0, total: selectedNodes.length, stage: '正在读取节点用户' })
      const usersByNode = await mapWithConcurrency(selectedNodes, 3, async (node) => {
        try {
          return { node, users: await loadUsersForExport(node.id) }
        } finally {
          exportProgress.current += 1
        }
      })
      combinedUsers = usersByNode.flatMap(({ node, users: nodeUsers }) =>
        nodeUsers.map((user) => ({ node, user })))
      const sequencedUsers = combinedUsers.map((item, index) => ({ ...item, sequence: index + 1 }))
      exportUsers = selectSequenceRange(sequencedUsers, exportForm.rangeStart, exportForm.rangeEnd)
    }
    if (combinedUsers.length === 0) {
      notify('当前搜索条件下没有可导出的节点用户', 'error')
      return
    }
    if (exportUsers.length === 0) {
      notify(exportingSelectedUsers
        ? '勾选的用户已不在当前搜索结果中，请重新选择'
        : `序号范围内没有用户，当前搜索结果共 ${combinedUsers.length} 条`, 'error')
      return
    }

    let unavailableCount = 0
    let failedCount = 0
    const directExportScenarios = new Set(['ipDirect', 'ipDirectSocks'])
    const directData = new Map()
    const connectionUsers = exportUsers.filter((item) => {
      if (!directExportScenarios.has(scenario.key)) return true
      const data = buildConnectionExportData(
        withDirectEndpoint({}, item.user.access),
        scenario.key,
      )
      directData.set(connectionLookupKey(item.node.id, item.user.userId), data)
      return !data.link
    })
    const connectionLookup = new Map()
    const batchJobs = buildConnectionBatchJobs(connectionUsers, 100)
    Object.assign(exportProgress, {
      current: 0,
      total: connectionUsers.length,
      stage: connectionUsers.length ? '正在批量读取连接信息' : '连接信息读取完成',
    })
    await mapWithConcurrency(batchJobs, 4, async (job) => {
      try {
        const results = await withExportRequestTimeout(
          (signal) => api.connectionsBatch(job.nodeId, job.userIds, { signal }),
          30000,
          '批量读取连接信息超时',
        )
        ;(results || []).forEach((result) => {
          connectionLookup.set(connectionLookupKey(job.nodeId, result.userId), result)
        })
      } catch (error) {
        job.userIds.forEach((userId) => connectionLookup.set(
          connectionLookupKey(job.nodeId, userId),
          { userId, connection: null, error: errorMessage(error) },
        ))
      } finally {
        exportProgress.current += job.userIds.length
      }
    })

    const rows = exportUsers.map((item) => {
      let connectionData = { ip: '', port: '', username: '', password: '', link: '' }
      const key = connectionLookupKey(item.node.id, item.user.userId)
      const localDirectData = directData.get(key)
      if (localDirectData?.link) {
        connectionData = localDirectData
      } else {
        const result = connectionLookup.get(key)
        if (result?.connection) {
          const connection = result.connection
          connectionData = buildConnectionExportData(
            withDirectEndpoint(connection, item.user.access),
            scenario.key,
          )
          if (!connectionData.link) unavailableCount += 1
        } else {
          failedCount += 1
        }
      }
      return {
        sequence: item.sequence,
        nodeName: item.node.name,
        ...connectionData,
        createdAt: item.user.createdAt,
      }
    })

    const [excelModule, qrModule] = await modulePromise
    const ExcelJS = excelModule.default || excelModule
    const QRCode = qrModule.default || qrModule
    let qrFailedCount = 0
    Object.assign(exportProgress, {
      current: 0,
      total: rows.length,
      stage: '正在生成二维码',
    })
    const qrConcurrency = Math.min(12, Math.max(6, Number(globalThis.navigator?.hardwareConcurrency || 8)))
    const qrImages = await mapWithConcurrency(rows, qrConcurrency, async (rowData) => {
      try {
        if (!rowData.link) return ''
        return await QRCode.toDataURL(rowData.link, {
          width: 128,
          margin: 1,
          errorCorrectionLevel: 'M',
          color: { dark: '#101827', light: '#ffffff' },
        })
      } catch {
        qrFailedCount += 1
        return ''
      } finally {
        exportProgress.current += 1
      }
    })
    const workbook = new ExcelJS.Workbook()
    workbook.creator = '牛速控制中心'
    workbook.created = new Date()
    const worksheet = workbook.addWorksheet('节点用户', {
      views: [{ state: 'frozen', ySplit: 1 }],
      properties: { defaultRowHeight: 20 },
    })
    worksheet.columns = [
      { header: '序号', key: 'sequence', width: 9 },
      ...(selectedNodes.length > 1 ? [{ header: '节点', key: 'nodeName', width: 24 }] : []),
      { header: 'IP', key: 'ip', width: 24 },
      { header: '连接端口', key: 'port', width: 13 },
      { header: '连接账号', key: 'username', width: 29 },
      { header: '连接密码', key: 'password', width: 24 },
      { header: '代理链接', key: 'link', width: 80 },
      { header: '二维码标签', key: 'qr', width: 18 },
      { header: '创建时间', key: 'createdAt', width: 24 },
    ]
    const header = worksheet.getRow(1)
    header.height = 25
    header.font = { bold: true, color: { argb: 'FFFFFFFF' } }
    header.alignment = { vertical: 'middle', horizontal: 'center' }
    header.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF256D85' } }
    header.eachCell((cell) => {
      cell.border = { bottom: { style: 'thin', color: { argb: 'FF174D60' } } }
    })

    rows.forEach((rowData, rowIndex) => {
      const excelRow = worksheet.addRow({
        sequence: rowData.sequence,
        nodeName: rowData.nodeName,
        ip: rowData.ip,
        port: rowData.port,
        username: rowData.username,
        password: rowData.password,
        link: rowData.link,
        qr: rowData.link ? scenario.label : '',
        createdAt: formatDate(rowData.createdAt),
      })
      excelRow.height = rowData.link ? 76 : 25
      excelRow.alignment = { vertical: 'middle', wrapText: true }
      excelRow.getCell('sequence').alignment = { vertical: 'middle', horizontal: 'center' }
      excelRow.getCell('port').alignment = { vertical: 'middle', horizontal: 'center' }
      excelRow.getCell('qr').alignment = { vertical: 'bottom', horizontal: 'center' }
      excelRow.eachCell((cell) => {
        cell.border = {
          bottom: { style: 'hair', color: { argb: 'FFDCE5EA' } },
          right: { style: 'hair', color: { argb: 'FFE7EDF0' } },
        }
      })
      const qrDataUrl = qrImages[rowIndex]
      if (qrDataUrl) {
        const imageId = workbook.addImage({ base64: qrDataUrl, extension: 'png' })
        const qrColumnIndex = worksheet.getColumn('qr').number - 1
        worksheet.addImage(imageId, {
          tl: { col: qrColumnIndex + 0.25, row: excelRow.number - 0.92 },
          ext: { width: 88, height: 88 },
        })
      }
    })
    worksheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: worksheet.columnCount } }

    Object.assign(exportProgress, { current: 0, total: 1, stage: '正在打包 Excel 文件' })
    const buffer = await workbook.xlsx.writeBuffer()
    exportProgress.current = 1
    const blob = new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${exportTimestamp()}-${safeExportFilename(scenario.label)}.xlsx`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 60000)
    modal.exportUsers = false
    const skipped = unavailableCount + failedCount + qrFailedCount
    notify(skipped
      ? `Excel 已导出，共 ${rows.length} 条，${skipped} 条二维码或连接信息为空`
      : `Excel 已导出，共 ${rows.length} 条节点用户`)
  } catch (error) {
    notify(`导出失败：${errorMessage(error)}`, 'error')
  } finally {
    exportingUsers.value = false
    Object.assign(exportProgress, { current: 0, total: 0, stage: '准备导出数据' })
  }
}

async function importNodeUsers() {
  if (!canProvision.value || importingUsers.value) return
  const selectedNodes = importForm.nodeIds
    .map((nodeId) => allocatableNodes.value.find((node) => node.id === nodeId))
    .filter(Boolean)
  if (!selectedNodes.length) {
    notify('请至少选择一个可用的导入节点', 'error')
    return
  }
  if (!importForm.file) {
    notify('请选择要导入的 Excel 文件', 'error')
    return
  }

  importingUsers.value = true
  importResult.value = null
  Object.assign(importProgress, { current: 0, total: 0, succeeded: 0, failed: 0 })
  try {
    const excelModule = await import('exceljs')
    const ExcelJS = excelModule.default || excelModule
    const workbook = new ExcelJS.Workbook()
    await workbook.xlsx.load(await importForm.file.arrayBuffer())
    const tables = workbook.worksheets.map((worksheet) => ({
      name: worksheet.name,
      rows: worksheet.getSheetValues().slice(1).map((row) => Array.isArray(row) ? row.slice(1) : []),
    }))
    const parsedRows = parseExcelTransferTables(tables)
    const selectedRows = filterImportedRowsBySequence(
      parsedRows,
      importForm.rangeStart,
      importForm.rangeEnd,
    )
    if (!selectedRows.length) {
      throw new Error(`序号范围内没有可导入数据，Excel 中共有 ${parsedRows.length} 条有效数据`)
    }

    const groups = distributeRowsRoundRobin(selectedRows, selectedNodes.map((node) => node.id))
    const jobs = groups.flatMap((group) => chunkRows(group.rows, 50).map((rows) => ({
      nodeId: group.nodeId,
      nodeName: selectedNodes.find((node) => node.id === group.nodeId)?.name || group.nodeId,
      rows,
    })))
    importProgress.total = selectedRows.length
    const errors = []

    for (const job of jobs) {
      const input = proxyInputFromRows(job.rows)
      try {
        const response = await api.provisionProxyBatch({
          input,
          protocols: ['vless', 'vmess', 'socks'],
          preferredNodeId: job.nodeId,
        }, createIdempotencyKey('excel-import'))
        const succeeded = Number(response.succeeded || 0)
        const failed = Number(response.failed || 0)
        importProgress.succeeded += succeeded
        importProgress.failed += failed
        ;(response.results || []).filter((result) => result.error).slice(0, 3).forEach((result) => {
          errors.push(`${job.nodeName} · 序号 ${job.rows[result.rowNumber - 1]?.sequence || '?'}：${localizedErrorMessage(result.error)}`)
        })
      } catch (error) {
        importProgress.failed += job.rows.length
        let message = errorMessage(error)
        job.rows.forEach((row) => { message = message.split(row.password).join('***') })
        errors.push(`${job.nodeName}：${message}`)
      } finally {
        importProgress.current += job.rows.length
      }
    }

    importResult.value = {
      total: selectedRows.length,
      succeeded: importProgress.succeeded,
      failed: importProgress.failed,
      errors: errors.slice(0, 6),
    }
    await Promise.all([loadUsers(true, { force: true }), loadNodes(), loadAllocations()])
    notify(importProgress.failed
      ? `导入完成：成功 ${importProgress.succeeded} 条，失败 ${importProgress.failed} 条`
      : `导入完成：成功生成 ${importProgress.succeeded} 条节点用户`, importProgress.failed ? 'error' : 'success')
  } catch (error) {
    notify(`导入失败：${errorMessage(error)}`, 'error')
  } finally {
    importingUsers.value = false
  }
}

async function copy(value) {
  if (!value) return
  await navigator.clipboard.writeText(value)
  notify('已复制到剪贴板')
}

async function nextPage(offset) {
  const target = userPage.page + offset
  if (target < 1 || target > totalPages.value || loading.users) return
  await loadUsers(false, { page: target })
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

function gigabytesToBytes(value) {
  const gigabytes = Number(value)
  return Number.isFinite(gigabytes) && gigabytes > 0 ? Math.round(gigabytes * (1024 ** 3)) : null
}

function positiveIntegerOrNull(value) {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : null
}

function userPolicyStatus(status) {
  return ({ active: '正常', traffic_limited: '流量已限', device_limited: '设备已限' })[status] || '正常'
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

function accessRegion(access) {
  if (!access) return '-'
  if (access.countryCode === 'ZZ' || access.countryName === '未知') return '待识别'
  return [access.countryName, access.cityName].filter(Boolean).join(' · ') || access.countryCode || '-'
}

function accessCredential(access) {
  if (!access?.username && !access?.password) return '-'
  if (!revealListCredentials.value) return '••••••••'
  return [access.username || '-', access.password || '-'].join(' / ')
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
        @mouseenter="prefetchNodeUsers(node.id)"
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
        <div v-if="canViewNodeToken" class="node-token-row">
          <span>API Token</span>
          <code>{{ nodeTokenNodeId === selectedNode.id && revealNodeToken ? nodeToken : '••••••••••••••••' }}</code>
          <button
            v-if="nodeTokenNodeId !== selectedNode.id || !nodeToken"
            class="icon-action"
            :disabled="loadingNodeToken"
            title="查看节点 Token"
            @click="loadNodeToken"
          ><RefreshCw v-if="loadingNodeToken" class="spin" :size="14" /><Eye v-else :size="14" />{{ loadingNodeToken ? '读取中' : '查看 Token' }}</button>
          <template v-else>
            <button class="icon-button token-icon-button" :title="revealNodeToken ? '隐藏节点 Token' : '显示节点 Token'" @click="revealNodeToken = !revealNodeToken"><EyeOff v-if="revealNodeToken" :size="14" /><Eye v-else :size="14" /></button>
            <button class="icon-button token-icon-button" title="复制节点 Token" @click="copy(nodeToken)"><Copy :size="14" /></button>
          </template>
        </div>
        <p v-if="selectedNode.lastError" class="node-error">{{ localizedErrorMessage(selectedNode.lastError) }}</p>
      </section>

      <section v-if="activeView === 'node-management'" class="panel proxy-batch-panel">
        <div class="panel-heading proxy-batch-heading">
          <div><p class="eyebrow">批量 SOCKS 节点生成</p><h2>节点信息输入</h2></div>
          <div class="batch-actions">
            <button v-if="canProvision" class="button ghost icon-text" :disabled="batchConnectionCount === 0" @click="copyAllBatchLinks"><Copy :size="14" />复制所有链接</button>
            <button v-if="canProvision" class="button primary icon-text" :disabled="loading.action" @click="provisionProxyBatch"><Link :size="15" />生成节点连接</button>
          </div>
        </div>
        <div class="proxy-batch-body">
          <div class="format-guide">
            <code>四列简写：SOCKS 地址 端口 用户名 密码</code>
            <strong>支持四种格式</strong>
            <code>住宅出口IP SOCKS接入地址 端口 用户名 密码</code>
            <code>序号 住宅出口IP SOCKS接入地址 端口 用户名 密码</code>
            <code>socks(s5)://账号:密码@接入地址:端口#[出口IP或备注]</code>
            <span>SOCKS 接入地址支持 IP 或域名；没有独立接入地址时填写 <code>-</code>，系统将使用住宅出口 IP。使用空格或 Tab 分隔，粘贴时自动清理 WPS/Excel 表格中的字节序标记、不换行空格和全角空格。socks:// 链接会自动从 <code>#备注</code> 中提取第一个 IPv4 作为出口 IP。</span>
          </div>
          <label class="proxy-input-label">批量 SOCKS 节点
            <textarea
              v-model="proxyBatchForm.input"
              rows="8"
              maxlength="50000"
              spellcheck="false"
              autocomplete="off"
              placeholder="203.0.113.10 203.0.113.20 5001 示例用户名 示例密码&#10;2 198.51.100.11 proxy.example.com 1080 示例用户名 示例密码"
              @paste="handleProxyPaste"
              @blur="cleanProxyBatchTextarea"
            ></textarea>
          </label>
          <div class="batch-options">
            <label>指定节点管理器（可选）
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
                  <div><span>上游 SOCKS 接入</span><strong>{{ result.sourceAddress || '-' }}:{{ result.sourcePort || '-' }}</strong></div>
                  <div><span>节点用户</span><strong>{{ result.allocation?.userId || '-' }}</strong></div>
                <div><span>节点入口 (VLESS/VMess)</span><strong>{{ result.allocation?.nodeHost || '-' }}</strong></div>
                  <div><span>分配节点</span><strong>{{ result.allocation?.nodeName || '-' }}</strong></div>
                </div>
                <ConnectionLinksPanel
                  :connection="withDirectEndpoint(result.allocation?.connection, { ip: result.sourceIp, port: result.sourcePort })"
                  :reveal-secrets="revealBatchSecrets"
                  @copy="copy"
                />
              </template>
            </article>
          </div>
        </div>
      </section>

      <section v-if="activeView === 'allocations'" class="panel allocation-panel">
        <div class="panel-heading">
          <div><p class="eyebrow">直出节点生成</p><h2>自动生成记录</h2></div>
          <div class="table-tools">
            <input v-model="allocationPage.ip" placeholder="按 IP 搜索" @keyup.enter="loadAllocations(true)" />
            <button class="button ghost icon-text" @click="loadAllocations(true)"><Search :size="14" />搜索</button>
            <button v-if="canViewSensitive" class="button ghost icon-text" :title="revealListCredentials ? '隐藏认证信息' : '显示认证信息'" @click="revealListCredentials = !revealListCredentials"><EyeOff v-if="revealListCredentials" :size="14" /><Eye v-else :size="14" />认证</button>
            <button v-if="canProvision" class="button primary icon-text" :disabled="allocatableNodes.length === 0" @click="openProvision"><Plus :size="15" />生成直出节点</button>
          </div>
        </div>
        <div class="table-wrap">
          <table class="allocation-table">
            <thead><tr><th class="sequence-column">序号</th><th>用户</th><th>状态</th><th>节点管理器</th><th>IP</th><th>端口</th><th>认证信息</th><th>地区</th><th>创建时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-if="loading.allocations"><td colspan="10" class="empty-state">正在加载自动开通记录...</td></tr>
              <tr v-else-if="allocations.length === 0"><td colspan="10" class="empty-state">暂无匹配的自动开通记录</td></tr>
              <tr v-for="(allocation, allocationIndex) in allocations" :key="allocation.id">
                <td class="sequence-cell">{{ (allocationPage.page - 1) * allocationPage.pageSize + allocationIndex + 1 }}</td>
                <td><div class="user-cell"><span class="avatar">{{ allocation.userId.slice(0, 2).toUpperCase() }}</span><span><strong>{{ allocation.userId }}</strong><small>{{ allocation.protocols.join(' / ') }}</small></span></div></td>
                <td><span class="allocation-state" :class="allocation.state.toLowerCase()">{{ allocationStateText(allocation.state) }}</span><small v-if="allocation.lastError" class="error-detail" :title="localizedErrorMessage(allocation.lastError)">{{ localizedErrorMessage(allocation.lastError) }}</small></td>
                <td><strong>{{ allocation.nodeName || '-' }}</strong><small class="table-subtext">{{ allocation.nodeHost || '等待节点' }}</small></td>
                <td><strong>{{ allocation.access?.ip || allocation.sourceIp || allocation.nodeHost || '-' }}</strong><small class="table-subtext">{{ allocation.provisioningMode === 'UPSTREAM_SOCKS' ? '住宅出口' : '节点入口' }}</small></td>
                <td>{{ allocation.access?.port || allocation.sourcePort || '-' }}</td>
                <td><span class="credential-text">{{ canViewSensitive ? accessCredential(allocation.access) : '-' }}</span></td>
                <td><strong>{{ accessRegion(allocation.access) }}</strong><small v-if="allocation.access?.countryCode" class="table-subtext">{{ allocation.access.countryCode }}</small></td>
                <td>{{ formatDate(allocation.createdAt) }}</td>
                <td><div class="row-actions"><button v-if="allocation.state === 'ACTIVE' && canViewSensitive" class="icon-action" title="查看连接" @click="showAllocationConnection(allocation)"><Link :size="14" />连接</button><template v-if="['RETRYABLE','FAILED','PENDING'].includes(allocation.state) && canProvision"><button class="icon-action" :disabled="loading.action" title="重新开通" @click="retryAllocation(allocation)"><RefreshCw :size="14" />重试</button><button class="icon-action danger-text" :disabled="loading.action" title="删除记录" @click="deleteAllocation(allocation)"><Trash2 :size="14" />删除</button></template></div></td>
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
            <input v-model="userPage.ip" placeholder="按 IP 搜索" @keyup.enter="loadUsers(true)" />
            <select v-model="userPage.sort" aria-label="创建时间排序" @change="loadUsers(true)">
              <option value="createdDesc">最新创建</option>
              <option value="createdAsc">最早创建</option>
            </select>
            <button class="button ghost icon-text" @click="loadUsers(true)"><Search :size="14" />搜索</button>
            <button v-if="canViewSensitive" class="button ghost icon-text" :title="revealListCredentials ? '隐藏认证信息' : '显示认证信息'" @click="revealListCredentials = !revealListCredentials"><EyeOff v-if="revealListCredentials" :size="14" /><Eye v-else :size="14" />认证</button>
            <button v-if="selectedUserCount" class="selection-count" type="button" title="清空已选用户" @click="clearUserSelection">已选 {{ selectedUserCount }} 条 <X :size="12" /></button>
            <button v-if="canViewSensitive || canProvision" class="button ghost icon-text" :disabled="loading.users || !selectedNode" @click="openUserExport"><FileSpreadsheet :size="14" />{{ selectedUserCount ? `导出所选 ${selectedUserCount} 条` : 'Excel 导入/导出' }}</button>
            <button class="button ghost icon-text" :disabled="loading.users || !selectedNode" @click="loadUsers(false, { force: true })"><RefreshCw :size="14" />刷新</button>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead><tr><th class="selection-column"><input type="checkbox" title="选择当前页" aria-label="选择当前页节点用户" :checked="allCurrentPageUsersSelected" :indeterminate="someCurrentPageUsersSelected && !allCurrentPageUsersSelected" :disabled="loading.users || users.length === 0" @change="toggleCurrentPageUsers($event.target.checked)" /></th><th class="sequence-column">序号</th><th>用户</th><th>协议</th><th>IP</th><th>端口</th><th>认证信息</th><th>地区</th><th>出口模式</th><th>流量</th><th>在线设备</th><th>状态</th><th>创建时间</th><th class="sticky-actions">操作</th></tr></thead>
            <tbody>
              <tr v-if="loading.users && users.length === 0"><td colspan="14" class="empty-state">正在加载节点用户…</td></tr>
              <tr v-else-if="userLoadError && users.length === 0"><td colspan="14" class="empty-state error-detail">节点用户加载失败：{{ userLoadError }}</td></tr>
              <tr v-else-if="!selectedNode"><td colspan="14" class="empty-state">请先添加并选择一个节点</td></tr>
              <tr v-else-if="users.length === 0"><td colspan="14" class="empty-state">当前节点暂无匹配用户</td></tr>
              <tr v-for="(user, userIndex) in users" :key="user.userId" :class="{ 'selected-user-row': selectedUserIds.includes(user.userId) }">
                <td class="selection-cell"><input type="checkbox" :aria-label="`选择节点用户 ${user.userId}`" :checked="selectedUserIds.includes(user.userId)" @change="toggleUserSelection(user.userId, $event.target.checked)" /></td>
                <td class="sequence-cell">{{ (userPage.page - 1) * userPage.pageSize + userIndex + 1 }}</td>
                <td><div class="user-cell"><span class="avatar">{{ user.userId.slice(0, 2).toUpperCase() }}</span><span><strong>{{ user.userId }}</strong><small>{{ user.socksUsername || '自动凭据' }}</small></span></div></td>
                <td><span v-for="protocol in user.protocols" :key="protocol" class="protocol-tag">{{ protocol }}</span></td>
                <td><strong>{{ user.access?.ip || selectedNode?.host || '-' }}</strong></td>
                <td>{{ user.access?.port || '-' }}</td>
                <td><span class="credential-text">{{ canViewSensitive ? accessCredential(user.access) : '-' }}</span></td>
                <td><strong>{{ accessRegion(user.access) }}</strong><small v-if="user.access?.countryCode" class="table-subtext">{{ user.access.countryCode }}</small></td>
                <td><span :class="user.proxyBound ? 'positive' : 'muted'">{{ user.proxyBound ? user.proxyServer : '直连出口' }}</span></td>
                <td><strong>{{ formatBytes(user.total) }}<template v-if="user.trafficLimitBytes"> / {{ formatBytes(user.trafficLimitBytes) }}</template></strong><small class="traffic-split">↑ {{ formatBytes(user.upload) }} / ↓ {{ formatBytes(user.download) }}</small></td>
                <td><strong>{{ user.activeSourceIps?.length || 0 }}<template v-if="user.maxSourceIps"> / {{ user.maxSourceIps }}</template></strong><small class="traffic-split">按同时来源 IP 统计</small></td>
                <td><span class="policy-status" :class="user.status">{{ userPolicyStatus(user.status) }}</span></td>
                <td>{{ formatDate(user.createdAt) }}</td>
                <td class="sticky-actions"><div class="row-actions"><button v-if="canViewSensitive" @click="showConnections(user)">连接</button><button v-if="canManageUsers" @click="openProxy(user)">代理</button><button v-if="canManageUsers" class="icon-action" title="编辑流量和设备限制" @click="openPolicy(user)"><Settings2 :size="14" />限制</button><button v-if="canDeleteUsers" class="icon-action danger-text delete-user-action" :disabled="Boolean(deletingUserId)" :title="deletingUserId === user.userId ? '正在删除节点用户' : '删除节点用户'" @click="deleteUser(user)"><RefreshCw v-if="deletingUserId === user.userId" class="spin" :size="14" /><Trash2 v-else :size="14" />{{ deletingUserId === user.userId ? '删除中' : '删除' }}</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ userPage.total }} 个用户 <small v-if="loading.users" class="page-loading"><RefreshCw class="spin" :size="12" />读取中</small><small v-else-if="userLoadError" class="page-load-error">{{ userLoadError }}</small></span>
          <div><button :disabled="userPage.page <= 1 || loading.users" @click="nextPage(-1)">上一页</button><strong>{{ userPage.page }} / {{ totalPages }}</strong><button :disabled="userPage.page >= totalPages || loading.users" @click="nextPage(1)">下一页</button></div>
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
        <p class="form-note">控制中心会调用 <code>/api/agent/info</code> 与心跳接口验证节点，并加密保存访问令牌；仅允许管理员和节点运维账号按需查看。</p>
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
          <label>流量额度（GB）<input v-model.number="provisionForm.trafficLimitGb" type="number" min="0" step="0.1" placeholder="留空不限" /></label>
          <label>最大同时来源 IP 数<input v-model.number="provisionForm.maxSourceIps" type="number" min="0" max="1000" step="1" placeholder="留空不限" /></label>
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
        <div class="form-grid"><label>用户 ID<input v-model.trim="userForm.userId" required pattern="[A-Za-z0-9._-]+" /></label><label>SOCKS 用户名（可选）<input v-model.trim="userForm.socksUsername" autocomplete="off" /></label><label>SOCKS 密码（可选）<input v-model="userForm.socksPassword" type="password" autocomplete="new-password" /></label><label>流量额度（GB）<input v-model.number="userForm.trafficLimitGb" type="number" min="0" step="0.1" placeholder="留空不限" /></label><label>最大同时来源 IP 数<input v-model.number="userForm.maxSourceIps" type="number" min="0" max="1000" step="1" placeholder="留空不限" /></label></div>
        <fieldset><legend>启用协议</legend><div class="checkbox-row"><label v-for="protocol in ['vless','vmess','socks']" :key="protocol"><input v-model="userForm.protocols" type="checkbox" :value="protocol" />{{ protocol.toUpperCase() }}</label></div></fieldset>
        <label class="toggle-row"><input v-model="userForm.useProxy" type="checkbox" /><span>创建时绑定住宅 SOCKS5 出口</span></label>
        <div v-if="userForm.useProxy" class="form-grid proxy-grid"><label>代理服务器<input v-model.trim="userForm.proxyServer" required /></label><label>端口<input v-model.number="userForm.proxyPort" type="number" min="1" max="65535" required /></label><label>用户名<input v-model.trim="userForm.proxyUsername" required autocomplete="off" /></label><label>密码<input v-model="userForm.proxyPassword" required type="password" autocomplete="new-password" /></label></div>
        <div class="modal-actions"><button type="button" class="button ghost" @click="closeUserModal">取消</button><button class="button primary" :disabled="loading.action || userForm.protocols.length === 0">创建用户</button></div>
      </form>
    </div>

    <div v-if="modal.policy" class="modal-backdrop" @mousedown.self="closePolicyModal">
      <form class="modal-card" @submit.prevent="savePolicy">
        <div class="modal-heading"><div><p class="eyebrow">用户限制策略</p><h2>{{ policyForm.userId }}</h2></div><button type="button" class="close-button" title="关闭" @click="closePolicyModal"><X :size="17" /></button></div>
        <div class="form-grid">
          <label>流量额度（GB）<input v-model.number="policyForm.trafficLimitGb" type="number" min="0" step="0.1" placeholder="留空或 0 表示不限" /></label>
          <label>最大同时来源 IP 数<input v-model.number="policyForm.maxSourceIps" type="number" min="0" max="1000" step="1" placeholder="留空或 0 表示不限" /></label>
        </div>
        <p class="form-note">设备数按代理连接的同时来源 IP 统计；超出额度或来源 IP 上限时，节点会主动关闭对应连接。</p>
        <div class="modal-actions"><button type="button" class="button ghost" @click="closePolicyModal">取消</button><button class="button primary" :disabled="loading.action">保存限制</button></div>
      </form>
    </div>

    <div v-if="modal.exportUsers" class="modal-backdrop" @mousedown.self="closeUserExport">
      <form class="modal-card export-users-card" @submit.prevent="excelMode === 'export' ? exportNodeUsers() : importNodeUsers()">
        <div class="modal-heading"><div><p class="eyebrow">节点用户数据</p><h2>Excel 导入 / 导出</h2></div><button type="button" class="close-button" title="关闭" :disabled="excelTransferBusy" @click="closeUserExport"><X :size="17" /></button></div>
        <div class="excel-mode-switch" role="tablist" aria-label="Excel 操作方式">
          <button v-if="canViewSensitive" type="button" :class="{ active: excelMode === 'export' }" :disabled="excelTransferBusy" @click="changeExcelMode('export')"><Download :size="15" />导出</button>
          <button v-if="canProvision" type="button" :class="{ active: excelMode === 'import' }" :disabled="excelTransferBusy" @click="changeExcelMode('import')"><Upload :size="15" />导入</button>
        </div>
        <div class="export-node-summary">
          <FileSpreadsheet :size="20" />
          <div><span>{{ excelMode === 'export' ? '导出规则' : '导入规则' }}</span><strong>{{ excelMode === 'export' && selectedUserCount ? `导出已勾选的 ${selectedUserCount} 条数据` : `${activeTransferNodeIds.length} 个节点已选择` }}</strong><small>{{ excelMode === 'export' ? (selectedUserCount ? '保留这些用户在当前搜索和排序结果中的原始序号' : '按当前搜索与时间排序合并后计算序号') : '按 Excel 序号筛选，多节点按勾选顺序轮流分配' }}</small></div>
        </div>

        <fieldset v-if="excelMode === 'import' || selectedUserCount === 0" class="transfer-node-picker">
          <legend>选择节点</legend>
          <div class="transfer-node-actions">
            <button type="button" :disabled="excelTransferBusy" @click="setTransferNodes('current')">当前节点</button>
            <button type="button" :disabled="excelTransferBusy" @click="setTransferNodes('all')">全选</button>
            <button type="button" :disabled="excelTransferBusy" @click="setTransferNodes('clear')">清空</button>
          </div>
          <div class="transfer-node-list">
            <label v-for="node in transferNodes" :key="node.id" :class="{ selected: activeTransferNodeIds.includes(node.id) }">
              <input v-if="excelMode === 'export'" v-model="exportForm.nodeIds" type="checkbox" :value="node.id" :disabled="excelTransferBusy" />
              <input v-else v-model="importForm.nodeIds" type="checkbox" :value="node.id" :disabled="excelTransferBusy" />
              <span><strong>{{ node.name }}</strong><small>{{ node.userCount }}/{{ node.maxUsers }} · {{ statusText(node.status) }}</small></span>
            </label>
            <p v-if="transferNodes.length === 0" class="transfer-node-empty">{{ excelMode === 'import' ? '当前没有可开通且有容量的节点' : '当前没有已登记节点' }}</p>
          </div>
        </fieldset>

        <div v-if="excelMode === 'import' || selectedUserCount === 0" class="sequence-range-fields">
          <label v-if="excelMode === 'export'">起始序号<input v-model="exportForm.rangeStart" type="number" min="1" step="1" required :disabled="excelTransferBusy" /></label>
          <label v-else>起始序号<input v-model="importForm.rangeStart" type="number" min="1" step="1" required :disabled="excelTransferBusy" /></label>
          <span>至</span>
          <label v-if="excelMode === 'export'">结束序号<input v-model="exportForm.rangeEnd" type="number" min="1" step="1" placeholder="留空到最后" :disabled="excelTransferBusy" /></label>
          <label v-else>结束序号<input v-model="importForm.rangeEnd" type="number" min="1" step="1" placeholder="留空到最后" :disabled="excelTransferBusy" /></label>
        </div>

        <label v-if="excelMode === 'export'">使用场景
          <select v-model="exportForm.scenario" :disabled="excelTransferBusy">
            <option v-for="scenario in connectionExportScenarios" :key="scenario.key" :value="scenario.key">{{ scenario.label }}</option>
          </select>
        </label>
        <label v-else class="excel-file-field">Excel 文件
          <input ref="importFileInput" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :disabled="excelTransferBusy" required @change="handleImportFile" />
          <small>{{ importForm.fileName || '请选择 .xlsx 文件' }}</small>
        </label>

        <p v-if="excelMode === 'export'" class="form-note">Excel 包含序号、IP、连接端口、连接账号、连接密码、代理链接、可扫描二维码和创建时间。多节点导出时增加“节点”列；没有所选场景连接的用户仍会保留，连接字段为空。</p>
        <p v-else class="form-note">导入读取“序号、IP、连接端口、连接账号、连接密码”。建议导入由“指纹浏览器 IP 直连”场景导出的文件；重复的连接账号仍会按原账号作为节点用户 ID，目标节点已有同名用户时该条会失败。</p>
        <div v-if="exportingUsers" class="export-progress">
          <div><span>{{ exportProgress.stage }}</span><strong>{{ exportProgress.current }} / {{ exportProgress.total || '...' }}</strong></div>
          <progress :value="exportProgress.current" :max="exportProgress.total || 1"></progress>
        </div>
        <div v-if="importingUsers" class="export-progress">
          <div><span>正在导入节点用户</span><strong>{{ importProgress.current }} / {{ importProgress.total || '...' }}</strong></div>
          <progress :value="importProgress.current" :max="importProgress.total || 1"></progress>
        </div>
        <div v-if="importResult" class="import-result" :class="{ failed: importResult.failed }">
          <strong>共 {{ importResult.total }} 条，成功 {{ importResult.succeeded }} 条，失败 {{ importResult.failed }} 条</strong>
          <small v-for="message in importResult.errors" :key="message">{{ message }}</small>
        </div>
        <div class="modal-actions">
          <button type="button" class="button ghost" :disabled="excelTransferBusy" @click="closeUserExport">关闭</button>
          <button v-if="excelMode === 'export'" class="button primary icon-text" :disabled="excelTransferBusy || exportForm.nodeIds.length === 0"><RefreshCw v-if="exportingUsers" class="spin" :size="15" /><Download v-else :size="15" />{{ exportingUsers ? '正在导出' : '生成 Excel' }}</button>
          <button v-else class="button primary icon-text" :disabled="excelTransferBusy || importForm.nodeIds.length === 0 || !importForm.file"><RefreshCw v-if="importingUsers" class="spin" :size="15" /><Upload v-else :size="15" />{{ importingUsers ? '正在导入' : '开始导入' }}</button>
        </div>
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
        <div v-if="connectionContext" class="connection-context"><span><Server :size="13" />{{ connectionContext.nodeName }} · {{ connectionContext.nodeHost }}</span><span v-if="connectionContext.sourceIp">住宅出口: {{ connectionContext.sourceIp }}</span><span><ShieldCheck :size="13" />{{ connectionContext.proxyBound ? '已绑定上游代理' : 'VPS 直出，未绑定上游代理' }}</span></div>
        <label class="reveal-toggle connection-reveal"><input v-model="revealConnectionSecrets" type="checkbox" /><component :is="revealConnectionSecrets ? EyeOff : Eye" :size="14" /><span>{{ revealConnectionSecrets ? '隐藏完整连接' : '显示完整连接' }}</span></label>
        <ConnectionLinksPanel
          v-if="connectionData"
          :connection="connectionData"
          :reveal-secrets="revealConnectionSecrets"
          @copy="copy"
        />
      </div>
    </div>

    <div v-if="modal.proxyDetails" class="modal-backdrop" @mousedown.self="closeProxyDetailsModal">
      <div class="modal-card">
        <div class="modal-heading"><div><p class="eyebrow">出口代理</p><h2>上游 SOCKS 详情</h2></div><button class="close-button" title="关闭" @click="closeProxyDetailsModal"><X :size="17" /></button></div>
        <div v-if="proxyCredentialData" class="detail-list">
          <div><span>住宅出口 IP</span><strong>{{ proxyCredentialData.sourceIp || proxyCredentialData.protocolInfo?.sourceIp || '-' }}</strong></div>
          <div><span>节点入口 IP</span><strong>{{ proxyCredentialData.nodeHost || '-' }}</strong></div>
          <div><span>上游 SOCKS 接入</span><strong>{{ proxyCredentialData.proxyServer || '-' }}</strong></div>
          <div><span>端口</span><strong>{{ proxyCredentialData.proxyPort || '-' }}</strong></div>
          <div><span>上游账号</span><strong>{{ proxyCredentialData.proxyUsername || proxyCredentialData.protocolInfo?.rawUsername || '-' }}</strong></div>
          <div><span>上游密码</span><strong>{{ revealProxyCredentials ? (proxyCredentialData.proxyPassword || proxyCredentialData.protocolInfo?.rawPassword || '-') : '••••••••' }}</strong></div>
          <div><span>节点用户 ID</span><strong>{{ proxyCredentialData.userId }}</strong></div>
        </div>
        <label class="reveal-toggle connection-reveal"><input v-model="revealProxyCredentials" type="checkbox" /><component :is="revealProxyCredentials ? EyeOff : Eye" :size="14" /><span>{{ revealProxyCredentials ? '隐藏账号密码' : '显示账号密码' }}</span></label>
        <div v-if="proxyOriginalSocksLink" class="proxy-original-link">
          <div class="proxy-original-title"><strong>SOCKS5 原始链接</strong><small>可直接在 v2rayN / Clash 中使用</small></div>
          <code class="proxy-original-code">{{ proxyOriginalSocksLink }}</code>
          <button class="button primary" @click="copy(proxyOriginalSocksLink)">复制链接</button>
        </div>
      </div>
    </div>

    <transition name="toast"><div v-if="toast.visible" class="toast" :class="toast.type">{{ toast.message }}</div></transition>
    <div v-if="loading.app" class="loading-screen"><div class="loader"></div><span>正在连接控制中心…</span></div>
  </div>
</template>
