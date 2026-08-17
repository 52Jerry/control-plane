export function normalizeUserPageQuery(query) {
  return {
    nodeId: String(query.nodeId || ''),
    page: Math.max(1, Number(query.page) || 1),
    pageSize: Math.max(1, Number(query.pageSize) || 20),
    keyword: String(query.keyword || '').trim(),
    ip: String(query.ip || '').trim(),
    sort: String(query.sort || 'createdDesc'),
  }
}

export function userPageCacheKey(query) {
  const normalized = normalizeUserPageQuery(query)
  return JSON.stringify([
    normalized.nodeId,
    normalized.page,
    normalized.pageSize,
    normalized.keyword,
    normalized.ip,
    normalized.sort,
  ])
}
