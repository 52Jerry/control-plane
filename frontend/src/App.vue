<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ApiError, api, getControlToken, setControlToken } from './api'

const meta = ref({ version: '0.1.0', authRequired: false })
const dashboard = ref({ nodeCount: 0, onlineNodeCount: 0, degradedNodeCount: 0, userCount: 0, connections: 0, totalTraffic: 0 })
const nodes = ref([])
const users = ref([])
const userPage = reactive({ page: 1, pageSize: 20, total: 0, keyword: '' })
const selectedNodeId = ref(localStorage.getItem('selected-node-id') || '')
const loading = reactive({ app: true, nodes: false, users: false, action: false })
const modal = reactive({ login: false, node: false, user: false, connection: false, proxy: false })
const toast = reactive({ visible: false, type: 'success', message: '' })
const loginToken = ref(getControlToken())
const connectionData = ref(null)
const connectionUser = ref('')
let refreshTimer
let toastTimer

const nodeForm = reactive({ name: 'Vultr Node', baseUrl: 'http://198.13.46.231:8088', token: '' })
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
const pageTitle = computed(() => selectedNode.value ? selectedNode.value.name : '节点控制面')

function notify(message, type = 'success') {
  clearTimeout(toastTimer)
  Object.assign(toast, { visible: true, message, type })
  toastTimer = setTimeout(() => { toast.visible = false }, 3200)
}

function errorMessage(error) {
  if (error instanceof ApiError && error.fields) {
    return `${error.message}：${Object.values(error.fields).join('，')}`
  }
  return error.message || '操作失败'
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
    return
  }
  if (resetPage) userPage.page = 1
  loading.users = true
  try {
    const data = await api.users(selectedNodeId.value, {
      page: userPage.page,
      pageSize: userPage.pageSize,
      keyword: userPage.keyword.trim(),
    })
    users.value = data.items
    userPage.total = data.total
  } finally {
    loading.users = false
  }
}

async function loadAll() {
  await loadNodes()
  await loadUsers()
}

async function bootstrap() {
  loading.app = true
  try {
    meta.value = await api.meta()
    if (meta.value.authRequired && !getControlToken()) {
      modal.login = true
      return
    }
    await loadAll()
  } catch (error) {
    if (error.status === 401) modal.login = true
    else notify(errorMessage(error), 'error')
  } finally {
    loading.app = false
  }
}

async function login() {
  setControlToken(loginToken.value)
  try {
    await loadAll()
    modal.login = false
    notify('控制面连接成功')
  } catch (error) {
    if (error.status === 401) setControlToken('')
    notify(errorMessage(error), 'error')
  }
}

async function selectNode(nodeId) {
  selectedNodeId.value = nodeId
  localStorage.setItem('selected-node-id', nodeId)
  userPage.page = 1
  await loadUsers()
}

