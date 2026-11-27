import React from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { SenderoProvider, useSendero } from './context/SenderoContext';
import { ConsoleLayout } from './components/ConsoleLayout';
import { LoginPage } from './pages/LoginPage';
import { AlertasDashboardPage } from './pages/AlertasDashboardPage';
import { AlertaDetallePage } from './pages/AlertaDetallePage';
import { TableroPage } from './pages/TableroPage';
import { ModeracionPage } from './pages/ModeracionPage';
import { CatalogoPage } from './pages/CatalogoPage';
import { CanalizacionPage } from './pages/CanalizacionPage';
import { UnidadesPage } from './pages/UnidadesPage';
import { UsuariosPage } from './pages/UsuariosPage';
import { ModelosIaPage } from './pages/ModelosIaPage';
import { BitacoraPage } from './pages/BitacoraPage';
import { PublicTrackingPage } from './pages/PublicTrackingPage';
import { PwaStatusBanners } from './components/PwaBanners';

const RequireAuth: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { currentUser } = useSendero();
  if (!currentUser) return <Navigate to="/login" replace />;
  return <>{children}</>;
};

const RequireAdmin: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { currentUser } = useSendero();
  if (!currentUser) return <Navigate to="/login" replace />;
  if (currentUser.rol !== 'ADMINISTRADOR') return <Navigate to="/alertas" replace />;
  return <>{children}</>;
};

const HomeRedirect: React.FC = () => {
  const { currentUser } = useSendero();
  if (!currentUser) return <Navigate to="/login" replace />;
  return <Navigate to={currentUser.rol === 'ADMINISTRADOR' ? '/tablero' : '/alertas'} replace />;
};

export const App: React.FC = () => {
  return (
    <SenderoProvider>
      <PwaStatusBanners />
      <BrowserRouter>
        <Routes>
          {/* W-01 Inicio de sesión web */}
          <Route path="/login" element={<LoginPage />} />

          {/* X-01 Enlace público temporal de seguimiento (sin autenticación) */}
          <Route path="/seguimiento/:token" element={<PublicTrackingPage />} />

          {/* Rutas autenticadas de la Consola Web (Operador y Administrador) */}
          <Route
            element={
              <RequireAuth>
                <ConsoleLayout />
              </RequireAuth>
            }
          >
            {/* W-02 y W-03: Operador y Administrador */}
            <Route path="/alertas" element={<AlertasDashboardPage />} />
            <Route path="/alertas/:id" element={<AlertaDetallePage />} />

            {/* W-04: Tablero y exportación (Operador y Administrador) */}
            <Route path="/tablero" element={<TableroPage />} />

            {/* W-05 a W-10 + Bitácora: exclusivos de Administrador (Fig. G-03) */}
            <Route
              path="/moderacion"
              element={
                <RequireAdmin>
                  <ModeracionPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/catalogo"
              element={
                <RequireAdmin>
                  <CatalogoPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/canalizacion"
              element={
                <RequireAdmin>
                  <CanalizacionPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/unidades"
              element={
                <RequireAdmin>
                  <UnidadesPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/usuarios"
              element={
                <RequireAdmin>
                  <UsuariosPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/modelos-ia"
              element={
                <RequireAdmin>
                  <ModelosIaPage />
                </RequireAdmin>
              }
            />
            <Route
              path="/bitacora"
              element={
                <RequireAdmin>
                  <BitacoraPage />
                </RequireAdmin>
              }
            />
          </Route>

          <Route path="*" element={<HomeRedirect />} />
        </Routes>
      </BrowserRouter>
    </SenderoProvider>
  );
};

export default App;
