# SoundMatch AI — backend de streaming (etapa 4)

Implementación incremental en los módulos existentes. Spring Boot es el gateway público; FastAPI conserva la instancia de CLAP, el preprocesamiento de catálogo, la búsqueda pgvector y el ranking de las etapas anteriores. No se modifica React, el esquema SQL, los embeddings almacenados ni el checkpoint. No se guarda el audio recibido.

## Arquitectura y compatibilidad

```text
Cliente → /ws/listen → validación PCM → buffer circular por sesión
  → ventanas 10 s / salto 5 s → ejecutor acotado → AiEngineClient existente
  → POST /internal/v1/recommendations/pcm → encoder CLAP compartido
  → RecommendationService.by_embedding → SQL y ranking existentes
  → JSON de recomendaciones por el mismo WebSocket
```

Versiones inspeccionadas: Java 17.0.13, Spring Boot 3.5.16, Maven 3.9.16, Python 3.12.4, FastAPI 0.143.2, PyTorch 2.14.1, NumPy 1.26.4 y librosa 0.11.0. Se mantienen las versiones del proyecto. El `pom.xml` ya tenía `spring-boot-starter-websocket`; no fue necesario modificarlo.

`SoundMatchController`, los DTOs REST, `routes.py`, el checkpoint `music_audioset_epoch_15_esc_90.14.pt`, `MODEL_VERSION`, la configuración HTSAT-base y las consultas SQL existentes se conservan. PostgreSQL se accede mediante el engine SQLAlchemy y `DATABASE_URL` existentes. El nuevo flujo llama a `RecommendationService.by_embedding`, que reutiliza `find_similar_embedding` y `rank_songs`.

El modelo utiliza ventanas de diez segundos. `STREAM_WINDOW_SECONDS` se valida como **10** en ambos servicios; cambiarla arbitrariamente alteraría el preprocesamiento del catálogo. El salto sí es configurable entre 1 y 10 segundos. Las ventanas contienen copias independientes del buffer circular.

## Protocolo WebSocket completo

Ruta nativa: `ws://localhost:8080/ws/listen`; usar `wss://` en producción. No se usa STOMP. Una conexión admite una sesión. No se envía audio antes de recibir `ready`.

### 1. Inicio: cliente → servidor

```json
{"type":"start","sampleRate":48000,"channels":1,"format":"pcm_f32le","windowSeconds":10,"hopSeconds":5}
```

- `sampleRate`: entero 44100 o 48000, dentro de la lista autorizada por el servidor.
- `channels`: entero 1; `format`: exactamente `pcm_f32le`.
- `windowSeconds` y `hopSeconds`: opcionales; si se envían, deben coincidir con la configuración del servidor. No permiten cambiarla.
- Se rechazan JSON inválido, controles mayores de 2048 bytes, tipos desconocidos, audio antes del inicio y un segundo `start`.

### 2. Confirmación: servidor → cliente

```json
{"type":"ready","sessionId":"aa7d8b04-1ef4-4dcc-b3ad-c399bbad9b65","sampleRate":48000,"windowSeconds":10,"hopSeconds":5}
```

El UUID se genera en el servidor. La frecuencia y el formato permanecen fijos hasta cerrar la conexión.

### 3. Audio: cliente → servidor

Mensajes WebSocket **binarios** con muestras mono float32 little-endian, sin cabecera, contenedor WAV/MP3 ni Base64. Cada mensaje debe tener longitud positiva, múltiplo de cuatro y como máximo 65536 bytes por defecto. Todas las muestras deben ser finitas y estar en `[-1, 1]`.

Enviar bloques al ritmo de captura; por ejemplo, 4800 muestras / 19200 bytes cada 100 ms a 48 kHz. Se aplica un token bucket por sesión: tasa máxima sostenida de 1,2 segundos de audio por segundo real y capacidad inicial/máxima de dos segundos. No se permite acumular crédito ilimitado durante una pausa. Una frecuencia real distinta de la declarada no puede deducirse de PCM sin metadatos: el cliente debe declarar la correcta.

La primera ventana aparece a las 480000 muestras (441000 a 44,1 kHz); las siguientes cada 240000 (220500). Veinte segundos de audio forman `0–10`, `5–15` y `10–20` segundos.

### 4. Procesamiento: servidor → cliente

```json
{"type":"processing","sequence":1,"windowStartSeconds":0.0,"windowEndSeconds":10.0}
```

Se envía cuando comienza el trabajo de esa ventana. Las secuencias se asignan al construir ventanas, empezando en 1; los tiempos se calculan desde muestras, no desde el reloj de red.

### 5. Resultado: servidor → cliente

