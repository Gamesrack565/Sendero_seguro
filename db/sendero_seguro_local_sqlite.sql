-- =====================================================================
-- SENDERO SEGURO — Diseño de Datos (Entregable 3)
-- Modelo local de la app Android · SQLite (Room)
-- ---------------------------------------------------------------------
-- Permite operar sin internet: alertas, cola de sincronización, paquetes
-- de la red malla, caché de mapas y datos de referencia (RF-14, RF-17,
-- RF-25, RNF15) y los trayectos compartidos (RF-23). Las fechas son INTEGER en milisegundos Unix (UTC), los
-- booleanos son INTEGER 0/1 y los id UUID son TEXT.
-- Secretos (token de sesión, hash del PIN, llave de cifrado) NO van aquí:
-- se guardan en el Android Keystore / EncryptedSharedPreferences.
-- =====================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- 1. SESIÓN, CONFIGURACIÓN Y DATOS DE REFERENCIA
-- ---------------------------------------------------------------------

-- Última sesión válida: permite usar las funciones locales sin conexión (CU-02, flujo alterno 3a).
CREATE TABLE sesion_local (
  usuario_id     TEXT PRIMARY KEY,
  nombre         TEXT NOT NULL,
  rol            TEXT NOT NULL CHECK (rol IN ('INTEGRANTE','COORDINADOR','OPERADOR','ADMINISTRADOR')),
  valida_hasta   INTEGER NOT NULL,
  actualizado_en INTEGER NOT NULL
);

-- Pares clave-valor: cuenta_regresiva_seg, malla_ttl_saltos, modo_conectividad, version_catalogo, etc.
CREATE TABLE config_local (
  clave TEXT PRIMARY KEY,
  valor TEXT NOT NULL
);

-- Catálogo vigente descargado, para clasificar la alerta sin conexión (RF-6).
CREATE TABLE catalogo_version_local (
  version_catalogo_id INTEGER PRIMARY KEY,
  numero              INTEGER NOT NULL,
  descargada_en       INTEGER NOT NULL
);

-- Catálogo único de la versión vigente: sirve para clasificar alertas y reportes (RF-6).
CREATE TABLE tipo_incidente_local (
  id            INTEGER PRIMARY KEY,
  codigo        TEXT NOT NULL UNIQUE,
  nombre        TEXT NOT NULL,
  categoria     TEXT CHECK (categoria IN ('FISICO','SOCIAL','DIGITAL')),   -- nulo solo en la genérica
  prioridad     INTEGER NOT NULL CHECK (prioridad BETWEEN 1 AND 5),
  aplica_a      TEXT NOT NULL CHECK (aplica_a IN ('ALERTA','REPORTE','AMBOS')),
  datos_minimos TEXT NOT NULL DEFAULT '[]',
  es_generica   INTEGER NOT NULL DEFAULT 0 CHECK (es_generica IN (0,1)),
  CHECK ((es_generica = 1) = (categoria IS NULL)),
  CHECK (es_generica = 0 OR aplica_a = 'ALERTA')
);

-- Copia local de los contactos: el SMS legible se envía aunque no haya internet (RF-3, RF-16).
CREATE TABLE contacto_confianza_local (
  id           TEXT PRIMARY KEY,
  nombre       TEXT NOT NULL,
  telefono     TEXT NOT NULL UNIQUE,
  prioridad    INTEGER NOT NULL CHECK (prioridad >= 1),
  con_cuenta   INTEGER NOT NULL DEFAULT 0 CHECK (con_cuenta IN (0,1)),   -- 1 = usa la app; 0 = solo teléfono (SMS o WhatsApp)
  nivel_acceso TEXT NOT NULL DEFAULT 'SEGUIMIENTO' CHECK (nivel_acceso IN ('SOLO_AVISO','SEGUIMIENTO')),
  estado_confirmacion TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK (estado_confirmacion IN ('PENDIENTE','CONFIRMADO')),
  canal_preferido TEXT NOT NULL DEFAULT 'SMS' CHECK (canal_preferido IN ('PUSH','SMS','WHATSAPP')),
  activo       INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1)),
  sincronizado INTEGER NOT NULL DEFAULT 0 CHECK (sincronizado IN (0,1)),
  CHECK (canal_preferido <> 'PUSH' OR con_cuenta = 1)
);

-- ---------------------------------------------------------------------
-- 2. ALERTAS Y REPORTES PROPIOS
-- ---------------------------------------------------------------------

