import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BellRing, Sparkles, Wifi, WifiOff } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { OperationalMap } from '../components/OperationalMap';
import { Alerta, AlertaEstado, CanalRecepcion } from '../types/domain';

type OrdenCola = 'IA' | 'CATALOGO' | 'ANTIGUEDAD';

function formatElapsed(totalSeconds: number): string {
  const mins = Math.floor(totalSeconds / 60);
  const secs = totalSeconds % 60;
  return `${mins}:${String(secs).padStart(2, '0')}`;
}

function getScoreBoxInfo(alerta: Alerta, fallback: boolean) {
  if (fallback || alerta.prioridad_ia === null) {
    const p = alerta.prioridad_catalogo;
    const cls = p === 1 ? 'score-urg' : p === 2 ? 'score-alta' : p === 3 ? 'score-media' : 'score-baja';
    return { num: `P${p}`, lbl: 'CAT.', cls };
  }
  const s = alerta.prioridad_ia;
  if (s >= 80) return { num: String(s), lbl: 'URG.', cls: 'score-urg' };
  if (s >= 60) return { num: String(s), lbl: 'ALTA', cls: 'score-alta' };
  if (s >= 35) return { num: String(s), lbl: 'MEDIA', cls: 'score-media' };
  return { num: String(s), lbl: 'BAJA', cls: 'score-baja' };
}

function estadoPillClass(estado: AlertaEstado): string {
  switch (estado) {
    case 'ESCALADA':
      return 'pill pill-amber';
    case 'EN_ATENCION':
    case 'ACUSADA':
    case 'ATENDIDA':
      return 'pill pill-green';
    case 'ASIGNADA':
      return 'pill pill-blue';
    default:
      return 'pill pill-gray';
  }
}

function canalPillClass(canal: CanalRecepcion): string {
  if (canal === 'MALLA') return 'pill pill-blue';
  if (canal === 'SMS') return 'pill pill-amber';
  return 'pill pill-green';
}

