# Informe de implementación — etapa 3

Fecha: 15 de octubre de 2026. La implementación conserva el catálogo y los
embeddings existentes y añade la API Spring Boot → FastAPI → CLAP/pgvector.

## Arquitectura encontrada y reutilizada

- Python 3.12, SQLAlchemy con psycopg y `.env`, PostgreSQL 17 en Docker.
- CLAP HTSAT-base, fusion desactivada, checkpoint local de 2.35 GB, salida L2
  de 512 dimensiones. Se mantiene el modo de inferencia de PyTorch.
- Preprocesamiento mono/48 kHz, ventanas completas de diez segundos, filtro RMS.
- Tabla `songs` (100 filas), `audio_embeddings` (333 filas), globales y fragmentos.
- Búsqueda coseno de etapa 2 y ranking por mejor vecino sin canciones duplicadas.
- No había backend Java ni FastAPI. El README anterior describía un estado anterior
  a la etapa 2. HNSW estaba definido en SQL, pero no instalado en la base real.

La API por ID reutiliza el segmento 0; la de audio promedia ventanas válidas,
normaliza y consulta el mismo buscador/ranking. Se documentan ambas estrategias
en las respuestas. Spring no carga ML ni accede directamente a la base.

## Archivos creados

Rutas relativas a `soundMatch`:

| Grupo | Archivos |
| --- | --- |
| Raíz | `.gitignore`, `STAGE3_REPORT.md` |
| API Python | `ai-engine/src/api/{__init__,app,settings,schemas,errors,middleware,routes}.py` |
| Servicios Python | `ai-engine/src/services/{__init__,inference,recommendations}.py` |
| Persistencia Python | `ai-engine/src/persistence/{__init__,catalog}.py` |
| Vectores compartidos | `ai-engine/src/embeddings/vectors.py` |
| Pruebas Python | `ai-engine/tests/{conftest,test_api,test_inference_service,test_recommendation_service,test_stage3_integration}.py` |
| Verificación de datos | `ai-engine/scripts/verify_stage3_database.py` |
| Entorno Python | `ai-engine/requirements-api.txt`, `requirements-dev.txt`, `pytest.ini`, `.env.example` |
| Docker | `ai-engine/Dockerfile`, `ai-engine/.dockerignore`, `backend/Dockerfile`, `backend/.dockerignore` |
| Build Java | `backend/pom.xml` |
| Aplicación Java | `backend/src/main/java/com/soundmatch/SoundMatchApplication.java` |
| Controlador/servicio | `controller/SoundMatchController.java`, `service/SoundMatchService.java` bajo el paquete Java |
| Cliente/DTOs | `client/AiEngineClient.java`, `dto/ApiDtos.java` |
| Excepciones Java | `exception/ApiException.java`, `exception/GlobalExceptionHandler.java` |
| Configuración Java | `config/AiEngineProperties.java`, `HttpClientConfig.java`, `RequestLoggingFilter.java`, `AiEngineHealthIndicator.java` |
| Recursos Java | `backend/src/main/resources/application.yml` |
| Pruebas Java | `backend/src/test/java/com/soundmatch/client/{AiEngineClientTest,AiEngineHttpTest}.java`, `controller/SoundMatchControllerTest.java`, `service/SoundMatchServiceTest.java` |

Los logs, el JAR compilado y las huellas locales `.stage3-*` quedan ignorados.

## Archivos modificados

- `README.md`: arquitectura, esquema real, instalación, variables, Docker,
  contratos, Swagger/Postman, errores, pruebas y limitaciones.
- `ai-engine/docker-compose.yml`: servicios bajo perfil `stage3`, credenciales
  desde entorno, mounts de sólo lectura para modelo/caché. Conserva contenedor,
  puerto, montaje SQL y volumen existente, cuyo nombre ahora es explícito.
- `ai-engine/.env` **local y no versionado**: se añaden claves de Compose derivadas
  de su URL existente y la ruta local de caché. Se conservan los valores previos;
  no se exponen credenciales en documentación, ejemplos o respuestas HTTP.
- `src/embeddings/clap_encoder.py`: checkpoint/dispositivo inyectables,
  validación VECTOR(512), reutilización de normalización y menos salida al cargar.
- `src/embeddings/generate_embeddings.py`: importa los helpers extraídos sin
  cambiar la ingesta ni las firmas públicas anteriores.
- `src/recommendations/similarity.py`: una consulta común de vecinos para referencias
  guardadas y vectores nuevos; filtro explícito de versión. Mantiene los resultados
  y ranking de etapa 2.
- `src/evaluation/evaluate.py`: reemplaza el import inexistente `get_connection`
  por el `engine` real; mismo cálculo de precisión.
- `tests/test_clap.py`: importa CLAP dentro de su ejecución manual, evitando
  importarlo durante la colección de pruebas unitarias.

No se modifican `audio_processing.py`, `ranking.py`, `recommend.py`, los archivos
SQL, los metadatos, el dataset ni el checkpoint. No se ejecutan scripts de ingesta.

## Endpoints

En ambos servicios:

- `GET /api/v1/songs?page=0&size=20`, filtros opcionales título/artista/género.
- `GET /api/v1/songs/{id}`.
- `GET /api/v1/recommendations/{id}?limit=5`.
- `POST /api/v1/recommendations/audio?limit=5`, multipart campo `file`.

FastAPI: `/health`, `/health/ready`, `/docs`, `/openapi.json`.
Spring: `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`, `/swagger-ui.html`, `/v3/api-docs`.

Errores JSON con `code`, `message`, `request_id`: 400, 404, 413, 415, 500, 503,
504. Timeouts configurables; sin reintentos de inferencia. Una sola inferencia por
proceso y turno retenido hasta terminar incluso tras un timeout del consumidor.