-- El id es el mismo UUID que se envía al backend y que identifica el paquete en la malla.
CREATE TABLE alerta_local (
  id                 TEXT PRIMARY KEY,
  folio              TEXT,                                 -- ALR-2026-004817; llega al sincronizar
  tipo_incidente_id  INTEGER REFERENCES tipo_incidente_local(id),
  tipo_sugerido_id   INTEGER REFERENCES tipo_incidente_local(id),   -- sugerencia de IA-1 en el teléfono
  confianza_sugerencia REAL CHECK (confianza_sugerencia BETWEEN 0 AND 1),
  descripcion_detalle TEXT,                                -- JSON {qué, dónde, quién, cuándo}
  detonador          TEXT NOT NULL DEFAULT 'BOTON' CHECK (detonador IN ('BOTON','ATAJO_FISICO','WIDGET','VOZ','CAIDA','TEMPORIZADOR')),
  nivel_bateria      INTEGER CHECK (nivel_bateria BETWEEN 0 AND 100),
  firma_paquete      TEXT,                                 -- firma con la llave del dispositivo; viaja con el paquete de la malla
  trayecto_id        TEXT REFERENCES trayecto_local(id),   -- trayecto cuyo temporizador la activó
  estado             TEXT NOT NULL CHECK (estado IN (
                       'CREADA','EN_CUENTA_REGRESIVA','PENDIENTE_ENVIO','EXPIRADA',
                       'ENVIADA','ASIGNADA','ESCALADA','ACUSADA','EN_ATENCION',
                       'ATENDIDA','CERRADA','CANCELADA')),
  descripcion        TEXT,
  audio_uri          TEXT,
  canal_actual       TEXT CHECK (canal_actual IN ('INTERNET','MALLA','SMS','COLA')),
  activada_en        INTEGER NOT NULL,
  enviada_en         INTEGER,
  cancelada_en       INTEGER,
  motivo_cancelacion TEXT CHECK (motivo_cancelacion IN ('YA_ESTOY_A_SALVO','FALSA_ALARMA','ACTIVACION_ACCIDENTAL')),
  sincronizada       INTEGER NOT NULL DEFAULT 0 CHECK (sincronizada IN (0,1)),
  CHECK ((tipo_sugerido_id IS NULL) = (confianza_sugerencia IS NULL)),
  CHECK (detonador <> 'TEMPORIZADOR' OR trayecto_id IS NOT NULL)
);
CREATE INDEX ix_alerta_local_estado ON alerta_local (estado, activada_en DESC);

-- Intentos de cancelar durante la cuenta regresiva. La alerta aún no existe en el servidor,
-- así que se guardan aquí y se suben al sincronizar la alerta.
CREATE TABLE intento_cancelacion_local (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  alerta_id    TEXT NOT NULL REFERENCES alerta_local(id) ON DELETE CASCADE,
  motivo       TEXT CHECK (motivo IN ('YA_ESTOY_A_SALVO','FALSA_ALARMA','ACTIVACION_ACCIDENTAL')),
  metodo       TEXT NOT NULL CHECK (metodo IN ('PIN','BIOMETRIA')),
  exitoso      INTEGER NOT NULL CHECK (exitoso IN (0,1)),
  es_coaccion  INTEGER NOT NULL DEFAULT 0 CHECK (es_coaccion IN (0,1)),   -- PIN de coacción: se simula la cancelación
  intentado_en INTEGER NOT NULL,
  sincronizado INTEGER NOT NULL DEFAULT 0 CHECK (sincronizado IN (0,1)),
  CHECK (NOT (es_coaccion = 1 AND exitoso = 1))
);
CREATE INDEX ix_intento_local_pendiente ON intento_cancelacion_local (sincronizado, alerta_id);

CREATE TABLE ubicacion_alerta_local (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  alerta_id    TEXT NOT NULL REFERENCES alerta_local(id) ON DELETE CASCADE,
  latitud      REAL NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud     REAL NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  precision_m  REAL CHECK (precision_m >= 0),
  velocidad_ms REAL CHECK (velocidad_ms >= 0),
  rumbo_grados INTEGER CHECK (rumbo_grados BETWEEN 0 AND 359),
  bateria      INTEGER CHECK (bateria BETWEEN 0 AND 100),
  fuente       TEXT NOT NULL CHECK (fuente IN ('GNSS','WIFI','CELULAR','ULTIMA_CONOCIDA')),
  capturada_en INTEGER NOT NULL,
  enviada      INTEGER NOT NULL DEFAULT 0 CHECK (enviada IN (0,1)),
  UNIQUE (alerta_id, capturada_en, fuente)
);

