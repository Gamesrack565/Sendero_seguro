-- Módulo 3 en el servidor (P9 a P12): parámetros del mapa de calor e índices de reportes, mapa y grafo peatonal.

-- Niveles de una zona del mapa de calor (A-06): suma de los pesos de riesgo de sus reportes.
INSERT INTO parametro_sistema (clave, valor, tipo_dato, descripcion) VALUES
  ('riesgo_umbral_medio', '4', 'NUM', 'Suma de pesos desde la que una zona del mapa de calor es MEDIO (A-06)'),
  ('riesgo_umbral_alto',  '8', 'NUM', 'Suma de pesos desde la que una zona del mapa de calor es ALTO (A-06)'),
  ('reporte_max_evidencias', '3', 'INT', 'Fotos o audios por reporte (A-15)')
ON CONFLICT (clave) DO NOTHING;

-- Un reporte es idempotente por id y la misma evidencia no se guarda dos veces.
CREATE UNIQUE INDEX uq_evidencia_objeto ON evidencia (reporte_id, url_objeto);
-- Feed y mapa: reportes publicados por fecha (los vigentes ya tienen índice).
CREATE INDEX ix_reporte_estado_fecha ON reporte (estado, recibido_en DESC);
-- Grafo peatonal: búsqueda del nodo más cercano por caja.
CREATE INDEX ix_nodo_ubicacion ON nodo_vial (latitud, longitud);
