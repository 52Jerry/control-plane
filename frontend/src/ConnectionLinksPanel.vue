<script setup>
import { computed, ref, watch } from 'vue'
import { Copy, QrCode } from 'lucide-vue-next'
import QRCode from 'qrcode'
import { buildConnectionLinks, socksCredentialCompatibility } from './protocols'

const props = defineProps({
  connection: { type: Object, default: null },
  revealSecrets: { type: Boolean, default: false },
})

const emit = defineEmits(['copy'])

const originalOptions = [
  { key: 'ipDirect', label: '指纹浏览器 IP 直连', hint: '原始 IP、端口、用户名、密码' },
  { key: 'ipDirectSocks', label: 'SOCKS IP 直连', hint: '原始 SOCKS5 直连地址' },
]
const accelerationOptions = [
  { key: 'vless', label: 'VLESS' },
  { key: 'socksAcceleration', label: 'SOCKS' },
  { key: 'vmess', label: 'VMess' },
  { key: 'fingerprintAcceleration', label: '指纹加速' },
]

const selectedOriginalKey = ref('ipDirect')
const selectedAccelerationKey = ref('vless')
const qrDataUrl = ref('')
let qrRequestId = 0

const links = computed(() => buildConnectionLinks(props.connection))
const linksByKey = computed(() => new Map(links.value.map((item) => [item.key, item])))
const originalLinks = computed(() => originalOptions
  .map((option) => ({ ...option, link: linksByKey.value.get(option.key) }))
  .filter((option) => option.link))
const availableAccelerationOptions = computed(() => accelerationOptions
  .filter((option) => linksByKey.value.has(option.key)))
const selectedOriginal = computed(() => linksByKey.value.get(selectedOriginalKey.value)
  || originalLinks.value[0]?.link
  || null)
const selectedAcceleration = computed(() => linksByKey.value.get(selectedAccelerationKey.value)
  || linksByKey.value.get(availableAccelerationOptions.value[0]?.key)
  || null)
const credentialCompatibility = computed(() => socksCredentialCompatibility(props.connection))

watch(originalLinks, (available) => {
  if (available.length && !available.some((option) => option.key === selectedOriginalKey.value)) {
    selectedOriginalKey.value = available[0].key
  }
}, { immediate: true })

watch(availableAccelerationOptions, (available) => {
  if (available.length && !available.some((option) => option.key === selectedAccelerationKey.value)) {
    selectedAccelerationKey.value = available[0].key
  }
}, { immediate: true })

watch(
  () => selectedAcceleration.value?.value,
  async (link) => {
    const requestId = ++qrRequestId
    qrDataUrl.value = ''
    if (!link) return
    try {
      const dataUrl = await QRCode.toDataURL(link, {
        width: 232,
        margin: 2,
        errorCorrectionLevel: 'M',
        color: { dark: '#101827', light: '#ffffff' },
      })
      if (requestId === qrRequestId) qrDataUrl.value = dataUrl
    } catch {
      if (requestId === qrRequestId) qrDataUrl.value = ''
    }
  },
  { immediate: true },
)

function maskedLink() {
  return '••••••••••••••••••••••••••••••••'
}
</script>

<template>
  <div class="connection-modules">
    <section v-if="originalLinks.length" class="connection-module original-address-module">
      <div class="connection-module-heading">
        <div><span class="module-index">01</span><h3>原始地址</h3></div>
        <small>直接使用录入的 IP、端口与认证信息</small>
      </div>
      <div class="scene-selector" role="tablist" aria-label="原始地址使用场景">
        <button
          v-for="option in originalLinks"
          :key="option.key"
          type="button"
          :class="{ active: selectedOriginal?.key === option.key }"
          @click="selectedOriginalKey = option.key"
        >
          {{ option.label }}
        </button>
      </div>
      <div v-if="selectedOriginal" class="selected-proxy-link compact-link">
        <div class="selected-link-copy">
          <span>{{ originalOptions.find((option) => option.key === selectedOriginal.key)?.hint }}</span>
          <code>{{ revealSecrets ? selectedOriginal.value : maskedLink() }}</code>
        </div>
        <button type="button" :title="`复制 ${selectedOriginal.protocol}`" @click="emit('copy', selectedOriginal.value)">
          <Copy :size="15" />
        </button>
      </div>
    </section>

    <section v-if="availableAccelerationOptions.length" class="connection-module acceleration-module">
      <div class="connection-module-heading">
        <div><span class="module-index">02</span><h3>加速线路</h3></div>
        <small>选择使用场景后显示对应二维码和代理链接</small>
      </div>
      <div class="scene-selector acceleration-scenes" role="tablist" aria-label="加速线路使用场景">
        <button
          v-for="option in accelerationOptions"
          :key="option.key"
          type="button"
          :disabled="!linksByKey.has(option.key)"
          :class="{ active: selectedAcceleration?.key === option.key }"
          @click="selectedAccelerationKey = option.key"
        >
          {{ option.label }}
        </button>
      </div>
      <div v-if="selectedAcceleration" class="acceleration-result">
        <div class="qr-panel">
          <img v-if="qrDataUrl" :src="qrDataUrl" :alt="`${selectedAcceleration.protocol} 二维码`" />
          <div v-else class="qr-placeholder">
            <QrCode :size="40" />
            <span>二维码生成中</span>
          </div>
          <small>{{ selectedAcceleration.protocol }}</small>
        </div>
        <div class="selected-proxy-link">
          <div class="selected-link-copy">
            <span>代理链接</span>
            <code>{{ revealSecrets ? selectedAcceleration.value : maskedLink() }}</code>
          </div>
          <button type="button" :title="`复制 ${selectedAcceleration.protocol}`" @click="emit('copy', selectedAcceleration.value)">
            <Copy :size="15" />
          </button>
        </div>
      </div>
      <p v-if="credentialCompatibility" class="security-note connection-credential-note">
        <span>{{ credentialCompatibility.label }}</span>
        <small v-if="credentialCompatibility.legacy">老账号继续使用节点当前生效的 SOCKS 账号密码，不会自动替换导致认证失效。</small>
        <small v-else>新账号的节点 SOCKS 认证信息已与录入账号保持一致。</small>
      </p>
      <p class="form-note connection-route-note">原始 IP 端口仅用于原始直连，是否可用取决于供应商网络和白名单；指纹加速与 SOCKS 加速固定使用加速入口端口 5001。</p>
    </section>

    <p v-if="links.length === 0" class="empty-state">暂无可用连接</p>
  </div>
</template>
