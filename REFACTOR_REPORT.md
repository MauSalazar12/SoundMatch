# Informe de refactorizaci?n

El ?rbol final, responsabilidades, entorno y comandos est?n en [README.md](README.md).

## Archivos movidos

Rutas relativas a `ai-engine`:

| Origen | Destino | Cambios de contenido |
| --- | --- | --- |
| src/audio_processing.py | src/audio/audio_processing.py | Ninguno; igualdad byte a byte |
| src/database.py | src/core/database.py | Ruta expl?cita de .env |
| src/clap_encoder.py | src/embeddings/clap_encoder.py | Checkpoint y versi?n desde config |
| src/generate_embeddings.py | src/embeddings/generate_embeddings.py | Imports y resoluci?n de audio_path |
| src/build_catalog.py | src/catalog/build_catalog.py | Rutas centralizadas, salida relativa conservada |
| src/import_songs.py | src/catalog/import_songs.py | Imports y rutas centralizadas |
| src/recommend.py | src/recommendations/recommend.py | Imports y versi?n centralizada |
| src/test_audio.py | tests/test_audio.py | Imports, ruta, main y correcci?n del diagn?stico |
| src/test_clap.py | tests/test_clap.py | Imports, ruta y main |
| prueba.py | scripts/prueba.py | Ninguno |

Archivos nuevos: `src/core/config.py`, los `__init__.py` de core, audio, embeddings,
catalog, recommendations, evaluation, tests y scripts; `requirements.txt`,
`README.md` e `REFACTOR_REPORT.md`. Se cre? el directorio vac?o `data/processed`.
El `src/__init__.py` existente se conserv?. No se movi? Compose.

## Decisiones

Imports absolutos `src.<responsabilidad>.<m?dulo>` y ejecuci?n con `python -m`.
No se modifica `sys.path`. Config usa `Path(__file__).resolve().parents[2]`;
no importa CLAP ni exige credenciales para resolver rutas.
Se conserva la salida `output/catalog.csv` para evitar una migraci?n innecesaria.
Las rutas relativas persistidas conservan su formato y se resuelven al consumirlas.
No se consolid? el peque?o helper duplicado `get_audio_path`: mantener ambos contratos
reduce el alcance de la refactorizaci?n. No se cambiaron los algoritmos ni el SQL.

## Verificaci?n ejecutada

- Sintaxis: todos los archivos Python de src, tests y scripts analizados con `ast.parse`.
- Imports: config, database, audio, los dos m?dulos de cat?logo, los dos de embeddings,
  recomendaciones y los dos scripts de pruebas importados correctamente.
- Archivos: tracks.csv, audio 000002.mp3, checkpoint y .env accesibles.
- Directorio alternativo: rutas resueltas correctamente fuera de ai-engine; consulta
  en transacci?n de solo lectura verific? 100 rutas de audio existentes, ninguna faltante.
- Audio: 1 438 876 muestras; dos fragmentos v?lidos de 480 000 muestras.
- CLAP: inferencia de un solo fragmento, vector (512,), norma 1.0; sin persistencia.
- Diagn?stico: PyTorch 2.14.1+cpu, GPU no disponible, imports correctos.
- PostgreSQL: conexi?n y SELECT current_database() correctos; driver psycopg, puerto 5433.
- Recomendaciones: demo para canci?n 2 ejecutada; cinco resultados, primera similitud 0.889.
- Docker: `docker compose config --quiet`, c?digo de salida 0; montaje SQL existente.
- Comparaci?n con originales: literales SQL id?nticos en todos los m?dulos movidos;
  audio_processing id?ntico byte a byte. Revisi?n del diff: par?metros de CLAP,
  normalizaci?n, procesamiento y l?mites conservados.
- Integridad: 8 018 archivos protegidos comparados por tama?o y fecha de modificaci?n,
  sin cambios. SHA-256 de .env, Compose, init.sql y checkpoint id?nticos antes/despu?s.

No se ejecutaron build_catalog, import_songs ni process_catalog. Su verificaci?n fue
est?tica/de imports, no de extremo a extremo. No se modificaron datos mediante SQL;
no existe una huella previa de toda la base que permita certificar cambios externos.
El dataset se verific? por tama?o/mtime, no con hashes de cada archivo.
No se instalaron dependencias ni se edit? .venv; Python se ejecut? con bytecode desactivado.

## Hallazgos y pendientes

- `test_audio` conten?a un error previo: cada segmento es dict, no ndarray. Corregido
  ?nicamente el acceso para imprimir su shape.
- La consulta b?sica de recomendaciones ya exist?a. Se conserva; no equivale a una
  etapa 2 completa ni a evaluaci?n/ranking avanzados implementados.
- CLAP emite advertencias de torch.meshgrid y carga inicial de RoBERTa; el checkpoint
  local carg? y la inferencia finaliz?. No se modificaron dependencias para silenciarlas.
- No se detectaron dependencias directas faltantes. requirements recoge las versiones
  instaladas, sin intentar una instalaci?n limpia ni fijar dependencias transitivas.
- Git detecta `C:/Users/mauri` como ra?z y este proyecto como no rastreado. No se hicieron
  commits ni se alter? ese repositorio. Conviene configurar un repositorio propio.
- La copia original del c?digo est? en `../.refactor-backup` respecto de soundMatch,
  junto con manifest de integridad y hashes; no contiene .env ni copia del dataset/modelo.
- Spring Boot, React, API, evaluaci?n y ampliaci?n de b?squeda/ranking quedan pendientes.
