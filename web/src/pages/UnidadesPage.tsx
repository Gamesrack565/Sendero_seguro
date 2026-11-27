import React, { useMemo, useState } from 'react';
import { AlertCircle, CheckCircle2 } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { CorporacionTipo, UnidadEstado } from '../types/domain';

const TIPOS_CHECKBOX = [
  { id: 1, label: 'Persona sospechosa' },
  { id: 2, label: 'Asalto o robo' },
  { id: 5, label: 'Acoso' },
  { id: 4, label: 'Emergencia médica' },
];

function estadoBadge(estado: UnidadEstado) {
  switch (estado) {
    case 'DISPONIBLE':
      return <span className="pill pill-green">DISPONIBLE</span>;
    case 'ASIGNADA':
      return <span className="pill pill-amber">ASIGNADA</span>;
    case 'ATENDIENDO':
      return <span className="pill pill-green-solid">ATENDIENDO</span>;
    default:
      return <span className="pill pill-gray">FUERA_DE_SERVICIO</span>;
  }
}

// W-08. Corporaciones y unidades (pág. 54, CU-10, RF-4, RF-10, P16)
export const UnidadesPage: React.FC = () => {
  const {
    corporaciones,
    unidades,
    users,
    registrarUnidad,
    registrarCorporacion,
    updateUnitStatus,
  } = useSendero();

  const [tab, setTab] = useState<'unidades' | 'corporaciones'>('unidades');

  // Formulario Registrar unidad
  const [corpId, setCorpId] = useState<number>(1);
  const [claveUnidad, setClaveUnidad] = useState('P-215');
  const [operadoresTexto, setOperadoresTexto] = useState('J. Torres, M. Vega');
  const [tiposAtiende, setTiposAtiende] = useState<number[]>([1, 2, 5]);

  // Formulario Registrar corporación
  const [corpNombre, setCorpNombre] = useState('');
  const [corpTipo, setCorpTipo] = useState<CorporacionTipo>('POLICIA');
  const [corpCanal, setCorpCanal] = useState('');

  const [feedback, setFeedback] = useState<{ tipo: 'ok' | 'err'; texto: string } | null>(null);

  const operadoresDisponibles = useMemo(
    () => users.filter((u) => u.rol === 'OPERADOR'),
    [users]
  );

  const toggleTipo = (id: number) => {
    setTiposAtiende((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
  };

  const handleRegistrarUnidad = (e: React.FormEvent) => {
    e.preventDefault();
    const ops = operadoresTexto
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

    const res = registrarUnidad({
      corporacion_id: corpId,
      nombre: claveUnidad,
      operadores_nombres: ops,
      tipos_incidente_ids: tiposAtiende,
    });

    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'No se pudo registrar la unidad.' });
      return;
    }
    setFeedback({
      tipo: 'ok',
      texto: `Unidad «${claveUnidad.trim()}» registrada y activa en la tabla unidad_atencion.`,
    });
    setClaveUnidad('');
  };

  const handleRegistrarCorp = (e: React.FormEvent) => {
    e.preventDefault();
    const res = registrarCorporacion({
      nombre: corpNombre,
      tipo: corpTipo,
      canal_codigo: corpCanal,
    });
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Error al registrar corporación.' });
      return;
    }
    setFeedback({ tipo: 'ok', texto: `Corporación «${corpNombre.trim()}» registrada correctamente.` });
    setCorpNombre('');
    setCorpCanal('');
  };

  const getCorpName = (id: number) =>
    corporaciones.find((c) => c.id === id)?.nombre ?? '—';

  return (
    <div>
      {/* Header con switch Corporaciones / Unidades */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Corporaciones y unidades</h1>
        </div>
        <div className="segmented-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'corporaciones'}
            className={`segmented-tab${tab === 'corporaciones' ? ' active' : ''}`}
            onClick={() => {
              setTab('corporaciones');
              setFeedback(null);
            }}
          >
            Corporaciones ({corporaciones.length})
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'unidades'}
            className={`segmented-tab${tab === 'unidades' ? ' active' : ''}`}
            onClick={() => {
              setTab('unidades');
              setFeedback(null);
            }}
          >
            Unidades ({unidades.length})
          </button>
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

      {tab === 'unidades' ? (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1.55fr) minmax(320px, 1fr)',
            gap: 20,
            alignItems: 'start',
          }}
        >
          {/* Tabla de unidades */}
          <section className="card" style={{ overflow: 'hidden' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Unidad</th>
                  <th>Corporación</th>
                  <th>Estado</th>
                  <th>Atiende</th>
                  <th>Última ubicación</th>
                  <th>Operador</th>
                </tr>
              </thead>
              <tbody>
                {unidades.map((u) => (
                  <tr key={u.id}>
                    <td style={{ fontWeight: 800 }}>{u.nombre}</td>
                    <td>{getCorpName(u.corporacion_id)}</td>
                    <td>
                      <button
                        type="button"
                        onClick={() =>
                          updateUnitStatus(
                            u.id,
                            u.estado === 'DISPONIBLE'
                              ? 'FUERA_DE_SERVICIO'
                              : 'DISPONIBLE'
                          )
                        }
                        title="Clic para alternar DISPONIBLE / FUERA_DE_SERVICIO"
                        style={{ background: 'none', border: 'none', padding: 0, cursor: 'pointer' }}
                      >
                        {estadoBadge(u.estado)}
                      </button>
                    </td>
                    <td>{u.atiende_resumen}</td>
                    <td className="mono" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                      {u.ultima_ubicacion_texto}
                    </td>
                    <td>{u.operadores_nombres.join(', ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {/* Panel Registrar unidad */}
          <section className="card-blue-panel">
            <h2 style={{ margin: '0 0 16px 0', fontSize: 18, fontWeight: 800 }}>
              Registrar unidad
            </h2>

            <form onSubmit={handleRegistrarUnidad}>
              <div className="form-field">
                <label htmlFor="uni-corp" className="form-label">
                  Corporación
                </label>
                <select
                  id="uni-corp"
                  className="form-select"
                  value={corpId}
                  onChange={(e) => setCorpId(Number(e.target.value))}
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
                <label htmlFor="uni-clave" className="form-label">
                  Nombre o clave
                </label>
                <input
                  id="uni-clave"
                  type="text"
                  className="form-input mono"
                  value={claveUnidad}
                  onChange={(e) => setClaveUnidad(e.target.value)}
                  placeholder="Ej. P-215"
                  required
                />
              </div>

              <div className="form-field">
                <label htmlFor="uni-ops" className="form-label">
                  Operadores asignados
                </label>
                <input
                  id="uni-ops"
                  type="text"
                  className="form-input"
                  value={operadoresTexto}
                  onChange={(e) => setOperadoresTexto(e.target.value)}
                  placeholder="Selecciona o escribe operadores"
                />
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 4 }}>
                  {operadoresDisponibles.slice(0, 5).map((op) => (
                    <button
                      key={op.id}
                      type="button"
                      className="pill pill-gray"
                      style={{ border: 'none', cursor: 'pointer' }}
                      onClick={() => {
                        const curr = operadoresTexto
                          .split(',')
                          .map((s) => s.trim())
                          .filter(Boolean);
                        if (!curr.includes(op.nombre_corto)) {
                          setOperadoresTexto([...curr, op.nombre_corto].join(', '));
                        }
                      }}
                    >
                      + {op.nombre_corto}
                    </button>
                  ))}
                </div>
              </div>

              <div className="form-field">
                <span className="form-label">Tipos de incidente que atiende</span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, marginTop: 4 }}>
                  {TIPOS_CHECKBOX.map((t) => (
                    <label
                      key={t.id}
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
                        checked={tiposAtiende.includes(t.id)}
                        onChange={() => toggleTipo(t.id)}
                      />
                      {t.label}
                    </label>
                  ))}
                </div>
              </div>

              <p className="form-help" style={{ marginBottom: 16 }}>
                El operador recibe la alerta en la web y en el modo operador de la app (red malla).
                La ubicación de la unidad se actualiza desde su teléfono mientras está DISPONIBLE.
              </p>

              <button type="submit" className="btn btn-primary btn-lg">
                Registrar unidad
              </button>
            </form>
          </section>
        </div>
      ) : (
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
                  <th>Corporación</th>
                  <th>Tipo</th>
                  <th>Canal código</th>
                  <th>Unidades</th>
                  <th>Estado</th>
                </tr>
              </thead>
              <tbody>
                {corporaciones.map((c) => {
                  const count = unidades.filter((u) => u.corporacion_id === c.id).length;
                  return (
                    <tr key={c.id}>
                      <td style={{ fontWeight: 700 }}>{c.nombre}</td>
                      <td className="mono" style={{ fontSize: 12.5 }}>
                        {c.tipo}
                      </td>
                      <td className="mono">{c.canal_codigo}</td>
                      <td className="mono">{count}</td>
                      <td>
                        <span className={c.activa ? 'pill pill-green' : 'pill pill-gray'}>
                          {c.activa ? 'ACTIVA' : 'INACTIVA'}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>

          <section className="card-blue-panel">
            <h2 style={{ margin: '0 0 16px 0', fontSize: 18, fontWeight: 800 }}>
              Registrar corporación
            </h2>
            <form onSubmit={handleRegistrarCorp}>
              <div className="form-field">
                <label htmlFor="corp-nom" className="form-label">
                  Nombre de la corporación
                </label>
                <input
                  id="corp-nom"
                  type="text"
                  className="form-input"
                  value={corpNombre}
                  onChange={(e) => setCorpNombre(e.target.value)}
                  placeholder="Ej. Policía Bancaria e Industrial (PBI)"
                  required
                />
              </div>
              <div className="form-field">
                <label htmlFor="corp-tipo" className="form-label">
                  Tipo de corporación
                </label>
                <select
                  id="corp-tipo"
                  className="form-select"
                  value={corpTipo}
                  onChange={(e) => setCorpTipo(e.target.value as CorporacionTipo)}
                >
                  <option value="SEGURIDAD_ESCOLAR">SEGURIDAD_ESCOLAR</option>
                  <option value="POLICIA">POLICIA</option>
                  <option value="BOMBEROS">BOMBEROS</option>
                  <option value="ERUM">ERUM</option>
                  <option value="OTRA">OTRA</option>
                </select>
              </div>
              <div className="form-field">
                <label htmlFor="corp-canal" className="form-label">
                  Código de canal destinatario
                </label>
                <input
                  id="corp-canal"
                  type="text"
                  className="form-input mono"
                  value={corpCanal}
                  onChange={(e) => setCorpCanal(e.target.value)}
                  placeholder="Ej. PBI_ZACATENCO"
                  required
                />
              </div>
              <button type="submit" className="btn btn-primary btn-lg">
                Registrar corporación
              </button>
            </form>
          </section>
        </div>
      )}
    </div>
  );
};