## Evidencia de verificación

- **Java:** 26 pruebas, 0 fallos, 0 errores. `mvn package` completado con Java 17;
  JAR `backend/target/soundmatch-backend-0.1.0.jar` generado.
- **Python:** 30 pruebas unitarias en la suite, más una prueba adicional de límite
  de cuerpo chunked; todas pasaron. Modelos simulados; sin cargar CLAP en unitarias.
- **Integración real:** 2 pruebas pasaron en 20.83 s, sin mocks de servicios, modelo
  ni PostgreSQL. Consulta pública y directa por ID coinciden; catálogo, filtros,
  errores, OpenAPI y readiness verificados.
- **Inferencia real:** MP3 FMA `000002.mp3` enviado a Spring, retransmitido a
  FastAPI y procesado con CLAP en CPU; inferencia 11.13 s y respuesta HTTP pública
  aproximadamente 11.9 s. Devuelve canciones únicas ordenadas por similitud.
- **Carga:** checkpoint y recursos auxiliares locales cargaron con modo offline.
- **ID 2:** primera recomendación real `song_id=5`, “This World”, AWOL, similitud
  `0.8852873581305315`, segmento 0; la canción original queda excluida.
- **Compose e imágenes:** `docker compose --profile stage3 config --quiet` pasó.
  Las dos imágenes se construyeron correctamente: `ai-engine-backend` y
  `ai-engine-ai-engine`. La imagen Python incluye PyTorch CPU y libpq para psycopg,
  sin incorporar el dataset, checkpoint o credenciales.
- **Spring en Docker:** el contenedor arrancó con Java 17; liveness y OpenAPI
  devolvieron 200. Con AI deliberadamente ausente, `/api/v1/songs/2` devolvió
  correctamente 503. El contenedor temporal se detuvo y eliminó tras la prueba.
- **AI en Docker/Linux:** imports de PyTorch 2.14.1+cpu y CLAP correctos; checkpoint
  montado accesible, tokenizer RoBERTa cargado offline, OpenAPI generado y consultas
  de sólo lectura a PostgreSQL devolvieron 100 canciones y 333 embeddings. El
  contenedor temporal se eliminó automáticamente al terminar.
- **Compatibilidad anterior:** `recommend(2, 5)` devolvió cinco resultados y
  `precision_at_k(2)` se ejecutó correctamente con resultado 0.6.
- **Integridad:** huellas de todas las filas coinciden con la captura anterior:

| Tabla | Filas | Huella MD5 del contenido ordenado por ID |
| --- | ---: | --- |
| songs | 100 | `501a4ea51b6a29cc92e8814e372344bb` |
| audio_embeddings | 333 | `72a5883cc486c44e7457c2f2bafc543d` |

La comprobación usa `row_to_json` de todas las columnas, incluido el vector, en
transacciones de sólo lectura. El script permite repetirla sin sobrescribir la
captura inicial. El volumen PostgreSQL no se elimina ni se cambia por otro.

## Límites operativos

- Equipo de validación: 8 GB RAM. Durante carga concurrente de modelo, Java y pruebas
  quedaron unos 400 MB libres y hubo paginación severa. Las pruebas se completaron
  secuencialmente y Java funcionó con heap máximo de 192 MB. Reservar varios GB
  para el motor y no multiplicar workers. Los tiempos no son un benchmark estable.
- Timeout de inferencia responde 504 sin cancelar el hilo PyTorch. No se aceptan
  inferencias adicionales hasta que el trabajo anterior termina.
- Sólo se aceptan audios con al menos una ventana completa no silenciosa; no hay
  padding de fragmentos cortos. Máximo predeterminado: 20 MiB y 60 s.
- La búsqueda considera hasta 1,000 vecinos y devuelve hasta el límite solicitado;
  para un catálogo mayor se debe medir el efecto de candidatos e índices.
- Advertencias heredadas/de dependencias: `torch.meshgrid`, inicialización de
  RoBERTa y deprecaciones de audioread y TestClient/httpx. No impidieron las pruebas.
- No se valida GPU ni carga sostenida multiusuario. Los datos de catálogo se
  devuelven tal como están; esta etapa no corrige metadatos históricos.
- La inferencia completa entre servicios se validó en ejecución local. Dentro de
  Docker se comprobaron construcción, arranque de Spring, dependencias AI, mounts,
  caché y conexión SQL; no se repitió la inferencia completa del modelo en Linux.
  Docker Desktop dispone de aproximadamente 3.7 GiB de RAM en este equipo.

## Estado al finalizar

PostgreSQL permanece activo y saludable en su contenedor y volumen originales.
Las aplicaciones locales y los contenedores temporales de comprobación quedan
detenidos para liberar memoria. Las dos imágenes y el JAR están disponibles para
el siguiente arranque. No se ejecutó `down -v`, no se recreó PostgreSQL y no se
eliminaron volúmenes. Las credenciales reales sólo permanecen en el `.env` local.

## Reproducción manual

Desde dos terminales, arrancar FastAPI y Spring según el README. Esperar readiness.
Abrir Swagger público y ejecutar en orden catálogo, canción 2, recomendaciones
por ID y multipart con `ai-engine/data/fma_small/000/000002.mp3`.
En Postman: POST a `http://localhost:8080/api/v1/recommendations/audio?limit=5`,
Body → form-data → clave `file`, tipo File. No fijar manualmente el boundary.

Los comandos exactos, variables y ejemplos están en [README.md](README.md),
secciones “Ejecución local”, “Verificar desde Postman o Swagger” y “Pruebas”.