CREATE TABLE reporte_local (
  id              TEXT PRIMARY KEY,
  folio           TEXT,                                    -- REP-2026-000391; llega al sincronizar
  tipo_incidente_id INTEGER NOT NULL REFERENCES tipo_incidente_local(id),
  tipo_sugerido_id INTEGER REFERENCES tipo_incidente_local(id),     -- sugerencia de IA-1 en el teléfono
  confianza_sugerencia REAL CHECK (confianza_sugerencia BETWEEN 0 AND 1),
  descripcion     TEXT NOT NULL,
  latitud         REAL NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud        REAL NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  precision_m     REAL CHECK (precision_m >= 0),
  es_anonimo      INTEGER NOT NULL DEFAULT 0 CHECK (es_anonimo IN (0,1)),
  estado          TEXT NOT NULL CHECK (estado IN (
                    'BORRADOR','PENDIENTE','EN_COLA_LOCAL','PUBLICADO',
                    'RECHAZADO','RETIRADO','DESCARTADO')),
  foto_uris       TEXT NOT NULL DEFAULT '[]',          -- arreglo JSON de archivos locales
  creado_en       INTEGER NOT NULL,
  CHECK ((tipo_sugerido_id IS NULL) = (confianza_sugerencia IS NULL))
);

-- Trayecto compartido propio: ruta segura o más corta que la persona inicia (RF-23).
-- El id es el mismo UUID que se envía al backend.
CREATE TABLE trayecto_local (
  id                TEXT PRIMARY KEY,
  tipo_ruta         TEXT NOT NULL DEFAULT 'SEGURA' CHECK (tipo_ruta IN ('SEGURA','MAS_CORTA')),
  origen_latitud    REAL NOT NULL CHECK (origen_latitud  BETWEEN -90  AND 90),
  origen_longitud   REAL NOT NULL CHECK (origen_longitud BETWEEN -180 AND 180),
  destino_latitud   REAL NOT NULL CHECK (destino_latitud  BETWEEN -90  AND 90),
  destino_longitud  REAL NOT NULL CHECK (destino_longitud BETWEEN -180 AND 180),
  distancia_m       INTEGER CHECK (distancia_m > 0),
  duracion_est_min  INTEGER CHECK (duracion_est_min > 0),
  tolerancia_desviacion_m INTEGER CHECK (tolerancia_desviacion_m > 0),
  intervalo_checkin_min   INTEGER CHECK (intervalo_checkin_min > 0),
  ruta_planificada  TEXT,                                  -- GeoJSON LineString
  compartido        INTEGER NOT NULL DEFAULT 0 CHECK (compartido IN (0,1)),   -- si lo comparte con sus contactos
  estado            TEXT NOT NULL DEFAULT 'EN_CURSO' CHECK (estado IN ('EN_CURSO','FINALIZADO','CANCELADO')),
  iniciado_en       INTEGER NOT NULL,
  finalizado_en     INTEGER,
  CHECK ((estado = 'EN_CURSO') = (finalizado_en IS NULL))
);

-- ---------------------------------------------------------------------
-- 3. COLA DE SINCRONIZACIÓN (store and forward, RF-17, A-05, RNF15)
-- ---------------------------------------------------------------------

CREATE TABLE cola_sincronizacion (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  tipo                 TEXT NOT NULL CHECK (tipo IN ('ALERTA','UBICACION','ACUSE','REPORTE','TRAYECTO')),
  entidad_id           TEXT NOT NULL,
  prioridad            INTEGER NOT NULL CHECK (prioridad IN (0,1)),   -- 0 = alertas y acuses, 1 = reportes y trayectos
  payload              TEXT NOT NULL,
  tamano_bytes         INTEGER NOT NULL CHECK (tamano_bytes > 0),
  estado               TEXT NOT NULL DEFAULT 'PENDIENTE'
                         CHECK (estado IN ('PENDIENTE','ENVIANDO','SINCRONIZADO','DESCARTADO')),
  intentos             INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
  creada_en            INTEGER NOT NULL,
  ttl_expira_en        INTEGER NOT NULL,
  proximo_reintento_en INTEGER,
  UNIQUE (tipo, entidad_id),
  CHECK ((tipo IN ('ALERTA','UBICACION','ACUSE') AND prioridad = 0)
      OR (tipo IN ('REPORTE','TRAYECTO')         AND prioridad = 1)),
  -- Vencimiento máximo por tipo: acuse 2 h, alerta y ubicación 72 h, trayecto 72 h, reporte 6 días.
  CHECK (ttl_expira_en <= creada_en + CASE tipo WHEN 'ACUSE'   THEN 7200000
                                                WHEN 'REPORTE' THEN 518400000
                                                ELSE 259200000 END)
);
CREATE INDEX ix_cola_pendientes ON cola_sincronizacion (prioridad, creada_en)
  WHERE estado IN ('PENDIENTE','ENVIANDO');

