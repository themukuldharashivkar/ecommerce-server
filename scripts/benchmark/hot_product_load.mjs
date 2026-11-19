// Usage: API=http://localhost:5460 ADMIN_EMAIL=... ADMIN_PASSWORD=... node scripts/benchmark/hot_product_load.mjs (see docs/PERFORMANCE.md section 4)
// Load test: N shoppers order the same product at once (C requests in flight). Prints one JSON line per run.
const API = process.env.API || 'http://localhost:5460';
const [N, C, STOCK, RUNS] = [500, 50, 200, 3];
const ADDRESS = { firstName: 'Asha', lastName: 'Rao', streetAddress: '12 MG Road', city: 'Pune', state: 'MH', zipCode: '411001', mobile: '9876543210' };
const j = (method, path, body, token) => fetch(API + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body ? JSON.stringify(body) : undefined });
async function pool(items, limit, fn) { const out = []; let i = 0; await Promise.all(Array.from({ length: limit }, async () => { while (i < items.length) { const k = i++; out[k] = await fn(items[k], k); } })); return out; }
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.ceil(p / 100 * s.length) - 1)] : null; };
const round = x => x == null ? null : Math.round(x * 10) / 10;

const admin = (await (await j('POST', '/auth/signin', { email: process.env.ADMIN_EMAIL || 'admin@loadtest.local', password: process.env.ADMIN_PASSWORD || 'admin-loadtest-123' })).json()).jwt;
async function product(stock) {
  const r = await j('POST', '/api/admin/products/', { title: 'Hot product ' + Date.now() + Math.random(), description: 'load test', brand: 'Load', color: 'Black', price: 1000, discountedPrice: 800, discountPersent: 20, quantity: stock, imageUrl: 'https://example.com/x.jpg', topLavelCategory: 'Men', secondLavelCategory: 'Clothing', thirdLavelCategory: 'loadtest', size: [{ name: 'M', quantity: stock }] }, admin);
  if (r.status >= 300) throw new Error('create product ' + r.status + ' ' + await r.text());
  return (await r.json()).id;
}
async function shoppers(n, productId) {
  return pool(Array.from({ length: n }), 20, async (_, k) => {
    const email = `load-${Date.now()}-${k}-${Math.random().toString(36).slice(2)}@loadtest.local`;
    const token = (await (await j('POST', '/auth/signup', { firstName: 'Load', lastName: 'Test', email, password: 'Passw0rd!' })).json()).jwt;
    const add = await j('PUT', '/api/cart/add', { productId, size: 'M', quantity: 1 }, token);
    if (add.status !== 202) throw new Error('add to cart ' + add.status);
    return token;
  });
}
async function run(label, n, c, stock) {
  const id = await product(stock);
  const tokens = await shoppers(n, id);
  const t0 = performance.now();
  const results = await pool(tokens, c, async token => { const s = performance.now(); const r = await j('POST', '/api/orders/', ADDRESS, token); await r.arrayBuffer(); return { status: r.status, ms: performance.now() - s }; });
  const wall = (performance.now() - t0) / 1000;
  const p = await (await j('GET', '/api/products/id/' + id)).json();
  const byStatus = {}; for (const r of results) (byStatus[r.status] ||= []).push(r.ms);
  const lat = Object.fromEntries(Object.entries(byStatus).map(([s, xs]) => [s, { count: xs.length, p50: round(pct(xs, 50)), p95: round(pct(xs, 95)), p99: round(pct(xs, 99)) }]));
  const all = results.map(r => r.ms);
  console.log(JSON.stringify({ label, productId: id, requests: n, concurrency: c, stock, statuses: lat, all: { p50: round(pct(all, 50)), p95: round(pct(all, 95)), p99: round(pct(all, 99)) }, wallSeconds: round(wall), reqPerSec: round(n / wall), finalStock: p.quantity, finalSizeStock: (p.sizes || []).map(s => s.quantity) }));
}
await run('warm-up', 200, C, 1000);
for (let r = 1; r <= RUNS; r++) await run('run ' + r, N, C, STOCK);
