import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertCircle, CheckCircle2, KeyRound } from 'lucide-react';
import { ShieldLogo } from '../components/ShieldLogo';
import { PwaInstallButton } from '../components/PwaBanners';
import { useSendero } from '../context/SenderoContext';

// W-01. Inicio de sesión web (pág. 47, CU-02, RF-1, RF-2, P1)
export const LoginPage: React.FC = () => {
  const { login } = useSendero();
  const navigate = useNavigate();

  // Estado inicial alineado al mockup W-01 para ilustrar el control de perfil y permitir acceso rápido
  const [identificador, setIdentificador] = useState('2023630417');
  const [password, setPassword] = useState('Password123');
  const [errorMsg, setErrorMsg] = useState<string | null>(
    'Tu perfil es «Integrante». Esta consola solo admite Operador de atención y Administrador; usa la app móvil.'
  );
  const [showRecoveryModal, setShowRecoveryModal] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const res = login(identificador, password);
    if (!res.ok) {
      setErrorMsg(res.error ?? 'Error al iniciar sesión.');
      return;
    }
    setErrorMsg(null);
    if (res.user?.rol === 'ADMINISTRADOR') {
      navigate('/tablero');
    } else {
      navigate('/alertas');
    }
  };

  const quickFill = (id: string, pwd: string, autoSubmit = false) => {
    setIdentificador(id);
    setPassword(pwd);
    setErrorMsg(null);
    if (autoSubmit) {
      const res = login(id, pwd);
      if (!res.ok) {
        setErrorMsg(res.error ?? 'Error al iniciar sesión.');
      } else if (res.user?.rol === 'ADMINISTRADOR') {
        navigate('/tablero');
      } else {
        navigate('/alertas');
      }
    }
  };

  return (
    <div className="login-shell">
      {/* Panel izquierdo oscuro */}
      <section className="login-left">
        <div className="login-brand">
          <ShieldLogo size={38} />
          <span>Sendero Seguro</span>
        </div>

        <div className="login-hero">
          <h1>Centro de atención de alertas</h1>
          <p>
            Consola para operadores de atención y administradores del sistema. El
            acceso queda registrado en la bitácora.
          </p>
        </div>

        <div className="login-footer">IPN · Dirección de Seguridad Escolar</div>
      </section>

      {/* Panel derecho claro con formulario */}
      <section className="login-right">
        <div className="login-form-box">
          <h2>Iniciar sesión</h2>

          <form onSubmit={handleSubmit} noValidate>
            <div className="form-field">
              <label htmlFor="login-id" className="form-label">
                Usuario (n.º de empleado o teléfono)
              </label>
              <input
                id="login-id"
                type="text"
                className="form-input mono"
                value={identificador}
                onChange={(e) => setIdentificador(e.target.value)}
                placeholder="Ej. E-48211 o 5598765432"
                autoComplete="username"
                required
              />
            </div>

            <div className="form-field">
              <label htmlFor="login-pwd" className="form-label">
                Contraseña
              </label>
              <input
                id="login-pwd"
                type="password"
                className="form-input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••••••"
                autoComplete="current-password"
                required
              />
            </div>

            {errorMsg && (
              <div className="alert-banner-red" style={{ marginBottom: 16 }} role="alert">
                <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 1 }} />
                <span>{errorMsg}</span>
              </div>
            )}

            <button
              type="submit"
              className="btn btn-primary btn-lg"
              style={{ marginBottom: 16 }}
            >
              Entrar
            </button>

            <div style={{ marginBottom: 26, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
              <button
                type="button"
                onClick={() => setShowRecoveryModal(true)}
                style={{
                  background: 'none',
                  border: 'none',
                  padding: 0,
                  color: 'var(--primary-blue)',
                  fontWeight: 700,
                  fontSize: 13.5,
                  textDecoration: 'underline',
                  cursor: 'pointer',
                }}
              >
                ¿Olvidaste tu contraseña?
              </button>
              <PwaInstallButton />
            </div>
          </form>

          {/* Accesos rápidos de demostración (todos con contraseña Password123) */}
          <div
            style={{
              borderTop: '1px solid var(--border-light)',
              paddingTop: 16,
              fontSize: 12,
              color: 'var(--text-secondary)',
            }}
          >
            <div style={{ fontWeight: 700, marginBottom: 8, color: 'var(--text-primary)' }}>
              Cuentas de prueba (contraseña: <code className="mono">Password123</code>)
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => quickFill('E-48211', 'Password123', true)}
              >
                Entrar como Operador (R. Méndez)
              </button>
              <button
                type="button"
                className="btn btn-outline-blue btn-sm"
                onClick={() => quickFill('E-30117', 'Password123', true)}
              >
                Entrar como Administrador (S. Aguilar)
              </button>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => quickFill('2024630902', 'Password123', true)}
              >
                Probar Integrante (rechazo)
              </button>
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => quickFill('2022630155', 'Password123', true)}
              >
                Probar cuenta bloqueada
              </button>
            </div>
          </div>
        </div>
      </section>

      {showRecoveryModal && (
        <div className="modal-backdrop" onClick={() => setShowRecoveryModal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
              <KeyRound size={20} color="#402b47" />
              <h3 style={{ margin: 0, fontSize: 18 }}>Recuperación de acceso</h3>
            </div>
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              Por política de seguridad institucional (RNF9), el restablecimiento de contraseña o
              desbloqueo temporal de cuentas de <strong>Operador</strong> y{' '}
              <strong>Administrador</strong> se gestiona mediante la Dirección de Seguridad Escolar
              o desde el módulo <em>Usuarios y perfiles (W-09)</em> con registro en bitácora.
            </p>
            <div className="alert-banner-green" style={{ marginBottom: 18 }}>
              <CheckCircle2 size={16} />
              <span>Todas las solicitudes quedan firmadas en la bitácora SHA-256.</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() => setShowRecoveryModal(false)}
              >
                Entendido
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
