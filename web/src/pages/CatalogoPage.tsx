import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertCircle, CheckCircle2, Plus } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { DatoMinimo, IncidenteCategoria, TipoAplicaA, TipoIncidenteVersion } from '../types/domain';

const DATOS_MINIMOS_OPCIONES: { key: DatoMinimo; label: string }[] = [
  { key: 'ubicacion', label: 'Ubicación' },
  { key: 'descripcion', label: 'Descripción' },
  { key: 'audio', label: 'Audio' },
  { key: 'foto', label: 'Foto' },
];

const PRIORIDADES: { val: 1 | 2 | 3 | 4 | 5; label: string }[] = [
  { val: 1, label: '1 (máxima)' },
  { val: 2, label: '2 (alta)' },
  { val: 3, label: '3 (media)' },
  { val: 4, label: '4 (baja)' },
  { val: 5, label: '5 (mínima)' },
];

// W-06. Catálogo de incidentes (pág. 52, CU-08, RF-4, P16)
export const CatalogoPage: React.FC = () => {
  const {
    versionesCatalogo,
    tiposCatalogo,
    validarCatalogoA08,
    guardarTipoCatalogo,
    publicarBorradorCatalogo,
  } = useSendero();
  const navigate = useNavigate();

  const borrador = versionesCatalogo.find((v) => v.estado === 'BORRADOR');
  const vigente = versionesCatalogo.find((v) => v.estado === 'PUBLICADA');

  const tiposVisibles = useMemo(
    () =>
      tiposCatalogo.filter(
        (t) => t.version_catalogo_id === borrador?.id && !t.es_generica
      ),
    [tiposCatalogo, borrador]
  );

  const validacion = validarCatalogoA08();
  const sinReglaIds = new Set(validacion.errores.map((e) => e.tipo_incidente_id));

  // Preseleccionar "Intento de privación de libertad" (id 6) como en el mockup
  const [selectedTipoId, setSelectedTipoId] = useState<number>(6);
  const [esNuevo, setEsNuevo] = useState(false);

  const [nombre, setNombre] = useState('');
  const [categoria, setCategoria] = useState<IncidenteCategoria>('SOCIAL');
  const [prioridad, setPrioridad] = useState<1 | 2 | 3 | 4 | 5>(1);
  const [aplicaA, setAplicaA] = useState<TipoAplicaA>('ALERTA');
  const [datosMinimos, setDatosMinimos] = useState<DatoMinimo[]>(['ubicacion', 'descripcion']);
  const [activo, setActivo] = useState(true);

  const [feedback, setFeedback] = useState<{ tipo: 'ok' | 'err'; texto: string } | null>(null);

  const selectedTipo = tiposVisibles.find((t) => t.tipo_incidente_id === selectedTipoId) ?? tiposVisibles[0];

  useEffect(() => {
    if (!esNuevo && selectedTipo) {
      setNombre(selectedTipo.nombre);
      setCategoria(selectedTipo.categoria ?? 'FISICO');
      setPrioridad(selectedTipo.prioridad);
      setAplicaA(selectedTipo.aplica_a);
      setDatosMinimos(selectedTipo.datos_minimos);
      setActivo(selectedTipo.activo);
    }
  }, [selectedTipo, esNuevo]);

  const toggleDatoMinimo = (key: DatoMinimo) => {
    setDatosMinimos((prev) =>
      prev.includes(key) ? prev.filter((k) => k !== key) : [...prev, key]
    );
  };

  const iniciarNuevoTipo = () => {
    setEsNuevo(true);
    setNombre('');
    setCategoria('FISICO');
    setPrioridad(2);
    setAplicaA('AMBOS');
    setDatosMinimos(['ubicacion', 'descripcion']);
    setActivo(true);
    setFeedback(null);
  };

  const handleGuardar = (e: React.FormEvent) => {
    e.preventDefault();
    if (!borrador) return;

    const nextId = esNuevo
      ? Math.max(10, ...tiposCatalogo.map((t) => (t.es_generica ? 0 : t.tipo_incidente_id))) + 1
      : selectedTipo.tipo_incidente_id;

    const payload: TipoIncidenteVersion = {
      version_catalogo_id: borrador.id,
      tipo_incidente_id: nextId,
      codigo: esNuevo
        ? nombre.trim().toUpperCase().replace(/[^A-Z0-9]+/g, '_').slice(0, 32)
        : selectedTipo.codigo,
      es_generica: false,
      nombre: nombre.trim(),
      categoria,
      prioridad,
      aplica_a: aplicaA,
      datos_minimos: datosMinimos,
      peso_riesgo: esNuevo ? 1.0 : selectedTipo.peso_riesgo,
      activo,
    };

    const res = guardarTipoCatalogo(payload, esNuevo);
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Error al guardar el tipo.' });
      return;
    }
    setEsNuevo(false);
    setSelectedTipoId(nextId);
    setFeedback({
      tipo: 'ok',
      texto: `Cambios guardados en v${borrador.numero} (BORRADOR).`,
    });
  };

  const handleValidar = () => {
    const res = validarCatalogoA08();
    if (res.valido) {
      setFeedback({
        tipo: 'ok',
        texto: `Validación (A-08) exitosa: todos los tipos activos de alerta en v${borrador?.numero} tienen regla de canalización.`,
      });
    } else {
      setFeedback({
        tipo: 'err',
        texto: `Validación (A-08): ${res.errores.length} error(es). ${res.errores[0].mensaje}`,
      });
    }
  };

  const handlePublicar = () => {
    const res = publicarBorradorCatalogo();
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'No se puede publicar aún.' });
      return;
    }
    setFeedback({
      tipo: 'ok',
      texto: `Catálogo v${borrador?.numero} publicado exitosamente. Se abrió un nuevo borrador para cambios futuros.`,
    });
  };

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <div className="page-header-left" style={{ alignItems: 'center' }}>
          <h1 className="page-title">Catálogo de incidentes</h1>
          {borrador && (
            <span className="pill pill-amber">v{borrador.numero} · BORRADOR</span>
          )}
          {vigente && (
            <span className="page-subtitle">
              Vigente: v{vigente.numero} PUBLICADA el {vigente.publicada_en}
            </span>
          )}
        </div>

        <div className="page-actions">
          <button type="button" className="btn btn-outline btn-sm" onClick={iniciarNuevoTipo}>
            <Plus size={14} />
            Nuevo tipo
          </button>
          <button type="button" className="btn btn-outline-blue" onClick={handleValidar}>
            Validar
          </button>
          <button type="button" className="btn btn-primary" onClick={handlePublicar}>
            Publicar v{borrador?.numero ?? 8}
          </button>
        </div>
      </div>

      {/* Banner de Validación A-08 */}
      {!validacion.valido ? (
        <div className="alert-banner-red" style={{ marginBottom: 16 }}>
          <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 1 }} />
          <div>
            <strong>
              Validación (A-08): {validacion.errores.length}{' '}
              {validacion.errores.length === 1 ? 'error' : 'errores'}.
            </strong>{' '}
            {validacion.errores.map((e) => e.mensaje).join(' ')}
          </div>
        </div>
      ) : (
        <div className="alert-banner-green" style={{ marginBottom: 16 }}>
          <CheckCircle2 size={17} />
          <span>
            Validación (A-08): 0 errores. La versión v{borrador?.numero} cumple todas las reglas y
            está lista para publicarse.
          </span>
        </div>
      )}

      {feedback && (
        <div
          className={feedback.tipo === 'ok' ? 'alert-banner-green' : 'alert-banner-red'}
          style={{ marginBottom: 16 }}
        >
          {feedback.tipo === 'ok' ? <CheckCircle2 size={16} /> : <AlertCircle size={16} />}
          <span>{feedback.texto}</span>
        </div>
      )}

      {/* Grid Tabla + Panel de edición */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.55fr) minmax(320px, 1fr)',
          gap: 20,
          alignItems: 'start',
        }}
      >
        <section className="card" style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Tipo</th>
                <th>Categoría</th>
                <th>Prior.</th>
                <th>Aplica a</th>
                <th>Datos mínimos</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {tiposVisibles.map((t) => {
                const sinRegla = sinReglaIds.has(t.tipo_incidente_id);
                const selected = !esNuevo && selectedTipo?.tipo_incidente_id === t.tipo_incidente_id;
                const datosTxt = t.datos_minimos
                  .map((d) =>
                    d === 'ubicacion'
                      ? 'ubicación'
                      : d === 'descripcion'
                        ? 'descripción'
                        : d
                  )
                  .join(', ');

                return (
                  <tr
                    key={t.tipo_incidente_id}
                    className={`clickable${selected ? ' row-selected' : ''}`}
                    onClick={() => {
                      setEsNuevo(false);
                      setSelectedTipoId(t.tipo_incidente_id);
                      setFeedback(null);
                    }}
                    style={!t.activo ? { color: 'var(--text-muted)' } : undefined}
                  >
                    <td style={{ fontWeight: t.activo ? 700 : 400 }}>{t.nombre}</td>
                    <td className="mono" style={{ fontSize: 12.5 }}>
                      {t.categoria ?? '—'}
                    </td>
                    <td className="mono">{t.prioridad}</td>
                    <td className="mono" style={{ fontSize: 12.5 }}>
                      {t.aplica_a}
                    </td>
                    <td style={{ fontSize: 13 }}>{datosTxt || '—'}</td>
                    <td>
                      {!t.activo ? (
                        <span className="pill pill-gray">INACTIVO</span>
                      ) : sinRegla ? (
                        <span className="pill pill-red">SIN REGLA</span>
                      ) : (
                        <span className="pill pill-green">ACTIVO</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>

        {/* Panel Editar tipo */}
        <section className="card-blue-panel">
          <h2 style={{ margin: '0 0 16px 0', fontSize: 18, fontWeight: 800 }}>
            {esNuevo ? 'Nuevo tipo de incidente' : 'Editar tipo'}
          </h2>

          <form onSubmit={handleGuardar}>
            <div className="form-field">
              <label htmlFor="cat-nombre" className="form-label">
                Nombre
              </label>
              <input
                id="cat-nombre"
                type="text"
                className="form-input"
                value={nombre}
                onChange={(e) => setNombre(e.target.value)}
                minLength={3}
                maxLength={60}
                placeholder="Ej. Intento de privación de libertad"
                required
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div className="form-field">
                <label htmlFor="cat-categoria" className="form-label">
                  Categoría
                </label>
                <select
                  id="cat-categoria"
                  className="form-select"
                  value={categoria}
                  onChange={(e) => setCategoria(e.target.value as IncidenteCategoria)}
                >
                  <option value="FISICO">FISICO</option>
                  <option value="SOCIAL">SOCIAL</option>
                  <option value="DIGITAL">DIGITAL</option>
                </select>
              </div>

              <div className="form-field">
                <label htmlFor="cat-prioridad" className="form-label">
                  Prioridad (1 a 5)
                </label>
                <select
                  id="cat-prioridad"
                  className="form-select"
                  value={prioridad}
                  onChange={(e) => setPrioridad(Number(e.target.value) as 1 | 2 | 3 | 4 | 5)}
                >
                  {PRIORIDADES.map((p) => (
                    <option key={p.val} value={p.val}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="form-field">
              <label htmlFor="cat-aplica" className="form-label">
                Aplica a
              </label>
              <select
                id="cat-aplica"
                className="form-select"
                value={aplicaA}
                onChange={(e) => setAplicaA(e.target.value as TipoAplicaA)}
              >
                <option value="ALERTA">ALERTA</option>
                <option value="REPORTE">REPORTE</option>
                <option value="AMBOS">AMBOS</option>
              </select>
            </div>

            <div className="form-field">
              <span className="form-label">Datos mínimos que pide la corporación</span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, marginTop: 4 }}>
                {DATOS_MINIMOS_OPCIONES.map((opt) => (
                  <label
                    key={opt.key}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 6,
                      fontSize: 13.5,
                      cursor: 'pointer',
                    }}
                  >
                    <input
                      type="checkbox"
                      checked={datosMinimos.includes(opt.key)}
                      onChange={() => toggleDatoMinimo(opt.key)}
                    />
                    {opt.label}
                  </label>
                ))}
              </div>
            </div>

            <div style={{ marginBottom: 18 }}>
              <label
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  fontSize: 13.5,
                  cursor: 'pointer',
                }}
              >
                <input
                  type="checkbox"
                  checked={activo}
                  onChange={(e) => setActivo(e.target.checked)}
                />
                Activo (visible en la app)
              </label>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              <button type="submit" className="btn btn-primary">
                Guardar en v{borrador?.numero ?? 8}
              </button>
              <button
                type="button"
                className="btn btn-outline-blue"
                disabled={esNuevo || aplicaA === 'REPORTE'}
                onClick={() => navigate(`/canalizacion?tipo=${selectedTipo?.tipo_incidente_id}`)}
              >
                Asignar regla
              </button>
            </div>
          </form>
        </section>
      </div>
    </div>
  );
};
