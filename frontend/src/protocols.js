// Build public connection links from Node Manager's structured protocolInfo.
// Keeping this in one module makes the API contract explicit and avoids
// persisting complete links or upstream credentials in browser storage.

function value(data, key, fallback = '') {
  const result = data?.[key]
  return result === undefined || result === null ? fallback : result
}

function host(valueToFormat) {
  const raw = String(valueToFormat || '')
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

export function buildSocks5Original(data) {
  const username = value(data, 'rawUsername') || value(data, 'username')
  const password = value(data, 'rawPassword') || value(data, 'password')
  if (!data || !value(data, 'rawProtocol') || !username || !password) return ''
  const targetPort = port(data, 'rawPort', value(data, 'port', 0))
  const hostValue = value(data, 'sourceIp') || value(data, 'ip')
  if (!targetPort || !hostValue) return ''
  return `socks://${encode(username)}:${encode(password)}@${host(hostValue)}:${targetPort}#${encode(`${value(data, 'countryCode', 'XX')}-${hostValue}`)}`
}

export function buildBitBrowser(data) {
  const username = value(data, 'rawUsername') || value(data, 'username')
  const password = value(data, 'rawPassword') || value(data, 'password')
  if (!data || !value(data, 'rawProtocol') || !username || !password) return ''
  const targetPort = port(data, 'rawPort', value(data, 'port', 0))
  const hostValue = value(data, 'sourceIp') || value(data, 'ip')
  if (!targetPort || !hostValue) return ''
  return `${hostValue}:${targetPort}:${username}:${password}`
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
  // URLSearchParams encodes '%' in the documented %2F path as %252F. Decode
  // only that value so the resulting URI remains compatible with sing-box.
  const query = params.toString().replace('spx=%252F', 'spx=%2F')
  return `vless://${value(data, 'uuid')}@${host(value(data, 'accelerationDomain'))}:${targetPort}?${query}#${remark(data)}`
}

export function buildSocksAcceleration(data) {
  if (!data || !value(data, 'accelerationDomain') || !value(data, 'username') || !value(data, 'password')) return ''
  const targetPort = port(data, 'accelerationPortSocks', 5001)
  return `socks://${encode(value(data, 'username'))}:${encode(value(data, 'password'))}@${host(value(data, 'accelerationDomain'))}:${targetPort}#${remark(data)}`
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

export function buildAllProtocols(data, enabledProtocols = ['vless', 'vmess', 'socks']) {
  if (!data) return []
  const links = []
  if (value(data, 'rawProtocol') === 'socks5') {
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

