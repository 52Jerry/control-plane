import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildConnectionLinks,
  buildSocks5Original,
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

test('connection links fall back per protocol and keep public order', () => {
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

  assert.deepEqual(links.map((item) => item.key), ['vless', 'socksAcceleration', 'vmess'])
  assert.match(links[0].value, /^vless:\/\//)
  assert.equal(links[1].value, 'legacy-socks')
  assert.match(links[2].value, /^vmess:\/\//)
  assert.equal(links.some((item) => item.key === 'socks5'), false)
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
