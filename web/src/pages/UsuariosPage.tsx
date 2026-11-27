import React, { useEffect, useMemo, useState } from 'react';
import { AlertCircle, CheckCircle2 } from 'lucide-react';
import { useSendero } from '../context/SenderoContext';
import { UsuarioEstado, UsuarioRol } from '../types/domain';

function estadoUsuarioPill(estado: UsuarioEstado) {
  switch (estado) {
    case 'ACTIVA':
      return <span className="pill pill-green">ACTIVA</span>;
    case 'BLOQUEADA_TEMPORAL':
      return <span className="pill pill-amber">BLOQUEADA_TEMPORAL</span>;
    case 'SUSPENDIDA':
      return <span className="pill pill-red">SUSPENDIDA</span>;
    default:
      return <span className="pill pill-gray">{estado}</span>;
  }
}

// W-09. Usuarios y perfiles (pág. 55, CU-07, RF-2, P16)
export const UsuariosPage: React.FC = () => {
  const { users, unidades, corporaciones, asignarPerfilUsuario, cambiarEstadoUsuario } =
    useSendero();

  const [busqueda, setBusqueda] = useState('');
  const [selectedUserId, setSelectedUserId] = useState<string>('usr-02'); // Ricardo Méndez Soto

  const usuariosVisibles = useMemo(() => {
    const q = busqueda.trim().toLowerCase();
    // Validación W-09: búsqueda efectiva a partir de 3 caracteres
    if (q.length < 3) return users.slice(0, 6);
    return users.filter(
      (u) =>
        u.nombre.toLowerCase().includes(q) ||
        u.identificador_institucional.toLowerCase().includes(q) ||
        u.telefono_movil.includes(q)
    );
  }, [users, busqueda]);

  const selectedUser =
    users.find((u) => u.id === selectedUserId) ?? usuariosVisibles[0] ?? users[0];

  const [perfil, setPerfil] = useState<UsuarioRol>('OPERADOR');
  const [unidadId, setUnidadId] = useState<string>('uni-p214');
  const [motivo, setMotivo] = useState('Alta como operador de guardia nocturna');
  const [feedback, setFeedback] = useState<{ tipo: 'ok' | 'err'; texto: string } | null>(null);

  useEffect(() => {
    if (selectedUser) {
      setPerfil(selectedUser.rol);
      setUnidadId(selectedUser.unidad_id ?? unidades[0]?.id ?? '');
      setMotivo(
        selectedUser.id === 'usr-02' ? 'Alta como operador de guardia nocturna' : ''
      );
      setFeedback(null);
    }
  }, [selectedUser?.id]);

  const getCorpName = (id: number) =>
    corporaciones.find((c) => c.id === id)?.nombre ?? '';

  const handleGuardarPerfil = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedUser) return;
    const res = asignarPerfilUsuario(
      selectedUser.id,
      perfil,
      perfil === 'OPERADOR' ? unidadId : undefined,
      motivo
    );
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Error al guardar perfil.' });
      return;
    }
    setFeedback({
      tipo: 'ok',
      texto: `Perfil de ${selectedUser.nombre} actualizado a ${perfil}. Sesiones activas revocadas y cambio registrado en bitácora.`,
    });
  };

  const handleEstado = (nuevoEstado: UsuarioEstado) => {
    if (!selectedUser) return;
    const motivoFinal =
      motivo.trim().length >= 10
        ? motivo
        : nuevoEstado === 'ACTIVA'
          ? 'Desbloqueo verificado por Dirección de Seguridad Escolar'
          : 'Suspensión preventiva solicitada por Administración';
    const res = cambiarEstadoUsuario(selectedUser.id, nuevoEstado, motivoFinal);
    if (!res.ok) {
      setFeedback({ tipo: 'err', texto: res.error ?? 'Error al cambiar estado.' });
      return;
    }
    setFeedback({
      tipo: 'ok',
      texto: `Estado de ${selectedUser.nombre} cambiado a ${nuevoEstado} y registrado en bitácora.`,
    });
  };

  return (
    <div>
      {/* Header + buscador */}
      <div className="page-header">
        <div className="page-header-left">
          <h1 className="page-title">Usuarios y perfiles</h1>
        </div>

        <div style={{ width: 320, maxWidth: '100%' }}>
          <input
            type="search"
            className="form-input"
            placeholder="Buscar por nombre, matrícula o teléfono"
            value={busqueda}
            onChange={(e) => setBusqueda(e.target.value)}
            aria-label="Buscar usuario por nombre, matrícula o teléfono"
          />
          {busqueda.trim().length > 0 && busqueda.trim().length < 3 && (
            <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 4 }}>
              Escribe al menos 3 caracteres para filtrar (W-09).
            </div>
          )}
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

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1.55fr) minmax(320px, 1fr)',
          gap: 20,
          alignItems: 'start',
        }}
      >
        {/* Tabla de usuarios */}
        <section className="card" style={{ overflow: 'hidden' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Matrícula / N.º emp.</th>
                <th>Escuela</th>
                <th>Perfil</th>
                <th>Estado</th>
              </tr>
            </thead>
            <tbody>
              {usuariosVisibles.map((u) => {
                const selected = selectedUser?.id === u.id;
                return (
                  <tr
                    key={u.id}
                    className={`clickable${selected ? ' row-selected' : ''}`}
                    onClick={() => setSelectedUserId(u.id)}
                  >
                    <td style={{ fontWeight: 700 }}>{u.nombre}</td>
                    <td className="mono" style={{ fontSize: 13 }}>
                      {u.identificador_institucional}
                    </td>
                    <td>{u.escuela}</td>
                    <td className="mono" style={{ fontSize: 12.5 }}>
                      {u.rol}
                    </td>
                    <td>{estadoUsuarioPill(u.estado)}</td>
                  </tr>
                );
              })}
              {usuariosVisibles.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ textAlign: 'center', padding: 28, color: 'var(--text-secondary)' }}>
                    No se encontraron usuarios con «{busqueda}».
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </section>

        {/* Panel Asignar perfil */}
        {selectedUser && (
          <section className="card-blue-panel">
            <h2 style={{ margin: '0 0 16px 0', fontSize: 17.5, fontWeight: 800 }}>
              Asignar perfil · {selectedUser.nombre}
            </h2>

            <form onSubmit={handleGuardarPerfil}>
              <div className="form-field">
                <label htmlFor="usr-perfil" className="form-label">
                  Perfil
                </label>
                <select
                  id="usr-perfil"
                  className="form-select mono"
                  value={perfil}
                  onChange={(e) => setPerfil(e.target.value as UsuarioRol)}
                >
                  <option value="INTEGRANTE">INTEGRANTE</option>
                  <option value="COORDINADOR">COORDINADOR</option>
                  <option value="OPERADOR">OPERADOR</option>
                  <option value="ADMINISTRADOR">ADMINISTRADOR</option>
                </select>
              </div>

              <div className="form-field">
                <label htmlFor="usr-unidad" className="form-label">
                  Unidad de atención (solo Operador)
                </label>
                <select
                  id="usr-unidad"
                  className="form-select"
                  disabled={perfil !== 'OPERADOR'}
                  value={unidadId}
                  onChange={(e) => setUnidadId(e.target.value)}
                >
                  {unidades.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.nombre} · {getCorpName(u.corporacion_id)}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-field">
                <label htmlFor="usr-motivo" className="form-label">
                  Motivo del cambio
                </label>
                <input
                  id="usr-motivo"
                  type="text"
                  className="form-input"
                  value={motivo}
                  onChange={(e) => setMotivo(e.target.value)}
                  placeholder="Mínimo 10 caracteres (ej. Alta como operador de guardia nocturna)"
                  required
                />
                <span style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>
                  {motivo.trim().length}/10 caracteres mínimos
                </span>
              </div>

              <p className="form-help" style={{ marginBottom: 16 }}>
                El cambio cierra sus sesiones activas y queda en la bitácora. En el siguiente
                inicio de sesión verá la interfaz del nuevo perfil.
              </p>

              <button
                type="submit"
                className="btn btn-primary btn-lg"
                style={{ marginBottom: 12 }}
              >
                Guardar perfil
              </button>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <button
                  type="button"
                  className="btn btn-outline"
                  disabled={selectedUser.estado === 'ACTIVA'}
                  onClick={() => handleEstado('ACTIVA')}
                >
                  Desbloquear
                </button>
                <button
                  type="button"
                  className="btn btn-outline-red"
                  disabled={selectedUser.estado === 'SUSPENDIDA'}
                  onClick={() => handleEstado('SUSPENDIDA')}
                >
                  Suspender
                </button>
              </div>
            </form>
          </section>
        )}
      </div>
    </div>
  );
};
