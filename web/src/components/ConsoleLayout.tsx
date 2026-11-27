import React from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AlertTriangle,
  BarChart3,
  BookOpen,
  Building2,
  FileClock,
  GitFork,
  ListChecks,
  LogOut,
  Shield,
  Sparkles,
  Users,
} from 'lucide-react';
import { ShieldLogo } from './ShieldLogo';
import { PwaInstallButton } from './PwaBanners';
import { useSendero } from '../context/SenderoContext';
import { UnidadEstado } from '../types/domain';

export const ConsoleLayout: React.FC = () => {
  const {
    currentUser,
    currentUnit,
    alertas,
    reportes,
    priorityBannerAlerta,
    dismissPriorityBanner,
    updateUnitStatus,
    logout,
  } = useSendero();
  const navigate = useNavigate();
  const location = useLocation();

  if (!currentUser) return null;

  const isAdmin = currentUser.rol === 'ADMINISTRADOR';
  const activeAlertsCount = alertas.filter(
    (a) => a.estado !== 'CERRADA' && a.estado !== 'CANCELADA'
  ).length;
  const pendingReportsCount = reportes.filter((r) => r.estado === 'PENDIENTE').length;

  // En W-03 el estado de la unidad en la barra superior refleja ASIGNADA/ATENDIENDO
  const inAlertDetail = location.pathname.startsWith('/alertas/');

  const unitPillClass = (estado: UnidadEstado) => {
    switch (estado) {
      case 'DISPONIBLE':
        return 'pill pill-green';
      case 'ASIGNADA':
        return 'pill pill-amber';
      case 'ATENDIENDO':
        return 'pill pill-green-solid';
      default:
        return 'pill pill-gray';
    }
  };

  const handleCycleUnitStatus = () => {
    if (!currentUnit) return;
    const next: UnidadEstado =
      currentUnit.estado === 'DISPONIBLE'
        ? 'ASIGNADA'
        : currentUnit.estado === 'ASIGNADA'
          ? 'ATENDIENDO'
          : currentUnit.estado === 'ATENDIENDO'
            ? 'FUERA_DE_SERVICIO'
            : 'DISPONIBLE';
    updateUnitStatus(currentUnit.id, next);
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="app-shell">
      {/* Barra superior oscura (#14171F) fiel a las vistas */}
      <header className="topbar">
        <div className="topbar-left">
          <ShieldLogo size={30} />
          <span>
            Sendero Seguro · {isAdmin ? 'Administración' : 'Operador'}
          </span>
        </div>

        <div className="topbar-right">
          {!isAdmin && currentUnit && (
            <>
              <button
                type="button"
                className={unitPillClass(inAlertDetail && currentUnit.estado === 'DISPONIBLE' ? 'ASIGNADA' : currentUnit.estado)}
                onClick={handleCycleUnitStatus}
                title="Clic para cambiar estado operativo de la unidad (E-08)"
                style={{ border: 'none', cursor: 'pointer', padding: '4px 11px' }}
              >
                {inAlertDetail && currentUnit.estado === 'DISPONIBLE'
                  ? 'ASIGNADA'
                  : currentUnit.estado}
              </button>
              <span style={{ color: '#e5e7eb', fontWeight: 500 }}>
                Unidad {currentUnit.nombre}
                {!inAlertDetail ? ' · Seguridad IPN' : ''} · {currentUser.nombre_corto}
              </span>
            </>
          )}

          {isAdmin && (
            <span style={{ color: '#e5e7eb', fontWeight: 500 }}>
              {currentUser.nombre_corto} · Administrador
            </span>
          )}

          <PwaInstallButton compact />

          <button
            type="button"
            onClick={handleLogout}
            title="Cerrar sesión"
            style={{
              background: 'rgba(255,255,255,0.08)',
              border: '1px solid rgba(255,255,255,0.14)',
              color: '#e5e7eb',
              borderRadius: 8,
              padding: '5px 9px',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              fontSize: 12,
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            <LogOut size={14} />
            Salir
          </button>
        </div>
      </header>

      {/* Regla de navegación Fig. G-03: "Una alerta nueva de prioridad 1 abre un aviso sobre cualquier pantalla del operador" */}
      {priorityBannerAlerta && (
        <div className="priority1-toast" role="alert">
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <AlertTriangle size={20} />
            <div>
              <strong>NUEVA ALERTA PRIORIDAD 1 · {priorityBannerAlerta.folio}</strong> —{' '}
              {priorityBannerAlerta.tipo_nombre} ({priorityBannerAlerta.zona_nombre},{' '}
              {priorityBannerAlerta.distancia_m} m · {priorityBannerAlerta.canal_recepcion})
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button
              type="button"
              className="btn btn-sm"
              style={{ background: '#c9e0eb', color: '#262423' }}
              onClick={() => {
                const id = priorityBannerAlerta.id;
                dismissPriorityBanner();
                navigate(`/alertas/${id}`);
              }}
            >
              Atender ahora
            </button>
            <button
              type="button"
              className="btn btn-sm"
              style={{ background: 'rgba(255,255,255,0.18)', color: '#ffffff' }}
              onClick={dismissPriorityBanner}
            >
              Cerrar aviso
            </button>
          </div>
        </div>
      )}

      <div className="app-body">
        {/* Menú lateral según perfil (Fig. G-03) */}
        <aside className="sidebar">
          {isAdmin && <div className="sidebar-section-label">OPERACIÓN</div>}

          <NavLink
            to="/alertas"
            className={({ isActive }) =>
              `nav-item${isActive || location.pathname.startsWith('/alertas') ? ' active' : ''}`
            }
          >
            <span className="nav-item-left">
              <Shield size={17} />
              <span className="nav-text">Alertas</span>
            </span>
            {!isAdmin && activeAlertsCount > 0 && (
              <span className="nav-badge-red">{activeAlertsCount}</span>
            )}
          </NavLink>

          <NavLink
            to="/tablero"
            className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
          >
            <span className="nav-item-left">
              <BarChart3 size={17} />
              <span className="nav-text">Tablero</span>
            </span>
          </NavLink>

          {isAdmin && (
            <>
              <NavLink
                to="/moderacion"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <ListChecks size={17} />
                  <span className="nav-text">Moderación</span>
                </span>
                {pendingReportsCount > 0 && (
                  <span className="nav-badge-dark">{pendingReportsCount}</span>
                )}
              </NavLink>

              <div className="sidebar-section-label">CONFIGURACIÓN</div>

              <NavLink
                to="/catalogo"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <BookOpen size={17} />
                  <span className="nav-text">Catálogo</span>
                </span>
              </NavLink>

              <NavLink
                to="/canalizacion"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <GitFork size={17} />
                  <span className="nav-text">Canalización</span>
                </span>
              </NavLink>

              <NavLink
                to="/unidades"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <Building2 size={17} />
                  <span className="nav-text">Unidades</span>
                </span>
              </NavLink>

              <NavLink
                to="/usuarios"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <Users size={17} />
                  <span className="nav-text">Usuarios</span>
                </span>
              </NavLink>

              <NavLink
                to="/modelos-ia"
                className={({ isActive }) =>
                  `nav-item${isActive ? ' active ia-active' : ''}`
                }
              >
                <span className="nav-item-left">
                  <Sparkles size={17} />
                  <span className="nav-text">Modelos IA</span>
                </span>
              </NavLink>

              <div className="sidebar-section-label">AUDITORÍA</div>

              <NavLink
                to="/bitacora"
                className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}
              >
                <span className="nav-item-left">
                  <FileClock size={17} />
                  <span className="nav-text">Bitácora</span>
                </span>
              </NavLink>
            </>
          )}
        </aside>

        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