async function registerNode() {
  loading.action = true
  try {
    const node = await api.registerNode({ ...nodeForm })
    modal.node = false
    nodeForm.token = ''
    await loadNodes()
    await selectNode(node.id)
    notify('节点注册成功')
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
  if (!confirm(`仅从控制面移除节点 ${node.name}，不会卸载服务器上的 Node Manager。继续？`)) return
  try {
    await api.deleteNode(node.id)
    if (selectedNodeId.value === node.id) selectedNodeId.value = ''
    await loadAll()
    notify('节点已从控制面移除')
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
    modal.user = false
    connectionData.value = result
    connectionUser.value = result.userId
    modal.connection = true
    await Promise.all([loadUsers(true), loadNodes()])
    notify('用户创建成功，连接信息已生成')
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

async function showConnections(user) {
  loading.action = true
  try {
    connectionData.value = await api.connections(selectedNodeId.value, user.userId)
    connectionUser.value = user.userId
    modal.connection = true
  } catch (error) {
    notify(errorMessage(error), 'error')
  } finally {
    loading.action = false
  }
}

function openProxy(user) {
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
    modal.proxy = false
    await loadUsers()
    notify('住宅代理绑定成功')
  } catch (error) {
    notify(errorMessage(error), 'error')
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

onMounted(async () => {
  await bootstrap()
  refreshTimer = setInterval(() => {
    if (!modal.login) loadNodes().catch(() => {})
  }, 15000)
})

onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">NC</div>
        <div><strong>Node Control</strong><span>Spring Boot Control Plane</span></div>
      </div>

      <button class="nav-item active"><span>◫</span>总览</button>
      <div class="nav-heading">受管节点</div>
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
      <button class="add-node-link" @click="modal.node = true">＋ 添加节点</button>

      <div class="sidebar-footer">
        <span>Control Plane</span><strong>v{{ meta.version }}</strong>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div>
          <p class="eyebrow">CONTROL PLANE / OVERVIEW</p>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="top-actions">
          <button class="button ghost" :disabled="!selectedNode || loading.action" @click="refreshNode()">刷新状态</button>
          <button class="button primary" :disabled="!selectedNode" @click="openCreateUser">＋ 创建用户</button>
        </div>
      </header>

      <section class="metrics-grid">
        <article class="metric-card"><span>在线节点</span><strong>{{ dashboard.onlineNodeCount }}<small>/ {{ dashboard.nodeCount }}</small></strong><i class="metric-icon green">N</i></article>
        <article class="metric-card"><span>全局用户</span><strong>{{ dashboard.userCount }}</strong><i class="metric-icon blue">U</i></article>
        <article class="metric-card"><span>活跃连接</span><strong>{{ dashboard.connections }}</strong><i class="metric-icon violet">C</i></article>
        <article class="metric-card"><span>累计流量</span><strong>{{ formatBytes(dashboard.totalTraffic) }}</strong><i class="metric-icon orange">T</i></article>
      </section>

      <section v-if="selectedNode" class="node-hero panel">
        <div class="node-identity">
          <div class="server-glyph">▰</div>
          <div>
            <div class="title-line"><h2>{{ selectedNode.name }}</h2><span class="status-pill" :class="selectedNode.status">{{ statusText(selectedNode.status) }}</span></div>
            <p>{{ selectedNode.remoteNodeId || '等待 agent 信息' }} · {{ selectedNode.host || selectedNode.baseUrl }}</p>
          </div>
        </div>
        <div class="node-stats">
          <div><span>CPU</span><strong>{{ selectedNode.cpu.toFixed(1) }}%</strong></div>
          <div><span>内存</span><strong>{{ selectedNode.memory.toFixed(1) }}%</strong></div>
          <div><span>用户</span><strong>{{ selectedNode.userCount }}</strong></div>
          <div><span>版本</span><strong>{{ selectedNode.managerVersion || '-' }}</strong></div>
        </div>
        <div class="node-actions">
          <button class="icon-button" title="重载 sing-box" @click="reloadNode">↻</button>
          <button class="icon-button danger-text" title="移除节点" @click="removeNode(selectedNode)">×</button>
        </div>
        <p v-if="selectedNode.lastError" class="node-error">{{ selectedNode.lastError }}</p>
      </section>

      <section class="panel users-panel">
        <div class="panel-heading">
          <div><p class="eyebrow">USER ALLOCATION</p><h2>节点用户</h2></div>
          <div class="table-tools">
            <input v-model="userPage.keyword" placeholder="搜索用户 ID" @keyup.enter="loadUsers(true)" />
            <button class="button ghost" @click="loadUsers(true)">搜索</button>
          </div>
        </div>

        <div class="table-wrap">
          <table>
            <thead><tr><th>用户</th><th>协议</th><th>住宅出口</th><th>流量</th><th>创建时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-if="loading.users"><td colspan="6" class="empty-state">正在加载节点用户…</td></tr>
              <tr v-else-if="!selectedNode"><td colspan="6" class="empty-state">请先添加并选择一个节点</td></tr>
              <tr v-else-if="users.length === 0"><td colspan="6" class="empty-state">当前节点暂无用户</td></tr>
              <tr v-for="user in users" :key="user.userId">
                <td><div class="user-cell"><span class="avatar">{{ user.userId.slice(0, 2).toUpperCase() }}</span><span><strong>{{ user.userId }}</strong><small>{{ user.socksUsername || '自动凭据' }}</small></span></div></td>
                <td><span v-for="protocol in user.protocols" :key="protocol" class="protocol-tag">{{ protocol }}</span></td>
                <td><span :class="user.proxyBound ? 'positive' : 'muted'">{{ user.proxyBound ? user.proxyServer : '直连出口' }}</span></td>
                <td><strong>{{ formatBytes(user.total) }}</strong><small class="traffic-split">↑ {{ formatBytes(user.upload) }} / ↓ {{ formatBytes(user.download) }}</small></td>
                <td>{{ formatDate(user.createdAt) }}</td>
                <td><div class="row-actions"><button @click="showConnections(user)">连接</button><button @click="openProxy(user)">代理</button><button class="danger-text" @click="deleteUser(user)">删除</button></div></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination">
          <span>共 {{ userPage.total }} 个用户</span>
          <div><button :disabled="userPage.page <= 1" @click="nextPage(-1)">上一页</button><strong>{{ userPage.page }} / {{ totalPages }}</strong><button :disabled="userPage.page >= totalPages" @click="nextPage(1)">下一页</button></div>
        </div>
      </section>

      <section class="node-list-section">
        <div class="section-title"><div><p class="eyebrow">NODE REGISTRY</p><h2>全部节点</h2></div><button class="button ghost" @click="modal.node = true">添加节点</button></div>
        <div class="node-grid">
          <article v-for="node in nodes" :key="node.id" class="compact-node" :class="{ selected: node.id === selectedNodeId }" @click="selectNode(node.id)">
            <div><span class="status-dot" :class="node.status"></span><strong>{{ node.name }}</strong></div>
            <p>{{ node.baseUrl }}</p>
            <dl><div><dt>状态</dt><dd>{{ statusText(node.status) }}</dd></div><div><dt>用户</dt><dd>{{ node.userCount }}</dd></div><div><dt>流量</dt><dd>{{ formatBytes(node.totalTraffic) }}</dd></div></dl>
          </article>
          <button v-if="nodes.length === 0" class="empty-node" @click="modal.node = true">＋ 注册第一个 Node Manager 节点</button>
        </div>
      </section>
    </main>

    <div v-if="modal.login" class="modal-backdrop locked">
      <form class="modal-card login-card" @submit.prevent="login">
        <div class="brand-mark large">NC</div><p class="eyebrow">SECURE CONTROL PLANE</p><h2>进入节点控制面</h2><p>输入后端配置的控制面令牌。</p>
        <label>控制面令牌<input v-model="loginToken" type="password" autocomplete="current-password" autofocus /></label>
        <button class="button primary wide" type="submit">连接控制面</button>
      </form>
    </div>

    <div v-if="modal.node" class="modal-backdrop" @mousedown.self="modal.node = false">
      <form class="modal-card" @submit.prevent="registerNode">
        <div class="modal-heading"><div><p class="eyebrow">NODE REGISTRY</p><h2>注册 Node Manager</h2></div><button type="button" class="close-button" @click="modal.node = false">×</button></div>
        <label>节点名称<input v-model.trim="nodeForm.name" required maxlength="120" /></label>
        <label>API 地址<input v-model.trim="nodeForm.baseUrl" required placeholder="http://server:8088" /></label>
        <label>Bearer Token<input v-model.trim="nodeForm.token" required type="password" autocomplete="new-password" /></label>
        <p class="form-note">控制面会调用 <code>/api/agent/info</code> 与心跳接口验证节点，token 不会返回给前端。</p>
        <div class="modal-actions"><button type="button" class="button ghost" @click="modal.node = false">取消</button><button class="button primary" :disabled="loading.action">验证并注册</button></div>
      </form>
    </div>

    <div v-if="modal.user" class="modal-backdrop" @mousedown.self="modal.user = false">
      <form class="modal-card wide-card" @submit.prevent="createUser">
        <div class="modal-heading"><div><p class="eyebrow">USER ALLOCATION</p><h2>创建节点用户</h2></div><button type="button" class="close-button" @click="modal.user = false">×</button></div>
        <div class="form-grid"><label>用户 ID<input v-model.trim="userForm.userId" required pattern="[A-Za-z0-9._-]+" /></label><label>SOCKS 用户名（可选）<input v-model.trim="userForm.socksUsername" /></label><label>SOCKS 密码（可选）<input v-model="userForm.socksPassword" type="password" /></label></div>
        <fieldset><legend>启用协议</legend><div class="checkbox-row"><label v-for="protocol in ['vless','vmess','socks']" :key="protocol"><input v-model="userForm.protocols" type="checkbox" :value="protocol" />{{ protocol.toUpperCase() }}</label></div></fieldset>
        <label class="toggle-row"><input v-model="userForm.useProxy" type="checkbox" /><span>创建时绑定住宅 SOCKS5 出口</span></label>
        <div v-if="userForm.useProxy" class="form-grid proxy-grid"><label>代理服务器<input v-model.trim="userForm.proxyServer" required /></label><label>端口<input v-model.number="userForm.proxyPort" type="number" min="1" max="65535" required /></label><label>用户名<input v-model.trim="userForm.proxyUsername" /></label><label>密码<input v-model="userForm.proxyPassword" type="password" /></label></div>
        <div class="modal-actions"><button type="button" class="button ghost" @click="modal.user = false">取消</button><button class="button primary" :disabled="loading.action || userForm.protocols.length === 0">创建用户</button></div>
      </form>
    </div>

    <div v-if="modal.proxy" class="modal-backdrop" @mousedown.self="modal.proxy = false">
      <form class="modal-card" @submit.prevent="bindProxy">
        <div class="modal-heading"><div><p class="eyebrow">EGRESS BINDING</p><h2>绑定住宅出口</h2></div><button type="button" class="close-button" @click="modal.proxy = false">×</button></div>
        <p class="form-note">用户：<strong>{{ proxyForm.userId }}</strong></p>
        <div class="form-grid"><label>代理服务器<input v-model.trim="proxyForm.server" required /></label><label>端口<input v-model.number="proxyForm.port" type="number" min="1" max="65535" required /></label><label>用户名<input v-model.trim="proxyForm.username" /></label><label>密码<input v-model="proxyForm.password" type="password" /></label></div>
        <div class="modal-actions"><button type="button" class="button ghost" @click="modal.proxy = false">取消</button><button class="button primary" :disabled="loading.action">确认绑定</button></div>
      </form>
    </div>

    <div v-if="modal.connection" class="modal-backdrop" @mousedown.self="modal.connection = false">
      <div class="modal-card connection-card">
        <div class="modal-heading"><div><p class="eyebrow">CONNECTION PROFILE</p><h2>{{ connectionUser }}</h2></div><button class="close-button" @click="modal.connection = false">×</button></div>
        <div v-if="connectionData" class="connection-list">
          <div v-if="connectionData.vless"><span>VLESS</span><code>{{ connectionData.vless }}</code><button @click="copy(connectionData.vless)">复制</button></div>
          <div v-if="connectionData.vmess"><span>VMess</span><code>{{ connectionData.vmess }}</code><button @click="copy(connectionData.vmess)">复制</button></div>
          <div v-if="connectionData.socks"><span>SOCKS5</span><code>{{ connectionData.socks.host }}:{{ connectionData.socks.port }} · {{ connectionData.socks.username }} · {{ connectionData.socks.password }}</code><button @click="copy(`${connectionData.socks.host}:${connectionData.socks.port}:${connectionData.socks.username}:${connectionData.socks.password}`)">复制</button></div>
        </div>
      </div>
    </div>

    <transition name="toast"><div v-if="toast.visible" class="toast" :class="toast.type">{{ toast.message }}</div></transition>
    <div v-if="loading.app" class="loading-screen"><div class="loader"></div><span>正在连接控制面…</span></div>
  </div>
</template>