// W-02. Dashboard de alertas (pág. 48, CU-17, RF-10, RF-11, P14)
export const AlertasDashboardPage: React.FC = () => {
  const {
    alertas,
    currentUnit,
    wsConnected,
    ia4FallbackActive,
    setIa4FallbackActive,
    simulateIncomingP1Alert,
  } = useSendero();
  const navigate = useNavigate();

  const [orden, setOrden] = useState<OrdenCola>('IA');
  const [tick, setTick] = useState(0);
  const [liveTime, setLiveTime] = useState(() => new Date().toTimeString().slice(0, 8));

  useEffect(() => {
    const interval = setInterval(() => {
      setTick((t) => t + 1);
      setLiveTime(new Date().toTimeString().slice(0, 8));
    }, 1000);
    return () => clearInterval(interval);
  }, []);

  const activas = useMemo(
    () => alertas.filter((a) => a.estado !== 'CERRADA' && a.estado !== 'CANCELADA'),
    [alertas]
  );

  const sinAcuseCount = useMemo(
    () => activas.filter((a) => a.estado === 'ESCALADA' || a.estado === 'ASIGNADA').length,
    [activas]
  );

  // Nota: en el mockup, Sin acuse destaca específicamente alertas escaladas/sin acuse vencido (1),
  // o si se acusan todas pasa a 0. Mostremos alertas sin acuse con estado ESCALADA o vencidas, o todas sin acusada_en.
  const escaladasOSinAcuseUrg = useMemo(
    () => activas.filter((a) => !a.acusada_en && a.estado === 'ESCALADA').length,
    [activas]
  );
  const kpiSinAcuse = escaladasOSinAcuseUrg > 0 ? escaladasOSinAcuseUrg : sinAcuseCount;

  const recibidasMallaSms = useMemo(
    () => activas.filter((a) => a.canal_recepcion === 'MALLA' || a.canal_recepcion === 'SMS').length,
    [activas]
  );

  const ordenEfectivo: OrdenCola = ia4FallbackActive && orden === 'IA' ? 'CATALOGO' : orden;

  const colaOrdenada = useMemo(() => {
    const copy = [...activas];
    if (ordenEfectivo === 'IA') {
      copy.sort((a, b) => (b.prioridad_ia ?? 0) - (a.prioridad_ia ?? 0));
    } else if (ordenEfectivo === 'CATALOGO') {
      copy.sort((a, b) => {
        if (a.prioridad_catalogo !== b.prioridad_catalogo) {
          return a.prioridad_catalogo - b.prioridad_catalogo;
        }
        return b.segundos_transcurridos_inicial - a.segundos_transcurridos_inicial;
      });
    } else {
      copy.sort((a, b) => b.segundos_transcurridos_inicial - a.segundos_transcurridos_inicial);
    }
    return copy;
  }, [activas, ordenEfectivo]);

  return (
    <div>
      {/* Encabezado */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Alertas activas</h1>
          <span className="page-subtitle mono" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            {wsConnected ? <Wifi size={14} color="#7392b5" /> : <WifiOff size={14} color="#8350c4" />}
            Actualizado en vivo · {liveTime}
          </span>
        </div>

        {/* Controles útiles de demostración en vivo (WebSocket P1 y Respaldo IA-4 >200 ms) */}
        <div className="page-actions">
          <button
            type="button"
            className={ia4FallbackActive ? 'btn btn-outline-red btn-sm' : 'btn btn-outline btn-sm'}
            onClick={() => setIa4FallbackActive(!ia4FallbackActive)}
            title="Simular que el servicio IA-4 excede 200 ms y entra el respaldo por catálogo y antigüedad"
          >
            {ia4FallbackActive ? 'IA-4 en respaldo (>200 ms)' : 'Simular respaldo IA-4'}
          </button>
          <button
            type="button"
            className="btn btn-outline-blue btn-sm"
            onClick={simulateIncomingP1Alert}
          >
            <BellRing size={14} />
            Simular alerta P1
          </button>
        </div>
      </div>

      {/* 4 Indicadores del turno */}
      <div className="kpi-grid-4">
        <div className="kpi-card">
          <div className="kpi-label">Activas</div>
          <div className="kpi-value">{activas.length}</div>
        </div>
        <div className={`kpi-card ${kpiSinAcuse > 0 ? 'kpi-highlight-amber' : ''}`}>
          <div className="kpi-label">Sin acuse</div>
          <div className="kpi-value">{kpiSinAcuse}</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Acuse promedio hoy</div>
          <div className="kpi-value">48 s</div>
        </div>
        <div className="kpi-card">
          <div className="kpi-label">Recibidas por malla/SMS</div>
          <div className="kpi-value">{recibidasMallaSms}</div>
        </div>
      </div>

      {/* Layout principal: Cola de atención + Mapa operativo */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.55fr) minmax(320px, 1fr)',
          gap: 18,
          alignItems: 'start',
        }}
      >
        {/* Cola de atención */}
        <section className="card" style={{ overflow: 'hidden' }}>
          <div
            style={{
              padding: '15px 18px',
              borderBottom: '1px solid var(--border-light)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 12,
              flexWrap: 'wrap',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <h2 style={{ margin: 0, fontSize: 16, fontWeight: 800 }}>Cola de atención</h2>
              {ordenEfectivo === 'IA' ? (
                <span className="pill pill-ia">
                  <Sparkles size={12} />
                  Ordenada por prioridad IA
                </span>
              ) : (
                <span className="pill pill-amber">
                  Respaldo: {ordenEfectivo === 'CATALOGO' ? 'prioridad catálogo y antigüedad' : 'antigüedad'}
                </span>
              )}
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <label htmlFor="orden-cola" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                Orden
              </label>
              <select
                id="orden-cola"
                className="form-select"
                style={{ width: 'auto', padding: '6px 28px 6px 10px', fontSize: 13 }}
                value={orden}
                onChange={(e) => setOrden(e.target.value as OrdenCola)}
              >
                <option value="IA">Prioridad IA</option>
                <option value="CATALOGO">Prioridad de catálogo</option>
                <option value="ANTIGUEDAD">Antigüedad</option>
              </select>
            </div>
          </div>

          {colaOrdenada.length === 0 ? (
            <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-secondary)' }}>
              No hay alertas activas en este momento.
            </div>
          ) : (
            <div>
              {colaOrdenada.map((alerta) => {
                const score = getScoreBoxInfo(alerta, ia4FallbackActive);
                const elapsedSec = alerta.segundos_transcurridos_inicial + tick;
                const distTexto =
                  alerta.distancia_m >= 1000
                    ? `${(alerta.distancia_m / 1000).toFixed(1)} km`
                    : `${alerta.distancia_m} m`;
                const canalTexto =
                  alerta.canal_recepcion === 'MALLA' && alerta.saltos_malla
                    ? `MALLA · ${alerta.saltos_malla} saltos`
                    : alerta.canal_recepcion;

                return (
                  <div
                    key={alerta.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate(`/alertas/${alerta.id}`)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') navigate(`/alertas/${alerta.id}`);
                    }}
                    style={{
                      display: 'flex',
                      alignItems: 'flex-start',
                      gap: 14,
                      padding: '15px 18px',
                      borderBottom: '1px solid #e6f0f6',
                      cursor: 'pointer',
                      transition: 'background 0.12s ease',
                    }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = '#f2f8fb')}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    {/* Caja de puntaje IA-4 o catálogo */}
                    <div className={`score-box ${score.cls}`}>
                      <span className="score-num">{score.num}</span>
                      <span className="score-lbl">{score.lbl}</span>
                    </div>

                    {/* Contenido central */}
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          flexWrap: 'wrap',
                          marginBottom: 4,
                        }}
                      >
                        <strong style={{ fontSize: 15.5, color: 'var(--text-primary)' }}>
                          {alerta.tipo_nombre}
                        </strong>
                        <span className={estadoPillClass(alerta.estado)}>{alerta.estado}</span>
                        <span className={canalPillClass(alerta.canal_recepcion)}>{canalTexto}</span>
                      </div>

                      <div
                        className="mono"
                        style={{
                          fontSize: 12.5,
                          color: 'var(--text-secondary)',
                          marginBottom: 8,
                        }}
                      >
                        {alerta.folio} · {alerta.emisor_nombre_corto} · {alerta.zona_nombre} ·{' '}
                        {distTexto}
                      </div>

                      {/* Motivos de IA-4 */}
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                        {alerta.prioridad_motivos.map((motivo) => (
                          <span
                            key={motivo}
                            className="pill pill-ia"
                            style={{ fontWeight: 600, fontSize: 11.5 }}
                          >
                            {motivo}
                          </span>
                        ))}
                      </div>
                    </div>

                    {/* Tiempo transcurrido */}
                    <div
                      className="mono"
                      style={{
                        fontSize: 16,
                        fontWeight: 800,
                        color: alerta.estado === 'ESCALADA' ? '#8350c4' : 'var(--text-primary)',
                        flexShrink: 0,
                      }}
                    >
                      {formatElapsed(elapsedSec)}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Nota de respaldo IA-4 */}
          <div
            style={{
              padding: '12px 18px',
              fontSize: 12,
              color: 'var(--text-secondary)',
              background: '#f5fafc',
            }}
          >
            Si el servicio de IA no responde en <span className="mono">200 ms</span>, la cola se
            ordena por prioridad del catálogo y antigüedad.
          </div>
        </section>

        {/* Mapa operativo */}
        <section>
          <OperationalMap
            mode="dashboard"
            alertas={activas}
            unit={currentUnit}
            height={510}
            onSelectAlerta={(id) => navigate(`/alertas/${id}`)}
          />
        </section>
      </div>
    </div>
  );
};
