import React, { useMemo, useState } from 'react';
import { AlertCircle, CheckCircle2, Image as ImageIcon, Lock, ShieldCheck, Sparkles, X } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { ReporteComunitario } from '../types/domain';

const MOTIVOS_RECHAZO = [
  'Duplicado',
  'Datos personales no redactables',
  'Lenguaje ofensivo o spam',
  'Ubicación fuera de zona de servicio',
  'Información insuficiente o falsa',
];

function renderHighlightedDescription(reporte: ReporteComunitario) {
  const token = reporte.analisis_ia.texto_sensible_resaltado;
  if (!token || !reporte.descripcion.includes(token)) {
    return reporte.descripcion;
  }
  const parts = reporte.descripcion.split(token);
  return (
    <>
      {parts[0]}
      <mark
        className="mono"
        style={{
          background: '#efe8fa',
          color: '#8350c4',
          padding: '2px 6px',
          borderRadius: 5,
          fontWeight: 700,
        }}
      >
        {token}
      </mark>
      {parts.slice(1).join(token)}
    </>
  );
}

// W-05. Moderación de reportes (pág. 51, CU-30, CU-31, RF-20, P15)
export const ModeracionPage: React.FC = () => {
  const { reportes, moderarReporte, consultarIdentidadProtegida } = useSendero();

  const pendientes = useMemo(
    () => reportes.filter((r) => r.estado === 'PENDIENTE'),
    [reportes]
  );

  const [selectedId, setSelectedId] = useState<string>(pendientes[0]?.id ?? '');
  const [motivoRechazo, setMotivoRechazo] = useState(MOTIVOS_RECHAZO[0]);
  const [toast, setToast] = useState<string | null>(null);

  // Modal CU-31: Consultar identidad protegida
  const [showCu31Modal, setShowCu31Modal] = useState(false);
  const [justificacionCu31, setJustificacionCu31] = useState('');
  const [cu31Error, setCu31Error] = useState<string | null>(null);

  // Modal referencia a reporte duplicado (REP-2026-000388)
  const [showDuplicateModal, setShowDuplicateModal] = useState<string | null>(null);

  const selectedReporte =
    pendientes.find((r) => r.id === selectedId) ?? pendientes[0] ?? null;

  const notify = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 4500);
  };

  const handlePublicar = () => {
    if (!selectedReporte) return;
    const tienePii = selectedReporte.analisis_ia.prob_datos_personales >= 0.5;
    moderarReporte(selectedReporte.id, 'PUBLICAR', {
      ocultarDatos: tienePii,
      unirConFolio: selectedReporte.analisis_ia.duplicado_de_folio,
    });
    notify(
      `Reporte ${selectedReporte.folio} publicado en el feed comunitario${tienePii ? ' (ocultando datos personales)' : ''} y guardado en retroalimentacion_ia.`
    );
  };

  const handleRechazar = () => {
    if (!selectedReporte) return;
    moderarReporte(selectedReporte.id, 'RECHAZAR', { motivo: motivoRechazo });
    notify(`Reporte ${selectedReporte.folio} rechazado con motivo «${motivoRechazo}».`);
  };

  const handleConsultarIdentidad = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedReporte) return;
    const res = consultarIdentidadProtegida(selectedReporte.id, justificacionCu31);
    if (!res.ok) {
      setCu31Error(res.error ?? 'Error de validación.');
      return;
    }
    setCu31Error(null);
    setJustificacionCu31('');
    setShowCu31Modal(false);
    notify(`Consulta de identidad protegida (CU-31) registrada en bitácora para ${selectedReporte.folio}.`);
  };

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Moderación de reportes</h1>
          <span className="page-subtitle mono">
            {pendientes.length} PENDIENTES
            {pendientes[0] ? ` · el más antiguo ${pendientes[0].hace_texto}` : ''}
          </span>
        </div>
      </div>

      {toast && (
        <div className="alert-banner-green" style={{ marginBottom: 16 }}>
          <CheckCircle2 size={16} />
          <span>{toast}</span>
        </div>
      )}

      {pendientes.length === 0 ? (
        <div className="card card-pad" style={{ textAlign: 'center', padding: 48 }}>
          <ShieldCheck size={38} color="#8350c4" style={{ marginBottom: 10 }} />
          <h2 style={{ margin: '0 0 6px 0' }}>Cola de moderación al día</h2>
          <p style={{ margin: 0, color: 'var(--text-secondary)' }}>
            Todos los reportes pendientes fueron revisados y registrados en la bitácora.
          </p>
        </div>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(270px, 340px) minmax(0, 1fr)',
            gap: 20,
            alignItems: 'start',
          }}
        >
          {/* Cola PENDIENTE */}
          <section className="card" style={{ overflow: 'hidden' }}>
            <div
              style={{
                padding: '14px 16px',
                borderBottom: '1px solid var(--border-light)',
                fontWeight: 800,
                fontSize: 15,
              }}
            >
              Cola PENDIENTE
            </div>
            <div>
              {pendientes.map((rep) => {
                const active = selectedReporte?.id === rep.id;
                return (
                  <button
                    key={rep.id}
                    type="button"
                    onClick={() => setSelectedId(rep.id)}
                    style={{
                      width: '100%',
                      textAlign: 'left',
                      border: 'none',
                      borderBottom: '1px solid #e6f0f6',
                      padding: '14px 16px',
                      background: active ? '#dcebf3' : '#ffffff',
                      cursor: 'pointer',
                      display: 'block',
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'baseline',
                        marginBottom: 8,
                        gap: 8,
                      }}
                    >
                      <strong style={{ fontSize: 14.5, color: 'var(--text-primary)' }}>
                        {rep.tipo_nombre}
                      </strong>
                      <span className="mono" style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>
                        {rep.hace_texto}
                      </span>
                    </div>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {rep.etiquetas_ia.map((tag) => (
                        <span
                          key={tag.texto}
                          className={tag.tono === 'ia' ? 'pill pill-ia' : 'pill pill-green'}
                        >
                          {tag.texto}
                        </span>
                      ))}
                    </div>
                  </button>
                );
              })}
            </div>
          </section>

          {/* Detalle del reporte seleccionado */}
          {selectedReporte && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              {/* Tarjeta principal del reporte */}
              <section className="card card-pad">
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 10,
                    marginBottom: 12,
                    flexWrap: 'wrap',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span className="mono" style={{ fontSize: 13.5, color: 'var(--text-secondary)' }}>
                      {selectedReporte.folio}
                    </span>
                    <span className="pill pill-gray">{selectedReporte.estado}</span>
                    {selectedReporte.es_anonimo ? (
                      <span className="pill pill-dark">ANÓNIMO</span>
                    ) : (
                      <span className="pill pill-blue">CON NOMBRE</span>
                    )}
                  </div>
                  <span className="mono" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                    {selectedReporte.fecha_texto}
                  </span>
                </div>

                <h2 style={{ margin: '0 0 12px 0', fontSize: 22, fontWeight: 800 }}>
                  {selectedReporte.tipo_nombre}
                </h2>

                <p style={{ margin: '0 0 18px 0', fontSize: 14.5, lineHeight: 1.55 }}>
                  {renderHighlightedDescription(selectedReporte)}
                </p>

                <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
                  {selectedReporte.tiene_evidencia_foto && (
                    <div
                      style={{
                        width: 112,
                        height: 82,
                        borderRadius: 10,
                        background: '#402b47',
                        color: '#c9e0eb',
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: 4,
                        fontSize: 11,
                        flexShrink: 0,
                      }}
                      title="Evidencia fotográfica privada (solo visible en moderación)"
                    >
                      <ImageIcon size={20} />
                      <span>Evidencia (1)</span>
                    </div>
                  )}

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13.5 }}>
                    <div>
                      <strong>Ubicación exacta (solo moderación):</strong>{' '}
                      <span className="mono">
                        {selectedReporte.latitud.toFixed(5)}, {selectedReporte.longitud.toFixed(5)}
                      </span>
                    </div>
                    <div>
                      <strong>Ubicación pública:</strong> {selectedReporte.ubicacion_publica}
                    </div>
                    {!selectedReporte.es_anonimo && (
                      <div>
                        <strong>Autor visible:</strong> {selectedReporte.autor_protegido.nombre} (
                        {selectedReporte.autor_protegido.escuela})
                      </div>
                    )}
                  </div>
                </div>

                {/* Si se reveló identidad protegida por CU-31 */}
                {selectedReporte.es_anonimo && selectedReporte.identidad_revelada && (
                  <div
                    style={{
                      marginTop: 16,
                      padding: '12px 14px',
                      borderRadius: 10,
                      background: '#e5f0f7',
                      border: '1px solid #7392b5',
                      color: '#262423',
                      fontSize: 13,
                    }}
                  >
                    <strong>Identidad protegida descifrada (CU-31 · registrado en bitácora):</strong>{' '}
                    {selectedReporte.autor_protegido.nombre} · Matrícula{' '}
                    <span className="mono">{selectedReporte.autor_protegido.matricula}</span> ·{' '}
                    {selectedReporte.autor_protegido.escuela} · Tel.{' '}
                    <span className="mono">{selectedReporte.autor_protegido.telefono}</span>
                  </div>
                )}
              </section>

              {/* Tarjeta Análisis IA-3 */}
              <section
                className="card card-pad"
                style={{ borderColor: 'var(--ia-purple-border)' }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: 14,
                    gap: 10,
                  }}
                >
                  <span className="pill pill-ia" style={{ padding: '4px 11px', fontSize: 12 }}>
                    <Sparkles size={13} />
                    Análisis IA-3 · modelo {selectedReporte.analisis_ia.modelo_identificador}
                  </span>
                  <span style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                    La decisión es tuya
                  </span>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13.5 }}>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      paddingBottom: 10,
                      borderBottom: '1px solid #f0eee8',
                      gap: 12,
                    }}
                  >
                    <span>
                      {selectedReporte.analisis_ia.duplicado_de_folio ? (
                        <>
                          Posible duplicado de{' '}
                          <button
                            type="button"
                            className="mono"
                            onClick={() =>
                              setShowDuplicateModal(selectedReporte.analisis_ia.duplicado_de_folio!)
                            }
                            style={{
                              background: 'none',
                              border: 'none',
                              padding: 0,
                              color: 'var(--primary-blue)',
                              fontWeight: 700,
                              textDecoration: 'underline',
                              cursor: 'pointer',
                            }}
                          >
                            {selectedReporte.analisis_ia.duplicado_de_folio}
                          </button>{' '}
                          {selectedReporte.analisis_ia.duplicado_nota}
                        </>
                      ) : (
                        'Probabilidad de duplicado con reportes publicados'
                      )}
                    </span>
                    <strong className="mono">
                      {selectedReporte.analisis_ia.prob_duplicado.toFixed(2)}
                    </strong>
                  </div>

                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      paddingBottom: 10,
                      borderBottom: '1px solid #f0eee8',
                      gap: 12,
                    }}
                  >
                    <span>
                      Datos personales en el texto
                      {selectedReporte.analisis_ia.datos_personales_detalle
                        ? `: ${selectedReporte.analisis_ia.datos_personales_detalle}`
                        : ''}
                    </span>
                    <strong className="mono">
                      {selectedReporte.analisis_ia.prob_datos_personales.toFixed(2)}
                    </strong>
                  </div>

                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      paddingBottom: 12,
                      gap: 12,
                    }}
                  >
                    <span>Lenguaje ofensivo o spam</span>
                    <strong className="mono">
                      {selectedReporte.analisis_ia.prob_ofensivo.toFixed(2)}
                    </strong>
                  </div>
                </div>

                <div
                  style={{
                    background: '#efe8fa',
                    borderRadius: 10,
                    padding: '12px 14px',
                    fontSize: 13.5,
                  }}
                >
                  <strong>Sugerencia:</strong> {selectedReporte.analisis_ia.sugerencia}
                </div>
              </section>

              {/* Tarjeta Decisión */}
              <section className="card card-pad">
                <h3 style={{ margin: '0 0 14px 0', fontSize: 15.5, fontWeight: 800 }}>Decisión</h3>

                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    flexWrap: 'wrap',
                    paddingBottom: 16,
                    borderBottom: '1px solid #f0eee8',
                    marginBottom: 16,
                  }}
                >
                  <button type="button" className="btn btn-success" onClick={handlePublicar}>
                    {selectedReporte.analisis_ia.prob_datos_personales >= 0.5
                      ? 'Publicar (ocultando teléfono)'
                      : 'Publicar reporte'}
                  </button>

                  <button type="button" className="btn btn-outline-red" onClick={handleRechazar}>
                    Rechazar
                  </button>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <label htmlFor="motivo-rechazo" className="form-label" style={{ margin: 0 }}>
                      Motivo
                    </label>
                    <select
                      id="motivo-rechazo"
                      className="form-select"
                      style={{ width: 200 }}
                      value={motivoRechazo}
                      onChange={(e) => setMotivoRechazo(e.target.value)}
                    >
                      {MOTIVOS_RECHAZO.map((m) => (
                        <option key={m} value={m}>
                          {m}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
                  <button
                    type="button"
                    className="btn btn-outline"
                    disabled={!selectedReporte.es_anonimo}
                    onClick={() => {
                      setCu31Error(null);
                      setShowCu31Modal(true);
                    }}
                  >
                    <Lock size={15} />
                    Consultar identidad protegida
                  </button>
                  <span style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                    Solo si el reporte es abusivo. Pide justificación y queda en la bitácora (CU-31).
                  </span>
                </div>
              </section>
            </div>
          )}
        </div>
      )}

      {/* Modal CU-31: Justificación obligatoria (mín. 20 caracteres) */}
      {showCu31Modal && selectedReporte && (
        <div className="modal-backdrop" onClick={() => setShowCu31Modal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Lock size={18} color="#8350c4" />
                <h3 style={{ margin: 0, fontSize: 17 }}>Consulta de identidad protegida (CU-31)</h3>
              </div>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => setShowCu31Modal(false)}
              >
                <X size={14} />
              </button>
            </div>
            <p style={{ fontSize: 13.5, color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              Vas a descifrar el autor protegido del reporte{' '}
              <strong className="mono">{selectedReporte.folio}</strong>. Esta acción es auditable
              y quedará encadenada con SHA-256 en la tabla <code className="mono">bitacora</code>.
            </p>
            <form onSubmit={handleConsultarIdentidad}>
              <div className="form-field">
                <label htmlFor="cu31-just" className="form-label">
                  Justificación formal (mínimo 20 caracteres)
                </label>
                <textarea
                  id="cu31-just"
                  className="form-textarea"
                  value={justificacionCu31}
                  onChange={(e) => setJustificacionCu31(e.target.value)}
                  placeholder="Ej. Reporte reincidente con datos falsos que genera pánico injustificado en ESCOM..."
                  required
                />
                <div style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>
                  {justificacionCu31.trim().length} / 20 caracteres mínimos
                </div>
              </div>
              {cu31Error && (
                <div className="alert-banner-red" style={{ marginBottom: 12 }}>
                  <AlertCircle size={15} />
                  <span>{cu31Error}</span>
                </div>
              )}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button
                  type="button"
                  className="btn btn-outline"
                  onClick={() => setShowCu31Modal(false)}
                >
                  Cancelar
                </button>
                <button type="submit" className="btn btn-primary">
                  Registrar en bitácora y revelar
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal de inspección del reporte duplicado */}
      {showDuplicateModal && (
        <div className="modal-backdrop" onClick={() => setShowDuplicateModal(null)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <strong className="mono">{showDuplicateModal} · PUBLICADO</strong>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => setShowDuplicateModal(null)}
              >
                <X size={14} />
              </button>
            </div>
            <h3 style={{ margin: '0 0 8px 0' }}>Asalto o robo</h3>
            <p style={{ fontSize: 14, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              Dos sujetos en motocicleta están robando celulares a estudiantes saliendo del Metro
              Politécnico, entre 16:00 y 16:25 h.
            </p>
            <div className="mono" style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              Ubicación pública: celda de ~150 m, Zacatenco · Publicado hace 5 h · Anónimo
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
