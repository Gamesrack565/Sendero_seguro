import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AlertCircle, ArrowLeft, CheckCircle2, ExternalLink } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { OperationalMap } from '../components/OperationalMap';
import { AlertaEstado } from '../types/domain';

const RESULTADOS_CATALOGO = [
  'Persona localizada a salvo',
  'Atendida por unidad en sitio',
  'Canalizada a corporación externa (SSC / ERUM)',
  'Falsa alarma verificada',
  'Sin localización en punto tras recorrido',
];

function formatTimer(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

// W-03. Detalle y atención de alerta (pág. 49, CU-18, CU-19, CU-20, RF-11, RF-12, P6, P14)
export const AlertaDetallePage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const {
    alertas,
    currentUser,
    currentUnit,
    acusarReciboAlerta,
    cambiarEstadoAlerta,
    cerrarAlerta,
  } = useSendero();

  const alerta = alertas.find((a) => a.id === id);

  const [estadoSelect, setEstadoSelect] = useState<AlertaEstado>('EN_ATENCION');
  const [resultado, setResultado] = useState('');
  const [observaciones, setObservaciones] = useState('');
  const [feedback, setFeedback] = useState<{ tipo: 'ok' | 'err'; texto: string } | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const t = setInterval(() => setTick((v) => v + 1), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (alerta) {
      if (alerta.estado === 'ACUSADA') setEstadoSelect('EN_ATENCION');
      else if (alerta.estado === 'EN_ATENCION') setEstadoSelect('ATENDIDA');
      else if (alerta.estado === 'ATENDIDA') setEstadoSelect('CERRADA');
    }
  }, [alerta?.estado]);

  if (!alerta) {
    return (
      <div className="card card-pad">
        <h2>Alerta no encontrada</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => navigate('/alertas')}>
          Volver a Alertas
        </button>
      </div>
    );
  }

  const yaAcusada = Boolean(alerta.acusada_en);
  const yaCerrada = alerta.estado === 'CERRADA' || alerta.estado === 'CANCELADA';
  const esOperadorAsignado =
    currentUser?.rol === 'OPERADOR' && currentUser.unidad_id === alerta.unidad_asignada_id;
  const elapsedSec = alerta.segundos_transcurridos_inicial + tick;

  const handleAcusar = () => {
    const res = acusarReciboAlerta(alerta.id);
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'No se pudo acusar recibo.' });
    } else {
      setFeedback({
        tipo: 'ok',
        texto: 'Acuse registrado. Se avisó a la emisora «Una unidad recibió tu alerta» y se detuvo el escalamiento.',
      });
    }
  };

  const handleCambiarEstado = (nuevo: AlertaEstado) => {
    setEstadoSelect(nuevo);
    if (!yaAcusada || yaCerrada) return;
    if (nuevo === 'CERRADA') return; // El cierre se realiza con el botón "Cerrar alerta" tras elegir Resultado
    const res = cambiarEstadoAlerta(alerta.id, nuevo);
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Transición no válida.' });
    } else {
      setFeedback({ tipo: 'ok', texto: `Estado actualizado a ${nuevo}.` });
    }
  };

  const handleCerrar = () => {
    const res = cerrarAlerta(alerta.id, resultado, observaciones);
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'No se pudo cerrar la alerta.' });
      return;
    }
    // Regla Fig. G-03: "W-03 regresa a W-02 al cerrar la alerta."
    navigate('/alertas');
  };

  const canalTexto =
    alerta.canal_recepcion === 'MALLA' && alerta.saltos_malla
      ? `MALLA · ${alerta.saltos_malla} saltos`
      : alerta.canal_recepcion;

  const dotColor = (tipo: 'info' | 'alerta' | 'exito') =>
    tipo === 'alerta' ? '#8350c4' : tipo === 'exito' ? '#7392b5' : '#402b47';

  return (
    <div>
      {/* Breadcrumb + enlace público X-01 */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 8,
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Link
            to="/alertas"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 5,
              color: 'var(--primary-blue)',
              textDecoration: 'none',
              fontWeight: 700,
              fontSize: 13.5,
            }}
          >
            <ArrowLeft size={15} />
            Alertas
          </Link>
          <span className="mono" style={{ fontSize: 13.5, color: 'var(--text-secondary)' }}>
            {alerta.folio}
          </span>
        </div>

        <Link
          to={`/seguimiento/${alerta.token_seguimiento}`}
          target="_blank"
          rel="noreferrer"
          className="btn btn-outline btn-sm"
          style={{ fontSize: 12 }}
        >
          <ExternalLink size={13} />
          Ver enlace público red de apoyo (X-01)
        </Link>
      </div>

      {/* Título y badges */}
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          justifyContent: 'space-between',
          gap: 16,
          marginBottom: 18,
        }}
      >
        <div>
          <h1 className="page-title" style={{ marginBottom: 10 }}>
            {alerta.tipo_nombre}
          </h1>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span
              className={
                alerta.estado === 'ESCALADA'
                  ? 'pill pill-amber'
                  : alerta.estado === 'ACUSADA' || alerta.estado === 'EN_ATENCION'
                    ? 'pill pill-green'
                    : 'pill pill-blue'
              }
            >
              {alerta.estado}
              {alerta.nivel_escalamiento > 0 ? ` · nivel ${alerta.nivel_escalamiento}` : ''}
            </span>
            <span className="pill pill-blue">{canalTexto}</span>
            {alerta.prioridad_ia !== null && (
              <span className="pill pill-ia">Prioridad IA {alerta.prioridad_ia}</span>
            )}
          </div>
        </div>

        <div
          className="mono"
          style={{
            fontSize: 22,
            fontWeight: 800,
            color: yaAcusada ? 'var(--palette-dusty-denim)' : '#8350c4',
            paddingTop: 6,
          }}
        >
          {formatTimer(elapsedSec)}
        </div>
      </div>

      {/* Grid 2 columnas */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.85fr) minmax(300px, 1fr)',
          gap: 20,
          alignItems: 'start',
        }}
      >
        {/* Columna izquierda: Mapa trayectoria + Emisor + Historial */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <OperationalMap
            mode="trajectory"
            selectedAlerta={alerta}
            unit={currentUnit}
            height={265}
          />

          {/* Emisor y descripción */}
          <section className="card card-pad">
            <h2 style={{ margin: '0 0 14px 0', fontSize: 16, fontWeight: 800 }}>
              Emisor y descripción
            </h2>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: '150px 1fr',
                rowGap: 11,
                columnGap: 14,
                fontSize: 13.5,
              }}
            >
              <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>Nombre</div>
              <div style={{ fontWeight: 600 }}>{alerta.emisor_nombre}</div>

              <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>Matrícula · escuela</div>
              <div className="mono">
                {alerta.emisor_matricula} · {alerta.emisor_escuela}
              </div>

              <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>Teléfono</div>
              <div className="mono">{alerta.emisor_telefono}</div>

              <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>Tipo</div>
              <div>
                <div>
                  {alerta.tipo_nombre}
                  {alerta.categoria ? ` · ${alerta.categoria === 'FISICO' ? 'Físico' : alerta.categoria === 'SOCIAL' ? 'Social' : 'Digital'}` : ''}{' '}
                  · prioridad {alerta.prioridad_catalogo}
                </div>
                {alerta.clasificacion_origen && (
                  <span className="pill pill-ia" style={{ marginTop: 6 }}>
                    {alerta.clasificacion_origen}
                  </span>
                )}
              </div>

              <div style={{ color: 'var(--text-secondary)', fontWeight: 600 }}>Descripción</div>
              <div style={{ lineHeight: 1.5 }}>{alerta.descripcion}</div>
            </div>
          </section>

          {/* Historial (evento_alerta) */}
          <section className="card card-pad">
            <h2 style={{ margin: '0 0 14px 0', fontSize: 16, fontWeight: 800 }}>
              Historial (<span className="mono" style={{ fontSize: 14 }}>evento_alerta</span>)
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
              {alerta.historial.map((ev) => (
                <div
                  key={ev.id}
                  style={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 12,
                    fontSize: 13.5,
                  }}
                >
                  <span className="mono" style={{ color: 'var(--text-secondary)', flexShrink: 0 }}>
                    {ev.hora}
                  </span>
                  <span
                    style={{
                      width: 10,
                      height: 10,
                      borderRadius: '50%',
                      background: dotColor(ev.tipo_punto),
                      marginTop: 5,
                      flexShrink: 0,
                    }}
                  />
                  <span style={{ lineHeight: 1.45 }}>{ev.detalle}</span>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* Columna derecha: Atender + Escalamiento + Red de apoyo */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Panel Atender (borde verde) */}
          <section className="card-green-panel">
            <h2 style={{ margin: '0 0 12px 0', fontSize: 16, fontWeight: 800 }}>Atender</h2>

            <button
              type="button"
              className="btn btn-success btn-lg"
              disabled={yaAcusada || yaCerrada}
              onClick={handleAcusar}
              style={{ letterSpacing: '0.03em', marginBottom: 10 }}
            >
              {yaAcusada ? 'RECIBO ACUSADO ✓' : 'ACUSAR RECIBO'}
            </button>

            <p className="form-help" style={{ margin: '0 0 14px 0' }}>
              Avisa a la emisora: «Una unidad recibió tu alerta». Detiene el escalamiento.
            </p>

            {!esOperadorAsignado && !yaAcusada && (
              <div className="alert-banner-red" style={{ marginBottom: 12, fontSize: 12 }}>
                <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 1 }} />
                <span>
                  Validación W-03: solo la unidad asignada (<strong>{alerta.unidad_asignada_nombre}</strong>) puede acusar recibo.
                </span>
              </div>
            )}

            {feedback && (
              <div
                className={feedback.tipo === 'ok' ? 'alert-banner-green' : 'alert-banner-red'}
                style={{ marginBottom: 12, fontSize: 12.5 }}
              >
                {feedback.tipo === 'ok' ? <CheckCircle2 size={15} /> : <AlertCircle size={15} />}
                <span>{feedback.texto}</span>
              </div>
            )}

            <div className="form-field">
              <label htmlFor="w03-estado" className="form-label">
                Cambiar estado
              </label>
              <select
                id="w03-estado"
                className="form-select mono"
                disabled={!yaAcusada || yaCerrada}
                value={estadoSelect}
                onChange={(e) => handleCambiarEstado(e.target.value as AlertaEstado)}
              >
                <option value="EN_ATENCION">EN_ATENCION</option>
                <option value="ATENDIDA">ATENDIDA</option>
              </select>
            </div>

            <div className="form-field">
              <label htmlFor="w03-resultado" className="form-label">
                Resultado
              </label>
              <select
                id="w03-resultado"
                className="form-select"
                disabled={!yaAcusada || yaCerrada}
                value={resultado}
                onChange={(e) => setResultado(e.target.value)}
              >
                <option value="">Seleccionar...</option>
                {RESULTADOS_CATALOGO.map((item) => (
                  <option key={item} value={item}>
                    {item}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-field">
              <label htmlFor="w03-obs" className="form-label">
                Observaciones
              </label>
              <textarea
                id="w03-obs"
                className="form-textarea"
                maxLength={1000}
                disabled={!yaAcusada || yaCerrada}
                placeholder="Se habilitan después del acuse."
                value={observaciones}
                onChange={(e) => setObservaciones(e.target.value)}
              />
              {yaAcusada && (
                <div style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'right' }}>
                  {observaciones.length}/1000 caracteres
                </div>
              )}
            </div>

            <button
              type="button"
              className={yaAcusada && !yaCerrada ? 'btn btn-primary btn-lg' : 'btn btn-muted-disabled btn-lg'}
              disabled={!yaAcusada || yaCerrada}
              onClick={handleCerrar}
            >
              Cerrar alerta
            </button>
          </section>

          {/* Escalamiento */}
          <section className="card card-pad">
            <h2 style={{ margin: '0 0 12px 0', fontSize: 15.5, fontWeight: 800 }}>Escalamiento</h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
              {alerta.escalamiento_cadena.map((paso) => (
                <div
                  key={paso.nivel}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    fontSize: 13.5,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <span
                      style={{
                        width: 24,
                        height: 24,
                        borderRadius: '50%',
                        background: paso.estado === 'avisado' ? '#402b47' : '#c9e0eb',
                        color: paso.estado === 'avisado' ? '#c9e0eb' : '#262423',
                        fontSize: 12,
                        fontWeight: 700,
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        flexShrink: 0,
                      }}
                    >
                      {paso.nivel}
                    </span>
                    <span style={{ fontWeight: paso.estado === 'avisado' ? 600 : 400 }}>
                      {paso.destino_nombre}
                    </span>
                  </div>
                  <span className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                    {paso.estado === 'avisado'
                      ? 'avisado'
                      : paso.estado === 'detenido'
                        ? 'detenido'
                        : typeof paso.segundos_restantes === 'number'
                          ? `en ${formatTimer(paso.segundos_restantes)}`
                          : '—'}
                  </span>
                </div>
              ))}
            </div>
          </section>

          {/* Red de apoyo */}
          <section className="card card-pad">
            <h2 style={{ margin: '0 0 12px 0', fontSize: 15.5, fontWeight: 800 }}>Red de apoyo</h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              {alerta.red_apoyo.map((aviso) => (
                <div
                  key={aviso.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    fontSize: 13,
                  }}
                >
                  <span>
                    {aviso.nombre}{' '}
                    <span style={{ color: 'var(--text-secondary)' }}>
                      · {aviso.canal === 'CIRCULO' ? aviso.detalle : aviso.canal}
                    </span>
                  </span>
                  <span
                    className={
                      aviso.estado === 'ENTREGADA' ? 'pill pill-green' : 'pill pill-blue'
                    }
                  >
                    {aviso.etiqueta_estado}
                  </span>
                </div>
              ))}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
};
