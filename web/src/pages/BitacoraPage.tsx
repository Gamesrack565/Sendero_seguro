import React, { useMemo } from 'react';
import { ShieldCheck } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { computeSha256HexSync } from '../utils/crypto';

// Vista de auditoría de la tabla bitacora (RNF16 - solo inserción encadenada con SHA-256)
export const BitacoraPage: React.FC = () => {
  const { bitacora } = useSendero();

  const integridadOk = useMemo(() => {
    for (let i = 0; i < bitacora.length; i++) {
      const e = bitacora[i];
      const expectedPrev = i === 0 ? '0'.repeat(64) : bitacora[i - 1].hash;
      if (e.hash_prev !== expectedPrev) return false;
      const payload = `${e.id}|${e.ocurrida_en}|${e.accion}|${e.entidad}|${e.entidad_id}|${JSON.stringify(e.detalle)}|${e.hash_prev}`;
      if (computeSha256HexSync(payload) !== e.hash) return false;
    }
    return true;
  }, [bitacora]);

  return (
    <div>
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Bitácora de auditoría</h1>
          <span className="page-subtitle mono">
            Tabla bitacora (solo inserción · RNF16) · {bitacora.length} registros
          </span>
        </div>

        <span className={integridadOk ? 'pill pill-green' : 'pill pill-red'} style={{ padding: '6px 12px' }}>
          <ShieldCheck size={14} />
          {integridadOk ? 'Cadena SHA-256 verificada' : 'Error de integridad'}
        </span>
      </div>

      <section className="card" style={{ overflowX: 'auto' }}>
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Fecha y hora</th>
              <th>Responsable</th>
              <th>Acción</th>
              <th>Entidad / ID</th>
              <th>Detalle</th>
              <th>Huella SHA-256</th>
            </tr>
          </thead>
          <tbody>
            {[...bitacora].reverse().map((row) => (
              <tr key={row.id}>
                <td className="mono">{row.id}</td>
                <td className="mono" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
                  {row.ocurrida_en}
                </td>
                <td>
                  <div style={{ fontWeight: 700, fontSize: 13 }}>{row.usuario_nombre}</div>
                  <div className="mono" style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                    {row.usuario_rol} · {row.ip}
                  </div>
                </td>
                <td>
                  <span className="pill pill-blue mono">{row.accion}</span>
                </td>
                <td className="mono" style={{ fontSize: 12 }}>
                  {row.entidad}
                  <div style={{ color: 'var(--text-secondary)' }}>{row.entidad_id}</div>
                </td>
                <td className="mono" style={{ fontSize: 11.5, maxWidth: 280, wordBreak: 'break-word' }}>
                  {JSON.stringify(row.detalle)}
                </td>
                <td className="mono" style={{ fontSize: 11, color: 'var(--text-secondary)' }} title={row.hash}>
                  {row.hash.slice(0, 14)}…
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
};
