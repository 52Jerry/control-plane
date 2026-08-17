import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildConnectionBatchJobs,
  chunkRows,
  connectionLookupKey,
  distributeRowsRoundRobin,
  filterImportedRowsBySequence,
  normalizeSequenceRange,
  parseExcelTransferTables,
  proxyInputFromRows,
  selectedRowsForExport,
  selectSequenceRange,
} from '../src/excelTransfer.js'

test('sequence ranges support open ends and clamp export ranges to the available rows', () => {
  assert.deepEqual(normalizeSequenceRange(2, '', 5), { from: 2, to: 5 })
  assert.deepEqual(selectSequenceRange([
    { sequence: 1 }, { sequence: 2 }, { sequence: 3 }, { sequence: 4 },
  ], 2, 20), [{ sequence: 2 }, { sequence: 3 }, { sequence: 4 }])
  assert.throws(() => normalizeSequenceRange(5, 2, 10), /起始序号不能大于结束序号/)
})

test('Excel transfer rows are parsed by Chinese headers and sorted by sequence', () => {
  const rows = parseExcelTransferTables([{
    name: '节点用户',
    rows: [
      ['序号', 'IP', '连接端口', '连接账号', '连接密码', '代理链接'],
      [3, '198.51.100.3', 5003, 'user-3', 'pass-3', 'ignored'],
      [1, '198.51.100.1', 5001, 'user-1', 'pass-1', 'ignored'],
    ],
  }])

  assert.deepEqual(rows.map((row) => row.sequence), [1, 3])
  assert.equal(rows[0].username, 'user-1')
  assert.deepEqual(filterImportedRowsBySequence(rows, 2, ''), [rows[1]])
})

test('Excel transfer validation reports invalid ports and credentials', () => {
  assert.throws(() => parseExcelTransferTables([{
    name: '错误数据',
    rows: [
      ['序号', 'IP', '连接端口', '连接账号', '连接密码'],
      [1, '999.1.1.1', 70000, 'bad user', ''],
    ],
  }]), /IP 地址格式不正确/)
})

test('rows are distributed round-robin and chunked at the backend batch limit', () => {
  const rows = Array.from({ length: 103 }, (_, index) => ({
    sequence: index + 1,
    ip: `198.51.100.${(index % 200) + 1}`,
    port: 5001,
    username: `user-${index + 1}`,
    password: `pass-${index + 1}`,
  }))
  const groups = distributeRowsRoundRobin(rows, ['node-a', 'node-b', 'node-c'])
  assert.deepEqual(groups.map((group) => group.rows.length), [35, 34, 34])
  assert.deepEqual(groups[1].rows.slice(0, 3).map((row) => row.sequence), [2, 5, 8])
  assert.deepEqual(chunkRows(rows, 50).map((chunk) => chunk.length), [50, 50, 3])
  assert.equal(proxyInputFromRows(rows.slice(0, 1)), '198.51.100.1 5001 user-1 pass-1')
})

test('connection export jobs are grouped by node, deduplicated, and chunked', () => {
  const items = [
    ...Array.from({ length: 101 }, (_, index) => ({
      node: { id: 'node-a' }, user: { userId: `user-${index + 1}` },
    })),
    { node: { id: 'node-a' }, user: { userId: 'user-1' } },
    { node: { id: 'node-b' }, user: { userId: 'other-user' } },
  ]
  const jobs = buildConnectionBatchJobs(items, 100)

  assert.deepEqual(jobs.map((job) => [job.nodeId, job.userIds.length]), [
    ['node-a', 100], ['node-a', 1], ['node-b', 1],
  ])
  assert.equal(connectionLookupKey('node-a', 'user-1'), 'node-a\u0000user-1')
})

test('selected user export keeps one or many selected rows without reloading the node list', () => {
  const node = { id: 'node-a', name: 'Node A' }
  const rows = [
    { nodeId: 'node-a', sequence: 22, user: { userId: 'user-22', access: { ip: '198.51.100.22' } } },
    { nodeId: 'node-b', sequence: 1, user: { userId: 'other-user' } },
    { nodeId: 'node-a', sequence: 3, user: { userId: 'user-3', access: { ip: '198.51.100.3' } } },
  ]

  const selected = selectedRowsForExport(rows, node)

  assert.deepEqual(selected.map((item) => [item.sequence, item.user.userId]), [
    [3, 'user-3'], [22, 'user-22'],
  ])
  assert.equal(selected[0].node, node)
  assert.deepEqual(selectedRowsForExport(rows.slice(0, 1), node)
    .map((item) => item.user.userId), ['user-22'])
})
