import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { CheckCircle2, PhoneCall, ShieldAlert } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { OperationalMap } from '../components/OperationalMap';
import { ShieldLogo } from '../components/ShieldLogo';

// X-01. Página web pública de enlace de ubicación (pág. 57, CU-16, RF-9)
// Expira al cerrarse la alerta y NO muestra matrícula ni datos del perfil.
export const PublicTrackingPage: React.FC = () => {
  const { token } = useParams<{ token: string }>();
  const { alertas } = useSendero();

  const alerta = alertas.find((a) => a.token_seguimiento === token);

  if (!alerta) {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 24,
        }}
      >
        <div className="card card-pad" style={{ maxWidth: 440, width: '100%', textAlign: 'center' }}>
          <ShieldLogo size={42} />
          <h2 style={{ marginTop: 14 }}>Enlace de ubicación expirado o inválido</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: 14 }}>
            Este enlace temporal de seguimiento ya no está vigente.
          </p>
          <Link to="/login" className="btn btn-primary btn-sm">
            Ir a Sendero Seguro
          </Link>
        </div>
      </div>
    );
  }

  const cerrada = alerta.estado === 'CERRADA' || alerta.estado === 'CANCELADA';
  const lastLoc = alerta.ubicaciones[alerta.ubicaciones.length - 1];

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#edf4f8',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        padding: '28px 16px',
      }}
    >
      <div style={{ maxWidth: 460, width: '100%' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: 14,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontWeight: 700 }}>
            <ShieldLogo size={28} />
            <span>Sendero Seguro · Enlace de ubicación (X-01)</span>
          </div>
          <span className="pill pill-gray mono">ss.ipn.mx/a/{alerta.token_seguimiento}</span>
        </div>

        <div className="card" style={{ overflow: 'hidden' }}>
          {/* Cabecera roja o verde si ya cerró */}
          <div
            style={{
              background: cerrada ? '#402b47' : '#8350c4',
              color: '#ffffff',
              padding: '18px 20px',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              {cerrada ? <CheckCircle2 size={20} /> : <ShieldAlert size={20} />}
              <strong style={{ fontSize: 18 }}>
                {cerrada
                  ? 'Esta alerta ya fue atendida'
                  : `${alerta.emisor_nombre_corto} necesita ayuda`}
              </strong>
            </div>
            <div style={{ fontSize: 13.5, opacity: 0.92 }}>
              {cerrada
                ? 'El enlace de ubicación en vivo ha expirado automáticamente.'
                : `${alerta.tipo_nombre} · ${alerta.activada_en.slice(11, 16)}`}
            </div>
          </div>

          {!cerrada ? (
            <div style={{ padding: 18 }}>
              <OperationalMap mode="public" selectedAlerta={alerta} height={240} />

              <div
                style={{
                  marginTop: 14,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8,
                  fontSize: 13.5,
                }}
              >
                <div>
                  <strong>Estado:</strong>{' '}
                  {alerta.acusada_en
                    ? `Unidad ${alerta.unidad_asignada_nombre} en camino (acuse recibido)`
                    : `Asignada a ${alerta.unidad_asignada_nombre} · esperando acuse`}
                </div>
                {lastLoc && (
                  <div className="mono" style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
                    Última ubicación: {lastLoc.capturada_en} · ±{lastLoc.precision_m} m ({lastLoc.fuente})
                  </div>
                )}
                <div
                  style={{
                    fontSize: 12,
                    color: 'var(--text-muted)',
                    paddingTop: 8,
                    borderTop: '1px solid var(--border-light)',
                  }}
                >
                  Por privacidad (RF-9), esta vista pública no muestra matrícula ni datos del perfil.
                  Al cerrar la alerta mostrará «Esta alerta ya fue atendida».
                </div>
              </div>

              <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
                <a href="tel:911" className="btn btn-primary" style={{ flex: 1 }}>
                  <PhoneCall size={15} />
                  Llamar al 911
                </a>
              </div>
            </div>
          ) : (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>
              <p style={{ margin: '0 0 10px 0', fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' }}>
                «Esta alerta ya fue atendida»
              </p>
              <p style={{ margin: 0, fontSize: 13 }}>
                La ubicación en vivo dejó de compartirse en cuanto la alerta fue cerrada o cancelada.
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
