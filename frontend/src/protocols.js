// Build public connection links from Node Manager's structured protocolInfo.
// Complete links are derived in memory and are never persisted in browser storage.

function value(data, key, fallback = '') {
  const result = data?.[key]
  return result === undefined || result === null ? fallback : result
}

function host(valueToFormat) {
  const raw = String(valueToFormat || '').trim()
  return raw.includes(':') && !raw.startsWith('[') ? `[${raw}]` : raw
}

function encode(valueToEncode) {
  return encodeURIComponent(String(valueToEncode ?? ''))
}

function utf8Base64(valueToEncode) {
  const bytes = new TextEncoder().encode(String(valueToEncode ?? ''))
  let binary = ''
  bytes.forEach((byte) => { binary += String.fromCharCode(byte) })
  return btoa(binary)
}

function socksAuth(valueToEncode) {
  const username = publicSocksUsername(valueToEncode?.username)
  const password = String(valueToEncode?.password ?? '')
  // V2Ray/V2RayN imports SOCKS credentials from a Base64-encoded
  // `username:password` userinfo. This is only a share-link representation;
  // Node Manager still authenticates with the separate plaintext fields.
  return utf8Base64(`${username}:${password}`)
}

// Older Node Manager installations exposed the internal VLESS/VMess auth
// alias as the public SOCKS username (for example
// ``node-manager:<user-id>``).  Keep accepting those historical payloads,
// but never emit the internal prefix in a link that is copied to a client.
function publicSocksUsername(rawUsername) {
  const original = String(rawUsername ?? '')
  // Older structured responses sometimes URL-encoded the internal alias as
  // `node-manager%3A<id>`.  Decode only for the prefix check; ordinary
  // usernames must remain byte-for-byte unchanged (including literal `%`).
  let username = original
  try {
    const decoded = decodeURIComponent(original)
    if (decoded.startsWith('node-manager:')) username = decoded
  } catch {
    // Keep the original value when it is not valid percent-encoding.
  }
  return username.startsWith('node-manager:')
    ? username.slice('node-manager:'.length)
    : original
}

function base64Json(valueToEncode) {
  return utf8Base64(JSON.stringify(valueToEncode))
}

function remark(data) {
  const code = value(data, 'countryCode', 'XX')
  const ip = value(data, 'ip', '')
  return encode(`[${code}] ${ip}`)
}

function port(data, key, fallback) {
  const number = Number(value(data, key, fallback))
  return Number.isInteger(number) && number > 0 && number <= 65535 ? number : fallback
}

function hasRawSocks(data) {
  if (!data) return false
  if (value(data, 'rawProtocol') === 'socks5') return true
  return Boolean(
    value(data, 'rawServer') || value(data, 'sourceAddress')
  ) && Boolean(value(data, 'rawPort') || value(data, 'sourcePort'))
    && Boolean(value(data, 'rawUsername') && value(data, 'rawPassword'))
}

function rawEndpoint(data) {
  // sourceIp is the residential exit metadata, not the SOCKS server a
  // client connects to.  Do not silently turn an exit IP into a dead proxy.
  const rawHost = value(data, 'rawServer') || value(data, 'sourceAddress')
  const rawPort = port(data, 'rawPort', value(data, 'sourcePort', 0))
  return rawHost && rawPort ? { host: rawHost, port: rawPort } : null
}

export function buildSocks5Original(data) {
  const username = value(data, 'rawUsername') || value(data, 'username')
  const password = value(data, 'rawPassword') || value(data, 'password')
  const endpoint = rawEndpoint(data)
  if (!hasRawSocks(data) || !username || !password || !endpoint) return ''
  return `socks://${socksAuth({ username, password })}@${host(endpoint.host)}:${endpoint.port}#${encode(`${value(data, 'countryCode', 'XX')}-${value(data, 'sourceIp') || endpoint.host}`)}`
}

export function buildBitBrowser(data) {
  const username = value(data, 'rawUsername') || value(data, 'username')
  const password = value(data, 'rawPassword') || value(data, 'password')
  const endpoint = rawEndpoint(data)
  if (!hasRawSocks(data) || !username || !password || !endpoint) return ''
  return `${endpoint.host}:${endpoint.port}:${username}:${password}`
}

export function buildVless(data) {
  if (!data || !value(data, 'uuid') || !value(data, 'accelerationDomain')) return ''
  const targetPort = port(data, 'vlessPort', 20168)
  const params = new URLSearchParams({
    encryption: value(data, 'vlessEncryption', 'none'),
    security: value(data, 'vlessSecurity', 'reality'),
    sni: value(data, 'vlessSni'),
    fp: value(data, 'vlessFp'),
    pbk: value(data, 'vlessPbk'),
    sid: value(data, 'vlessSid'),
    spx: value(data, 'vlessSpx', '%2F'),
    type: value(data, 'vlessType', 'tcp'),
    headerType: value(data, 'vlessHeaderType', 'none'),
    flow: value(data, 'vlessFlow', 'xtls-rprx-vision'),
  })
  const query = params.toString().replace('spx=%252F', 'spx=%2F')
  return `vless://${value(data, 'uuid')}@${host(value(data, 'accelerationDomain'))}:${targetPort}?${query}#${remark(data)}`
}

