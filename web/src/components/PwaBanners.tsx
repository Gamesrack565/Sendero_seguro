import React, { useState } from 'react';
import { AlertCircle, CheckCircle2, Download, RefreshCw, Smartphone, WifiOff, X } from 'lucide-react';
import { usePwaInstall, usePwaStatus } from '../hooks/usePwa';

interface PwaInstallButtonProps {
  compact?: boolean;
}

export const PwaInstallButton: React.FC<PwaInstallButtonProps> = ({ compact = false }) => {
  const { canPromptInstall, isInstalled, isSecureContext, triggerInstall } = usePwaInstall();
  const [showHelpModal, setShowHelpModal] = useState(false);

  if (isInstalled) return null;

  const handleClick = async () => {
    const result = await triggerInstall();
    if (result === 'manual') {
      setShowHelpModal(true);
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={handleClick}
        title={
          canPromptInstall
            ? 'Instalar Sendero Seguro ahora en tu dispositivo'
            : 'Ver cómo instalar Sendero Seguro como PWA'
        }
        style={
          compact
            ? {
                background: canPromptInstall ? '#8350c4' : 'rgba(131, 80, 196, 0.28)',
                border: '1px solid rgba(201, 224, 235, 0.45)',
                color: '#ffffff',
                borderRadius: 8,
                padding: '5px 10px',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                fontSize: 12,
                fontWeight: 700,
                cursor: 'pointer',
              }
            : undefined
        }
        className={compact ? undefined : canPromptInstall ? 'btn btn-purple btn-sm' : 'btn btn-outline-blue btn-sm'}
      >
        <Download size={14} />
        <span>Instalar app</span>
      </button>

      {showHelpModal && (
        <div className="modal-backdrop" onClick={() => setShowHelpModal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Smartphone size={19} color="#8350c4" />
                <h3 style={{ margin: 0, fontSize: 17 }}>Instalar Sendero Seguro (PWA)</h3>
              </div>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => setShowHelpModal(false)}
              >
                <X size={14} />
              </button>
            </div>

            {!isSecureContext && (
              <div className="alert-banner-red" style={{ marginBottom: 14 }}>
                <AlertCircle size={17} style={{ flexShrink: 0, marginTop: 1 }} />
                <span>
                  Estás abriendo la app por una IP sin HTTPS (<code>{window.location.origin}</code>).
                  Para que el navegador permita la instalación directa, ábrela en{' '}
                  <strong>http://localhost:5173</strong> o mediante <strong>HTTPS</strong>.
                </span>
              </div>
            )}

            <p style={{ fontSize: 13.5, color: 'var(--text-secondary)', lineHeight: 1.55, marginTop: 0 }}>
              El navegador no habilitó el diálogo automático en este momento (puede pasar si usas
              modo incógnito, Firefox/Safari, o si ya está instalada). Puedes instalarla manualmente así:
            </p>

            <ul style={{ fontSize: 13.5, color: 'var(--text-primary)', lineHeight: 1.6, paddingLeft: 20 }}>
              <li>
                <strong>Chrome / Edge (escritorio):</strong> haz clic en el icono de computadora con
                flecha en el extremo derecho de la barra de direcciones, o en el menú{' '}
                <em>⋮ → Enviar, guardar y compartir → Instalar página como app</em>.
              </li>
              <li>
                <strong>Android (Chrome):</strong> toca <em>⋮ → Instalar aplicación</em>.
              </li>
              <li>
                <strong>iPhone / iPad (Safari):</strong> toca <em>Compartir</em> →{' '}
                <em>Añadir a pantalla de inicio</em>.
              </li>
            </ul>

            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => setShowHelpModal(false)}
              >
                Cerrar
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};

export const PwaStatusBanners: React.FC = () => {
  const {
    isOnline,
    offlineReady,
    needRefresh,
    updateServiceWorker,
    dismissOfflineReady,
    dismissNeedRefresh,
  } = usePwaStatus();

  return (
    <>
      {!isOnline && (
        <div className="pwa-offline-bar" role="status">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <WifiOff size={15} />
            <span>
              <strong>Modo sin conexión (PWA):</strong> operando desde caché local. Tus cambios se
              guardan en este dispositivo.
            </span>
          </div>
        </div>
      )}

      {(needRefresh || offlineReady) && (
        <div className="pwa-toast" role="status">
          {needRefresh ? (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <RefreshCw size={16} color="#8350c4" />
                <span>Hay una nueva versión disponible de Sendero Seguro.</span>
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  type="button"
                  className="btn btn-purple btn-sm"
                  onClick={() => updateServiceWorker(true)}
                >
                  Actualizar ahora
                </button>
                <button
                  type="button"
                  className="btn btn-outline btn-sm"
                  onClick={dismissNeedRefresh}
                >
                  <X size={14} />
                </button>
              </div>
            </>
          ) : (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <CheckCircle2 size={16} color="#7392b5" />
                <span>App lista para funcionar sin conexión (caché PWA instalada).</span>
              </div>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={dismissOfflineReady}
              >
                <X size={14} />
              </button>
            </>
          )}
        </div>
      )}
    </>
  );
};
