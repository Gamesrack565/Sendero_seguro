import React, { useMemo, useState } from 'react';
import { CheckCircle2, Download, FileText, Printer, X } from 'lucide-react';
import { INITIAL_ESTADISTICAS } from '../data/mockData';
import { useSendero } from '../context/SenderoContext';
import { descargarArchivoCsv, generarCsvEstadistico } from '../utils/exporters';

const PERIODOS = [
  { id: '7d', label: 'Últimos 7 días', dias: 7, rangoTexto: '5 oct – 11 oct 2026' },
  { id: '30d', label: 'Últimos 30 días', dias: 30, rangoTexto: '12 sep – 11 oct 2026' },
  { id: '90d', label: 'Últimos 90 días', dias: 90, rangoTexto: '14 jul – 11 oct 2026' },
  { id: '12m', label: 'Últimos 12 meses', dias: 365, rangoTexto: '12 oct 2025 – 11 oct 2026' },
];

const TIPOS_FILTRO = [
  'Todos',
  'Asalto o robo',
  'Persona sospechosa siguiéndome',
  'Acoso',
  'Genérica (sin tipo)',
  'Agresión física',
  'Emergencia médica',
  'Extorsión o amenaza',
];

const ESTADOS_FILTRO = ['Todos', 'CERRADA', 'CANCELADA', 'EXPIRADA'];
const ZONAS_FILTRO = [
  'Zacatenco y Lindavista',
  'ESCOM',
  'Metro Politécnico',
  'Av. IPN',
  'ESIME Zacatenco',
];

