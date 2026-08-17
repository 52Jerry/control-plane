import test from 'node:test'
import assert from 'node:assert/strict'

import { normalizeUserPageQuery, userPageCacheKey } from '../src/userPageCache.js'

test('user page cache key separates nodes pages filters and sorting', () => {
  const base = { nodeId: 'node-a', page: 1, pageSize: 20, keyword: 'alice', ip: '203.0.113', sort: 'createdDesc' }

  assert.notEqual(userPageCacheKey(base), userPageCacheKey({ ...base, nodeId: 'node-b' }))
  assert.notEqual(userPageCacheKey(base), userPageCacheKey({ ...base, page: 2 }))
  assert.notEqual(userPageCacheKey(base), userPageCacheKey({ ...base, keyword: 'bob' }))
  assert.notEqual(userPageCacheKey(base), userPageCacheKey({ ...base, ip: '198.51.100' }))
  assert.notEqual(userPageCacheKey(base), userPageCacheKey({ ...base, sort: 'createdAsc' }))
})

test('user page query normalization trims search fields and applies defaults', () => {
  assert.deepEqual(normalizeUserPageQuery({
    nodeId: 'node-a',
    page: 0,
    keyword: '  alice  ',
    ip: ' 203.0.113.8 ',
  }), {
    nodeId: 'node-a',
    page: 1,
    pageSize: 20,
    keyword: 'alice',
    ip: '203.0.113.8',
    sort: 'createdDesc',
  })
})