```json
{
  "type":"recommendations",
  "sessionId":"aa7d8b04-1ef4-4dcc-b3ad-c399bbad9b65",
  "sequence":1,
  "windowStartSeconds":0.0,
  "windowEndSeconds":10.0,
  "processingMs":850,
  "recommendations":[{
    "song_id":123,"title":"Example Song","artist":"Example Artist",
    "genre":null,"similarity":0.89,"segment_index":0,
    "start_seconds":0.0,"end_seconds":10.0
  }]
}
```

Se reutilizan los elementos `ApiDtos.Recommendation` de REST, incluidos sus campos adicionales. Los tiempos dentro de cada recomendación pertenecen al fragmento del catálogo; los tiempos exteriores pertenecen al audio entrante. `processingMs` mide el trabajo HTTP/resultados y excluye la espera en cola.

Solo puede haber un trabajo activo/en cola y una ventana pendiente por sesión. Una ventana nueva reemplaza la pendiente anterior. Si un trabajo aún no comenzó, toma la pendiente más reciente. Si ya comenzó y aparece una ventana posterior, su resultado se descarta. Se permiten saltos de secuencia bajo carga; nunca se envía un resultado anterior a la última ventana aceptada. Cada trabajo vuelve a la cola global al terminar para compartir los trabajadores entre sesiones.

### 6. Finalización: cliente → servidor

```json
{"type":"stop"}
```

Libera el buffer, elimina el trabajo en cola, solicita la interrupción del HTTP activo y cierra con código WebSocket **1000**. No hay mensaje JSON `stopped`. Las ventanas parciales no se rellenan ni se procesan. También se limpian sesiones al desconectar, ante errores fatales, por inactividad, por duración máxima y al apagar Spring.

### 7. Errores: servidor → cliente

```json
{"type":"error","code":"INVALID_AUDIO","message":"Muestras PCM fuera de rango o no finitas"}
```

| Código | Comportamiento |
| --- | --- |
| `INVALID_PARAMETERS`, `INVALID_STATE`, `INVALID_AUDIO`, `RATE_LIMIT`, `MESSAGE_TOO_LARGE` | Error de protocolo/entrada: se cierra con 1008. |
| `OVERLOADED` | Admisión llena: cierre 1013. Cola llena: descarta esa ventana y permite continuar. |
| `SESSION_EXPIRED` | Expiración o inactividad: cierre 1011. Limpieza periódica cada segundo. |
| `CONNECTION_ERROR` | Error de transporte: limpieza y cierre 1011 cuando es posible. |
| `INFERENCE_TIMEOUT` | Timeout HTTP/IA; la conexión puede continuar con ventanas posteriores. |
| `AI_UNAVAILABLE`, `INTERNAL_ERROR`, `INFERENCE_ERROR`, `UNSUPPORTED_AUDIO`, `FILE_TOO_LARGE` | Error de IA/HTTP; se conserva la sesión si el transporte sigue abierto. |

El contenedor puede rechazar mensajes excesivos con **1009 antes del handler**; en ese caso no se garantiza JSON. Tampoco se garantiza un mensaje de error si la conexión ya se perdió. Las respuestas REST anteriores no cambian.

## Endpoint interno PCM

`POST /internal/v1/recommendations/pcm?limit=5`

| Encabezado | Contrato |
| --- | --- |
| `Content-Type` | `application/octet-stream` |
| `X-Sample-Rate` | 44100 o 48000, autorizado por el servidor |
| `X-Audio-Channels` | 1 |
| `X-Sequence` | entero >= 1 |
| `X-Session-Id` | UUID opcional, enviado por Spring |
| `X-Request-Id` | UUID de correlación opcional, enviado por Spring y devuelto por FastAPI |
| `X-Internal-Token` | obligatorio si `INTERNAL_API_TOKEN` tiene un valor |

El cuerpo tiene exactamente 1920000 bytes a 48 kHz o 1764000 a 44,1 kHz. `limit` admite 1–100; por defecto 5. La lectura está acotada incluso con transferencia chunked. Se validan duración, canales, formato, tamaño, muestras finitas y amplitud antes de llamar al encoder. Se remuestrea con librosa a 48 kHz y se aplica el filtro de silencio existente (`RMS < 0.001`).

Respuesta 200: el DTO REST existente, sin audio ni embeddings:

```json
{"reference_song_id":null,"strategy":"mean_query_best_match","recommendations":[{"song_id":123,"title":"Example Song","artist":"Example Artist","genre":null,"similarity":0.89,"segment_index":0,"start_seconds":0.0,"end_seconds":10.0}]}
```

Se mantiene el nombre de estrategia existente para compatibilidad; en PCM representa la consulta de una sola ventana. Los errores FastAPI usan `{"code":"...","message":"...","request_id":"..."}`: 400 parámetros/audio, 401 token, 413 tamaño, 415 contenido, 500 modelo/vector inválido, 503 modelo ocupado/no disponible o PostgreSQL, 504 timeout. Spring conserva su traducción segura de errores upstream; no refleja trazas ni secretos.