export function buildSocksAcceleration(data) {
  if (!data || !value(data, 'accelerationDomain') || !value(data, 'username') || !value(data, 'password')) return ''
  const targetPort = port(data, 'accelerationPortSocks', 5001)
  // sing-box expects the actual local SOCKS credentials, not Base64 text.
  // Base64 is only the share-link representation consumed by the client.
  const auth = socksAuth({ username: value(data, 'username'), password: value(data, 'password') })
  return `socks://${auth}@${host(value(data, 'accelerationDomain'))}:${targetPort}#${remark(data)}`
}

function hasCompleteStructuredSocks(data) {
  if (!data) return false
  const targetPort = Number(data.accelerationPortSocks)
  return Boolean(
    value(data, 'accelerationDomain')
      && value(data, 'username')
      && value(data, 'password')
      && Number.isInteger(targetPort)
      && targetPort >= 1
      && targetPort <= 65535,
  )
}

export function buildVmess(data) {
  if (!data || !value(data, 'uuid') || !value(data, 'accelerationDomain')) return ''
  const config = {
    v: value(data, 'vmessV', '2'),
    ps: `[${value(data, 'countryCode', 'XX')}] ${value(data, 'ip')}`,
    add: value(data, 'accelerationDomain'),
    port: String(port(data, 'vmessPort', 20169)),
    id: value(data, 'uuid'),
    aid: value(data, 'vmessAid', '0'),
    scy: value(data, 'vmessScy', 'auto'),
    net: value(data, 'vmessNet', 'tcp'),
    type: value(data, 'vmessType', 'none'),
    host: value(data, 'vmessHost'),
    path: value(data, 'vmessPath'),
    tls: value(data, 'vmessTls'),
    sni: value(data, 'vmessSni'),
    alpn: value(data, 'vmessAlpn'),
    fp: value(data, 'vmessFp'),
  }
  return `vmess://${base64Json(config)}`
}

export function buildAllProtocols(
  data,
  enabledProtocols = ['vless', 'vmess', 'socks'],
  includeOriginal = false,
) {
  if (!data) return []
  const links = []
  // Raw residential SOCKS and BitBrowser credentials are only for the
  // explicit proxy-details view.  Normal connection lists must contain the
  // three Node Manager acceleration entry points only.
  if (includeOriginal && hasRawSocks(data)) {
    const original = buildSocks5Original(data)
    const browser = buildBitBrowser(data)
    if (original) links.push({ key: 'socks5', protocol: 'SOCKS5 原始', value: original })
    if (browser) links.push({ key: 'bitbrowser', protocol: 'BitBrowser', value: browser })
  }
  if (enabledProtocols.includes('vless')) {
    const link = buildVless(data)
    if (link) links.push({ key: 'vless', protocol: 'VLESS 加速', value: link })
  }
  if (enabledProtocols.includes('socks')) {
    const link = buildSocksAcceleration(data)
    if (link) links.push({ key: 'socksAcceleration', protocol: 'SOCKS 加速', value: link })
  }
  if (enabledProtocols.includes('vmess')) {
    const link = buildVmess(data)
    if (link) links.push({ key: 'vmess', protocol: 'VMess 加速', value: link })
  }
  return links
}

const connectionLabels = {
  vless: 'VLESS 加速',
  socksAcceleration: 'SOCKS 加速',
  vmess: 'VMess 加速',
}

function legacySocksLink(valueToCheck) {
  const text = String(valueToCheck || '')
  return text.includes('node-manager:') || text.includes('node-manager%3A')
    ? ''
    : text
}

function legacyConnectionValue(connection, key) {
  if (key === 'vless') return connection?.vless || ''
  if (key === 'vmess') return connection?.vmess || ''
  if (key !== 'socksAcceleration' || !connection?.socks) return ''

  const socks = connection.socks
  if (!socks.host || !socks.username || !socks.password) return ''
  const targetPort = Number(socks.port)
  if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) return ''
  return `socks://${socksAuth(socks)}@${host(socks.host)}:${targetPort}`
}

// Select each public connection protocol independently. A partially populated
// protocolInfo must not hide a valid legacy link for a different protocol.
export function buildConnectionLinks(connection) {
  if (!connection) return []

  const enabled = new Set(connection.protocols || ['vless', 'vmess', 'socks'])
  const structured = connection.protocolInfo || {}
  const legacy = connection.protocolsAll || {}
  const keys = [
    ['vless', buildVless],
    ['socksAcceleration', buildSocksAcceleration],
    ['vmess', buildVmess],
  ]

  return keys
    .filter(([key]) => enabled.has(key === 'socksAcceleration' ? 'socks' : key))
    .map(([key, builder]) => {
      let link = ''
      if (key !== 'socksAcceleration' || hasCompleteStructuredSocks(structured)) {
        link = builder(structured)
      }
      if (!link) link = legacySocksLink(legacy[key])
      if (!link) link = legacyConnectionValue(connection, key)
      return link ? { key, protocol: connectionLabels[key], value: link } : null
    })
    .filter(Boolean)
}
