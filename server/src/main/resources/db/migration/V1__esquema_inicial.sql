-- =====================================================================
-- V1__esquema_inicial.sql · Migración inicial (Flyway) de Sendero Seguro
-- Sin BEGIN/COMMIT: Flyway ejecuta cada migración dentro de su propia transacción.
-- Para correrlo a mano: psql -v ON_ERROR_STOP=1 --single-transaction -f V1__esquema_inicial.sql
-- =====================================================================

-- =====================================================================
-- SENDERO SEGURO — Diseño de Datos (Entregable 3)
-- Modelo físico del backend · PostgreSQL 14 o superior
-- ---------------------------------------------------------------------
-- Fuentes: Entregable 2 (Análisis) y Diseño de E/S. Los nombres de estados
-- y campos enumerados son exactamente los de los diagramas E-01 a E-09.
-- No requiere extensiones: gen_random_uuid() y sha256() son nativas.
-- Ejecución: psql -v ON_ERROR_STOP=1 -d sendero_seguro -f este_archivo.sql
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. FUNCIONES DE APOYO
-- ---------------------------------------------------------------------

-- Distancia en metros entre dos puntos (Haversine). Se usa en la
-- asignación de la unidad más cercana (RF-10, A-02). Si más adelante se
-- instala PostGIS, puede sustituirse por ST_Distance sobre geography.
CREATE FUNCTION fn_distancia_m(lat1 double precision, lon1 double precision,
                               lat2 double precision, lon2 double precision)
RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT 2 * 6371000 * asin(sqrt(
           power(sin(radians(lat2 - lat1) / 2), 2) +
           cos(radians(lat1)) * cos(radians(lat2)) *
           power(sin(radians(lon2 - lon1) / 2), 2)))
$$;

CREATE FUNCTION fn_set_actualizado() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.actualizado_en := now();
  RETURN NEW;
END $$;

-- Tablas de solo inserción (RNF16): rechaza UPDATE, DELETE y TRUNCATE.
CREATE FUNCTION fn_solo_insercion() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'La tabla % es de solo inserción (RNF16)', TG_TABLE_NAME
    USING ERRCODE = '42501';
END $$;

-- ---------------------------------------------------------------------
-- 1. TIPOS ENUMERADOS
-- ---------------------------------------------------------------------
-- Valores tomados literalmente de los diagramas de estados (E-01..E-09).

CREATE TYPE alerta_estado AS ENUM (            -- E-01  alerta.estado
  'CREADA','EN_CUENTA_REGRESIVA','PENDIENTE_ENVIO','EXPIRADA',
  'ENVIADA','ASIGNADA','ESCALADA','ACUSADA','EN_ATENCION',
  'ATENDIDA','CERRADA','CANCELADA');
CREATE TYPE reporte_estado AS ENUM (           -- E-02  reporte.estado
  'BORRADOR','PENDIENTE','EN_COLA_LOCAL','PUBLICADO',
  'RECHAZADO','RETIRADO','DESCARTADO');
CREATE TYPE notificacion_estado AS ENUM (      -- E-03  notificacion.estado
  'PENDIENTE','ENVIADA','ENTREGADA','FALLIDA','DESCARTADA');
CREATE TYPE usuario_estado AS ENUM (           -- E-06  usuario.estado
  'ACTIVA','BLOQUEADA_TEMPORAL','SUSPENDIDA','BAJA_SOLICITADA','ELIMINADA');
CREATE TYPE usuario_rol AS ENUM (              -- E-06  usuario.rol
  'INTEGRANTE','COORDINADOR','OPERADOR','ADMINISTRADOR');
CREATE TYPE codigo_estado AS ENUM (            -- E-07  circulo.estado_codigo
  'VIGENTE','VENCIDO');
CREATE TYPE miembro_estado AS ENUM (           -- E-07  miembro_circulo.estado
  'ACTIVA','RETIRADA','DADA_DE_BAJA');
CREATE TYPE unidad_estado AS ENUM (            -- E-08  unidad_atencion.estado
  'FUERA_DE_SERVICIO','DISPONIBLE','ASIGNADA','ATENDIENDO');
CREATE TYPE catalogo_estado AS ENUM (          -- E-09  version_catalogo.estado
  'BORRADOR','EN_VALIDACION','PUBLICADA','REEMPLAZADA');

-- Enumerados propios del modelo de datos
CREATE TYPE tipo_comunidad        AS ENUM ('ALUMNO','DOCENTE','PERSONAL');
CREATE TYPE arco_tipo             AS ENUM ('ACCESO','RECTIFICACION','CANCELACION','OPOSICION');
CREATE TYPE arco_estado           AS ENUM ('SOLICITADA','EN_PROCESO','RESUELTA','RECHAZADA');
CREATE TYPE corporacion_tipo      AS ENUM ('SEGURIDAD_ESCOLAR','POLICIA','BOMBEROS','ERUM','OTRA');
CREATE TYPE incidente_categoria   AS ENUM ('FISICO','SOCIAL','DIGITAL');          -- RF-4
CREATE TYPE destino_escalamiento  AS ENUM ('OPERADOR','CORPORACION','COORDINADOR_CIRCULO'); -- CU-09
CREATE TYPE canal_recepcion       AS ENUM ('INTERNET','MALLA','SMS');             -- S-05, S-06
CREATE TYPE fuente_ubicacion      AS ENUM ('GNSS','WIFI','CELULAR','ULTIMA_CONOCIDA'); -- RF-7
CREATE TYPE asignacion_rol        AS ENUM ('PRINCIPAL','INFORMATIVA');            -- CU-17
CREATE TYPE metodo_autenticacion  AS ENUM ('PIN','BIOMETRIA');                    -- RF-13
CREATE TYPE notificacion_canal    AS ENUM ('PUSH','SMS','WHATSAPP');
CREATE TYPE notificacion_tipo     AS ENUM (
  'ALERTA_PRIORITARIA','ALERTA_INFORMATIVA','RED_APOYO','ACUSE','CAMBIO_ESTADO',
  'CIERRE','CANCELACION','ESCALAMIENTO','SIN_ACUSE','CONTACTO_AGREGADO',
  'NUEVO_INTEGRANTE','REPORTE_RECHAZADO','CATALOGO_PUBLICADO','TRAYECTO_COMPARTIDO');
CREATE TYPE moderacion_decision   AS ENUM ('PUBLICAR','RECHAZAR','RETIRAR');      -- RF-20
CREATE TYPE riesgo_nivel          AS ENUM ('BAJO','MEDIO','ALTO');                -- A-06
CREATE TYPE tipo_aplica_a         AS ENUM ('ALERTA','REPORTE','AMBOS');           -- catálogo único de incidentes
CREATE TYPE cancelacion_motivo    AS ENUM ('YA_ESTOY_A_SALVO','FALSA_ALARMA','ACTIVACION_ACCIDENTAL');  -- RF-13
CREATE TYPE ia_estado             AS ENUM ('ACTIVO','EN_PRUEBA','INACTIVO');
CREATE TYPE ia_ejecucion          AS ENUM ('TELEFONO','SERVIDOR','MIXTO');
CREATE TYPE ruta_tipo             AS ENUM ('SEGURA','MAS_CORTA');                 -- RF-23
CREATE TYPE trayecto_estado       AS ENUM ('EN_CURSO','FINALIZADO','CANCELADO');
CREATE TYPE contacto_acceso       AS ENUM ('SOLO_AVISO','SEGUIMIENTO');          -- P2: qué puede ver el contacto
CREATE TYPE contacto_estado       AS ENUM ('PENDIENTE','CONFIRMADO');            -- P2: confirmación del contacto
CREATE TYPE alerta_detonador      AS ENUM ('BOTON','ATAJO_FISICO','WIDGET','VOZ','CAIDA','TEMPORIZADOR'); -- P4 y RF-5: qué activó la alerta
CREATE TYPE sensibilidad_nivel    AS ENUM ('BAJA','MEDIA','ALTA');               -- P3: detección de caídas

-- ---------------------------------------------------------------------
-- 2. ÁREA 1 · USUARIOS Y COMUNIDAD  (RF-1, RF-2, RF-3)
-- ---------------------------------------------------------------------

CREATE TABLE institucion (
  id      smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre  varchar(120) NOT NULL UNIQUE,
  siglas  varchar(20),
  activa  boolean NOT NULL DEFAULT true
);

CREATE TABLE escuela (
  id             smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  institucion_id smallint NOT NULL REFERENCES institucion(id),
  nombre         varchar(120) NOT NULL,
  clave          varchar(20),
  activa         boolean NOT NULL DEFAULT true,
  UNIQUE (institucion_id, nombre)
);

-- Versiones del aviso de privacidad (RNF10). Cada consentimiento apunta
-- a la versión aceptada.
CREATE TABLE aviso_privacidad (
  id             smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version        varchar(20) NOT NULL UNIQUE,
  contenido_hash char(64) NOT NULL,
  vigente_desde  timestamptz NOT NULL DEFAULT now(),
  vigente        boolean NOT NULL DEFAULT true
);

