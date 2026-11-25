# LocalDrop Host API (prototype)
- `GET /` web client
- `GET /api/info` screen dimensions and capture state
- `GET /api/screen` latest JPEG frame (requires `X-Host-Pin`)
- `POST /api/input` JSON: `{action:'tap',x:0..1,y:0..1,screenWidth,screenHeight}` or `{action:'swipe',x1,y1,x2,y2,duration,screenWidth,screenHeight}`
- `GET /api/chat`, `POST /api/chat` JSON `{from,text}`
- `GET /api/files`, `POST /api/files?name=filename` raw request body
- `GET /download/name?pin=123456`
- `GET /api/devices`