CLAP usa **la misma instancia y el mismo semáforo** que REST. Un timeout o desconexión no puede detener de forma segura una operación PyTorch ya iniciada: el slot se mantiene ocupado hasta que termina, evitando inferencias concurrentes sobre el modelo. No hay reintentos automáticos.

## Configuración

Variables de entorno de Spring (también disponibles en Compose). Spring local lee el entorno del proceso, no `.env` de Python.

| Variable | Valor inicial | Finalidad |
| --- | --- | --- |
| `WS_ALLOWED_ORIGINS` | `http://localhost:5173` | Orígenes explícitos separados por coma; se rechazan comodines. |
| `STREAM_SAMPLE_RATES` | `44100,48000` | Lista autorizada, compartida con FastAPI. |
| `STREAM_WINDOW_SECONDS` | `10` | Validado como diez, compartido con FastAPI. |
| `STREAM_HOP_SECONDS` | `5` | Salto entre ventanas, de 1 a 10. |
| `STREAM_MAX_SESSIONS` | `32` | Incluye conexiones esperando `start`. |
| `STREAM_MAX_DURATION` | `30m` | Duración máxima desde la conexión. |
| `STREAM_IDLE_TIMEOUT` | `30s` | Tiempo sin audio válido desde inicio/conexión. |
| `STREAM_MAX_BINARY_BYTES` | `65536` | Múltiplo de 4, hasta 192000. |
| `STREAM_AUDIO_RATE_LIMIT` | `1.2` | Segundos de audio/segundo real, rango 1–4. |
| `STREAM_BURST_SECONDS` | `2` | Capacidad del token bucket, rango 1–10. |
| `STREAM_WORKERS` | `1` | Trabajos HTTP simultáneos globales. |
| `STREAM_QUEUE_CAPACITY` | `8` | Trabajos globales en espera. |
| `STREAM_RECOMMENDATION_LIMIT` | `5` | Canciones solicitadas por ventana. |
| `STREAM_SEND_TIMEOUT_MS` | `5000` | Límite de envío WebSocket; incluye envío bloqueante de Tomcat. |
| `STREAM_SEND_BUFFER_BYTES` | `262144` | Límite del decorador de envío. |
| `INTERNAL_API_TOKEN` | vacío | Secreto compartido Spring/FastAPI; configurar en producción. |
| `AI_ENGINE_URL` | `http://localhost:8000` | URL existente reutilizada. |
| `AI_CONNECT_TIMEOUT` | `3s` | Timeout existente de conexión. |
| `AI_READ_TIMEOUT` | `100s` | Timeout existente de HTTP, también para PCM. |
| `INFERENCE_TIMEOUT_SECONDS` | `90` | Timeout de CLAP en FastAPI. |

Mantener inicialmente un trabajador Java y un proceso Uvicorn: FastAPI serializa el acceso al único encoder. Aumentar solo los trabajadores Java no aumenta la capacidad del modelo. La política de cola permite perder ventanas cuando la inferencia no alcanza la cadencia. Ajustar inactividad si se espera una última inferencia lenta después de dejar de enviar audio.

No existía autenticación de usuarios en este backend. El control de origen no autentica clientes nativos. Para producción, el gateway debe integrarse con la autenticación del despliegue y TLS/WSS. FastAPI debe permanecer en loopback/red privada, sin publicación en el proxy público. El Compose existente publica su puerto únicamente en `127.0.0.1`; el token compartido añade autenticación entre servicios. No registrar el token ni incluirlo en el frontend.

## Arranque local (PowerShell)

Usar el PostgreSQL y las credenciales existentes. No inicializar otro catálogo ni regenerar embeddings. Si el contenedor existente está detenido:

```powershell
docker start soundMatch-postgres
```

Terminal 1, desde `soundMatch/ai-engine`, con `.env` existente y checkpoint/caché RoBERTa disponibles:

```powershell
.\.venv\Scripts\python.exe -m uvicorn src.api.app:create_app --factory --host 127.0.0.1 --port 8000 --workers 1
```

Terminal 2, desde `soundMatch/backend`:

```powershell
$env:AI_ENGINE_URL='http://localhost:8000'
mvn spring-boot:run
```

Comprobar `http://localhost:8000/health/ready` y `http://localhost:8080/actuator/health/readiness`. La carga del modelo ocurre una vez al iniciar FastAPI. Establecer `INTERNAL_API_TOKEN` con el mismo valor en ambos procesos cuando se use autenticación interna.

También se conserva el perfil Docker existente, desde `ai-engine`, tras verificar `.env` y el volumen de catálogo existente:

```powershell
docker compose --profile stage3 up -d --build
```

El nombre del perfil se mantiene por compatibilidad. Los Dockerfiles no fueron modificados. No utilizar `down -v` ni recrear el volumen para instalar esta etapa.

## Cliente de prueba sin micrófono ni frontend

Desde `soundMatch/backend`, con Java 17:

```powershell
java scripts/StreamPcm.java
```

El cliente genera un tono mono de 440 Hz a 48 kHz, transmite veinte segundos en bloques de 100 ms, muestra los mensajes, espera el resultado de la secuencia 3 y envía `stop`. No necesita dependencias adicionales. Un tono permite comprobar el transporte y la inferencia; no sirve para evaluar calidad musical.

También acepta URL, origen y un archivo PCM existente de exactamente veinte segundos:

```powershell
java scripts/StreamPcm.java ws://localhost:8080/ws/listen http://localhost:5173 C:\audio\fragmento.f32le
```

No acepta WAV/MP3 en este cliente. Para validar automáticamente las tres ventanas sin depender de la velocidad de CLAP se usa `StreamingWebSocketTest`, que arranca Spring y un servidor HTTP simulado, y verifica bytes, encabezados, secuencias, resultados y limpieza.

## Pruebas

Desde `backend`:

```powershell
mvn test
```

Desde `ai-engine`:

```powershell
.\.venv\Scripts\python.exe -m pytest -q -m 'not integration'
$env:RUN_STAGE4_MODEL='1'
$env:RUN_STAGE4_DATABASE='1'
.\.venv\Scripts\python.exe -m pytest -q tests/test_stage4_integration.py
```

Las dos pruebas de integración usan el checkpoint real. La segunda consulta el catálogo existente sin escribir. Se omiten por defecto. Las pruebas REST reales previas se ejecutan con ambos servicios activos y `RUN_STAGE3_INTEGRATION=1`.

En esta máquina Python 3.12 encontró un error de WMI de Windows al consultar la arquitectura. Para ejecutar pruebas sin esa consulta, se utilizó este ajuste **solo en el proceso de prueba**, sin alterar el código de producción ni las dependencias:

```powershell
.\.venv\Scripts\python.exe -c "import platform; platform._wmi_query=lambda *a: (_ for _ in ()).throw(OSError('WMI disabled for test process')); import pytest; raise SystemExit(pytest.main(['-q','-m','not integration']))"
```

Los resultados finales y la relación de archivos se registran en [STAGE4_REPORT.md](STAGE4_REPORT.md).

## Observabilidad

Micrometer/Actuator existente registra:

- Gauges: `soundmatch.streaming.sessions.active`, `.jobs.active`, `.queue.size`.
- Contadores: `soundmatch.streaming.windows.assembled`, `.windows.dropped` (motivos `superseded`, `queue_full`, `stale_result`) y `.errors` (etiqueta `code`).
- Timers: `soundmatch.streaming.queue.wait`, `.processing`, `.total`.

Los nombres abreviados anteriores comparten el prefijo `soundmatch.streaming`. Los IDs de sesión no se usan como etiquetas para evitar cardinalidad ilimitada. Actuator conserva la exposición existente de `health,info`; para consultar métricas en un entorno protegido, configurar `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics` y acceder a `/actuator/metrics/soundmatch.streaming.sessions.active`.

Spring emite logs JSON de sesiones, errores, secuencia, frecuencia, posiciones de ventana, espera y duración. FastAPI emite `pcm_preprocess`, `pcm_inference`, `pcm_search`, `pcm_request` y `pcm_error` con tiempos separados y correlación por `X-Request-Id`/`X-Session-Id`. El tiempo HTTP existente incluye el trabajo interno. No se registran muestras, embeddings completos ni credenciales.

## Mejoras pendientes y límites deliberados

- Suavizado EMA no implementado: requeriría estado por sesión en FastAPI y una política explícita de caducidad/afinidad. El ranking existente permanece intacto.
- Las colas y sesiones son locales a un proceso. Escalar réplicas requiere mantener cada WebSocket en su instancia y dimensionar la capacidad CLAP.
- El cálculo PyTorch iniciado no se interrumpe físicamente al cerrar el cliente; se cancelan la espera Java y los trabajos pendientes, y se descartan resultados tardíos.
- La admisión del modelo devuelve ocupado en vez de acumular otra cola de inferencia en Python; REST y streaming comparten esa capacidad.
- La calidad musical y la latencia sostenida bajo múltiples usuarios requieren evaluación con audio representativo y el hardware de producción.

Referencia técnica: [API WebSocket de Spring](https://docs.spring.io/spring-framework/reference/web/websocket/server.html), utilizada para la configuración del contenedor, los orígenes y la serialización de envíos.
