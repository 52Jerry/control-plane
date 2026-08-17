const requiredHeaders = ['序号', 'IP', '连接端口', '连接账号', '连接密码']

export function normalizeSequenceRange(start, end, total = null) {
  const from = Number(start === '' || start === null || start === undefined ? 1 : start)
  const requestedEnd = end === '' || end === null || end === undefined ? total : Number(end)
  if (!Number.isInteger(from) || from < 1) throw new Error('起始序号必须是大于 0 的整数')
  if (requestedEnd === null) return { from, to: null }
  if (!Number.isInteger(requestedEnd) || requestedEnd < 1) throw new Error('结束序号必须是大于 0 的整数')
  if (from > requestedEnd) throw new Error('起始序号不能大于结束序号')
  const to = total === null ? requestedEnd : Math.min(requestedEnd, total)
  return { from, to }
}

export function selectSequenceRange(items, start, end) {
  if (!items.length) return []
  const { from, to } = normalizeSequenceRange(start, end, items.length)
  if (from > items.length) return []
  return items.filter((item) => item.sequence >= from && item.sequence <= to)
}

export function excelCellText(value) {
  if (value === null || value === undefined) return ''
  if (value instanceof Date) return value.toISOString()
  if (typeof value !== 'object') return String(value).trim()
  if (Array.isArray(value.richText)) return value.richText.map((part) => part.text || '').join('').trim()
  if (value.text !== undefined) return String(value.text).trim()
  if (value.result !== undefined) return excelCellText(value.result)
  if (value.hyperlink !== undefined) return String(value.text || value.hyperlink).trim()
  return String(value).trim()
}

function normalizeHeader(value) {
  return excelCellText(value).replace(/\s+/g, '').toUpperCase()
}

function validIpv4(value) {
  const segments = String(value).split('.')
  return segments.length === 4 && segments.every((segment) => /^\d{1,3}$/.test(segment)
    && Number(segment) >= 0 && Number(segment) <= 255)
}

function validateCredential(value, rowLabel, field) {
  if (!value) throw new Error(`${rowLabel}${field}不能为空`)
  if (value.length > 255) throw new Error(`${rowLabel}${field}不能超过 255 个字符`)
  if (/\s/.test(value)) throw new Error(`${rowLabel}${field}不能包含空白字符`)
  if ([...value].some((character) => /[\u0000-\u001f\u007f]/.test(character))) {
    throw new Error(`${rowLabel}${field}不能包含控制字符`)
  }
}

export function parseExcelTransferTables(tables) {
  const parsedRows = []
  const errors = []

  tables.forEach((table) => {
    const rows = table.rows || []
    const headerIndex = rows.findIndex((row, index) => index < 20
      && requiredHeaders.every((header) => row.some((cell) => normalizeHeader(cell) === normalizeHeader(header))))
    if (headerIndex < 0) {
      if (rows.some((row) => row.some((cell) => excelCellText(cell)))) {
        errors.push(`${table.name || '工作表'}缺少表头：${requiredHeaders.join('、')}`)
      }
      return
    }

    const headers = rows[headerIndex].map(normalizeHeader)
    const column = Object.fromEntries(requiredHeaders.map((header) => [
      header,
      headers.indexOf(normalizeHeader(header)),
    ]))

    rows.slice(headerIndex + 1).forEach((row, offset) => {
      const excelRowNumber = headerIndex + offset + 2
      const values = Object.fromEntries(requiredHeaders.map((header) => [
        header,
        excelCellText(row[column[header]]),
      ]))
      if (Object.values(values).every((value) => !value)) return

      const rowLabel = `${table.name || '工作表'}第 ${excelRowNumber} 行：`
      try {
        const sequence = Number(values['序号'])
        const port = Number(values['连接端口'])
        if (!Number.isInteger(sequence) || sequence < 1) throw new Error(`${rowLabel}序号必须是大于 0 的整数`)
        if (!validIpv4(values.IP)) throw new Error(`${rowLabel}IP 地址格式不正确`)
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
          throw new Error(`${rowLabel}连接端口必须在 1-65535 之间`)
        }
        validateCredential(values['连接账号'], rowLabel, '连接账号')
        validateCredential(values['连接密码'], rowLabel, '连接密码')
        parsedRows.push({
          sequence,
          ip: values.IP,
          port,
          username: values['连接账号'],
          password: values['连接密码'],
          sheetName: table.name || '工作表',
          excelRowNumber,
        })
      } catch (error) {
        errors.push(error.message)
      }
    })
  })

  if (errors.length) {
    const shown = errors.slice(0, 8)
    const remaining = errors.length - shown.length
    throw new Error(`${shown.join('；')}${remaining ? `；另有 ${remaining} 个错误` : ''}`)
  }
  if (!parsedRows.length) throw new Error('Excel 中没有可导入的数据')

  const duplicateSequences = [...new Set(parsedRows
    .map((row) => row.sequence)
    .filter((sequence, index, all) => all.indexOf(sequence) !== index))]
  if (duplicateSequences.length) {
    throw new Error(`Excel 序号不能重复：${duplicateSequences.slice(0, 10).join('、')}`)
  }
  return parsedRows.sort((left, right) => left.sequence - right.sequence)
}

export function filterImportedRowsBySequence(rows, start, end) {
  const { from, to } = normalizeSequenceRange(start, end, null)
  return rows.filter((row) => row.sequence >= from && (to === null || row.sequence <= to))
}

export function distributeRowsRoundRobin(rows, nodeIds) {
  if (!nodeIds.length) throw new Error('请至少选择一个节点')
  const groups = nodeIds.map((nodeId) => ({ nodeId, rows: [] }))
  rows.forEach((row, index) => groups[index % groups.length].rows.push(row))
  return groups.filter((group) => group.rows.length)
}

export function chunkRows(rows, size = 50) {
  if (!Number.isInteger(size) || size < 1) throw new Error('分批数量必须是大于 0 的整数')
  const chunks = []
  for (let index = 0; index < rows.length; index += size) chunks.push(rows.slice(index, index + size))
  return chunks
}

export function connectionLookupKey(nodeId, userId) {
  return `${nodeId}\u0000${userId}`
}

export function selectedRowsForExport(rows, node) {
  if (!node?.id) return []
  return rows
    .filter((item) => item?.nodeId === node.id && item?.user?.userId)
    .map((item) => ({ node, user: item.user, sequence: item.sequence }))
    .sort((left, right) => left.sequence - right.sequence)
}

export function buildConnectionBatchJobs(items, size = 100) {
  if (!Number.isInteger(size) || size < 1) throw new Error('连接分批数量必须是大于 0 的整数')
  const grouped = new Map()
  items.forEach((item) => {
    const nodeId = item?.node?.id
    const userId = item?.user?.userId
    if (!nodeId || !userId) return
    if (!grouped.has(nodeId)) grouped.set(nodeId, [])
    const userIds = grouped.get(nodeId)
    if (!userIds.includes(userId)) userIds.push(userId)
  })
  return [...grouped.entries()].flatMap(([nodeId, userIds]) => chunkRows(userIds, size)
    .map((batchUserIds) => ({ nodeId, userIds: batchUserIds })))
}

export function proxyInputFromRows(rows) {
  return rows.map((row) => `${row.ip} ${row.port} ${row.username} ${row.password}`).join('\n')
}
