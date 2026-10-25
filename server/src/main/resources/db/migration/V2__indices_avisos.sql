-- Búsquedas del envío de avisos (P16, E-03).
-- La confirmación de entrega del proveedor de SMS llega con su id de mensaje.
CREATE INDEX ix_notificacion_proveedor ON notificacion (proveedor_mensaje_id) WHERE proveedor_mensaje_id IS NOT NULL;
-- Envíos que esperan confirmación: vencen y se reintentan si no llega a tiempo.
CREATE INDEX ix_notificacion_sin_confirmar ON notificacion (proximo_reintento_en) WHERE estado = 'ENVIADA';
