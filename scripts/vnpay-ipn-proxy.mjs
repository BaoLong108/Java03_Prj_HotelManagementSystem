import http from 'node:http'

const path = '/api/v1/payments/vnpay/ipn'
const port = Number(process.env.VNPAY_IPN_PROXY_PORT ?? '5188')
const backendPort = Number(process.env.PORT ?? '8080')

const server = http.createServer({
  maxHeaderSize: 8192,
  headersTimeout: 8000,
  requestTimeout: 10000,
  connectionsCheckingInterval: 1000,
}, (request, response) => {
  const target = request.url ?? ''
  if (request.method !== 'GET' || (target !== path && !target.startsWith(path + '?'))) {
    response.writeHead(404).end()
    return
  }
  if (target.length > 4096) {
    response.writeHead(414).end()
    return
  }
  if (request.headers['transfer-encoding'] || Number(request.headers['content-length'] ?? 0) !== 0) {
    response.writeHead(400).end()
    return
  }

  const upstream = http.get({
    hostname: '127.0.0.1',
    port: backendPort,
    path: target,
    timeout: 8000,
  }, (result) => {
    response.writeHead(result.statusCode ?? 502, {
      'Content-Type': result.headers['content-type'] ?? 'application/json',
      'Cache-Control': 'no-store',
    })
    result.on('error', () => response.destroy())
    result.pipe(response)
  })
  upstream.on('timeout', () => upstream.destroy())
  upstream.on('error', () => {
    if (response.headersSent) response.destroy()
    else response.writeHead(502).end()
  })
  response.on('close', () => upstream.destroy())
})

server.listen(port, '127.0.0.1', () => {
  console.log(`VNPay IPN proxy listening at 127.0.0.1:${port}`)
})