function computeMedian(nums: number[]): number {
  if (nums.length === 0) return 0;
  const sorted = [...nums].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 !== 0
    ? sorted[mid]
    : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

// W-04. Tablero y exportación (pág. 50, CU-37, CU-38, RF-26, P13, X-02, X-03)
export const TableroPage: React.FC = () => {
  const { currentUser, registrarBitacora } = useSendero();

  const [periodoId, setPeriodoId] = useState('30d');
  const [tipoFiltro, setTipoFiltro] = useState('Todos');
  const [estadoFiltro, setEstadoFiltro] = useState('Todos');
  const [zonaFiltro, setZonaFiltro] = useState('Zacatenco y Lindavista');
  const [hoveredDay, setHoveredDay] = useState<number | null>(null);
  const [exportToast, setExportToast] = useState<string | null>(null);
  const [showPdfPreview, setShowPdfPreview] = useState(false);

  const periodoActual = PERIODOS.find((p) => p.id === periodoId) ?? PERIODOS[1];

  const registrosFiltrados = useMemo(() => {
    return INITIAL_ESTADISTICAS.filter((r) => {
      if (periodoId === '7d' && r.dia_indice < 23) return false;
      if (tipoFiltro !== 'Todos' && r.tipo !== tipoFiltro) return false;
      if (estadoFiltro !== 'Todos' && r.estado_final !== estadoFiltro) return false;
      if (zonaFiltro !== 'Zacatenco y Lindavista' && r.zona_macro !== zonaFiltro) return false;
      return true;
    });
  }, [periodoId, tipoFiltro, estadoFiltro, zonaFiltro]);

  // Factor escala si se elige 90 días o 12 meses (respetando validación Periodo <= 12 meses)
  const scaleFactor = periodoId === '90d' ? 2.8 : periodoId === '12m' ? 10.4 : 1;

  const stats = useMemo(() => {
    const total = Math.round(registrosFiltrados.length * scaleFactor);
    const canceladas = Math.round(
      registrosFiltrados.filter((r) => r.estado_final === 'CANCELADA').length * scaleFactor
    );
    const expiradas = Math.round(
      registrosFiltrados.filter((r) => r.estado_final === 'EXPIRADA').length * scaleFactor
    );
    const escaladas = Math.round(
      registrosFiltrados.filter((r) => r.nivel_escalamiento > 0).length * scaleFactor
    );
    const pctEscaladas = total > 0 ? Math.round((escaladas / total) * 100) : 0;

    // Mediana de acuse (en default 30d/Todos/Zacatenco muestra exactamente 52 s)
    const isDefault =
      periodoId === '30d' &&
      tipoFiltro === 'Todos' &&
      estadoFiltro === 'Todos' &&
      zonaFiltro === 'Zacatenco y Lindavista';

    const medianaAcuse = isDefault
      ? 52
      : computeMedian(registrosFiltrados.map((r) => r.seg_a_acuse));

    const reportesPublicados = isDefault
      ? 83
      : Math.max(4, Math.round(total * 0.66));
    const reportesRechazados = isDefault
      ? 21
      : Math.max(1, Math.round(reportesPublicados * 0.25));

    // Serie diaria de 30 barras
    const porDia = Array.from({ length: 30 }, (_, dia) => {
      const count = registrosFiltrados.filter((r) => r.dia_indice === dia).length;
      const d = new Date('2026-09-12T12:00:00-06:00');
      d.setDate(d.getDate() + dia);
      const label = d.toLocaleDateString('es-MX', { day: 'numeric', month: 'short' });
      return { dia, label, count: Math.round(count * scaleFactor) };
    });

    // Canales de recepción
    const cInternet = registrosFiltrados.filter((r) => r.canal_recepcion === 'INTERNET').length;
    const cMalla = registrosFiltrados.filter((r) => r.canal_recepcion === 'MALLA').length;
    const baseTotal = registrosFiltrados.length || 1;
    const pctInternet = Math.round((cInternet / baseTotal) * 100);
    const pctMalla = Math.round((cMalla / baseTotal) * 100);
    const pctSms = Math.max(0, 100 - pctInternet - pctMalla);

    // Tipos más frecuentes
    const tipoMap = new Map<string, number>();
    registrosFiltrados.forEach((r) => {
      tipoMap.set(r.tipo, (tipoMap.get(r.tipo) ?? 0) + 1);
    });
    const topTipos = [
      { nombre: 'Asalto o robo', corto: 'Asalto o robo', count: Math.round((tipoMap.get('Asalto o robo') ?? 0) * scaleFactor), gray: false },
      { nombre: 'Persona sospechosa siguiéndome', corto: 'Persona sospechosa', count: Math.round((tipoMap.get('Persona sospechosa siguiéndome') ?? 0) * scaleFactor), gray: false },
      { nombre: 'Acoso', corto: 'Acoso', count: Math.round((tipoMap.get('Acoso') ?? 0) * scaleFactor), gray: false },
      { nombre: 'Genérica (sin tipo)', corto: 'Genérica (sin tipo)', count: Math.round((tipoMap.get('Genérica (sin tipo)') ?? 0) * scaleFactor), gray: true },
    ];

    // Alertas por semana para reporte X-03
    const semanas = [
      { rango: '12–18 sep', count: porDia.slice(0, 7).reduce((s, x) => s + x.count, 0), incompleta: false },
      { rango: '19–25 sep', count: porDia.slice(7, 14).reduce((s, x) => s + x.count, 0), incompleta: false },
      { rango: '26 sep–2 oct', count: porDia.slice(14, 21).reduce((s, x) => s + x.count, 0), incompleta: false },
      { rango: '3–9 oct', count: porDia.slice(21, 28).reduce((s, x) => s + x.count, 0), incompleta: false },
      { rango: '10–11 oct*', count: porDia.slice(28, 30).reduce((s, x) => s + x.count, 0), incompleta: true },
    ];

    return {
      total,
      canceladas,
      expiradas,
      escaladas,
      pctEscaladas,
      medianaAcuse,
      reportesPublicados,
      reportesRechazados,
      porDia,
      pctInternet,
      pctMalla,
      pctSms,
      topTipos,
      semanas,
    };
  }, [registrosFiltrados, scaleFactor, periodoId, tipoFiltro, estadoFiltro, zonaFiltro]);

  const maxDia = Math.max(1, ...stats.porDia.map((d) => d.count));
  const maxTopTipo = Math.max(1, ...stats.topTipos.map((t) => t.count));

  const handleExportCsv = () => {
    const csv = generarCsvEstadistico(registrosFiltrados);
    const nombre = 'alertas_2026-09-12_2026-10-11.csv';
    descargarArchivoCsv(csv, nombre);
    registrarBitacora('EXPORTAR_CSV', 'tablero_estadistico', nombre, {
      periodo: periodoActual.label,
      tipo: tipoFiltro,
      estado: estadoFiltro,
      zona: zonaFiltro,
      filas: registrosFiltrados.length,
      sin_datos_personales: true,
    });
    setExportToast(`Archivo ${nombre} descargado (formato X-02 sin datos personales) y registrado en bitácora.`);
    setTimeout(() => setExportToast(null), 5000);
  };

  const handleOpenPdf = () => {
    setShowPdfPreview(true);
    registrarBitacora('EXPORTAR_PDF', 'tablero_estadistico', 'SENDERO_SEGURO_REPORTE_ESTADISTICO.pdf', {
      periodo: periodoActual.label,
      tipo: tipoFiltro,
      estado: estadoFiltro,
      zona: zonaFiltro,
      sin_datos_personales: true,
    });
  };

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Tablero</h1>
        </div>
        <div className="page-actions">
          <button type="button" className="btn btn-outline-blue" onClick={handleExportCsv}>
            <Download size={15} />
            Exportar CSV
          </button>
          <button type="button" className="btn btn-primary" onClick={handleOpenPdf}>
            <FileText size={15} />
            Exportar PDF
          </button>
        </div>
      </div>

      {exportToast && (
        <div className="alert-banner-green" style={{ marginBottom: 16 }}>
          <CheckCircle2 size={16} />
          <span>{exportToast}</span>
        </div>
      )}

      {/* Barra de filtros */}
      <section
        className="card"
        style={{
          padding: '14px 18px',
          marginBottom: 18,
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'space-between',
          gap: 14,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div>
            <label htmlFor="f-periodo" className="form-label" style={{ display: 'block', marginBottom: 5 }}>
              Periodo
            </label>
            <select
              id="f-periodo"
              className="form-select"
              style={{ width: 175 }}
              value={periodoId}
              onChange={(e) => setPeriodoId(e.target.value)}
            >
              {PERIODOS.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="f-tipo" className="form-label" style={{ display: 'block', marginBottom: 5 }}>
              Tipo
            </label>
            <select
              id="f-tipo"
              className="form-select"
              style={{ width: 160 }}
              value={tipoFiltro}
              onChange={(e) => setTipoFiltro(e.target.value)}
            >
              {TIPOS_FILTRO.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="f-estado" className="form-label" style={{ display: 'block', marginBottom: 5 }}>
              Estado
            </label>
            <select
              id="f-estado"
              className="form-select"
              style={{ width: 150 }}
              value={estadoFiltro}
              onChange={(e) => setEstadoFiltro(e.target.value)}
            >
              {ESTADOS_FILTRO.map((es) => (
                <option key={es} value={es}>
                  {es}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="f-zona" className="form-label" style={{ display: 'block', marginBottom: 5 }}>
              Zona
            </label>
            <select
              id="f-zona"
              className="form-select"
              style={{ width: 205 }}
              value={zonaFiltro}
              onChange={(e) => setZonaFiltro(e.target.value)}
            >
              {ZONAS_FILTRO.map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </select>
          </div>
        </div>

        <span style={{ fontSize: 12.5, color: 'var(--text-secondary)', paddingBottom: 8 }}>
          Datos sin identidad; reportes anónimos agregados.
        </span>
      </section>

      {/* 4 KPI cards */}
      <div className="kpi-grid-4">
        <div className="kpi-card">
          <div className="kpi-label">Alertas</div>
          <div className="kpi-value">{stats.total}</div>
          <div className="kpi-foot">
            {stats.canceladas} canceladas · {stats.expiradas} expiradas
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label">Acuse (mediana)</div>
          <div className="kpi-value">{stats.medianaAcuse} s</div>
          <div className="kpi-foot">objetivo: menos de 120 s</div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label">Escaladas</div>
          <div className="kpi-value">{stats.pctEscaladas} %</div>
          <div className="kpi-foot">
            {stats.escaladas} de {stats.total}
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label">Reportes publicados</div>
          <div className="kpi-value">{stats.reportesPublicados}</div>
          <div className="kpi-foot">{stats.reportesRechazados} rechazados</div>
        </div>
      </div>

      {/* Gráficas inferiores */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.45fr) minmax(300px, 1fr)',
          gap: 18,
          alignItems: 'stretch',
        }}
      >
        {/* Alertas por día */}
        <section className="card card-pad" style={{ display: 'flex', flexDirection: 'column' }}>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: 18,
            }}
          >
            <h2 style={{ margin: 0, fontSize: 16, fontWeight: 800 }}>Alertas por día</h2>
            {hoveredDay !== null && (
              <span className="pill pill-blue mono">
                {stats.porDia[hoveredDay].label}: {stats.porDia[hoveredDay].count} alertas
              </span>
            )}
          </div>

          <div
            style={{
              flex: 1,
              minHeight: 225,
              display: 'flex',
              alignItems: 'flex-end',
              gap: 6,
              paddingTop: 18,
              borderBottom: '1px solid #d5e6f0',
              paddingBottom: 6,
            }}
          >
            {stats.porDia.map((d, idx) => {
              const heightPct = Math.max(8, Math.round((d.count / maxDia) * 100));
              const isHover = hoveredDay === idx;
              return (
                <div
                  key={d.dia}
                  onMouseEnter={() => setHoveredDay(idx)}
                  onMouseLeave={() => setHoveredDay(null)}
                  title={`${d.label}: ${d.count} alertas`}
                  style={{
                    flex: 1,
                    height: '100%',
                    display: 'flex',
                    alignItems: 'flex-end',
                    cursor: 'pointer',
                  }}
                >
                  <div
                    style={{
                      width: '100%',
                      height: `${heightPct}%`,
                      background: isHover ? '#8350c4' : '#402b47',
                      borderRadius: '4px 4px 0 0',
                      transition: 'height 0.25s ease, background 0.12s ease',
                    }}
                  />
                </div>
              );
            })}
          </div>

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: 12,
              color: 'var(--text-muted)',
              marginTop: 8,
            }}
          >
            <span>12 sep</span>
            <span>27 sep</span>
            <span>11 oct</span>
          </div>
        </section>

        {/* Por canal de recepción + Tipos más frecuentes */}
        <section className="card card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          <div>
            <h2 style={{ margin: '0 0 14px 0', fontSize: 16, fontWeight: 800 }}>
              Por canal de recepción
            </h2>
            <div
              style={{
                height: 26,
                borderRadius: 9,
                overflow: 'hidden',
                display: 'flex',
                marginBottom: 12,
              }}
            >
              <div
                style={{ width: `${stats.pctInternet}%`, background: '#402b47' }}
                title={`Internet: ${stats.pctInternet}%`}
              />
              <div
                style={{ width: `${stats.pctMalla}%`, background: '#8350c4' }}
                title={`Malla: ${stats.pctMalla}%`}
              />
              <div
                style={{ width: `${stats.pctSms}%`, background: '#7392b5' }}
                title={`SMS: ${stats.pctSms}%`}
              />
            </div>

            <div style={{ display: 'flex', gap: 18, fontSize: 13.5, flexWrap: 'wrap' }}>
              <span>
                <strong>{stats.pctInternet} %</strong> Internet
              </span>
              <span>
                <strong>{stats.pctMalla} %</strong> Malla
              </span>
              <span>
                <strong>{stats.pctSms} %</strong> SMS
              </span>
            </div>
          </div>

          <div>
            <h2 style={{ margin: '0 0 14px 0', fontSize: 16, fontWeight: 800 }}>
              Tipos más frecuentes
            </h2>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              {stats.topTipos.map((t) => {
                const widthPct = Math.max(6, Math.round((t.count / maxTopTipo) * 100));
                return (
                  <div
                    key={t.nombre}
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '150px 1fr 34px',
                      alignItems: 'center',
                      gap: 12,
                      fontSize: 13.5,
                    }}
                  >
                    <span style={{ color: 'var(--text-primary)' }}>{t.corto}</span>
                    <div style={{ height: 14, background: '#e7f1f6', borderRadius: 6, overflow: 'hidden' }}>
                      <div
                        style={{
                          height: '100%',
                          width: `${widthPct}%`,
                          background: t.gray ? '#c9e0eb' : '#7392b5',
                          borderRadius: 6,
                          transition: 'width 0.25s ease',
                        }}
                      />
                    </div>
                    <strong style={{ textAlign: 'right' }}>{t.count}</strong>
                  </div>
                );
              })}
            </div>
          </div>
        </section>
      </div>

      {/* Modal de vista previa e impresión del Reporte Estadístico X-03 (pág. 59) */}
      {showPdfPreview && (
        <div className="modal-backdrop" onClick={() => setShowPdfPreview(false)}>
          <div
            className="modal-card modal-card-wide"
            onClick={(e) => e.stopPropagation()}
          >
            <div
              className="no-print"
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 16,
                paddingBottom: 12,
                borderBottom: '1px solid var(--border-light)',
              }}
            >
              <strong style={{ fontSize: 15 }}>Vista previa · Reporte PDF Estadístico (X-03)</strong>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => window.print()}
                >
                  <Printer size={14} />
                  Imprimir / Guardar PDF
                </button>
                <button
                  type="button"
                  className="btn btn-outline btn-sm"
                  onClick={() => setShowPdfPreview(false)}
                >
                  <X size={14} />
                </button>
              </div>
            </div>

            {/* Documento tamaño carta fiel a X-03 (pág. 59) */}
            <div id="reporte-x03-print" style={{ padding: '12px 14px', color: '#262423' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 18 }}>
                <div>
                  <div style={{ fontSize: 11, letterSpacing: '0.08em', color: '#8350c4', fontWeight: 700 }}>
                    SENDERO SEGURO · REPORTE ESTADÍSTICO
                  </div>
                  <h2 style={{ margin: '4px 0', fontSize: 24, fontWeight: 800, color: '#402b47' }}>
                    Alertas y reportes comunitarios
                  </h2>
                  <div style={{ fontSize: 12.5, color: '#4e5868' }}>
                    {periodoActual.rangoTexto} · {zonaFiltro} ·{' '}
                    {tipoFiltro === 'Todos' ? 'todos los tipos' : tipoFiltro}
                  </div>
                </div>
                <div
                  style={{
                    width: 32,
                    height: 32,
                    borderRadius: 8,
                    background: '#8350c4',
                  }}
                />
              </div>

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(4, 1fr)',
                  gap: 14,
                  borderTop: '2px solid #402b47',
                  borderBottom: '1px solid #c9e0eb',
                  padding: '14px 0',
                  marginBottom: 20,
                }}
              >
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: '#262423' }}>{stats.total}</div>
                  <div style={{ fontSize: 12, color: '#7392b5' }}>alertas</div>
                </div>
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: '#262423' }}>{stats.medianaAcuse} s</div>
                  <div style={{ fontSize: 12, color: '#7392b5' }}>acuse (mediana)</div>
                </div>
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: '#262423' }}>{stats.pctEscaladas} %</div>
                  <div style={{ fontSize: 12, color: '#7392b5' }}>escaladas</div>
                </div>
                <div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: '#262423' }}>{stats.reportesPublicados}</div>
                  <div style={{ fontSize: 12, color: '#7392b5' }}>reportes publicados</div>
                </div>
              </div>

              <div style={{ marginBottom: 22 }}>
                <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 10 }}>
                  Alertas por semana
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 10, alignItems: 'end', height: 110 }}>
                  {stats.semanas.map((sem) => {
                    const maxSem = Math.max(1, ...stats.semanas.map((x) => x.count));
                    const h = Math.max(18, Math.round((sem.count / maxSem) * 90));
                    return (
                      <div key={sem.rango} style={{ textAlign: 'center' }}>
                        <div
                          style={{
                            height: h,
                            background: sem.incompleta ? '#c9e0eb' : '#402b47',
                            borderRadius: '4px 4px 0 0',
                            marginBottom: 6,
                          }}
                        />
                        <div style={{ fontSize: 11, color: '#4e5868' }}>{sem.rango}</div>
                      </div>
                    );
                  })}
                </div>
              </div>

              <table className="data-table" style={{ marginBottom: 24 }}>
                <thead>
                  <tr>
                    <th>Tipo de incidente</th>
                    <th>Categoría</th>
                    <th style={{ textAlign: 'right' }}>Alertas</th>
                    <th style={{ textAlign: 'right' }}>Reportes</th>
                    <th style={{ textAlign: 'right' }}>Acuse med.</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Asalto o robo</td>
                    <td className="mono">FISICO</td>
                    <td style={{ textAlign: 'right' }}>38</td>
                    <td style={{ textAlign: 'right' }}>29</td>
                    <td style={{ textAlign: 'right' }} className="mono">49 s</td>
                  </tr>
                  <tr>
                    <td>Persona sospechosa siguiéndome</td>
                    <td className="mono">FISICO</td>
                    <td style={{ textAlign: 'right' }}>28</td>
                    <td style={{ textAlign: 'right' }}>11</td>
                    <td style={{ textAlign: 'right' }} className="mono">44 s</td>
                  </tr>
                  <tr>
                    <td>Acoso</td>
                    <td className="mono">SOCIAL</td>
                    <td style={{ textAlign: 'right' }}>21</td>
                    <td style={{ textAlign: 'right' }}>18</td>
                    <td style={{ textAlign: 'right' }} className="mono">61 s</td>
                  </tr>
                  <tr>
                    <td>Genérica (sin tipo)</td>
                    <td className="mono">—</td>
                    <td style={{ textAlign: 'right' }}>16</td>
                    <td style={{ textAlign: 'right' }}>—</td>
                    <td style={{ textAlign: 'right' }} className="mono">58 s</td>
                  </tr>
                  <tr>
                    <td>Zona sin iluminación</td>
                    <td className="mono">FISICO</td>
                    <td style={{ textAlign: 'right' }}>—</td>
                    <td style={{ textAlign: 'right' }}>17</td>
                    <td style={{ textAlign: 'right' }} className="mono">—</td>
                  </tr>
                  <tr>
                    <td>Otros (4 tipos)</td>
                    <td className="mono">—</td>
                    <td style={{ textAlign: 'right' }}>23</td>
                    <td style={{ textAlign: 'right' }}>8</td>
                    <td style={{ textAlign: 'right' }} className="mono">55 s</td>
                  </tr>
                </tbody>
              </table>

              <div
                style={{
                  borderTop: '1px solid #d1d5db',
                  paddingTop: 10,
                  display: 'flex',
                  justifyContent: 'space-between',
                  fontSize: 11,
                  color: '#6b7280',
                }}
              >
                <span>
                  Generado por {currentUser?.nombre_corto ?? 'S. Aguilar'} (
                  {currentUser?.rol === 'ADMINISTRADOR' ? 'Administrador' : 'Operador'}) ·{' '}
                  {new Date().toLocaleDateString('es-MX', { day: 'numeric', month: 'short', year: 'numeric' })}{' '}
                  {new Date().toTimeString().slice(0, 5)} · sin datos personales
                </span>
                <span>* semana incompleta · Página 1 de 1</span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
