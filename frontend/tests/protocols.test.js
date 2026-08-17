import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildConnectionExportData,
  buildConnectionLinks,
  buildFingerprintAcceleration,
  buildIpDirect,
  buildIpDirectSocks,
  buildSocks5Original,
  connectionExportScenarios,
  socksCredentialCompatibility,
} from '../src/protocols.js'

function structured(overrides = {}) {
  return {
    uuid: '11111111-1111-4111-8111-111111111111',
    accelerationDomain: 'proxy.example.test',
    accelerationPortSocks: 5001,
    username: 'local-user',
    password: 'local-password',
    countryCode: 'US',
    ip: '198.51.100.10',
    ...overrides,
  }
}

test('connection links use fixed SOCKS port when the response omits it and keep public order', () => {
  const links = buildConnectionLinks({
    protocols: ['vless', 'vmess', 'socks'],
    protocolInfo: structured({ accelerationPortSocks: undefined }),
    protocolsAll: {
      vless: 'legacy-vless',
      socksAcceleration: 'legacy-socks',
      vmess: 'legacy-vmess',
      socks5: 'upstream-socks-must-not-show',
    },
  })

  assert.deepEqual(links.map((item) => item.key), [
    'vless',
    'socksAcceleration',
    'vmess',
    'fingerprintAcceleration',
  ])
  assert.match(links[0].value, /^vless:\/\//)
  assert.match(links[1].value, /@proxy\.example\.test:5001#/)
  assert.match(links[2].value, /^vmess:\/\//)
  assert.equal(links[3].value, 'proxy.example.test:5001:local-user:local-password')
  assert.equal(links.some((item) => item.key === 'socks5'), false)
})

test('acceleration aliases use an uppercase country abbreviation', () => {
  const links = new Map(buildConnectionLinks({
    protocols: ['vless', 'vmess', 'socks'],
    protocolInfo: structured({ countryCode: 'us' }),
  }).map((item) => [item.key, item.value]))

  assert.equal(decodeURIComponent(links.get('vless').split('#')[1]), '[US] 198.51.100.10')
  assert.equal(
    decodeURIComponent(links.get('socksAcceleration').split('#')[1]),
    '[US] 198.51.100.10',
  )
  const vmess = JSON.parse(Buffer.from(links.get('vmess').split('//')[1], 'base64'))
  assert.equal(vmess.ps, '[US] 198.51.100.10')
})

test('unknown XX and ZZ codes are not treated as real countries', () => {
  for (const countryCode of ['XX', 'ZZ']) {
    const links = new Map(buildConnectionLinks({
      protocols: ['vless', 'vmess', 'socks'],
      protocolInfo: structured({ countryCode }),
    }).map((item) => [item.key, item.value]))

    assert.equal(decodeURIComponent(links.get('vless').split('#')[1]), '[XX] 198.51.100.10')
    assert.equal(decodeURIComponent(links.get('socksAcceleration').split('#')[1]), '[XX] 198.51.100.10')
    const vmess = JSON.parse(Buffer.from(links.get('vmess').split('//')[1], 'base64'))
    assert.equal(vmess.ps, '[XX] 198.51.100.10')
  }
})

test('incomplete structured SOCKS falls back to the same protocol only', () => {
  const links = buildConnectionLinks({
    protocols: ['socks'],
    protocolInfo: structured({ username: '', password: '', accelerationDomain: '' }),
    protocolsAll: { socksAcceleration: 'legacy-socks' },
  })

  assert.deepEqual(links, [{
    key: 'socksAcceleration',
    protocol: 'SOCKS 加速',
    value: 'legacy-socks',
  }])
})

test('original formats use submitted endpoint while fingerprint acceleration fixes the gateway port to 5001', () => {
  const connection = {
    protocolInfo: structured({
      accelerationDomain: 'proxy.xinxinip.com',
      accelerationPortSocks: 6000,
    }),
    socks: {
      host: '203.0.113.20',
      port: 6000,
      username: '42HVF7w7fi',
      password: 'pLwDKFdz',
    },
    directEndpoint: {
      host: '38.30.216.149',
      port: 5001,
      username: '42HVF7w7fi',
      password: 'pLwDKFdz',
    },
  }

  assert.equal(
    buildFingerprintAcceleration(connection),
    'proxy.xinxinip.com:5001:42HVF7w7fi:pLwDKFdz',
  )
  assert.equal(buildIpDirect(connection), '38.30.216.149:5001:42HVF7w7fi:pLwDKFdz')
  assert.equal(
    buildIpDirectSocks(connection),
    'socks5://42HVF7w7fi:pLwDKFdz@38.30.216.149:5001',
  )
})

test('IP direct SOCKS encodes reserved credentials from the submitted endpoint', () => {
  const connection = {
    directEndpoint: {
      host: '2001:db8::10',
      port: 5001,
      username: 'ip@user',
      password: 'p:a/ss',
    },
  }

  assert.equal(
    buildIpDirectSocks(connection),
    'socks5://ip%40user:p%3Aa%2Fss@[2001:db8::10]:5001',
  )
})

test('complete connections append all three direct SOCKS formats in public order', () => {
  const links = buildConnectionLinks({
    protocols: ['vless', 'vmess', 'socks'],
    protocolInfo: structured(),
    socks: {
      host: '203.0.113.20',
      port: 6000,
      username: 'ip-user',
      password: 'ip-password',
    },
    directEndpoint: {
      host: '38.30.216.149',
      port: 5001,
      username: 'ip-user',
      password: 'ip-password',
    },
  })

  assert.deepEqual(links.map((item) => item.key), [
    'vless',
    'socksAcceleration',
    'vmess',
    'fingerprintAcceleration',
    'ipDirect',
    'ipDirectSocks',
  ])
})

test('connection links provide two original scenarios and four acceleration scenarios', () => {
  const links = buildConnectionLinks({
    protocols: ['vless', 'vmess', 'socks'],
    protocolInfo: structured(),
    directEndpoint: {
      host: '38.30.216.149',
      port: 5001,
      username: 'ip-user',
      password: 'ip-password',
    },
  })
  const values = new Map(links.map((item) => [item.key, item.value]))

  assert.deepEqual(
    ['ipDirect', 'ipDirectSocks'].filter((key) => values.has(key)),
    ['ipDirect', 'ipDirectSocks'],
  )
  assert.deepEqual(
    ['vless', 'socksAcceleration', 'vmess', 'fingerprintAcceleration'].filter((key) => values.has(key)),
    ['vless', 'socksAcceleration', 'vmess', 'fingerprintAcceleration'],
  )
  assert.match(values.get('vless'), /^vless:\/\//)
  assert.equal(values.get('fingerprintAcceleration'), 'proxy.example.test:5001:local-user:local-password')
  assert.equal(values.get('ipDirect'), '38.30.216.149:5001:ip-user:ip-password')
})

test('historical SOCKS credentials remain active for acceleration links', () => {
  const connection = {
    protocols: ['socks'],
    protocolInfo: structured({
      accelerationDomain: 'proxy.xinxinip.com',
      accelerationPortSocks: 7001,
      username: 'stale-structured-user',
      password: 'stale-structured-password',
    }),
    socks: {
      host: '203.0.113.20',
      port: 7001,
      username: 'legacy-random-user',
      password: 'legacy-random-password',
    },
    directEndpoint: {
      host: '38.30.216.149',
      port: 5001,
      username: 'submitted-user',
      password: 'submitted-password',
    },
  }

  const links = new Map(buildConnectionLinks(connection).map((item) => [item.key, item.value]))
  assert.equal(
    links.get('fingerprintAcceleration'),
    'proxy.xinxinip.com:5001:legacy-random-user:legacy-random-password',
  )
  assert.match(links.get('socksAcceleration'), /@proxy\.xinxinip\.com:5001#/)
  const socksAuth = links.get('socksAcceleration').split('socks://')[1].split('@')[0]
  assert.equal(Buffer.from(socksAuth, 'base64').toString('utf8'), 'legacy-random-user:legacy-random-password')
  assert.deepEqual(socksCredentialCompatibility(connection), {
    legacy: true,
    label: '历史节点凭据（与原始账号不同）',
  })
})

test('connections without submitted upstream data do not invent original IP links', () => {
  const connection = {
    protocols: ['socks'],
    protocolInfo: structured(),
    socks: {
      host: 'proxy.example.test',
      port: 5001,
      username: 'local-user',
      password: 'local-password',
    },
  }

  const keys = buildConnectionLinks(connection).map((item) => item.key)
  assert.deepEqual(keys, ['socksAcceleration', 'fingerprintAcceleration'])
  assert.equal(buildIpDirect(connection), '')
  assert.equal(buildIpDirectSocks(connection), '')
})

test('SOCKS acceleration ignores both submitted and returned endpoint ports', () => {
  const connection = {
    protocols: ['socks'],
    protocolInfo: structured({
      accelerationDomain: 'proxy.xinxinip.com',
      accelerationPortSocks: 7999,
    }),
    socks: {
      host: '203.0.113.20',
      port: 6888,
      username: 'node-user',
      password: 'node-password',
    },
    directEndpoint: {
      host: '38.30.216.149',
      port: 9335,
      username: 'source-user',
      password: 'source-password',
    },
  }

  const links = new Map(buildConnectionLinks(connection).map((item) => [item.key, item.value]))
  assert.equal(buildIpDirect(connection), '38.30.216.149:9335:source-user:source-password')
  assert.equal(
    links.get('fingerprintAcceleration'),
    'proxy.xinxinip.com:5001:node-user:node-password',
  )
  assert.match(links.get('socksAcceleration'), /@proxy\.xinxinip\.com:5001#/)
  assert.equal(buildConnectionExportData(connection, 'socksAcceleration').port, 5001)
  assert.equal(buildConnectionExportData(connection, 'fingerprintAcceleration').port, 5001)
})

test('allocation details recover original links from explicit raw upstream fields', () => {
  const connection = {
    protocols: ['socks'],
    protocolInfo: structured({
      sourceIp: '38.30.216.149',
      sourcePort: 5001,
      rawUsername: 'submitted-user',
      rawPassword: 'submitted-password',
    }),
    socks: {
      host: 'proxy.example.test',
      port: 6000,
      username: 'legacy-random-user',
      password: 'legacy-random-password',
    },
  }

  assert.equal(
    buildIpDirect(connection),
    '38.30.216.149:5001:submitted-user:submitted-password',
  )
  assert.equal(socksCredentialCompatibility(connection)?.legacy, true)
})

test('legacy internal SOCKS usernames are never returned to normal connections', () => {
  for (const value of [
    'socks://node-manager:user@proxy.example.test:5001',
    'socks://node-manager%3Auser@proxy.example.test:5001',
  ]) {
    const links = buildConnectionLinks({
      protocols: ['socks'],
      protocolsAll: { socksAcceleration: value },
      socks: { host: 'proxy.example.test', port: 5001, username: '', password: '' },
    })
    assert.deepEqual(links, [])
  }
})

test('SOCKS share auth is UTF-8 Base64 and supports reserved characters', () => {
  const link = buildSocks5Original({
    rawProtocol: 'socks5',
    rawServer: '2001:db8::10',
    rawPort: 5001,
    rawUsername: '用户@name',
    rawPassword: 'p:a+ss/word=',
    countryCode: 'US',
    sourceIp: '198.51.100.10',
  })
  const encoded = link.split('socks://')[1].split('@')[0]
  assert.equal(Buffer.from(encoded, 'base64').toString('utf8'), '用户@name:p:a+ss/word=')
  assert.match(link, /@\[2001:db8::10\]:5001#/)
})

test('export scenarios expose all selectable connection formats', () => {
  assert.deepEqual(connectionExportScenarios.map((item) => item.key), [
    'ipDirect',
    'ipDirectSocks',
    'vless',
    'socksAcceleration',
    'vmess',
    'fingerprintAcceleration',
  ])
})

test('export data maps original and accelerated credentials consistently', () => {
  const connection = {
    protocols: ['vless', 'vmess', 'socks'],
    protocolInfo: structured({
      vlessPort: 20168,
      vmessPort: 20169,
    }),
    socks: {
      host: 'proxy.example.test',
      port: 5001,
      username: 'node-manager:public-user',
      password: 'local-password',
    },
    directEndpoint: {
      host: '38.30.216.149',
      port: 9335,
      username: 'source-user',
      password: 'source-password',
    },
  }

  assert.deepEqual(buildConnectionExportData(connection, 'ipDirect'), {
    ip: '38.30.216.149',
    port: 9335,
    username: 'source-user',
    password: 'source-password',
    link: '38.30.216.149:9335:source-user:source-password',
  })
  assert.deepEqual(buildConnectionExportData(connection, 'fingerprintAcceleration'), {
    ip: 'proxy.example.test',
    port: 5001,
    username: 'public-user',
    password: 'local-password',
    link: 'proxy.example.test:5001:public-user:local-password',
  })

  const socks = buildConnectionExportData(connection, 'socksAcceleration')
  assert.equal(socks.ip, 'proxy.example.test')
  assert.equal(socks.port, 5001)
  assert.equal(socks.username, 'public-user')
  assert.equal(socks.password, 'local-password')
  assert.match(socks.link, /^socks:\/\//)

  const vless = buildConnectionExportData(connection, 'vless')
  assert.equal(vless.ip, 'proxy.example.test')
  assert.equal(vless.port, 20168)
  assert.equal(vless.username, structured().uuid)
  assert.equal(vless.password, '')
  assert.match(vless.link, /^vless:\/\//)

  const vmess = buildConnectionExportData(connection, 'vmess')
  assert.equal(vmess.ip, 'proxy.example.test')
  assert.equal(vmess.port, 20169)
  assert.equal(vmess.username, structured().uuid)
  assert.equal(vmess.password, '')
  assert.match(vmess.link, /^vmess:\/\//)
})

test('export data returns blank connection fields when a scenario is unavailable', () => {
  assert.deepEqual(buildConnectionExportData({ protocols: ['vless'] }, 'socksAcceleration'), {
    ip: '',
    port: '',
    username: '',
    password: '',
    link: '',
  })
})
