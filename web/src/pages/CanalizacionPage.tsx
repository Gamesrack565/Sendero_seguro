import React, { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AlertCircle, CheckCircle2, Plus, X } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { DestinoEscalamiento, PasoEscalamiento } from '../types/domain';

const DESTINOS_POR_TIPO: Record<DestinoEscalamiento, string[]> = {
  CORPORACION: ['Seguridad IPN', 'SSC CDMX (C5)', 'ERUM', 'Bomberos CDMX'],
  COORDINADOR_CIRCULO: [
    'Coordinador del círculo de la emisora',
    'Coordinador de Seguridad ESCOM',
    'Coordinador de Seguridad ESIME Zacatenco',
  ],
  ADMINISTRADOR: ['Guardia del sistema', 'Dirección de Seguridad Escolar'],
  OPERADOR: ['R. Méndez (P-214)', 'C. Ávila (P-209)', 'T. Robles (ERUM-12)'],
};

// W-07. Canalización y escalamiento (pág. 53, CU-09, RF-4, RF-12, P16)
export const CanalizacionPage: React.FC = () => {
  const {
    versionesCatalogo,
    tiposCatalogo,
    reglasCanalizacion,
    corporaciones,
    guardarReglaCanalizacion,
  } = useSendero();
  const [searchParams] = useSearchParams();

  const borrador = versionesCatalogo.find((v) => v.estado === 'BORRADOR');
  const tiposAlerta = useMemo(
    () =>
      tiposCatalogo.filter(
        (t) =>
          t.version_catalogo_id === borrador?.id &&
          t.activo &&
          (t.aplica_a === 'ALERTA' || t.aplica_a === 'AMBOS')
      ),
    [tiposCatalogo, borrador]
  );

  const paramTipo = Number(searchParams.get('tipo'));
  const [selectedTipoId, setSelectedTipoId] = useState<number>(
    paramTipo && tiposAlerta.some((t) => t.tipo_incidente_id === paramTipo) ? paramTipo : 6
  );

  const selectedTipo =
    tiposAlerta.find((t) => t.tipo_incidente_id === selectedTipoId) ?? tiposAlerta[0];

  const reglaActual = useMemo(
    () =>
      reglasCanalizacion.find(
        (r) =>
          r.version_catalogo_id === borrador?.id &&
          r.tipo_incidente_id === selectedTipo?.tipo_incidente_id
      ),
    [reglasCanalizacion, borrador, selectedTipo]
  );

  const [corporacionId, setCorporacionId] = useState<number>(2);
  const [tiempoAcuse, setTiempoAcuse] = useState<number>(60);
  const [pasos, setPasos] = useState<PasoEscalamiento[]>([]);
  const [feedback, setFeedback] = useState<{ tipo: 'ok' | 'err'; texto: string } | null>(null);

  const loadFromSelected = () => {
    if (reglaActual) {
      setCorporacionId(reglaActual.corporacion_destino_id);
      setTiempoAcuse(reglaActual.tiempo_max_acuse_seg);
      setPasos(reglaActual.pasos.map((p) => ({ ...p })));
    } else {
      // Plantilla precargada idéntica al mockup W-07 para "Intento de privación de libertad"
      setCorporacionId(2);
      setTiempoAcuse(60);
      setPasos([
        { id: 1, orden: 1, destino_tipo: 'CORPORACION', corporacion_id: 1, etiqueta_destino: 'Seguridad IPN' },
        { id: 2, orden: 2, destino_tipo: 'COORDINADOR_CIRCULO', etiqueta_destino: 'Coordinador del círculo de la emisora' },
        { id: 3, orden: 3, destino_tipo: 'ADMINISTRADOR', etiqueta_destino: 'Guardia del sistema' },
      ]);
    }
    setFeedback(null);
  };

  useEffect(() => {
    loadFromSelected();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedTipo?.tipo_incidente_id, reglaActual?.id]);

  const handlePasoTipoChange = (index: number, tipo: DestinoEscalamiento) => {
    const opciones = DESTINOS_POR_TIPO[tipo];
    setPasos((prev) =>
      prev.map((p, i) =>
        i === index ? { ...p, destino_tipo: tipo, etiqueta_destino: opciones[0] } : p
      )
    );
  };

  const handlePasoEtiquetaChange = (index: number, etiqueta: string) => {
    setPasos((prev) =>
      prev.map((p, i) => (i === index ? { ...p, etiqueta_destino: etiqueta } : p))
    );
  };

  const handleRemovePaso = (index: number) => {
    setPasos((prev) =>
      prev.filter((_, i) => i !== index).map((p, i) => ({ ...p, orden: i + 1 }))
    );
  };

  const handleAddPaso = () => {
    setPasos((prev) => [
      ...prev,
      {
        id: Date.now(),
        orden: prev.length + 1,
        destino_tipo: 'OPERADOR',
        etiqueta_destino: DESTINOS_POR_TIPO.OPERADOR[0],
      },
    ]);
  };

  const handleGuardar = (e: React.FormEvent) => {
    e.preventDefault();
    if (!borrador || !selectedTipo) return;

    const res = guardarReglaCanalizacion({
      version_catalogo_id: borrador.id,
      tipo_incidente_id: selectedTipo.tipo_incidente_id,
      corporacion_destino_id: corporacionId,
      tiempo_max_acuse_seg: Number(tiempoAcuse),
      pasos: pasos.map((p, idx) => ({ ...p, orden: idx + 1 })),
    });

    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Error al guardar regla.' });
      return;
    }
    setFeedback({
      tipo: 'ok',
      texto: `Regla de canalización guardada para «${selectedTipo.nombre}» en v${borrador.numero}. La validación A-08 ya se actualizó.`,
    });
  };

  const getCorpName = (id: number) =>
    corporaciones.find((c) => c.id === id)?.nombre ?? 'Corporación';

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <div className="page-header-left" style={{ alignItems: 'center' }}>
          <h1 className="page-title">Canalización y escalamiento</h1>
          {borrador && (
            <span className="pill pill-amber">editando v{borrador.numero} · BORRADOR</span>
          )}
        </div>
      </div>

      {feedback && (
        <div
          className={feedback.tipo === 'ok' ? 'alert-banner-green' : 'alert-banner-red'}
          style={{ marginBottom: 16 }}
        >
          {feedback.tipo === 'ok' ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
          <span>{feedback.texto}</span>
        </div>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(270px, 340px) minmax(0, 1fr)',
          gap: 20,
          alignItems: 'start',
        }}
      >
        {/* Lista izquierda: Reglas por tipo */}
        <section className="card" style={{ overflow: 'hidden' }}>
          <div
            style={{
              padding: '14px 16px',
              borderBottom: '1px solid var(--border-light)',
              fontWeight: 800,
              fontSize: 15,
            }}
          >
            Reglas por tipo
          </div>
          <div>
            {tiposAlerta.map((t) => {
              const regla = reglasCanalizacion.find(
                (r) =>
                  r.version_catalogo_id === borrador?.id &&
                  r.tipo_incidente_id === t.tipo_incidente_id
              );
              const active = selectedTipo?.tipo_incidente_id === t.tipo_incidente_id;
              return (
                <button
                  key={t.tipo_incidente_id}
                  type="button"
                  onClick={() => setSelectedTipoId(t.tipo_incidente_id)}
                  style={{
                    width: '100%',
                    textAlign: 'left',
                    border: 'none',
                    borderBottom: '1px solid #e6f0f6',
                    padding: '13px 16px',
                    background: active ? '#dcebf3' : '#ffffff',
                    cursor: 'pointer',
                    display: 'block',
                  }}
                >
                  <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--text-primary)', marginBottom: 4 }}>
                    {t.nombre}
                  </div>
                  {regla ? (
                    <div style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                      {getCorpName(regla.corporacion_destino_id)} · acuse{' '}
                      <span className="mono">{regla.tiempo_max_acuse_seg} s</span> ·{' '}
                      {regla.pasos.length} {regla.pasos.length === 1 ? 'nivel' : 'niveles'}
                    </div>
                  ) : (
                    <div style={{ fontSize: 12.5, fontWeight: 700, color: '#8350c4' }}>
                      Sin regla
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </section>

        {/* Panel derecho de configuración */}
        {selectedTipo && (
          <section className="card-blue-panel">
            <h2 style={{ margin: '0 0 18px 0', fontSize: 20, fontWeight: 800 }}>
              {selectedTipo.nombre}
            </h2>

            <form onSubmit={handleGuardar}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                <div className="form-field">
                  <label htmlFor="can-corp" className="form-label">
                    Canal destinatario
                  </label>
                  <select
                    id="can-corp"
                    className="form-select"
                    value={corporacionId}
                    onChange={(e) => setCorporacionId(Number(e.target.value))}
                  >
                    {corporaciones
                      .filter((c) => c.activa)
                      .map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.nombre}
                        </option>
                      ))}
                  </select>
                </div>

                <div className="form-field">
                  <label htmlFor="can-acuse" className="form-label">
                    Tiempo máximo de acuse (s)
                  </label>
                  <input
                    id="can-acuse"
                    type="number"
                    min={30}
                    max={600}
                    className="form-input mono"
                    value={tiempoAcuse}
                    onChange={(e) => setTiempoAcuse(Number(e.target.value))}
                    required
                  />
                </div>
              </div>

              <p className="form-help" style={{ marginTop: 0, marginBottom: 20 }}>
                La alerta se asigna a la unidad disponible más cercana del canal que atienda este
                tipo (A-02). Rango válido: 30–600 s.
              </p>

              <div style={{ fontWeight: 800, fontSize: 14, marginBottom: 12 }}>
                Cadena de escalamiento si nadie acusa
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 14 }}>
                {pasos.map((paso, idx) => (
                  <div
                    key={paso.id}
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '28px 1fr 1.3fr 38px',
                      gap: 10,
                      alignItems: 'center',
                    }}
                  >
                    <span
                      style={{
                        width: 26,
                        height: 26,
                        borderRadius: '50%',
                        background: '#402b47',
                        color: '#c9e0eb',
                        fontSize: 12,
                        fontWeight: 700,
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      {idx + 1}
                    </span>

                    <select
                      className="form-select"
                      value={paso.destino_tipo}
                      onChange={(e) =>
                        handlePasoTipoChange(idx, e.target.value as DestinoEscalamiento)
                      }
                    >
                      <option value="CORPORACION">CORPORACION</option>
                      <option value="COORDINADOR_CIRCULO">COORDINADOR</option>
                      <option value="ADMINISTRADOR">ADMINISTRADOR</option>
                      <option value="OPERADOR">OPERADOR</option>
                    </select>

                    <select
                      className="form-select"
                      value={paso.etiqueta_destino}
                      onChange={(e) => handlePasoEtiquetaChange(idx, e.target.value)}
                    >
                      {DESTINOS_POR_TIPO[paso.destino_tipo].map((opt) => (
                        <option key={opt} value={opt}>
                          {opt}
                        </option>
                      ))}
                    </select>

                    <button
                      type="button"
                      className="btn btn-outline"
                      style={{ padding: 8, height: 40 }}
                      onClick={() => handleRemovePaso(idx)}
                      aria-label={`Quitar nivel ${idx + 1}`}
                    >
                      <X size={15} />
                    </button>
                  </div>
                ))}
              </div>

              <button
                type="button"
                onClick={handleAddPaso}
                style={{
                  border: '1.5px dashed var(--primary-blue)',
                  background: '#edf4f8',
                  color: 'var(--primary-blue)',
                  borderRadius: 10,
                  padding: '9px 16px',
                  fontWeight: 700,
                  fontSize: 13.5,
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  cursor: 'pointer',
                  marginBottom: 14,
                }}
              >
                <Plus size={15} />
                Agregar nivel
              </button>

              <p className="form-help" style={{ margin: '0 0 18px 0' }}>
                Cada nivel espera el mismo tiempo de acuse. Si se agota la cadena, la alerta queda
                marcada «sin_acuse» y se avisa a la emisora.
              </p>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
                <button type="button" className="btn btn-outline" onClick={loadFromSelected}>
                  Descartar
                </button>
                <button type="submit" className="btn btn-primary">
                  Guardar regla
                </button>
              </div>
            </form>
          </section>
        )}
      </div>
    </div>
  );
};