-- Límite de 20 MB para lo pendiente de enviar (RNF15).
CREATE TRIGGER trg_cola_limite_20mb
BEFORE INSERT ON cola_sincronizacion
WHEN (SELECT COALESCE(SUM(tamano_bytes), 0) FROM cola_sincronizacion
      WHERE estado IN ('PENDIENTE','ENVIANDO')) + NEW.tamano_bytes > 20971520
BEGIN
  SELECT RAISE(ABORT, 'La cola offline excede 20 MB (RNF15)');
END;

-- ---------------------------------------------------------------------
-- 4. RED MALLA P2P  (RF-14, RF-15, A-01, E-04)
-- ---------------------------------------------------------------------

-- La clave primaria hace de «tabla de vistos»: un paquete con ID ya
-- registrado se descarta como duplicado.
CREATE TABLE paquete_malla (
  paquete_id        TEXT PRIMARY KEY,
  origen            TEXT NOT NULL CHECK (origen IN ('PROPIO','RETRANSMITIDO')),
  estado            TEXT NOT NULL CHECK (estado IN ('CREADO','DIFUNDIENDO','ENTREGADO','EXPIRADO')),
  ttl_restante      INTEGER NOT NULL CHECK (ttl_restante >= 0),
  saltos            INTEGER NOT NULL DEFAULT 0 CHECK (saltos >= 0),
  payload_cifrado   BLOB,
  primera_vez_en    INTEGER NOT NULL,
  ultimo_reenvio_en INTEGER,
  ack_en            INTEGER
);
CREATE INDEX ix_paquete_malla_antiguedad ON paquete_malla (primera_vez_en);

-- ---------------------------------------------------------------------
-- 5. CACHÉ PARA OPERAR SIN CONEXIÓN  (RF-21, RF-22, RF-25, CU-36)
-- ---------------------------------------------------------------------

CREATE TABLE cache_capa_riesgo (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  version_capa    INTEGER NOT NULL,
  periodo_dias    INTEGER NOT NULL CHECK (periodo_dias > 0),
  tipo_incidente_id INTEGER,                              -- NULL = todos los tipos
  geojson         TEXT NOT NULL,
  pronostico      TEXT,                                   -- JSON del pronóstico por celda y franja (IA-2)
  tamano_bytes    INTEGER NOT NULL CHECK (tamano_bytes > 0),
  descargada_en   INTEGER NOT NULL
);
CREATE UNIQUE INDEX uq_cache_capa ON cache_capa_riesgo (periodo_dias, COALESCE(tipo_incidente_id, 0));

-- Grafo y riesgo por tesela (prefijo geohash), solo de las zonas habituales del usuario.
CREATE TABLE cache_grafo_tesela (
  tesela        TEXT PRIMARY KEY,
  version_grafo INTEGER NOT NULL,
  version_capa  INTEGER NOT NULL,
  contenido     BLOB NOT NULL,                            -- nodos, tramos y riesgo, comprimido
  tamano_bytes  INTEGER NOT NULL CHECK (tamano_bytes > 0),
  descargada_en INTEGER NOT NULL,
  ultimo_uso_en INTEGER NOT NULL                          -- para depurar las menos usadas al llegar al límite
);

-- Paquete de mapas y ruteo descargado para trabajar sin conexión (P12).
CREATE TABLE paquete_mapa_local (
  id            INTEGER PRIMARY KEY,                       -- mismo id que en el servidor
  region        TEXT NOT NULL UNIQUE,
  version       TEXT NOT NULL,
  bbox_min_lat  REAL NOT NULL,
  bbox_min_lon  REAL NOT NULL,
  bbox_max_lat  REAL NOT NULL,
  bbox_max_lon  REAL NOT NULL,
  tamano_bytes  INTEGER NOT NULL CHECK (tamano_bytes > 0),
  hash_sha256   TEXT NOT NULL,
  descargado_en INTEGER NOT NULL,
  CHECK (bbox_min_lat < bbox_max_lat AND bbox_min_lon < bbox_max_lon)
);

-- Últimos reportes publicados para el feed sin conexión (CU-32).
CREATE TABLE feed_cache (
  reporte_id      TEXT PRIMARY KEY,
  tipo_incidente_id INTEGER NOT NULL,
  descripcion     TEXT NOT NULL,
  latitud_aprox   REAL NOT NULL,
  longitud_aprox  REAL NOT NULL,
  referencia      TEXT,                                 -- «Zacatenco, cerca de Av. Wilfrido Massieu»
  autor_visible   TEXT,                                 -- nulo si el reporte es anónimo
  publicado_en    INTEGER NOT NULL,
  descargado_en   INTEGER NOT NULL
);
CREATE INDEX ix_feed_publicado ON feed_cache (publicado_en DESC);
