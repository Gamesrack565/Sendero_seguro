-- =====================================================================
-- Datos de prueba SOLO PARA DESARROLLO (db.datosPrueba = true).
-- En la nube se apaga con DB_DATOS_PRUEBA=false y este archivo no se carga.
--
-- Flyway lo vuelve a correr cada vez que cambia (migración repetible "R__"),
-- por eso cada INSERT usa ON CONFLICT DO NOTHING: correrlo dos veces no duplica.
--
-- Contraseña de todas las cuentas de prueba: sendero123 (hash bcrypt).
-- Ubicación de referencia: ESCOM (19.504507, -99.146911).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Personas
-- ---------------------------------------------------------------------
INSERT INTO usuario (id, nombre, telefono_movil, correo, identificador_institucional,
                     tipo_comunidad, escuela_id, password_hash, rol)
SELECT x.id::uuid, x.nombre, x.tel, x.correo, x.ident, x.comunidad::tipo_comunidad,
       (SELECT id FROM escuela WHERE clave = 'ESCOM'),
       '$2a$10$/o29GJrLqBxIcgI9A7qj5.MWUTW.2UPAtWIYhnvU/QV10XULFAfM6', x.rol::usuario_rol
FROM (VALUES
  -- Quien activa las alertas de prueba
  ('00000000-0000-4000-8000-000000000001', 'Ana Prueba',          '5510000001', 'ana@prueba.mx',   '2026630001', 'ALUMNO',   'INTEGRANTE'),
  -- Coordinador del círculo de Ana; también es su contacto con app
  ('00000000-0000-4000-8000-000000000002', 'Beto Prueba',         '5510000002', 'beto@prueba.mx',  '2026630002', 'ALUMNO',   'COORDINADOR'),
  ('00000000-0000-4000-8000-000000000003', 'Admin Prueba',        '5510000003', 'admin@prueba.mx', 'ADM-0001',   'PERSONAL', 'ADMINISTRADOR'),
  -- Un operador por unidad con turno
  ('00000000-0000-4000-8000-000000000011', 'Operador Caseta',     '5510000011', NULL, 'OP-0011', 'PERSONAL', 'OPERADOR'),
  ('00000000-0000-4000-8000-000000000012', 'Operador C5',         '5510000012', NULL, 'OP-0012', 'PERSONAL', 'OPERADOR'),
  ('00000000-0000-4000-8000-000000000013', 'Operador ERUM',       '5510000013', NULL, 'OP-0013', 'PERSONAL', 'OPERADOR'),
  ('00000000-0000-4000-8000-000000000014', 'Operador PBI',        '5510000014', NULL, 'OP-0014', 'PERSONAL', 'OPERADOR')
) AS x(id, nombre, tel, correo, ident, comunidad, rol)
ON CONFLICT DO NOTHING;

INSERT INTO configuracion_usuario (usuario_id)
VALUES ('00000000-0000-4000-8000-000000000001')
ON CONFLICT DO NOTHING;

-- Teléfono de Ana (el token FCM es de mentira; sirve para probar el armado del push)
INSERT INTO dispositivo (id, usuario_id, modelo, android_api, fcm_token)
VALUES ('00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-000000000001',
        'Pixel de prueba', 34, 'token-fcm-de-prueba-ana')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------
-- Red de apoyo de Ana: un contacto sin app (SMS) y uno con app (push)
-- ---------------------------------------------------------------------
INSERT INTO contacto_confianza (id, usuario_id, nombre, telefono, usuario_vinculado_id,
                                nivel_acceso, estado_confirmacion, canal_preferido, prioridad)
VALUES
  ('00000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-000000000001',
   'Mamá de Ana', '5520000001', NULL, 'SEGUIMIENTO', 'CONFIRMADO', 'SMS', 1),
  ('00000000-0000-4000-8000-0000000000c2', '00000000-0000-4000-8000-000000000001',
   'Beto Prueba', '5510000002', '00000000-0000-4000-8000-000000000002', 'SEGUIMIENTO', 'CONFIRMADO', 'PUSH', 2)
ON CONFLICT DO NOTHING;

INSERT INTO circulo (id, coordinador_id, escuela_id, nombre_grupo, codigo_invitacion, codigo_vence_en)
SELECT '00000000-0000-4000-8000-0000000000e1', '00000000-0000-4000-8000-000000000002',
       e.id, 'Grupo de prueba 5CV1', 'PRUEBA01', now() + interval '1 year'
FROM escuela e WHERE e.clave = 'ESCOM'
ON CONFLICT DO NOTHING;

INSERT INTO miembro_circulo (circulo_id, usuario_id)
SELECT '00000000-0000-4000-8000-0000000000e1', u.id::uuid
FROM (VALUES ('00000000-0000-4000-8000-000000000001'), ('00000000-0000-4000-8000-000000000002')) AS u(id)
WHERE NOT EXISTS (
  SELECT 1 FROM miembro_circulo m
  WHERE m.circulo_id = '00000000-0000-4000-8000-0000000000e1' AND m.usuario_id = u.id::uuid AND m.estado = 'ACTIVA');

-- ---------------------------------------------------------------------
-- Unidades de atención, a distintas distancias de ESCOM
-- ---------------------------------------------------------------------
INSERT INTO unidad_atencion (id, corporacion_id, nombre, estado, ultima_latitud, ultima_longitud, ultima_ubicacion_en)
SELECT x.id::uuid, c.id, x.nombre, x.estado::unidad_estado, x.lat, x.lon, now()
FROM (VALUES
  -- Seguridad IPN
  ('00000000-0000-4000-8000-0000000000a1', 'SEG_ESCOLAR', 'Caseta ESCOM',          'DISPONIBLE',        19.504600, -99.147000),  -- ~15 m
  ('00000000-0000-4000-8000-0000000000a2', 'SEG_ESCOLAR', 'Rondín Zacatenco',      'DISPONIBLE',        19.501000, -99.134000),  -- ~1.4 km
  ('00000000-0000-4000-8000-0000000000a3', 'SEG_ESCOLAR', 'Caseta Biblioteca',     'FUERA_DE_SERVICIO', 19.505500, -99.146500),  -- cerca, pero fuera de servicio
  -- SSC CDMX (C5)
  ('00000000-0000-4000-8000-0000000000a4', 'SSC_C5',      'Patrulla Lindavista',   'DISPONIBLE',        19.489000, -99.130000),
  ('00000000-0000-4000-8000-0000000000a5', 'SSC_C5',      'Patrulla Ticomán',      'DISPONIBLE',        19.520000, -99.140000),
  -- ERUM
  ('00000000-0000-4000-8000-0000000000a6', 'ERUM',        'Ambulancia ERUM 12',    'DISPONIBLE',        19.495000, -99.150000),
  -- PBI
  ('00000000-0000-4000-8000-0000000000a7', 'PBI',         'PBI Zacatenco',         'DISPONIBLE',        19.500000, -99.138000)
) AS x(id, canal, nombre, estado, lat, lon)
JOIN corporacion c ON c.canal_codigo = x.canal
ON CONFLICT DO NOTHING;

INSERT INTO operador (usuario_id, unidad_id)
VALUES
  ('00000000-0000-4000-8000-000000000011', '00000000-0000-4000-8000-0000000000a1'),
  ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-0000000000a4'),
  ('00000000-0000-4000-8000-000000000013', '00000000-0000-4000-8000-0000000000a6'),
  ('00000000-0000-4000-8000-000000000014', '00000000-0000-4000-8000-0000000000a7')
ON CONFLICT DO NOTHING;
