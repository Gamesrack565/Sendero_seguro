-- Versiona las cargas del grafo para que los teléfonos detecten cambios en las rutas offline.
INSERT INTO parametro_sistema (clave, valor, tipo_dato, descripcion)
VALUES ('version_grafo', '0', 'INT', 'Versión del grafo peatonal descargable por los teléfonos')
ON CONFLICT (clave) DO NOTHING;
