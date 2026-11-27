import React, { useState } from 'react';
import { CheckCircle2, RefreshCw, Sparkles, X } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';

// W-10. Supervisión de modelos IA (pág. 56, CU-08, CU-37, RF-4, RF-26, P17)
export const ModelosIaPage: React.FC = () => {
  const {
    modelosIA,
    retroalimentacionIA,
    reentrenamientoInfo,
    actualizarUmbralIA1,
    actualizarMinReportesIA2,
    cambiarEstadoModeloIA4,
    ejecutarReentrenamientoIA,
  } = useSendero();

  const [showRetrainModal, setShowRetrainModal] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const notify = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 4500);
  };

  const ia1 = modelosIA.find((m) => m.codigo === 'IA-1')!;
  const ia2 = modelosIA.find((m) => m.codigo === 'IA-2')!;
  const ia3 = modelosIA.find((m) => m.codigo === 'IA-3')!;
  const ia4 = modelosIA.find((m) => m.codigo === 'IA-4')!;

  const umbralPct = (((ia1.umbral_confianza ?? 0.7) - 0.5) / (0.95 - 0.5)) * 100;
  const minRepPct = (((ia2.min_reportes_celda ?? 3) - 1) / (10 - 1)) * 100;

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Supervisión de modelos IA</h1>
        </div>
        <span className="page-subtitle">
          Valores de ejemplo para el diseño · se calculan con{' '}
          <span className="mono">retroalimentacion_ia</span>
        </span>
      </div>

      {toast && (
        <div className="alert-banner-green" style={{ marginBottom: 16 }}>
          <CheckCircle2 size={16} />
          <span>{toast}</span>
        </div>
      )}

      {/* Grid 2x2 de modelos IA-1 a IA-4 */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
          gap: 18,
          marginBottom: 18,
        }}
      >
        {/* IA-1 Clasificación asistida */}
        <section className="card card-pad">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <h2 style={{ margin: 0, fontSize: 16.5, fontWeight: 800 }}>{ia1.nombre}</h2>
            <span className="pill pill-green">{ia1.estado}</span>
          </div>
          <div className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 14 }}>
            {ia1.subtitulo}
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
              background: '#edf4f8',
              borderRadius: 10,
              padding: '12px 14px',
              marginBottom: 16,
            }}
          >
            {ia1.metricas.map((m) => (
              <div key={m.etiqueta}>
                <div style={{ fontSize: 20, fontWeight: 800 }}>{m.valor}</div>
                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>{m.etiqueta}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 10 }}>
            <label htmlFor="slider-ia1" style={{ fontSize: 13, fontWeight: 700, whiteSpace: 'nowrap' }}>
              Umbral de confianza
            </label>
            <input
              id="slider-ia1"
              type="range"
              className="ia-slider"
              min={0.5}
              max={0.95}
              step={0.01}
              value={ia1.umbral_confianza ?? 0.7}
              style={{
                background: `linear-gradient(to right, #8350c4 0%, #8350c4 ${umbralPct}%, #c9e0eb ${umbralPct}%, #c9e0eb 100%)`,
              }}
              onChange={(e) => actualizarUmbralIA1(Number(e.target.value))}
            />
            <span className="mono" style={{ fontWeight: 800, fontSize: 13.5, width: 38, textAlign: 'right' }}>
              {(ia1.umbral_confianza ?? 0.7).toFixed(2)}
            </span>
          </div>

          <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{ia1.nota_pie}</div>
        </section>

        {/* IA-2 Riesgo predictivo por zona */}
        <section className="card card-pad">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <h2 style={{ margin: 0, fontSize: 16.5, fontWeight: 800 }}>{ia2.nombre}</h2>
            <span className="pill pill-green">{ia2.estado}</span>
          </div>
          <div className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 14 }}>
            {ia2.subtitulo}
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
              background: '#edf4f8',
              borderRadius: 10,
              padding: '12px 14px',
              marginBottom: 16,
            }}
          >
            {ia2.metricas.map((m) => (
              <div key={m.etiqueta}>
                <div style={{ fontSize: 20, fontWeight: 800 }}>{m.valor}</div>
                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>{m.etiqueta}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 10 }}>
            <label htmlFor="slider-ia2" style={{ fontSize: 13, fontWeight: 700, maxWidth: 145, lineHeight: 1.25 }}>
              Mínimo de reportes por celda
            </label>
            <input
              id="slider-ia2"
              type="range"
              className="ia-slider"
              min={1}
              max={10}
              step={1}
              value={ia2.min_reportes_celda ?? 3}
              style={{
                background: `linear-gradient(to right, #8350c4 0%, #8350c4 ${minRepPct}%, #c9e0eb ${minRepPct}%, #c9e0eb 100%)`,
              }}
              onChange={(e) => actualizarMinReportesIA2(Number(e.target.value))}
            />
            <span className="mono" style={{ fontWeight: 800, fontSize: 13.5, width: 24, textAlign: 'right' }}>
              {ia2.min_reportes_celda ?? 3}
            </span>
          </div>

          <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{ia2.nota_pie}</div>
        </section>

        {/* IA-3 Moderación asistida */}
        <section className="card card-pad">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <h2 style={{ margin: 0, fontSize: 16.5, fontWeight: 800 }}>{ia3.nombre}</h2>
            <span className="pill pill-green">{ia3.estado}</span>
          </div>
          <div className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 14 }}>
            {ia3.subtitulo}
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
              background: '#edf4f8',
              borderRadius: 10,
              padding: '12px 14px',
              marginBottom: 14,
            }}
          >
            {ia3.metricas.map((m) => (
              <div key={m.etiqueta}>
                <div style={{ fontSize: 20, fontWeight: 800 }}>{m.valor}</div>
                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>{m.etiqueta}</div>
              </div>
            ))}
          </div>

          <div style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>{ia3.nota_pie}</div>
        </section>

        {/* IA-4 Priorización de alertas */}
        <section className="card card-pad">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <h2 style={{ margin: 0, fontSize: 16.5, fontWeight: 800 }}>{ia4.nombre}</h2>
            <span className={ia4.estado === 'ACTIVO' ? 'pill pill-green' : 'pill pill-amber'}>
              {ia4.estado}
            </span>
          </div>
          <div className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 14 }}>
            {ia4.subtitulo}
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
              background: '#edf4f8',
              borderRadius: 10,
              padding: '12px 14px',
              marginBottom: 14,
            }}
          >
            {ia4.metricas.map((m) => (
              <div key={m.etiqueta}>
                <div style={{ fontSize: 20, fontWeight: 800 }}>{m.valor}</div>
                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>{m.etiqueta}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <button
              type="button"
              className="btn btn-purple btn-sm"
              style={{ padding: '9px 16px' }}
              disabled={ia4.estado === 'ACTIVO' || !ia4.tiene_metricas_validacion}
              onClick={() => {
                cambiarEstadoModeloIA4('ACTIVO');
                notify('Modelo IA-4 prioridad-v0.9 activado en producción y registrado en bitácora.');
              }}
            >
              {ia4.estado === 'ACTIVO' ? 'v0.9 Activo' : 'Activar v0.9'}
            </button>
            <button
              type="button"
              className="btn btn-outline btn-sm"
              style={{ padding: '9px 16px' }}
              disabled={ia4.estado === 'EN_PRUEBA'}
              onClick={() => {
                cambiarEstadoModeloIA4('EN_PRUEBA');
                notify('Modelo IA-4 prioridad-v0.9 mantenido en estado EN_PRUEBA.');
              }}
            >
              Seguir en prueba
            </button>
          </div>
        </section>
      </div>

      {/* Correcciones recientes (retroalimentación) */}
      <section className="card" style={{ overflow: 'hidden' }}>
        <div style={{ padding: '16px 20px 8px', fontWeight: 800, fontSize: 16 }}>
          Correcciones recientes (retroalimentación)
        </div>

        <table className="data-table">
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Modelo</th>
              <th>Sugerencia</th>
              <th>Decisión humana</th>
              <th style={{ textAlign: 'right' }}>Conf.</th>
            </tr>
          </thead>
          <tbody>
            {retroalimentacionIA.map((row) => (
              <tr key={row.id}>
                <td className="mono" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                  {row.fecha}
                </td>
                <td className="mono" style={{ fontWeight: 700 }}>
                  {row.modelo}
                </td>
                <td>{row.sugerencia}</td>
                <td>{row.decision_humana}</td>
                <td className="mono" style={{ textAlign: 'right' }}>
                  {row.confianza !== null ? row.confianza.toFixed(2) : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <div
          style={{
            padding: '14px 20px',
            borderTop: '1px solid var(--border-light)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            Próximo reentrenamiento: <strong>{reentrenamientoInfo.fecha}</strong> ·{' '}
            <span className="mono">{reentrenamientoInfo.ejemplos.toLocaleString('es-MX')}</span>{' '}
            ejemplos nuevos, sin datos de identidad
          </span>

          <button
            type="button"
            className="btn btn-outline-blue"
            style={{ borderColor: 'var(--ia-purple)', color: 'var(--ia-purple)' }}
            onClick={() => setShowRetrainModal(true)}
          >
            <RefreshCw size={15} />
            Reentrenar ahora
          </button>
        </div>
      </section>

      {/* Modal de confirmación de reentrenamiento (W-10: botón con confirmación) */}
      {showRetrainModal && (
        <div className="modal-backdrop" onClick={() => setShowRetrainModal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Sparkles size={18} color="#8350c4" />
                <h3 style={{ margin: 0, fontSize: 17 }}>Confirmar reentrenamiento de modelos IA</h3>
              </div>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => setShowRetrainModal(false)}
              >
                <X size={14} />
              </button>
            </div>
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.55 }}>
              Se lanzará un lote de reentrenamiento usando{' '}
              <strong>{reentrenamientoInfo.ejemplos.toLocaleString('es-MX')}</strong> ejemplos
              anonimizados provenientes de <code className="mono">retroalimentacion_ia</code> con
              consentimiento explícito (<code className="mono">apta_entrenamiento = true</code>).
              Ningún dato de identidad personal participa en el proceso.
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
              <button
                type="button"
                className="btn btn-outline"
                onClick={() => setShowRetrainModal(false)}
              >
                Cancelar
              </button>
              <button
                type="button"
                className="btn btn-purple"
                onClick={() => {
                  ejecutarReentrenamientoIA();
                  setShowRetrainModal(false);
                  notify('Reentrenamiento completado: clasificador-v2.4 generado y registrado en bitácora.');
                }}
              >
                Confirmar y reentrenar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