CREATE TABLE usuario (
  id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  nombre                      varchar(150) NOT NULL,
  telefono_movil              varchar(16)  NOT NULL UNIQUE
        CHECK (telefono_movil ~ '^\+?[0-9]{10,15}$'),
  correo                      varchar(254)
        CHECK (correo ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),   -- opcional (P1)
  identificador_institucional varchar(30)  NOT NULL UNIQUE,   -- matrícula o n.º de empleado
  tipo_comunidad              tipo_comunidad NOT NULL,
  escuela_id                  smallint NOT NULL REFERENCES escuela(id),
  carrera_adscripcion         varchar(120),
  password_hash               text NOT NULL,                  -- Argon2id o bcrypt; nunca texto claro (RNF9)
  acepta_entrenamiento_ia     boolean NOT NULL DEFAULT false, -- «Usar mis reportes para mejorar la IA»
  rol                         usuario_rol    NOT NULL DEFAULT 'INTEGRANTE',
  estado                      usuario_estado NOT NULL DEFAULT 'ACTIVA',
  intentos_fallidos           smallint NOT NULL DEFAULT 0 CHECK (intentos_fallidos >= 0),
  bloqueada_hasta             timestamptz,
  ultimo_acceso_en            timestamptz,
  creado_en                   timestamptz NOT NULL DEFAULT now(),
  actualizado_en              timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_usuario_correo ON usuario (lower(correo)) WHERE correo IS NOT NULL;
CREATE INDEX ix_usuario_escuela ON usuario (escuela_id);
CREATE INDEX ix_usuario_rol_estado ON usuario (rol, estado);
CREATE TRIGGER trg_usuario_actualizado BEFORE UPDATE ON usuario
  FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado();

CREATE TABLE consentimiento (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  usuario_id  uuid NOT NULL REFERENCES usuario(id),
  aviso_id    smallint NOT NULL REFERENCES aviso_privacidad(id),
  aceptado_en timestamptz NOT NULL DEFAULT now(),
  UNIQUE (usuario_id, aviso_id)
);

CREATE TABLE dispositivo (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  usuario_id         uuid NOT NULL REFERENCES usuario(id),
  modelo             varchar(80),
  android_api        smallint CHECK (android_api >= 26),        -- RNF14: Android 8.0 o superior
  soporta_wifi_aware boolean NOT NULL DEFAULT false,
  fcm_token          text,
  llave_publica      text,                                      -- verifica la firma de los paquetes que viajan por la malla
  ultimo_visto_en    timestamptz,
  creado_en          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_dispositivo_usuario ON dispositivo (usuario_id);

CREATE TABLE sesion (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  usuario_id     uuid NOT NULL REFERENCES usuario(id),
  dispositivo_id uuid REFERENCES dispositivo(id),
  token_hash     char(64) NOT NULL UNIQUE,                        -- se guarda el hash, no el token
  creada_en      timestamptz NOT NULL DEFAULT now(),
  expira_en      timestamptz NOT NULL,
  revocada_en    timestamptz,
  CHECK (expira_en > creada_en)
);
CREATE INDEX ix_sesion_usuario_vigente ON sesion (usuario_id) WHERE revocada_en IS NULL;

-- "¿Olvidaste tu contraseña?": el código de un solo uso llega por SMS; solo se guarda su hash.
CREATE TABLE recuperacion_password (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  usuario_id  uuid NOT NULL REFERENCES usuario(id),
  codigo_hash char(64) NOT NULL,                                   -- SHA-256 del código; el código nunca se guarda
  intentos    smallint NOT NULL DEFAULT 0 CHECK (intentos BETWEEN 0 AND 5),
  creado_en   timestamptz NOT NULL DEFAULT now(),
  expira_en   timestamptz NOT NULL,
  usado_en    timestamptz,
  CHECK (expira_en > creado_en)
);
CREATE INDEX ix_recuperacion_usuario_pendiente ON recuperacion_password (usuario_id, creado_en DESC) WHERE usado_en IS NULL;

CREATE TABLE contacto_confianza (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),         -- generado en el dispositivo (RF-3: BD local y remota)
  usuario_id uuid NOT NULL REFERENCES usuario(id),
  nombre     varchar(150) NOT NULL,
  telefono   varchar(16)  NOT NULL CHECK (telefono ~ '^\+?[0-9]{10,15}$'),
  usuario_vinculado_id uuid REFERENCES usuario(id),                -- cuenta del contacto, si ya usa la app
  nivel_acceso        contacto_acceso NOT NULL DEFAULT 'SEGUIMIENTO',  -- SOLO_AVISO o además ve la ubicación en vivo
  estado_confirmacion contacto_estado NOT NULL DEFAULT 'PENDIENTE',    -- el contacto aceptó o no el vínculo
  canal_preferido     notificacion_canal NOT NULL DEFAULT 'SMS',       -- PUSH solo si tiene cuenta vinculada; SMS y WHATSAPP usan solo el teléfono
  prioridad  smallint NOT NULL CHECK (prioridad >= 1),
  activo     boolean NOT NULL DEFAULT true,
  creado_en  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (usuario_id, telefono),
  CHECK (usuario_vinculado_id IS DISTINCT FROM usuario_id),
  CHECK (canal_preferido <> 'PUSH' OR usuario_vinculado_id IS NOT NULL)
);
CREATE INDEX ix_contacto_vinculado ON contacto_confianza (usuario_vinculado_id) WHERE usuario_vinculado_id IS NOT NULL;

-- Parámetros de seguridad de la persona (P3). El hash del PIN de coacción vive solo en el Keystore del teléfono.
CREATE TABLE configuracion_usuario (
  usuario_id            uuid PRIMARY KEY REFERENCES usuario(id),
  intervalo_checkin_min smallint CHECK (intervalo_checkin_min > 0),   -- nulo = sin check-in
  palabras_clave_voz    text[] NOT NULL DEFAULT '{}',
  sensibilidad_caidas   sensibilidad_nivel NOT NULL DEFAULT 'MEDIA',
  tiene_pin_coaccion    boolean NOT NULL DEFAULT false,
  actualizado_en        timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_configuracion_usuario_actualizado BEFORE UPDATE ON configuracion_usuario
  FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado();

CREATE TABLE circulo (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  coordinador_id  uuid NOT NULL REFERENCES usuario(id),
  escuela_id      smallint NOT NULL REFERENCES escuela(id),
  nombre_grupo    varchar(100) NOT NULL,
  codigo_invitacion varchar(12) NOT NULL,
  estado_codigo   codigo_estado NOT NULL DEFAULT 'VIGENTE',
  codigo_vence_en timestamptz NOT NULL,
  creado_en       timestamptz NOT NULL DEFAULT now()
);
-- Un código vigente identifica un solo círculo (CU-05); al regenerarlo el anterior pasa a VENCIDO (CU-06).
CREATE UNIQUE INDEX uq_circulo_codigo_vigente ON circulo (codigo_invitacion) WHERE estado_codigo = 'VIGENTE';
CREATE INDEX ix_circulo_coordinador ON circulo (coordinador_id);

CREATE TABLE miembro_circulo (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  circulo_id uuid NOT NULL REFERENCES circulo(id),
  usuario_id uuid NOT NULL REFERENCES usuario(id),
  estado     miembro_estado NOT NULL DEFAULT 'ACTIVA',
  unido_en   timestamptz NOT NULL DEFAULT now(),
  baja_en    timestamptz,
  CHECK ((estado = 'ACTIVA') = (baja_en IS NULL))
);
CREATE UNIQUE INDEX uq_miembro_activo ON miembro_circulo (circulo_id, usuario_id) WHERE estado = 'ACTIVA';
CREATE INDEX ix_miembro_usuario ON miembro_circulo (usuario_id) WHERE estado = 'ACTIVA';

-- Solicitudes de derechos ARCO (CU-03, E-06 BAJA_SOLICITADA, RNF10)
CREATE TABLE solicitud_arco (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  usuario_id    uuid NOT NULL REFERENCES usuario(id),
  tipo          arco_tipo NOT NULL,
  estado        arco_estado NOT NULL DEFAULT 'SOLICITADA',
  detalle       text,
  solicitada_en timestamptz NOT NULL DEFAULT now(),
  resuelta_en   timestamptz,
  resuelta_por  uuid REFERENCES usuario(id)
);
CREATE INDEX ix_arco_pendientes ON solicitud_arco (estado) WHERE estado IN ('SOLICITADA','EN_PROCESO');

-- ---------------------------------------------------------------------
-- 3. ÁREA 2 · CONFIGURACIÓN: CATÁLOGO, REGLAS Y UNIDADES  (RF-4)
-- ---------------------------------------------------------------------

-- Canal destinatario = corporación (supuesto S2 del documento).
CREATE TABLE corporacion (
  id           smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre       varchar(100) NOT NULL UNIQUE,
  tipo         corporacion_tipo NOT NULL,
  canal_codigo varchar(30) NOT NULL UNIQUE,
  activa       boolean NOT NULL DEFAULT true
);

CREATE TABLE version_catalogo (
  id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  numero        integer NOT NULL UNIQUE CHECK (numero >= 1),
  estado        catalogo_estado NOT NULL DEFAULT 'BORRADOR',
  creada_por    uuid REFERENCES usuario(id),
  creada_en     timestamptz NOT NULL DEFAULT now(),
  publicada_en  timestamptz,
  CHECK ((estado IN ('PUBLICADA','REEMPLAZADA')) = (publicada_en IS NOT NULL))
);
-- Una sola versión vigente y una sola en edición/validación a la vez (A-08).
CREATE UNIQUE INDEX uq_catalogo_vigente  ON version_catalogo (estado) WHERE estado = 'PUBLICADA';
CREATE UNIQUE INDEX uq_catalogo_edicion  ON version_catalogo ((1)) WHERE estado IN ('BORRADOR','EN_VALIDACION');

-- Identidad estable del tipo de incidente. Su configuración (nombre,
-- categoría, prioridad, «aplica a», datos mínimos y peso de riesgo) vive en
-- tipo_incidente_version, versionada, y es la misma para alertas y reportes.
CREATE TABLE tipo_incidente (
  id          smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  codigo      varchar(40) NOT NULL UNIQUE,
  es_generica boolean NOT NULL DEFAULT false,                  -- "alerta genérica de auxilio" (RF-6)
  UNIQUE (id, es_generica)
);
CREATE UNIQUE INDEX uq_tipo_generica ON tipo_incidente (es_generica) WHERE es_generica;

CREATE TABLE tipo_incidente_version (
  version_catalogo_id integer  NOT NULL REFERENCES version_catalogo(id),
  tipo_incidente_id   smallint NOT NULL,
  es_generica         boolean  NOT NULL DEFAULT false,         -- copia de tipo_incidente.es_generica
  nombre              varchar(120) NOT NULL,
  categoria           incidente_categoria,                     -- nulo solo en la genérica (RF-4)
  prioridad           smallint NOT NULL CHECK (prioridad BETWEEN 1 AND 5),   -- 1 = máxima
  aplica_a            tipo_aplica_a NOT NULL,
  datos_minimos       jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(datos_minimos) = 'array'
               AND datos_minimos <@ '["ubicacion","descripcion","audio","foto"]'::jsonb),
  peso_riesgo         numeric(4,2) NOT NULL DEFAULT 1 CHECK (peso_riesgo >= 0),  -- pondera el reporte en el mapa de calor (A-06)
  activo              boolean NOT NULL DEFAULT true,           -- desactivar conserva el historial (CU-08)
  PRIMARY KEY (version_catalogo_id, tipo_incidente_id),
  FOREIGN KEY (tipo_incidente_id, es_generica) REFERENCES tipo_incidente (id, es_generica),
  UNIQUE (version_catalogo_id, nombre),
  CHECK (es_generica = (categoria IS NULL)),                   -- la genérica no lleva categoría; las demás sí
  CHECK (NOT es_generica OR aplica_a = 'ALERTA')               -- la genérica solo aplica a alertas
);

CREATE TABLE regla_canalizacion (
  id                    integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version_catalogo_id   integer  NOT NULL,
  tipo_incidente_id     smallint NOT NULL,
  corporacion_destino_id smallint NOT NULL REFERENCES corporacion(id),
  tiempo_max_acuse_seg  integer  NOT NULL CHECK (tiempo_max_acuse_seg > 0),
  UNIQUE (version_catalogo_id, tipo_incidente_id),
  FOREIGN KEY (version_catalogo_id, tipo_incidente_id)
    REFERENCES tipo_incidente_version (version_catalogo_id, tipo_incidente_id)
);
CREATE INDEX ix_regla_destino ON regla_canalizacion (corporacion_destino_id);

CREATE TABLE paso_escalamiento (
  id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  regla_id       integer NOT NULL REFERENCES regla_canalizacion(id) ON DELETE CASCADE,
  orden          smallint NOT NULL CHECK (orden >= 1),
  destino_tipo   destino_escalamiento NOT NULL,
  corporacion_id smallint REFERENCES corporacion(id),
  operador_id    uuid REFERENCES usuario(id),
  UNIQUE (regla_id, orden),
  CHECK (
    (destino_tipo = 'CORPORACION'         AND corporacion_id IS NOT NULL AND operador_id IS NULL) OR
    (destino_tipo = 'OPERADOR'            AND operador_id    IS NOT NULL AND corporacion_id IS NULL) OR
    (destino_tipo = 'COORDINADOR_CIRCULO' AND corporacion_id IS NULL     AND operador_id IS NULL))
);

CREATE TABLE unidad_atencion (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  corporacion_id      smallint NOT NULL REFERENCES corporacion(id),
  nombre              varchar(120) NOT NULL,
  estado              unidad_estado NOT NULL DEFAULT 'FUERA_DE_SERVICIO',
  ultima_latitud      numeric(9,6) CHECK (ultima_latitud  BETWEEN -90  AND 90),
  ultima_longitud     numeric(9,6) CHECK (ultima_longitud BETWEEN -180 AND 180),
  ultima_ubicacion_en timestamptz,
  activa              boolean NOT NULL DEFAULT true,
  creada_en           timestamptz NOT NULL DEFAULT now(),
  actualizado_en      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (corporacion_id, nombre),
  CHECK ((ultima_latitud IS NULL) = (ultima_longitud IS NULL))
);
CREATE INDEX ix_unidad_servicio ON unidad_atencion (corporacion_id) WHERE activa AND estado <> 'FUERA_DE_SERVICIO';
CREATE TRIGGER trg_unidad_actualizado BEFORE UPDATE ON unidad_atencion
  FOR EACH ROW EXECUTE FUNCTION fn_set_actualizado();

CREATE TABLE unidad_tipo_atencion (
  unidad_id         uuid     NOT NULL REFERENCES unidad_atencion(id) ON DELETE CASCADE,
  tipo_incidente_id smallint NOT NULL REFERENCES tipo_incidente(id),
  PRIMARY KEY (unidad_id, tipo_incidente_id)
);

-- Usuarios con rol OPERADOR vinculados a una unidad (CU-10). La
-- coherencia usuario.rol = 'OPERADOR' se valida en la aplicación (CU-07).
CREATE TABLE operador (
  usuario_id uuid PRIMARY KEY REFERENCES usuario(id),
  unidad_id  uuid NOT NULL REFERENCES unidad_atencion(id),
  activo     boolean NOT NULL DEFAULT true,
  desde      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_operador_unidad ON operador (unidad_id) WHERE activo;

CREATE TABLE parametro_sistema (
  clave          varchar(60) PRIMARY KEY,
  valor          text NOT NULL,
  tipo_dato      varchar(10) NOT NULL CHECK (tipo_dato IN ('INT','NUM','BOOL','TEXT')),
  descripcion    text NOT NULL,
  actualizado_por uuid REFERENCES usuario(id),
  actualizado_en timestamptz NOT NULL DEFAULT now()
);

-- Contador de folios legibles (ALR-2026-004817, REP-2026-000391). El id sigue
-- siendo el UUID generado en el teléfono; el folio lo asigna el servidor.
CREATE TABLE folio_secuencia (
  prefijo varchar(3) NOT NULL CHECK (prefijo IN ('ALR','REP')),
  anio    smallint   NOT NULL,
  ultimo  integer    NOT NULL DEFAULT 0 CHECK (ultimo >= 0),
  PRIMARY KEY (prefijo, anio)
);

-- Consecutivo sin huecos ni repetidos: el contador se incrementa dentro de la
-- misma transacción que el registro, y un reintento con el mismo UUID reutiliza
-- el folio existente en lugar de consumir uno nuevo.
CREATE FUNCTION fn_siguiente_folio(p_prefijo text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
  v_anio smallint := EXTRACT(YEAR FROM now())::smallint;
  v_n    integer;
BEGIN
  INSERT INTO folio_secuencia AS f (prefijo, anio, ultimo) VALUES (p_prefijo, v_anio, 1)
  ON CONFLICT (prefijo, anio) DO UPDATE SET ultimo = f.ultimo + 1
  RETURNING f.ultimo INTO v_n;
  RETURN p_prefijo || '-' || v_anio || '-' || lpad(v_n::text, 6, '0');
END $$;

CREATE FUNCTION fn_asignar_folio() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_existente text;
BEGIN
  IF NEW.folio IS NULL THEN
    EXECUTE format('SELECT folio FROM %I WHERE id = $1', TG_TABLE_NAME) INTO v_existente USING NEW.id;
    NEW.folio := COALESCE(v_existente, fn_siguiente_folio(TG_ARGV[0]));
  END IF;
  RETURN NEW;
END $$;

-- Modelos de IA (IA-1 a IA-4). Se crean aquí porque la alerta y la capa de riesgo
-- los referencian; el resto del área de IA va después de reportes y mapas.
CREATE TABLE modelo_ia (
  id                          smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  codigo                      varchar(4)  NOT NULL CHECK (codigo IN ('IA-1','IA-2','IA-3','IA-4')),
  nombre                      varchar(80) NOT NULL,
  identificador               varchar(40) NOT NULL UNIQUE,     -- clasificador-v2.3
  ejecucion                   ia_ejecucion NOT NULL,
  estado                      ia_estado NOT NULL DEFAULT 'EN_PRUEBA',
  umbral_confianza            numeric(3,2) CHECK (umbral_confianza BETWEEN 0 AND 1),
  parametros                  jsonb NOT NULL DEFAULT '{}'::jsonb,
  metricas_validacion         jsonb NOT NULL DEFAULT '{}'::jsonb,
  entrenado_en                timestamptz,
  proximo_reentrenamiento_en  timestamptz,
  activado_en                 timestamptz,
  creado_en                   timestamptz NOT NULL DEFAULT now(),
  CHECK (estado <> 'ACTIVO' OR activado_en IS NOT NULL)
);
-- Un solo modelo ACTIVO por función.
CREATE UNIQUE INDEX uq_modelo_ia_activo ON modelo_ia (codigo) WHERE estado = 'ACTIVO';

-- ---------------------------------------------------------------------
-- 4. ÁREA 3 · ALERTAS  (RF-5 a RF-13)
-- ---------------------------------------------------------------------

-- El id se genera en el dispositivo: sirve de identificador del paquete
-- en la malla y hace idempotente el reenvío (RF-14, RF-17, A-05).
CREATE TABLE alerta (
  id                   uuid PRIMARY KEY,
  folio                varchar(20) NOT NULL UNIQUE,            -- ALR-2026-004817; lo asigna el servidor
  usuario_id           uuid NOT NULL REFERENCES usuario(id),
  dispositivo_id       uuid REFERENCES dispositivo(id),
  tipo_incidente_id    smallint NOT NULL,
  version_catalogo_id  integer  NOT NULL,
  tipo_sugerido_id     smallint REFERENCES tipo_incidente(id),  -- sugerencia de IA-1
  confianza_sugerencia numeric(3,2) CHECK (confianza_sugerencia BETWEEN 0 AND 1),
  descripcion          text,
  descripcion_detalle  jsonb,                                  -- qué, dónde, quién y cuándo extraídos del texto
  descripcion_audio_url text,
  prioridad_ia         smallint CHECK (prioridad_ia BETWEEN 0 AND 100),   -- IA-4; nula si respondió el respaldo
  prioridad_modelo_id  smallint REFERENCES modelo_ia(id),
  prioridad_motivos    jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(prioridad_motivos) = 'array'),
  estado               alerta_estado NOT NULL DEFAULT 'ENVIADA',
  canal_recepcion      canal_recepcion NOT NULL,
  saltos_malla         smallint CHECK (saltos_malla >= 0),
  detonador            alerta_detonador NOT NULL DEFAULT 'BOTON',   -- P4: botón, voz, caída o temporizador
  nivel_bateria        smallint CHECK (nivel_bateria BETWEEN 0 AND 100),
  trayecto_id          uuid,                                        -- trayecto cuyo temporizador la activó (FK al final de la sección 5)
  coaccion_detectada   boolean NOT NULL DEFAULT false,              -- P8: se canceló con el PIN de coacción
  nivel_escalamiento   smallint NOT NULL DEFAULT 0 CHECK (nivel_escalamiento >= 0),
  sin_acuse            boolean NOT NULL DEFAULT false,
  acuse_limite_en      timestamptz,                         -- vencimiento del acuse (asignación + tiempo_max_acuse_seg); sobrevive a un reinicio del servicio
  firma_paquete        text,                                -- firma del paquete con la llave del dispositivo (malla); nula si llegó por internet o SMS
  -- Tiempos para medir la meta de 2 minutos (RNF4)
  activada_en          timestamptz NOT NULL,                 -- reloj del dispositivo
  enviada_en           timestamptz,                          -- primer intento de transmisión
  registrada_en        timestamptz NOT NULL DEFAULT now(),   -- entrega al backend
  acusada_en           timestamptz,
  acusada_por          uuid REFERENCES usuario(id),
  cerrada_en           timestamptz,
  cerrada_por          uuid REFERENCES usuario(id),
  resultado_cierre     text,
  observaciones_cierre text,
  cancelada_en         timestamptz,
  motivo_cancelacion   cancelacion_motivo,
  FOREIGN KEY (version_catalogo_id, tipo_incidente_id)
    REFERENCES tipo_incidente_version (version_catalogo_id, tipo_incidente_id),
  CHECK ((tipo_sugerido_id IS NULL) = (confianza_sugerencia IS NULL)),
  CHECK (prioridad_ia IS NULL OR prioridad_modelo_id IS NOT NULL),
  CHECK (cerrada_en   IS NULL OR estado = 'CERRADA'),
  CHECK (cancelada_en IS NULL OR estado = 'CANCELADA'),
  CHECK (estado <> 'CANCELADA' OR cancelada_en IS NOT NULL),
  CHECK (estado <> 'CERRADA'   OR cerrada_en   IS NOT NULL)
);
CREATE TRIGGER trg_alerta_folio BEFORE INSERT ON alerta
  FOR EACH ROW EXECUTE FUNCTION fn_asignar_folio('ALR');
CREATE INDEX ix_alerta_usuario ON alerta (usuario_id, activada_en DESC);
CREATE INDEX ix_alerta_activas ON alerta (estado, registrada_en)
  WHERE estado IN ('ENVIADA','ASIGNADA','ESCALADA','ACUSADA','EN_ATENCION');
CREATE INDEX ix_alerta_tipo_fecha ON alerta (tipo_incidente_id, registrada_en);   -- tablero RF-26
-- El proceso de escalamiento busca las alertas cuyo acuse ya venció.
CREATE INDEX ix_alerta_acuse_vence ON alerta (acuse_limite_en)
  WHERE acusada_en IS NULL AND acuse_limite_en IS NOT NULL AND estado IN ('ASIGNADA','ESCALADA');

CREATE TABLE ubicacion_alerta (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id    uuid NOT NULL REFERENCES alerta(id),
  latitud      numeric(9,6) NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud     numeric(9,6) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  precision_m  numeric(7,2) CHECK (precision_m >= 0),
  velocidad_ms numeric(5,2) CHECK (velocidad_ms >= 0),
  rumbo_grados smallint CHECK (rumbo_grados BETWEEN 0 AND 359),
  bateria      smallint CHECK (bateria BETWEEN 0 AND 100),
  fuente       fuente_ubicacion NOT NULL,
  capturada_en timestamptz NOT NULL,
  UNIQUE (alerta_id, capturada_en, fuente)                    -- evita duplicados al reintentar
);
CREATE INDEX ix_ubicacion_alerta_tiempo ON ubicacion_alerta (alerta_id, capturada_en DESC);

CREATE TABLE asignacion (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id          uuid NOT NULL REFERENCES alerta(id),
  unidad_id          uuid NOT NULL REFERENCES unidad_atencion(id),
  rol                asignacion_rol NOT NULL,
  nivel_escalamiento smallint NOT NULL DEFAULT 0,
  distancia_m        numeric(10,1) CHECK (distancia_m >= 0),
  metodo_distancia   varchar(10) CHECK (metodo_distancia IN ('EUCLIDIANA','RUTA')),
  asignada_en        timestamptz NOT NULL DEFAULT now(),
  liberada_en        timestamptz,
  UNIQUE (alerta_id, unidad_id, nivel_escalamiento)
);
CREATE INDEX ix_asignacion_unidad ON asignacion (unidad_id, asignada_en DESC);

CREATE TABLE escalamiento (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id             uuid NOT NULL REFERENCES alerta(id),
  nivel                 smallint NOT NULL CHECK (nivel >= 1),
  paso_escalamiento_id  integer REFERENCES paso_escalamiento(id),
  destino_tipo          destino_escalamiento NOT NULL,
  corporacion_id        smallint REFERENCES corporacion(id),
  operador_id           uuid REFERENCES usuario(id),
  motivo                varchar(10) NOT NULL DEFAULT 'SIN_ACUSE' CHECK (motivo IN ('SIN_ACUSE','MANUAL')),
  escalada_en           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (alerta_id, nivel)
);

-- Historial de cambios de estado (RF-11, RNF16). Solo inserción.
CREATE TABLE evento_alerta (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id       uuid NOT NULL REFERENCES alerta(id),
  estado_anterior alerta_estado,
  estado_nuevo    alerta_estado NOT NULL,
  ocurrido_en     timestamptz NOT NULL DEFAULT now(),
  actor_id        uuid REFERENCES usuario(id),
  detalle         jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_evento_alerta ON evento_alerta (alerta_id, ocurrido_en);

-- Cancelaciones y también intentos fallidos (posible coacción, CU-21). Solo inserción.
CREATE TABLE intento_cancelacion (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id      uuid NOT NULL REFERENCES alerta(id),
  usuario_id     uuid NOT NULL REFERENCES usuario(id),
  dispositivo_id uuid REFERENCES dispositivo(id),
  motivo         cancelacion_motivo,
  metodo         metodo_autenticacion NOT NULL,
  exitoso        boolean NOT NULL,
  es_coaccion    boolean NOT NULL DEFAULT false,                -- PIN de coacción: la pantalla simula la cancelación y la alerta sigue
  intentado_en   timestamptz NOT NULL DEFAULT now(),
  CHECK (NOT (es_coaccion AND exitoso))
);
CREATE INDEX ix_intento_cancelacion ON intento_cancelacion (alerta_id, intentado_en);

-- Enlace seguro de ubicación en tiempo real para la red de apoyo (CU-16):
-- de una alerta o de un trayecto compartido. La llave a trayecto se agrega
-- más abajo, cuando existe esa tabla.
CREATE TABLE enlace_ubicacion (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  alerta_id   uuid REFERENCES alerta(id),
  trayecto_id uuid,
  token_hash  char(64) NOT NULL UNIQUE,
  creado_en   timestamptz NOT NULL DEFAULT now(),
  vence_en    timestamptz NOT NULL,
  revocado_en timestamptz,
  CHECK (vence_en > creado_en),
  CHECK (num_nonnulls(alerta_id, trayecto_id) = 1)
);
CREATE INDEX ix_enlace_alerta ON enlace_ubicacion (alerta_id) WHERE alerta_id IS NOT NULL AND revocado_en IS NULL;
CREATE INDEX ix_enlace_trayecto ON enlace_ubicacion (trayecto_id) WHERE trayecto_id IS NOT NULL AND revocado_en IS NULL;

-- ---------------------------------------------------------------------
-- 5. ÁREA 4 · REPORTES COMUNITARIOS, MAPAS Y AUDITORÍA  (RF-19 a RF-26)
-- ---------------------------------------------------------------------

-- id generado en el dispositivo: idempotencia de la sincronización diferida (RF-17).
CREATE TABLE reporte (
  id               uuid PRIMARY KEY,
  folio            varchar(20) NOT NULL UNIQUE,                -- REP-2026-000391; lo asigna el servidor
  tipo_incidente_id smallint NOT NULL,
  version_catalogo_id integer NOT NULL,
  tipo_sugerido_id smallint REFERENCES tipo_incidente(id),     -- sugerencia de IA-1
  confianza_sugerencia numeric(3,2) CHECK (confianza_sugerencia BETWEEN 0 AND 1),
  descripcion      text NOT NULL,
  latitud          numeric(9,6) NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud         numeric(9,6) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  precision_m      numeric(7,2) CHECK (precision_m >= 0),
  es_anonimo       boolean NOT NULL DEFAULT false,
  autor_id         uuid REFERENCES usuario(id),               -- solo si NO es anónimo
  autor_cifrado    bytea,                                      -- solo si es anónimo (RNF11), AES-GCM
  clave_version    smallint,                                   -- versión de la llave que cifró el autor
  estado           reporte_estado NOT NULL DEFAULT 'PENDIENTE',
  creado_en        timestamptz NOT NULL,                       -- reloj del dispositivo
  recibido_en      timestamptz NOT NULL DEFAULT now(),
  moderado_en      timestamptz,
  FOREIGN KEY (version_catalogo_id, tipo_incidente_id)
    REFERENCES tipo_incidente_version (version_catalogo_id, tipo_incidente_id),
  CHECK ((tipo_sugerido_id IS NULL) = (confianza_sugerencia IS NULL)),
  CHECK (
    (es_anonimo     AND autor_id IS NULL     AND autor_cifrado IS NOT NULL AND clave_version IS NOT NULL) OR
    (NOT es_anonimo AND autor_id IS NOT NULL AND autor_cifrado IS NULL     AND clave_version IS NULL))
);
CREATE TRIGGER trg_reporte_folio BEFORE INSERT ON reporte
  FOR EACH ROW EXECUTE FUNCTION fn_asignar_folio('REP');
CREATE INDEX ix_reporte_cola_moderacion ON reporte (recibido_en) WHERE estado = 'PENDIENTE';
CREATE INDEX ix_reporte_autor ON reporte (autor_id) WHERE autor_id IS NOT NULL;

CREATE TABLE evidencia (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporte_id   uuid NOT NULL REFERENCES reporte(id),
  url_objeto   text NOT NULL,
  mime         varchar(60) NOT NULL,
  tamano_bytes integer NOT NULL CHECK (tamano_bytes > 0),
  hash_sha256  char(64) NOT NULL,
  creada_en    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_evidencia_reporte ON evidencia (reporte_id);

-- Decisiones de moderación (RF-20). Solo inserción.
CREATE TABLE decision_moderacion (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reporte_id   uuid NOT NULL REFERENCES reporte(id),
  moderador_id uuid NOT NULL REFERENCES usuario(id),
  decision     moderacion_decision NOT NULL,
  motivo       text,
  unido_a_id   uuid REFERENCES reporte(id),                    -- «Publicar unido a REP-…»
  oculta_datos_personales boolean NOT NULL DEFAULT false,      -- «Publicar (ocultando teléfono)»
  decidido_en  timestamptz NOT NULL DEFAULT now(),
  CHECK (decision = 'PUBLICAR' OR (motivo IS NOT NULL AND length(btrim(motivo)) > 0)),
  CHECK (decision = 'PUBLICAR' OR (unido_a_id IS NULL AND NOT oculta_datos_personales)),
  CHECK (unido_a_id IS DISTINCT FROM reporte_id)
);
CREATE INDEX ix_decision_reporte ON decision_moderacion (reporte_id, decidido_en);

-- Versión pública: sin identidad y con ubicación aproximada. El feed, el
-- mapa de calor y el rol sendero_feed leen SOLO esta tabla (RF-20, RF-21).
CREATE TABLE reporte_publico (
  reporte_id      uuid PRIMARY KEY REFERENCES reporte(id),
  tipo_incidente_id smallint NOT NULL REFERENCES tipo_incidente(id),
  descripcion     text NOT NULL,
  celda           char(7) NOT NULL,                             -- geohash de 7 caracteres (~150 m)
  latitud_aprox   numeric(9,6) NOT NULL,                        -- centro de la celda
  longitud_aprox  numeric(9,6) NOT NULL,
  referencia      varchar(160),                                 -- «Zacatenco, cerca de Av. Wilfrido Massieu»
  autor_visible   varchar(150),                                 -- solo si eligió «Con mi nombre»
  publicado_en    timestamptz NOT NULL DEFAULT now(),
  retirado_en     timestamptz
);
CREATE INDEX ix_publico_vigentes ON reporte_publico (publicado_en DESC) WHERE retirado_en IS NULL;
CREATE INDEX ix_publico_tipo_fecha ON reporte_publico (tipo_incidente_id, publicado_en) WHERE retirado_en IS NULL;
CREATE INDEX ix_publico_celda ON reporte_publico (celda) WHERE retirado_en IS NULL;

-- Capa de riesgo versionada (RF-22, A-06).
CREATE TABLE capa_riesgo (
  id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version     integer NOT NULL UNIQUE CHECK (version >= 1),
  generada_en timestamptz NOT NULL DEFAULT now(),
  parametros  jsonb NOT NULL DEFAULT '{}'::jsonb,              -- eps, minPts, ventana, pesos usados
  num_zonas   integer NOT NULL DEFAULT 0,
  modelo_ia_id smallint REFERENCES modelo_ia(id),               -- modelo IA-2 que generó el pronóstico
  es_vigente  boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uq_capa_vigente ON capa_riesgo (es_vigente) WHERE es_vigente;

-- Una fila por zona, por combinación (tipo de incidente, periodo). tipo_incidente_id
-- NULL = todos los tipos. La geometría es un polígono GeoJSON; el bbox permite
-- filtrar por zona sin PostGIS.
CREATE TABLE zona_riesgo (
  id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  capa_id            integer NOT NULL REFERENCES capa_riesgo(id) ON DELETE CASCADE,
  tipo_incidente_id  smallint REFERENCES tipo_incidente(id),
  periodo_dias       smallint NOT NULL CHECK (periodo_dias > 0),
  nivel              riesgo_nivel NOT NULL,
  densidad_ponderada numeric(10,4) NOT NULL CHECK (densidad_ponderada >= 0),
  num_reportes       integer NOT NULL CHECK (num_reportes >= 1),
  centroide_latitud  numeric(9,6) NOT NULL,
  centroide_longitud numeric(9,6) NOT NULL,
  bbox_min_lat       numeric(9,6) NOT NULL,
  bbox_min_lon       numeric(9,6) NOT NULL,
  bbox_max_lat       numeric(9,6) NOT NULL,
  bbox_max_lon       numeric(9,6) NOT NULL,
  geometria          jsonb NOT NULL CHECK (geometria ->> 'type' IN ('Polygon','MultiPolygon')),
  CHECK (bbox_min_lat <= bbox_max_lat AND bbox_min_lon <= bbox_max_lon)
);
CREATE INDEX ix_zona_consulta ON zona_riesgo (capa_id, periodo_dias, tipo_incidente_id);
CREATE INDEX ix_zona_bbox ON zona_riesgo (capa_id, bbox_min_lat, bbox_max_lat);

CREATE TABLE zona_reporte (
  zona_id    bigint NOT NULL REFERENCES zona_riesgo(id) ON DELETE CASCADE,
  reporte_id uuid   NOT NULL REFERENCES reporte(id),
  PRIMARY KEY (zona_id, reporte_id)
);
CREATE INDEX ix_zona_reporte_reporte ON zona_reporte (reporte_id);

-- Grafo peatonal para el ruteo seguro (RF-23, A-07). tesela = prefijo
-- geohash que permite a la app descargar solo sus zonas habituales (CU-36).
CREATE TABLE nodo_vial (
  id       bigint PRIMARY KEY,                                 -- id del nodo en OpenStreetMap
  latitud  numeric(9,6) NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud numeric(9,6) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  tesela   varchar(8) NOT NULL
);
CREATE INDEX ix_nodo_tesela ON nodo_vial (tesela);

CREATE TABLE arista_vial (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nodo_origen    bigint NOT NULL REFERENCES nodo_vial(id),
  nodo_destino   bigint NOT NULL REFERENCES nodo_vial(id),
  longitud_m     numeric(8,2) NOT NULL CHECK (longitud_m > 0),
  nombre_via     varchar(120),
  tipo_via       varchar(30),
  bidireccional  boolean NOT NULL DEFAULT true,
  tesela         varchar(8) NOT NULL,
  fuente         varchar(20) NOT NULL DEFAULT 'OSM',
  osm_way_id     bigint,
  UNIQUE (nodo_origen, nodo_destino),
  CHECK (nodo_origen <> nodo_destino)
);
CREATE INDEX ix_arista_tesela ON arista_vial (tesela);
CREATE INDEX ix_arista_destino ON arista_vial (nodo_destino);

-- Riesgo normalizado [0,1] de cada tramo por versión de capa: peso = distancia × (1 + α·riesgo).
CREATE TABLE riesgo_tramo (
  capa_id   integer NOT NULL REFERENCES capa_riesgo(id) ON DELETE CASCADE,
  arista_id bigint  NOT NULL REFERENCES arista_vial(id),
  riesgo    numeric(4,3) NOT NULL CHECK (riesgo BETWEEN 0 AND 1),
  PRIMARY KEY (capa_id, arista_id)
);

-- Trayecto compartido: la ruta que la persona inicia y comparte con su red de apoyo (RF-23).
-- El id lo genera el teléfono.
CREATE TABLE trayecto (
  id                  uuid PRIMARY KEY,
  usuario_id          uuid NOT NULL REFERENCES usuario(id),
  capa_id             integer REFERENCES capa_riesgo(id),
  tipo_ruta           ruta_tipo NOT NULL DEFAULT 'SEGURA',
  origen_latitud      numeric(9,6) NOT NULL CHECK (origen_latitud  BETWEEN -90  AND 90),
  origen_longitud     numeric(9,6) NOT NULL CHECK (origen_longitud BETWEEN -180 AND 180),
  destino_latitud     numeric(9,6) NOT NULL CHECK (destino_latitud  BETWEEN -90  AND 90),
  destino_longitud    numeric(9,6) NOT NULL CHECK (destino_longitud BETWEEN -180 AND 180),
  distancia_m         integer CHECK (distancia_m > 0),
  duracion_est_min    smallint CHECK (duracion_est_min > 0),
  tolerancia_desviacion_m smallint CHECK (tolerancia_desviacion_m > 0),   -- P7: desviación aceptada respecto de la ruta
  intervalo_checkin_min   smallint CHECK (intervalo_checkin_min > 0),     -- P7: cada cuánto debe confirmar presencia
  ruta_planificada    jsonb CHECK (jsonb_typeof(ruta_planificada) = 'object'),   -- GeoJSON LineString
  estado              trayecto_estado NOT NULL DEFAULT 'EN_CURSO',
  ultima_latitud      numeric(9,6) CHECK (ultima_latitud  BETWEEN -90  AND 90),
  ultima_longitud     numeric(9,6) CHECK (ultima_longitud BETWEEN -180 AND 180),
  ultima_ubicacion_en timestamptz,
  iniciado_en         timestamptz NOT NULL,
  finalizado_en       timestamptz,
  CHECK ((estado = 'EN_CURSO') = (finalizado_en IS NULL)),
  CHECK ((ultima_latitud IS NULL) = (ultima_longitud IS NULL))
);
CREATE INDEX ix_trayecto_usuario ON trayecto (usuario_id, iniciado_en DESC);
CREATE INDEX ix_trayecto_en_curso ON trayecto (usuario_id) WHERE estado = 'EN_CURSO';
ALTER TABLE enlace_ubicacion ADD CONSTRAINT fk_enlace_trayecto FOREIGN KEY (trayecto_id) REFERENCES trayecto(id);
-- Una alerta activada por temporizador (dead-man's switch) pertenece siempre a un trayecto.
ALTER TABLE alerta ADD CONSTRAINT fk_alerta_trayecto FOREIGN KEY (trayecto_id) REFERENCES trayecto(id);
ALTER TABLE alerta ADD CONSTRAINT ck_alerta_temporizador CHECK (detonador <> 'TEMPORIZADOR' OR trayecto_id IS NOT NULL);
CREATE INDEX ix_alerta_trayecto ON alerta (trayecto_id) WHERE trayecto_id IS NOT NULL;

-- Confirmaciones de presencia del Modo Acompañamiento (P7). Sin confirmar a tiempo se activa la alerta.
CREATE TABLE checkin_trayecto (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trayecto_id   uuid NOT NULL REFERENCES trayecto(id),
  esperado_en   timestamptz NOT NULL,
  confirmado_en timestamptz,
  UNIQUE (trayecto_id, esperado_en)                         -- un reintento no duplica el check-in
);

-- Puntos de interés de seguridad que muestra el mapa comunitario (P9).
CREATE TABLE punto_interes (
  id        integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre    varchar(150) NOT NULL,
  tipo      varchar(40)  NOT NULL,
  latitud   numeric(9,6) NOT NULL CHECK (latitud  BETWEEN -90  AND 90),
  longitud  numeric(9,6) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
  activo    boolean NOT NULL DEFAULT true,
  creado_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_punto_interes_ubicacion ON punto_interes (latitud, longitud) WHERE activo;

-- Paquetes de mapas y ruteo que el teléfono descarga para trabajar sin conexión (P12).
CREATE TABLE paquete_mapa (
  id           integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  region       varchar(100) NOT NULL,
  version      varchar(20)  NOT NULL,
  bbox_min_lat numeric(9,6) NOT NULL,
  bbox_min_lon numeric(9,6) NOT NULL,
  bbox_max_lat numeric(9,6) NOT NULL,
  bbox_max_lon numeric(9,6) NOT NULL,
  tamano_bytes bigint NOT NULL CHECK (tamano_bytes > 0),
  hash_sha256  char(64) NOT NULL,
  ruta_archivo text NOT NULL,                               -- ruta en el almacén de objetos
  vigente      boolean NOT NULL DEFAULT true,
  publicado_en timestamptz NOT NULL DEFAULT now(),
  UNIQUE (region, version),
  CHECK (bbox_min_lat < bbox_max_lat AND bbox_min_lon < bbox_max_lon)
);
CREATE UNIQUE INDEX uq_paquete_mapa_vigente ON paquete_mapa (region) WHERE vigente;

-- Zonas con nombre para filtrar el tablero y rotular el mapa («Zacatenco», «Lindavista»).
CREATE TABLE zona_geografica (
  id           smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre       varchar(100) NOT NULL UNIQUE,
  bbox_min_lat numeric(9,6) NOT NULL,
  bbox_min_lon numeric(9,6) NOT NULL,
  bbox_max_lat numeric(9,6) NOT NULL,
  bbox_max_lon numeric(9,6) NOT NULL,
  activa       boolean NOT NULL DEFAULT true,
  CHECK (bbox_min_lat < bbox_max_lat AND bbox_min_lon < bbox_max_lon)
);

-- Bitácora inmutable con cadena de hashes (RNF16, RNF11).
-- El id se asigna dentro del trigger, ya bajo el candado, para que el
-- orden de id coincida con el orden de la cadena.
CREATE SEQUENCE bitacora_id_seq;
CREATE TABLE bitacora (
  id          bigint PRIMARY KEY,
  ocurrida_en timestamptz NOT NULL DEFAULT clock_timestamp(),
  usuario_id  uuid REFERENCES usuario(id),
  accion      varchar(60) NOT NULL,
  entidad     varchar(60),
  entidad_id  text,
  detalle     jsonb NOT NULL DEFAULT '{}'::jsonb,
  ip          inet,
  hash_prev   bytea,
  hash        bytea NOT NULL
);
ALTER SEQUENCE bitacora_id_seq OWNED BY bitacora.id;
CREATE INDEX ix_bitacora_usuario ON bitacora (usuario_id, ocurrida_en);
CREATE INDEX ix_bitacora_entidad ON bitacora (entidad, entidad_id);

CREATE FUNCTION fn_bitacora_hash(p_prev bytea, p_id bigint, p_en timestamptz, p_usuario uuid,
                                 p_accion text, p_entidad text, p_entidad_id text, p_detalle jsonb)
RETURNS bytea LANGUAGE sql IMMUTABLE AS $$
  SELECT sha256(convert_to(concat_ws('|',
           COALESCE(encode(p_prev, 'hex'), ''),
           p_id::text,
           to_char(p_en AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'),
           COALESCE(p_usuario::text, ''),
           p_accion,
           COALESCE(p_entidad, ''),
           COALESCE(p_entidad_id, ''),
           p_detalle::text), 'UTF8'))
$$;

CREATE FUNCTION fn_bitacora_encadenar() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE v_prev bytea;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('sendero_bitacora_cadena'));
  SELECT b.hash INTO v_prev FROM bitacora b ORDER BY b.id DESC LIMIT 1;
  NEW.id        := nextval('bitacora_id_seq');
  NEW.hash_prev := v_prev;
  NEW.hash      := fn_bitacora_hash(v_prev, NEW.id, NEW.ocurrida_en, NEW.usuario_id,
                                    NEW.accion, NEW.entidad, NEW.entidad_id, NEW.detalle);
  RETURN NEW;
END $$;

CREATE TRIGGER trg_bitacora_cadena BEFORE INSERT ON bitacora
  FOR EACH ROW EXECUTE FUNCTION fn_bitacora_encadenar();

-- Verificación de integridad: devuelve las filas cuya cadena no coincide.
CREATE FUNCTION fn_verificar_bitacora()
RETURNS TABLE (id bigint, problema text)
LANGUAGE plpgsql STABLE AS $$
DECLARE r record; v_prev bytea := NULL;
BEGIN
  FOR r IN SELECT * FROM bitacora ORDER BY bitacora.id LOOP
    IF r.hash_prev IS DISTINCT FROM v_prev THEN
      id := r.id; problema := 'hash_prev no coincide con la fila anterior'; RETURN NEXT;
    END IF;
    IF r.hash <> fn_bitacora_hash(r.hash_prev, r.id, r.ocurrida_en, r.usuario_id,
                                  r.accion, r.entidad, r.entidad_id, r.detalle) THEN
      id := r.id; problema := 'el contenido fue alterado'; RETURN NEXT;
    END IF;
    v_prev := r.hash;
  END LOOP;
END $$;

-- Tablas de solo inserción (RNF16)
CREATE TRIGGER trg_bitacora_inmutable BEFORE UPDATE OR DELETE ON bitacora
  FOR EACH ROW EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_bitacora_no_truncate BEFORE TRUNCATE ON bitacora
  FOR EACH STATEMENT EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_evento_alerta_inmutable BEFORE UPDATE OR DELETE ON evento_alerta
  FOR EACH ROW EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_intento_cancelacion_inmutable BEFORE UPDATE OR DELETE ON intento_cancelacion
  FOR EACH ROW EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_decision_moderacion_inmutable BEFORE UPDATE OR DELETE ON decision_moderacion
  FOR EACH ROW EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_evento_alerta_no_truncate BEFORE TRUNCATE ON evento_alerta
  FOR EACH STATEMENT EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_intento_cancelacion_no_truncate BEFORE TRUNCATE ON intento_cancelacion
  FOR EACH STATEMENT EXECUTE FUNCTION fn_solo_insercion();
CREATE TRIGGER trg_decision_moderacion_no_truncate BEFORE TRUNCATE ON decision_moderacion
  FOR EACH STATEMENT EXECUTE FUNCTION fn_solo_insercion();

-- ---------------------------------------------------------------------
-- 5b. ÁREA 5 · INTELIGENCIA ARTIFICIAL  (resto; modelo_ia está en el área 2)
-- ---------------------------------------------------------------------

-- Cada sugerencia de un modelo frente a la decisión humana. No guarda identidad;
-- solo las filas aptas (consentimiento de la persona) sirven para reentrenar.
CREATE TABLE retroalimentacion_ia (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  modelo_id           smallint NOT NULL REFERENCES modelo_ia(id),
  alerta_id           uuid REFERENCES alerta(id),
  reporte_id          uuid REFERENCES reporte(id),
  sugerencia          varchar(200) NOT NULL,
  sugerencia_tipo_id  smallint REFERENCES tipo_incidente(id),
  decision_humana     varchar(200),
  decision_tipo_id    smallint REFERENCES tipo_incidente(id),
  confianza           numeric(3,2) CHECK (confianza BETWEEN 0 AND 1),
  aceptada            boolean,
  latencia_ms         integer CHECK (latencia_ms >= 0),
  uso_respaldo        boolean NOT NULL DEFAULT false,          -- IA-4: se ordenó por la prioridad del catálogo
  apta_entrenamiento  boolean NOT NULL DEFAULT false,
  creada_en           timestamptz NOT NULL DEFAULT now(),
  CHECK (num_nonnulls(alerta_id, reporte_id) = 1)
);
CREATE INDEX ix_retro_modelo ON retroalimentacion_ia (modelo_id, creada_en DESC);
CREATE INDEX ix_retro_entrenamiento ON retroalimentacion_ia (modelo_id) WHERE apta_entrenamiento;

-- Resultado de IA-3 sobre cada reporte recibido. Solo marca y sugiere: nunca publica ni rechaza.
CREATE TABLE analisis_reporte_ia (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reporte_id             uuid NOT NULL REFERENCES reporte(id),
  modelo_id              smallint NOT NULL REFERENCES modelo_ia(id),
  prob_duplicado         numeric(3,2) NOT NULL CHECK (prob_duplicado BETWEEN 0 AND 1),
  duplicado_de_id        uuid REFERENCES reporte(id),
  prob_datos_personales  numeric(3,2) NOT NULL CHECK (prob_datos_personales BETWEEN 0 AND 1),
  prob_ofensivo          numeric(3,2) NOT NULL CHECK (prob_ofensivo BETWEEN 0 AND 1),
  sugerencia             varchar(200),
  latencia_ms            integer CHECK (latencia_ms >= 0),
  analizado_en           timestamptz NOT NULL DEFAULT now(),
  UNIQUE (reporte_id, modelo_id),
  CHECK (duplicado_de_id IS DISTINCT FROM reporte_id)
);

-- Pronóstico de IA-2: celdas de ~150 m (geohash de 7 caracteres) y 8 franjas de
-- 3 horas (0 = 00–03 h … 7 = 21–24 h) por versión de la capa de riesgo.
CREATE TABLE pronostico_riesgo (
  capa_id           integer  NOT NULL REFERENCES capa_riesgo(id) ON DELETE CASCADE,
  celda             char(7)  NOT NULL,
  franja            smallint NOT NULL CHECK (franja BETWEEN 0 AND 7),
  modelo_id         smallint NOT NULL REFERENCES modelo_ia(id),
  riesgo            numeric(4,3) NOT NULL CHECK (riesgo BETWEEN 0 AND 1),
  nivel             riesgo_nivel NOT NULL,
  solo_historico    boolean NOT NULL DEFAULT false,             -- pocos reportes: se muestra el histórico
  num_reportes_30d  integer NOT NULL DEFAULT 0 CHECK (num_reportes_30d >= 0),
  motivos           jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(motivos) = 'array'),
  centro_latitud    numeric(9,6) NOT NULL,
  centro_longitud   numeric(9,6) NOT NULL,
  PRIMARY KEY (capa_id, celda, franja)
);
CREATE INDEX ix_pronostico_franja ON pronostico_riesgo (capa_id, franja, nivel);

-- ---------------------------------------------------------------------
-- 6. NOTIFICACIONES  (RF-8, RF-9, E-03)
-- ---------------------------------------------------------------------

CREATE TABLE notificacion (
  id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id            uuid REFERENCES alerta(id),
  reporte_id           uuid REFERENCES reporte(id),
  trayecto_id          uuid REFERENCES trayecto(id),
  usuario_id           uuid REFERENCES usuario(id),             -- destinatario con cuenta
  contacto_id          uuid REFERENCES contacto_confianza(id),  -- destinatario sin cuenta (SMS)
  tipo                 notificacion_tipo NOT NULL,
  canal                notificacion_canal NOT NULL,
  estado               notificacion_estado NOT NULL DEFAULT 'PENDIENTE',
  reintentos           smallint NOT NULL DEFAULT 0 CHECK (reintentos >= 0),
  ultimo_error         text,
  proveedor_mensaje_id varchar(120),
  creada_en            timestamptz NOT NULL DEFAULT now(),
  enviada_en           timestamptz,
  entregada_en         timestamptz,
  proximo_reintento_en timestamptz,
  CHECK (num_nonnulls(usuario_id, contacto_id) = 1)
);
CREATE INDEX ix_notificacion_alerta ON notificacion (alerta_id) WHERE alerta_id IS NOT NULL;
CREATE INDEX ix_notificacion_reintento ON notificacion (proximo_reintento_en)
  WHERE estado IN ('PENDIENTE','FALLIDA');
CREATE INDEX ix_notificacion_usuario ON notificacion (usuario_id, creada_en DESC) WHERE usuario_id IS NOT NULL;

-- SMS de contingencia recibidos en la pasarela (RF-16, CU-25, S-06). Se guarda
-- cada mensaje, válido o no; alerta_id se llena cuando se decodifica y registra.
CREATE TABLE mensaje_sms (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  alerta_id     uuid REFERENCES alerta(id),
  numero_origen varchar(16) NOT NULL CHECK (numero_origen ~ '^\+?[0-9]{10,15}$'),
  contenido     varchar(160) NOT NULL,                          -- texto recibido (formato SS1|…)
  valido        boolean NOT NULL DEFAULT false,                 -- pasó la verificación del mensaje
  recibido_en   timestamptz NOT NULL DEFAULT now(),
  procesado_en  timestamptz,
  CHECK (alerta_id IS NULL OR valido)
);
CREATE INDEX ix_mensaje_sms_pendiente ON mensaje_sms (recibido_en) WHERE procesado_en IS NULL;
CREATE INDEX ix_mensaje_sms_alerta ON mensaje_sms (alerta_id) WHERE alerta_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 7. VISTAS DE APOYO
-- ---------------------------------------------------------------------

-- Última ubicación conocida de cada alerta (panel del operador, RF-7).
CREATE VIEW v_ubicacion_actual_alerta AS
SELECT DISTINCT ON (u.alerta_id)
       u.alerta_id, u.latitud, u.longitud, u.precision_m, u.fuente, u.capturada_en
FROM ubicacion_alerta u
ORDER BY u.alerta_id, u.capturada_en DESC;

-- Historial de cada persona: sus alertas, reportes con nombre y trayectos (P13).
-- Los reportes anónimos no aparecen porque no guardan el autor.
CREATE VIEW v_historial_usuario AS
SELECT usuario_id, 'ALERTA'::text AS tipo, id, folio AS referencia, estado::text AS estado, activada_en AS ocurrido_en
FROM alerta
UNION ALL
SELECT autor_id, 'REPORTE', id, folio, estado::text, creado_en
FROM reporte WHERE autor_id IS NOT NULL
UNION ALL
SELECT usuario_id, 'TRAYECTO', id, NULL::varchar, estado::text, iniciado_en
FROM trayecto;

-- Tiempos de cada alerta y cumplimiento de la meta de acuse (RNF4, RF-26).
CREATE VIEW v_alerta_tiempos AS
SELECT a.id AS alerta_id, a.tipo_incidente_id, a.estado, a.canal_recepcion,
       a.activada_en, a.enviada_en, a.registrada_en, a.acusada_en, a.cerrada_en, a.cancelada_en,
       EXTRACT(EPOCH FROM (a.registrada_en - a.activada_en))::integer AS seg_activacion_entrega,
       EXTRACT(EPOCH FROM (a.acusada_en    - a.activada_en))::integer AS seg_activacion_acuse,
       EXTRACT(EPOCH FROM (a.cerrada_en    - a.activada_en))::integer AS seg_activacion_cierre,
       CASE WHEN a.acusada_en IS NULL THEN NULL
            ELSE EXTRACT(EPOCH FROM (a.acusada_en - a.activada_en)) <=
                 (SELECT p.valor::integer FROM parametro_sistema p WHERE p.clave = 'meta_acuse_seg')
       END AS cumple_meta_acuse
FROM alerta a;

-- Validación al publicar (A-08): tipos de alerta activos sin regla, o con regla
-- sin pasos de escalamiento, en la versión en edición.
CREATE VIEW v_catalogo_errores AS
SELECT v.id AS version_catalogo_id, v.numero, t.tipo_incidente_id, t.nombre,
       CASE WHEN r.id IS NULL THEN 'SIN_REGLA' ELSE 'SIN_ESCALAMIENTO' END AS problema
FROM version_catalogo v
JOIN tipo_incidente_version t ON t.version_catalogo_id = v.id
LEFT JOIN regla_canalizacion r
       ON r.version_catalogo_id = t.version_catalogo_id AND r.tipo_incidente_id = t.tipo_incidente_id
WHERE v.estado IN ('BORRADOR','EN_VALIDACION')
  AND t.activo AND t.aplica_a IN ('ALERTA','AMBOS')
  AND (r.id IS NULL OR NOT EXISTS (SELECT 1 FROM paso_escalamiento p WHERE p.regla_id = r.id));

-- Supervisión de modelos de IA: aceptación, latencia y uso del respaldo.
CREATE VIEW v_ia_metricas AS
SELECT m.id AS modelo_id, m.codigo, m.identificador, m.estado,
       count(r.id)                                              AS sugerencias,
       count(r.id) FILTER (WHERE r.aceptada)                    AS aceptadas,
       round(100.0 * count(r.id) FILTER (WHERE r.aceptada)
             / NULLIF(count(r.id) FILTER (WHERE r.aceptada IS NOT NULL), 0), 1) AS pct_aceptadas,
       round(avg(r.latencia_ms))                                AS latencia_media_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY r.latencia_ms) AS latencia_p95_ms,
       round(100.0 * count(r.id) FILTER (WHERE r.uso_respaldo)
             / NULLIF(count(r.id), 0), 1)                       AS pct_respaldo
FROM modelo_ia m
LEFT JOIN retroalimentacion_ia r ON r.modelo_id = m.id
GROUP BY m.id, m.codigo, m.identificador, m.estado;

-- ---------------------------------------------------------------------
-- 8. SEMILLAS INICIALES
-- Los valores marcados «propuesto» son supuestos a confirmar con el cliente.
-- ---------------------------------------------------------------------

INSERT INTO institucion (nombre, siglas) VALUES ('Instituto Politécnico Nacional', 'IPN');
INSERT INTO escuela (institucion_id, nombre, clave)
  SELECT i.id, x.nombre, x.clave
  FROM institucion i
  CROSS JOIN (VALUES
    ('Escuela Superior de Cómputo',                                          'ESCOM'),
    ('Escuela Superior de Ingeniería Mecánica y Eléctrica, Unidad Zacatenco', 'ESIME-ZAC'),
    ('Dirección de Seguridad Escolar',                                       'DSE')) AS x(nombre, clave)
  WHERE i.siglas = 'IPN';
INSERT INTO aviso_privacidad (version, contenido_hash)
  VALUES ('3.1', repeat('0', 64));                              -- sustituir por el hash real del aviso

-- Corporaciones del Diseño de E/S (Seguridad IPN, SSC CDMX (C5), ERUM) y PBI del Entregable 2.
INSERT INTO corporacion (nombre, tipo, canal_codigo) VALUES
  ('Seguridad IPN',  'SEGURIDAD_ESCOLAR', 'SEG_ESCOLAR'),
  ('SSC CDMX (C5)',  'POLICIA',           'SSC_C5'),
  ('ERUM',           'ERUM',              'ERUM'),
  ('PBI',            'POLICIA',           'PBI');

INSERT INTO tipo_incidente (codigo, es_generica) VALUES
  ('GENERICA',             true),
  ('PERSONA_SOSPECHOSA',   false),
  ('ASALTO_ROBO',          false),
  ('AGRESION_FISICA',      false),
  ('EMERGENCIA_MEDICA',    false),
  ('ACOSO',                false),
  ('INTENTO_PRIVACION',    false),
  ('EXTORSION_AMENAZA',    false),
  ('ZONA_SIN_ILUMINACION', false),
  ('VENTA_SUSTANCIAS',     false);

-- Catálogo v1 publicado, con los tipos y la prioridad de la pantalla «Catálogo de incidentes».
-- Pesos de riesgo y los tipos que la pantalla no detalla: propuestos.
INSERT INTO version_catalogo (numero, estado, publicada_en) VALUES (1, 'PUBLICADA', now());

INSERT INTO tipo_incidente_version (version_catalogo_id, tipo_incidente_id, es_generica, nombre, categoria,
                                    prioridad, aplica_a, datos_minimos, peso_riesgo, activo)
SELECT v.id, t.id, t.es_generica, x.nombre, x.categoria::incidente_categoria,
       x.prioridad, x.aplica::tipo_aplica_a, x.datos::jsonb, x.peso, x.activo
FROM (VALUES
  ('GENERICA',             'Genérica',                        NULL,      2, 'ALERTA',  '["ubicacion"]',                1.00, true),
  ('PERSONA_SOSPECHOSA',   'Persona sospechosa siguiéndome',  'FISICO',  1, 'AMBOS',   '["ubicacion","descripcion"]',  1.50, true),
  ('ASALTO_ROBO',          'Asalto o robo',                   'FISICO',  1, 'AMBOS',   '["ubicacion"]',                3.00, true),
  ('AGRESION_FISICA',      'Agresión física',                 'FISICO',  1, 'ALERTA',  '["ubicacion"]',                1.00, true),
  ('EMERGENCIA_MEDICA',    'Emergencia médica',               'FISICO',  1, 'ALERTA',  '["ubicacion"]',                1.00, true),
  ('ACOSO',                'Acoso',                           'SOCIAL',  2, 'AMBOS',   '["descripcion"]',              2.50, true),
  ('INTENTO_PRIVACION',    'Intento de privación de libertad','SOCIAL',  1, 'ALERTA',  '["ubicacion","descripcion"]',  1.00, true),
  ('EXTORSION_AMENAZA',    'Extorsión o amenaza',             'DIGITAL', 3, 'AMBOS',   '["descripcion"]',              1.50, true),
  ('ZONA_SIN_ILUMINACION', 'Zona sin iluminación',            'FISICO',  5, 'REPORTE', '["ubicacion"]',                1.50, true),
  ('VENTA_SUSTANCIAS',     'Venta de sustancias',             'SOCIAL',  4, 'REPORTE', '["ubicacion"]',                2.00, false)
) AS x(codigo, nombre, categoria, prioridad, aplica, datos, peso, activo)
JOIN tipo_incidente t ON t.codigo = x.codigo
CROSS JOIN version_catalogo v WHERE v.numero = 1;

-- Reglas de canalización de los tipos de alerta (8 de los 10 tipos; los dos solo de reporte no tienen):
--   Seguridad IPN, 120 s: Genérica, Persona sospechosa, Acoso, Intento de privación de libertad
--   SSC CDMX (C5), 90 s:  Asalto o robo, Agresión física, Extorsión o amenaza
--   ERUM, 60 s:           Emergencia médica
INSERT INTO regla_canalizacion (version_catalogo_id, tipo_incidente_id, corporacion_destino_id, tiempo_max_acuse_seg)
SELECT v.id, t.id, c.id, x.acuse
FROM (VALUES
  ('GENERICA',           'SEG_ESCOLAR', 120),
  ('PERSONA_SOSPECHOSA', 'SEG_ESCOLAR', 120),
  ('ACOSO',              'SEG_ESCOLAR', 120),
  ('INTENTO_PRIVACION',  'SEG_ESCOLAR', 120),
  ('ASALTO_ROBO',        'SSC_C5',       90),
  ('AGRESION_FISICA',    'SSC_C5',       90),
  ('EXTORSION_AMENAZA',  'SSC_C5',       90),
  ('EMERGENCIA_MEDICA',  'ERUM',         60)
) AS x(codigo, canal, acuse)
JOIN tipo_incidente t ON t.codigo = x.codigo
JOIN corporacion    c ON c.canal_codigo = x.canal
CROSS JOIN version_catalogo v WHERE v.numero = 1;

-- Cadenas de escalamiento (20 pasos), cada nivel espera el mismo tiempo de acuse:
--   destino Seguridad IPN: 1.º SSC CDMX (C5), 2.º PBI, 3.º Coordinador del círculo
--   destino SSC CDMX (C5): 1.º Seguridad IPN, 2.º Coordinador del círculo
--   destino ERUM:          1.º SSC CDMX (C5), 2.º Coordinador del círculo
INSERT INTO paso_escalamiento (regla_id, orden, destino_tipo, corporacion_id)
SELECT r.id, p.orden, p.destino::destino_escalamiento,
       (SELECT id FROM corporacion WHERE canal_codigo = p.corp)
FROM regla_canalizacion r
JOIN corporacion c ON c.id = r.corporacion_destino_id
JOIN (VALUES
  ('SEG_ESCOLAR', 1, 'CORPORACION',        'SSC_C5'),
  ('SEG_ESCOLAR', 2, 'CORPORACION',        'PBI'),
  ('SEG_ESCOLAR', 3, 'COORDINADOR_CIRCULO', NULL),
  ('SSC_C5',      1, 'CORPORACION',        'SEG_ESCOLAR'),
  ('SSC_C5',      2, 'COORDINADOR_CIRCULO', NULL),
  ('ERUM',        1, 'CORPORACION',        'SSC_C5'),
  ('ERUM',        2, 'COORDINADOR_CIRCULO', NULL)
) AS p(canal, orden, destino, corp) ON p.canal = c.canal_codigo;

INSERT INTO parametro_sistema (clave, valor, tipo_dato, descripcion) VALUES
  ('cuenta_regresiva_seg',          '5',    'INT',  'Cuenta regresiva de cancelación (RF-13, ejemplo del documento)'),
  ('cuenta_regresiva_opciones',     '3,5,10','TEXT','Segundos que la persona puede elegir en Activación y energía'),
  ('malla_ttl_saltos',              '5',    'INT',  'TTL inicial de un paquete de la red malla (E-04)'),
  ('login_max_intentos',            '5',    'INT',  'Intentos fallidos antes de BLOQUEADA_TEMPORAL (CU-02)'),
  ('login_bloqueo_minutos',         '15',   'INT',  'Duración del bloqueo temporal (propuesto)'),
  ('cola_max_mb',                   '20',   'INT',  'Límite de la cola offline (RNF15)'),
  ('cola_ttl_acuse_horas',          '2',    'INT',  'Vencimiento de un acuse pendiente en la cola offline'),
  ('cola_ttl_alerta_horas',         '72',   'INT',  'Vencimiento de una alerta pendiente en la cola offline (propuesto)'),
  ('cola_ttl_reporte_dias',         '6',    'INT',  'Vencimiento de un reporte pendiente en la cola offline'),
  ('meta_acuse_seg',                '120',  'INT',  'Meta de acuse en segundos (RNF4)'),
  ('codigo_invitacion_dias',        '7',    'INT',  'Vigencia del código de invitación (propuesto)'),
  ('riesgo_alfa',                   '0.35', 'NUM',  'Factor α del peso de ruta = distancia × (1 + α·riesgo) (A-07, valor del Diseño de E/S)'),
  ('dbscan_eps_m',                  '100',  'INT',  'Radio ε de DBSCAN en metros (A-06, propuesto)'),
  ('dbscan_min_puntos',             '3',    'INT',  'minPts de DBSCAN (A-06, propuesto)'),
  ('precision_aceptable_m',         '20',   'INT',  'Precisión de ubicación considerada buena (RNF7)'),
  ('ia_timeout_ms',                 '200',  'INT',  'Tiempo máximo de IA-4 antes de ordenar la cola por la prioridad del catálogo');

-- Modelos de IA del Diseño de E/S (nombres y versiones; los valores de umbral y parámetros son de ejemplo).
INSERT INTO modelo_ia (codigo, nombre, identificador, ejecucion, estado, umbral_confianza, parametros, activado_en) VALUES
  ('IA-1', 'Clasificación asistida',     'clasificador-v2.3', 'MIXTO',    'ACTIVO',    0.70, '{}',                                                  now()),
  ('IA-2', 'Riesgo predictivo por zona', 'riesgo-v1.0',       'SERVIDOR', 'ACTIVO',    NULL, '{"celda_m": 150, "franjas": 8, "min_reportes_celda": 3}', now()),
  ('IA-3', 'Moderación asistida',        'moderacion-v1.2',   'SERVIDOR', 'ACTIVO',    NULL, '{}',                                                  now()),
  ('IA-4', 'Priorización de alertas',    'prioridad-v0.9',    'SERVIDOR', 'EN_PRUEBA', NULL, '{"timeout_ms": 200}',                                 NULL);

-- ---------------------------------------------------------------------
-- 9. COMENTARIOS DE TABLA (alimentan el diccionario de datos)
-- ---------------------------------------------------------------------

COMMENT ON TABLE institucion           IS 'Institución educativa (RF-1)';
COMMENT ON TABLE escuela               IS 'Escuela o plantel de una institución (RF-1)';
COMMENT ON TABLE aviso_privacidad      IS 'Versiones del aviso de privacidad (RNF10)';
COMMENT ON TABLE usuario               IS 'Cuenta de un integrante de la comunidad, con su perfil (RF-1, RF-2)';
COMMENT ON TABLE consentimiento        IS 'Aceptación del aviso de privacidad por usuario (RF-1, RNF10)';
COMMENT ON TABLE dispositivo           IS 'Teléfono del usuario: token FCM y soporte de Wi-Fi Aware (CU-02, RNF14)';
COMMENT ON TABLE sesion                IS 'Sesión autenticada; se guarda el hash del token (CU-02, RNF9)';
COMMENT ON TABLE recuperacion_password IS 'Código de un solo uso para restablecer la contraseña; solo se guarda su hash (CU-02, RNF9)';
COMMENT ON TABLE contacto_confianza    IS 'Contactos que reciben la alerta del usuario: PUSH si tienen cuenta vinculada, SMS si no (RF-3, RF-9)';
COMMENT ON TABLE circulo               IS 'Primer círculo comunitario de un grupo escolar (RF-3)';
COMMENT ON TABLE miembro_circulo       IS 'Pertenencia de un usuario a un círculo (RF-3)';
COMMENT ON TABLE solicitud_arco        IS 'Solicitudes de derechos ARCO (RNF10)';
COMMENT ON TABLE corporacion           IS 'Corporación o canal destinatario de alertas (RF-4)';
COMMENT ON TABLE version_catalogo      IS 'Versión del catálogo de incidentes y reglas (RF-4, E-09)';
COMMENT ON TABLE tipo_incidente        IS 'Identidad estable de un tipo de incidente, común a alertas y reportes (RF-4)';
COMMENT ON TABLE tipo_incidente_version IS 'Configuración de un tipo en una versión del catálogo: nombre, categoría, prioridad, aplica a, datos mínimos y peso (RF-4)';
COMMENT ON TABLE regla_canalizacion    IS 'Corporación destino y tiempo de acuse de un tipo de alerta en una versión del catálogo (RF-4)';
COMMENT ON TABLE paso_escalamiento     IS 'Cadena ordenada de escalamiento de una regla (RF-12, CU-09)';
COMMENT ON TABLE unidad_atencion       IS 'Unidad de una corporación con su última ubicación (RF-4, RF-10, E-08)';
COMMENT ON TABLE unidad_tipo_atencion  IS 'Tipos de incidente que atiende cada unidad (RF-4)';
COMMENT ON TABLE operador              IS 'Usuario operador vinculado a una unidad (CU-10)';
COMMENT ON TABLE parametro_sistema     IS 'Parámetros configurables: TTL, tiempos, α, DBSCAN';
COMMENT ON TABLE folio_secuencia       IS 'Contador por prefijo y año de los folios ALR y REP';
COMMENT ON TABLE alerta                IS 'Alerta de auxilio; id generado en el dispositivo (RF-5 a RF-13, E-01)';
COMMENT ON TABLE ubicacion_alerta      IS 'Lecturas de ubicación de una alerta con precisión y fuente (RF-7)';
COMMENT ON TABLE asignacion            IS 'Unidad asignada a una alerta (RF-10)';
COMMENT ON TABLE escalamiento          IS 'Escalamientos realizados por falta de acuse (RF-12)';
COMMENT ON TABLE evento_alerta         IS 'Historial de cambios de estado de la alerta; solo inserción (RF-11, RNF16)';
COMMENT ON TABLE intento_cancelacion   IS 'Cancelaciones e intentos fallidos con PIN o biometría; solo inserción (RF-13)';
COMMENT ON TABLE enlace_ubicacion      IS 'Enlace seguro de ubicación de una alerta o de un trayecto para la red de apoyo (RF-9)';
COMMENT ON TABLE mensaje_sms           IS 'SMS de contingencia recibidos en la pasarela, válidos o no (RF-16)';
COMMENT ON TABLE notificacion          IS 'Entregas push y SMS con política de reintentos (RF-8, RF-9, E-03)';
COMMENT ON TABLE reporte               IS 'Reporte comunitario con identidad protegida si es anónimo (RF-19, RNF11, E-02)';
COMMENT ON TABLE evidencia             IS 'Fotografías de un reporte; el archivo vive en el almacén de objetos (RF-19)';
COMMENT ON TABLE decision_moderacion   IS 'Decisiones del Administrador sobre reportes; solo inserción (RF-20)';
COMMENT ON TABLE reporte_publico       IS 'Versión pública del reporte sin identidad y con ubicación aproximada (RF-20, RF-21)';
COMMENT ON TABLE capa_riesgo           IS 'Versión de la capa del mapa de calor (RF-22)';
COMMENT ON TABLE zona_riesgo           IS 'Zona de riesgo calculada con DBSCAN (RF-22)';
COMMENT ON TABLE zona_reporte          IS 'Reportes que alimentan una zona (RF-22)';
COMMENT ON TABLE nodo_vial             IS 'Nodo del grafo peatonal (RF-23)';
COMMENT ON TABLE arista_vial           IS 'Tramo del grafo peatonal (RF-23)';
COMMENT ON TABLE riesgo_tramo          IS 'Riesgo de cada tramo por versión de capa (RF-23, A-07)';
COMMENT ON TABLE trayecto              IS 'Ruta que la persona inicia y comparte con su red de apoyo (RF-23)';
COMMENT ON TABLE bitacora              IS 'Bitácora inmutable con cadena de hashes (RNF16)';
COMMENT ON TABLE configuracion_usuario IS 'Parámetros de seguridad de cada persona: check-in, palabras de voz y sensibilidad de caídas (P3)';
COMMENT ON TABLE checkin_trayecto      IS 'Confirmaciones de presencia esperadas y recibidas durante un trayecto (P7)';
COMMENT ON TABLE punto_interes         IS 'Puntos de interés de seguridad del mapa comunitario (P9)';
COMMENT ON TABLE paquete_mapa          IS 'Paquetes de mapas y ruteo para descarga sin conexión (P12)';
COMMENT ON TABLE zona_geografica      IS 'Zonas con nombre (caja de coordenadas) para filtrar el tablero y rotular el mapa';
COMMENT ON VIEW  v_historial_usuario   IS 'Línea de tiempo de alertas, reportes y trayectos de cada persona (P13)';
COMMENT ON TABLE modelo_ia             IS 'Modelos de IA IA-1 a IA-4 con su versión, estado y parámetros';
COMMENT ON TABLE retroalimentacion_ia  IS 'Sugerencia del modelo frente a la decisión humana, sin datos de identidad';
COMMENT ON TABLE analisis_reporte_ia   IS 'Resultado de IA-3 sobre cada reporte recibido; solo sugiere';
COMMENT ON TABLE pronostico_riesgo     IS 'Riesgo pronosticado por celda de 150 m y franja de 3 horas (IA-2)';

-- ---------------------------------------------------------------------
-- 10. ROLES Y PERMISOS (opcional: requiere privilegios de superusuario)
-- ---------------------------------------------------------------------

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sendero_api') THEN
    CREATE ROLE sendero_api NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sendero_feed') THEN
    CREATE ROLE sendero_feed NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sendero_auditor') THEN
    CREATE ROLE sendero_auditor NOLOGIN;
  END IF;

  -- API del backend: lectura y escritura general, bitácora solo inserción
  GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sendero_api;
  GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sendero_api;
  REVOKE UPDATE, DELETE, TRUNCATE ON bitacora, evento_alerta, intento_cancelacion, decision_moderacion FROM sendero_api;

  -- Feed y mapa: solo la versión pública, sin acceso a identidad
  GRANT SELECT ON reporte_publico, tipo_incidente, tipo_incidente_version, version_catalogo,
                  capa_riesgo, zona_riesgo, pronostico_riesgo, riesgo_tramo,
                  nodo_vial, arista_vial, punto_interes, paquete_mapa TO sendero_feed;

  -- Auditoría: solo lectura de la bitácora
  GRANT SELECT ON bitacora TO sendero_auditor;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'Sin privilegios para crear roles: omita la sección 10 o ejecútela como superusuario.';
END $$;

