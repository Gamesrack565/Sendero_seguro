import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
  Alerta,
  AlertaEstado,
  Corporacion,
  EntradaBitacora,
  ModeloIA,
  ReglaCanalizacion,
  ReporteComunitario,
  RetroalimentacionIA,
  TipoIncidenteVersion,
  UnidadAtencion,
  UnidadEstado,
  Usuario,
  UsuarioEstado,
  UsuarioRol,
  VersionCatalogo,
} from '../types/domain';
import {
  INITIAL_ALERTAS,
  INITIAL_CORPORACIONES,
  INITIAL_MODELOS_IA,
  INITIAL_REGLAS_V8,
  INITIAL_REPORTES,
  INITIAL_RETROALIMENTACION,
  INITIAL_TIPOS_CATALOGO_V8,
  INITIAL_UNIDADES,
  INITIAL_USERS,
  INITIAL_VERSIONES_CATALOGO,
  buildInitialBitacora,
} from '../data/mockData';
import { computeSha256HexSync } from '../utils/crypto';
import { consolaApi } from '../services/apiClient';

export interface LoginResult {
  ok: boolean;
  error?: string;
  user?: Usuario;
}

export interface ValidacionA08Resultado {
  valido: boolean;
  errores: { tipo_incidente_id: number; nombre: string; mensaje: string }[];
}

interface SenderoContextValue {
  currentUser: Usuario | null;
  currentUnit: UnidadAtencion | null;
  wsConnected: boolean;
  ia4FallbackActive: boolean;
  setIa4FallbackActive: (v: boolean) => void;
  priorityBannerAlerta: Alerta | null;
  dismissPriorityBanner: () => void;
  simulateIncomingP1Alert: () => void;

  users: Usuario[];
  corporaciones: Corporacion[];
  unidades: UnidadAtencion[];
  versionesCatalogo: VersionCatalogo[];
  tiposCatalogo: TipoIncidenteVersion[];
  reglasCanalizacion: ReglaCanalizacion[];
  alertas: Alerta[];
  reportes: ReporteComunitario[];
  modelosIA: ModeloIA[];
  retroalimentacionIA: RetroalimentacionIA[];
  bitacora: EntradaBitacora[];
  reentrenamientoInfo: { fecha: string; ejemplos: number };

  // Auth
  login: (identificador: string, password: string) => LoginResult;
  logout: () => void;

  // Bitácora
  registrarBitacora: (
    accion: string,
    entidad: string,
    entidadId: string,
    detalle: Record<string, unknown>,
    actorOverride?: { nombre: string; rol: string }
  ) => void;

  // Operador (W-02, W-03)
  updateUnitStatus: (unitId: string, estado: UnidadEstado) => void;
  acusarReciboAlerta: (alertaId: string) => { ok: boolean; error?: string };
  cambiarEstadoAlerta: (alertaId: string, nuevoEstado: AlertaEstado) => { ok: boolean; error?: string };
  cerrarAlerta: (alertaId: string, resultado: string, observaciones: string) => { ok: boolean; error?: string };

  // Moderación (W-05)
  moderarReporte: (
    reporteId: string,
    decision: 'PUBLICAR' | 'RECHAZAR',
    opciones: { motivo?: string; ocultarDatos?: boolean; unirConFolio?: string }
  ) => void;
  consultarIdentidadProtegida: (reporteId: string, justificacion: string) => { ok: boolean; error?: string };

  // Catálogo y Canalización (W-06, W-07)
  validarCatalogoA08: () => ValidacionA08Resultado;
  guardarTipoCatalogo: (tipo: TipoIncidenteVersion, esNuevo?: boolean) => { ok: boolean; error?: string };
  publicarBorradorCatalogo: () => { ok: boolean; error?: string };
  guardarReglaCanalizacion: (regla: Omit<ReglaCanalizacion, 'id'>) => { ok: boolean; error?: string };

  // Corporaciones y Unidades (W-08)
  registrarUnidad: (data: {
    corporacion_id: number;
    nombre: string;
    operadores_nombres: string[];
    tipos_incidente_ids: number[];
  }) => { ok: boolean; error?: string };
  registrarCorporacion: (data: {
    nombre: string;
    tipo: Corporacion['tipo'];
    canal_codigo: string;
  }) => { ok: boolean; error?: string };

  // Usuarios (W-09)
  asignarPerfilUsuario: (
    userId: string,
    rol: UsuarioRol,
    unidadId: string | undefined,
    motivo: string
  ) => { ok: boolean; error?: string };
  cambiarEstadoUsuario: (
    userId: string,
    nuevoEstado: UsuarioEstado,
    motivo: string
  ) => { ok: boolean; error?: string };

  // Modelos IA (W-10)
  actualizarUmbralIA1: (umbral: number) => void;
  actualizarMinReportesIA2: (min: number) => void;
  cambiarEstadoModeloIA4: (estado: 'ACTIVO' | 'EN_PRUEBA') => void;
  ejecutarReentrenamientoIA: () => void;
}

const SenderoContext = createContext<SenderoContextValue | null>(null);

function formatHoraNow(): string {
  const now = new Date();
  return now.toTimeString().slice(0, 8);
}

function formatFechaNow(): string {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d} ${formatHoraNow()}`;
}

const STORAGE_KEY = 'sendero_seguro_pwa_state_v1';

interface PersistedState {
  currentUser: Usuario | null;
  users: Usuario[];
  corporaciones: Corporacion[];
  unidades: UnidadAtencion[];
  versionesCatalogo: VersionCatalogo[];
  tiposCatalogo: TipoIncidenteVersion[];
  reglasCanalizacion: ReglaCanalizacion[];
  alertas: Alerta[];
  reportes: ReporteComunitario[];
  modelosIA: ModeloIA[];
  retroalimentacionIA: RetroalimentacionIA[];
  bitacora: EntradaBitacora[];
  reentrenamientoInfo: { fecha: string; ejemplos: number };
}

function loadPersistedState(): Partial<PersistedState> {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as Partial<PersistedState>;
  } catch {
    return {};
  }
}

export const SenderoProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const initial = useMemo(() => loadPersistedState(), []);

  const [currentUser, setCurrentUser] = useState<Usuario | null>(initial.currentUser ?? null);
  const [wsConnected, setWsConnected] = useState<boolean>(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );
  const [ia4FallbackActive, setIa4FallbackActive] = useState(false);
  const [priorityBannerAlerta, setPriorityBannerAlerta] = useState<Alerta | null>(null);

  const [users, setUsers] = useState<Usuario[]>(initial.users ?? INITIAL_USERS);
  const [corporaciones, setCorporaciones] = useState<Corporacion[]>(
    initial.corporaciones ?? INITIAL_CORPORACIONES
  );
  const [unidades, setUnidades] = useState<UnidadAtencion[]>(initial.unidades ?? INITIAL_UNIDADES);
  const [versionesCatalogo, setVersionesCatalogo] = useState<VersionCatalogo[]>(
    initial.versionesCatalogo ?? INITIAL_VERSIONES_CATALOGO
  );
  const [tiposCatalogo, setTiposCatalogo] = useState<TipoIncidenteVersion[]>(
    initial.tiposCatalogo ?? INITIAL_TIPOS_CATALOGO_V8
  );
  const [reglasCanalizacion, setReglasCanalizacion] = useState<ReglaCanalizacion[]>(
    initial.reglasCanalizacion ?? INITIAL_REGLAS_V8
  );
  const [alertas, setAlertas] = useState<Alerta[]>(initial.alertas ?? INITIAL_ALERTAS);
  const [reportes, setReportes] = useState<ReporteComunitario[]>(
    initial.reportes ?? INITIAL_REPORTES
  );
  const [modelosIA, setModelosIA] = useState<ModeloIA[]>(initial.modelosIA ?? INITIAL_MODELOS_IA);
  const [retroalimentacionIA, setRetroalimentacionIA] = useState<RetroalimentacionIA[]>(
    initial.retroalimentacionIA ?? INITIAL_RETROALIMENTACION
  );
  const [bitacora, setBitacora] = useState<EntradaBitacora[]>(
    () => initial.bitacora ?? buildInitialBitacora()
  );
  const [reentrenamientoInfo, setReentrenamientoInfo] = useState(
    initial.reentrenamientoInfo ?? {
      fecha: '1 nov 2026',
      ejemplos: 1284,
    }
  );

  // Persistencia local para operación offline de la PWA
  useEffect(() => {
    try {
      const payload: PersistedState = {
        currentUser,
        users,
        corporaciones,
        unidades,
        versionesCatalogo,
        tiposCatalogo,
        reglasCanalizacion,
        alertas,
        reportes,
        modelosIA,
        retroalimentacionIA,
        bitacora,
        reentrenamientoInfo,
      };
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
    } catch {
      // Ignorar si el almacenamiento local está lleno o restringido
    }
  }, [
    currentUser,
    users,
    corporaciones,
    unidades,
    versionesCatalogo,
    tiposCatalogo,
    reglasCanalizacion,
    alertas,
    reportes,
    modelosIA,
    retroalimentacionIA,
    bitacora,
    reentrenamientoInfo,
  ]);

  // Sincroniza estado de red del navegador con el indicador de conexión en vivo
  useEffect(() => {
    const onOnline = () => setWsConnected(true);
    const onOffline = () => setWsConnected(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  // Conexión WebSocket (o simulada) al iniciar sesión
  useEffect(() => {
    if (!currentUser) return;
    const disconnect = consolaApi.connectWebSocket(
      () => {},
      (status) => setWsConnected(status && navigator.onLine)
    );
    return disconnect;
  }, [currentUser]);

  // Temporizador en vivo: actualiza cronómetro de escalamiento en alertas sin acuse cada segundo
  useEffect(() => {
    const timer = setInterval(() => {
      setAlertas((prev) =>
        prev.map((a) => {
          if (a.estado !== 'ASIGNADA' && a.estado !== 'ESCALADA') return a;
          const idxPendiente = a.escalamiento_cadena.findIndex(
            (n) => n.estado === 'pendiente' && typeof n.segundos_restantes === 'number'
          );
          if (idxPendiente === -1) return a;

          const currentSec = a.escalamiento_cadena[idxPendiente].segundos_restantes ?? 0;
          if (currentSec > 1) {
            const nextChain = a.escalamiento_cadena.map((n, i) =>
              i === idxPendiente ? { ...n, segundos_restantes: currentSec - 1 } : n
            );
            return { ...a, escalamiento_cadena: nextChain };
          }

          // Vence el tiempo de acuse: escala automáticamente al siguiente nivel
          const nextChain = a.escalamiento_cadena.map((n, i) => {
            if (i === idxPendiente) return { ...n, estado: 'avisado' as const, segundos_restantes: undefined };
            if (i === idxPendiente + 1) return { ...n, segundos_restantes: 120 };
            return n;
          });
          const nuevoNivel = a.escalamiento_cadena[idxPendiente].nivel;
          const destino = a.escalamiento_cadena[idxPendiente].destino_nombre;
          const sinAcuse = idxPendiente === a.escalamiento_cadena.length - 1;
          const nuevoEvento = {
            id: a.historial.length + 1,
            hora: formatHoraNow(),
            estado_anterior: a.estado,
            estado_nuevo: 'ESCALADA' as AlertaEstado,
            detalle: `${a.estado} → ESCALADA · nivel ${nuevoNivel}; avisado ${destino}${sinAcuse ? ' (cadena agotada: sin_acuse)' : ''}`,
            tipo_punto: 'alerta' as const,
          };
          return {
            ...a,
            estado: 'ESCALADA',
            nivel_escalamiento: nuevoNivel,
            sin_acuse: sinAcuse,
            escalamiento_cadena: nextChain,
            historial: [...a.historial, nuevoEvento],
          };
        })
      );
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Simulación GNSS cada 10 segundos en alertas activas (P6)
  useEffect(() => {
    const gnssTimer = setInterval(() => {
      setAlertas((prev) =>
        prev.map((a) => {
          if (a.estado === 'CERRADA' || a.estado === 'CANCELADA') return a;
          const last = a.ubicaciones[a.ubicaciones.length - 1];
          if (!last) return a;
          const jitterLat = (Math.random() - 0.45) * 0.00018;
          const jitterLon = (Math.random() - 0.45) * 0.00018;
          const nextX = Math.min(82, Math.max(18, last.mapX + (Math.random() - 0.4) * 1.2));
          const nextY = Math.min(82, Math.max(18, last.mapY + (Math.random() - 0.55) * 1.2));
          const nuevaLectura = {
            id: last.id + 1,
            latitud: Number((last.latitud + jitterLat).toFixed(6)),
            longitud: Number((last.longitud + jitterLon).toFixed(6)),
            precision_m: Math.max(8, Math.min(22, last.precision_m + Math.round((Math.random() - 0.5) * 3))),
            fuente: last.fuente,
            capturada_en: formatHoraNow(),
            mapX: Number(nextX.toFixed(1)),
            mapY: Number(nextY.toFixed(1)),
          };
          return {
            ...a,
            ubicaciones: [...a.ubicaciones.slice(-7), nuevaLectura],
          };
        })
      );
    }, 10000);
    return () => clearInterval(gnssTimer);
  }, []);

  const currentUnit = useMemo(() => {
    if (!currentUser?.unidad_id) return null;
    return unidades.find((u) => u.id === currentUser.unidad_id) ?? null;
  }, [currentUser, unidades]);

  const registrarBitacora = useCallback(
    (
      accion: string,
      entidad: string,
      entidadId: string,
      detalle: Record<string, unknown>,
      actorOverride?: { nombre: string; rol: string }
    ) => {
      setBitacora((prev) => {
        const ultimo = prev[prev.length - 1];
        const id = ultimo ? ultimo.id + 1 : 1;
        const hashPrev = ultimo ? ultimo.hash : '0'.repeat(64);
        const ocurrida_en = formatFechaNow();
        const actorNombre = actorOverride?.nombre ?? currentUser?.nombre ?? 'Sistema';
        const actorRol = actorOverride?.rol ?? currentUser?.rol ?? 'SISTEMA';
        const payload = `${id}|${ocurrida_en}|${accion}|${entidad}|${entidadId}|${JSON.stringify(detalle)}|${hashPrev}`;
        const hash = computeSha256HexSync(payload);
        return [
          ...prev,
          {
            id,
            ocurrida_en,
            usuario_nombre: actorNombre,
            usuario_rol: actorRol,
            accion,
            entidad,
            entidad_id: entidadId,
            detalle,
            ip: '148.204.58.29',
            hash_prev: hashPrev,
            hash,
          },
        ];
      });
    },
    [currentUser]
  );

  const login = useCallback(
    (identificador: string, password: string): LoginResult => {
      const cleanId = identificador.trim();
      if (!cleanId || !password.trim()) {
        return { ok: false, error: 'Ingresa tu número de empleado o teléfono y tu contraseña.' };
      }

      const found = users.find(
        (u) =>
          u.identificador_institucional.toLowerCase() === cleanId.toLowerCase() ||
          u.telefono_movil.replace(/\s+/g, '') === cleanId.replace(/\s+/g, '')
      );

      if (!found) {
        registrarBitacora(
          'LOGIN_FALLIDO',
          'usuario',
          cleanId,
          { motivo: 'Usuario no registrado en el sistema' },
          { nombre: cleanId, rol: 'DESCONOCIDO' }
        );
        return { ok: false, error: 'Usuario no registrado en el sistema.' };
      }

      if (found.estado === 'BLOQUEADA_TEMPORAL') {
        return {
          ok: false,
          error:
            'Cuenta en estado BLOQUEADA_TEMPORAL tras 5 intentos fallidos. Solicita desbloqueo a un Administrador.',
        };
      }

      if (found.estado === 'SUSPENDIDA' || found.estado === 'ELIMINADA') {
        return {
          ok: false,
          error: `Tu cuenta se encuentra ${found.estado}. Contacta a Dirección de Seguridad Escolar.`,
        };
      }

      // Verificación de rol para consola web (W-01, pág. 47)
      // Si la contraseña no coincide, contar intentos fallidos (P1: bloqueo al 5.º intento)
      if (password !== found.password) {
        const nuevosIntentos = found.intentos_fallidos + 1;
        const bloqueada = nuevosIntentos >= 5;
        setUsers((prev) =>
          prev.map((u) =>
            u.id === found.id
              ? {
                  ...u,
                  intentos_fallidos: nuevosIntentos,
                  estado: bloqueada ? 'BLOQUEADA_TEMPORAL' : u.estado,
                }
              : u
          )
        );
        registrarBitacora(
          bloqueada ? 'CUENTA_BLOQUEADA_TEMPORAL' : 'LOGIN_FALLIDO',
          'usuario',
          found.identificador_institucional,
          { intentos_fallidos: nuevosIntentos },
          { nombre: found.nombre, rol: found.rol }
        );
        if (bloqueada) {
          return {
            ok: false,
            error:
              'Has superado los 5 intentos fallidos. Tu cuenta pasó a BLOQUEADA_TEMPORAL por seguridad.',
          };
        }
        return {
          ok: false,
          error: `Contraseña incorrecta. Te quedan ${5 - nuevosIntentos} intentos antes del bloqueo temporal.`,
        };
      }

      // Rechazo por perfil (W-01: Integrante y Coordinador solo usan app móvil)
      if (found.rol === 'INTEGRANTE' || found.rol === 'COORDINADOR') {
        const nombrePerfil = found.rol === 'INTEGRANTE' ? 'Integrante' : 'Coordinador';
        registrarBitacora(
          'ACCESO_RECHAZADO_PERFIL',
          'usuario',
          found.identificador_institucional,
          { perfil: found.rol, motivo: 'Perfil no permitido en consola web' },
          { nombre: found.nombre, rol: found.rol }
        );
        return {
          ok: false,
          error: `Tu perfil es «${nombrePerfil}». Esta consola solo admite Operador de atención y Administrador; usa la app móvil.`,
        };
      }

      // Acceso exitoso
      setUsers((prev) =>
        prev.map((u) => (u.id === found.id ? { ...u, intentos_fallidos: 0 } : u))
      );
      setCurrentUser({ ...found, intentos_fallidos: 0 });
      consolaApi.setToken(`jwt-demo-${found.id}`);
      registrarBitacora(
        'INICIO_SESION',
        'sesion',
        `ses-${Date.now().toString().slice(-4)}`,
        { rol: found.rol, unidad_id: found.unidad_id ?? null },
        { nombre: found.nombre, rol: found.rol }
      );
      return { ok: true, user: found };
    },
    [users, registrarBitacora]
  );

  const logout = useCallback(() => {
    if (currentUser) {
      registrarBitacora('CIERRE_SESION', 'sesion', currentUser.identificador_institucional, {
        rol: currentUser.rol,
      });
    }
    consolaApi.setToken(null);
    setCurrentUser(null);
    setPriorityBannerAlerta(null);
  }, [currentUser, registrarBitacora]);

  const updateUnitStatus = useCallback(
    (unitId: string, estado: UnidadEstado) => {
      setUnidades((prev) =>
        prev.map((u) => (u.id === unitId ? { ...u, estado, ultima_ubicacion_texto: 'ahora' } : u))
      );
      registrarBitacora('CAMBIO_ESTADO_UNIDAD', 'unidad_atencion', unitId, { estado });
    },
    [registrarBitacora]
  );

  const simulateIncomingP1Alert = useCallback(() => {
    const num = 4818 + alertas.length;
    const nueva: Alerta = {
      id: `alr-00${num}`,
      folio: `ALR-2026-00${num}`,
      token_seguimiento: `p1X${num.toString().slice(-3)}`,
      usuario_id: 'usr-04',
      emisor_nombre: 'Valeria Cruz Peña',
      emisor_nombre_corto: 'Valeria Cruz',
      emisor_matricula: '2022630604',
      emisor_escuela: 'ESFM, IPN',
      emisor_telefono: '55 8112 3309',
      tipo_incidente_id: 2,
      tipo_nombre: 'Asalto o robo',
      categoria: 'FISICO',
      prioridad_catalogo: 1,
      clasificacion_origen: 'sugerido por IA (92 %), confirmado por la usuaria',
      descripcion: 'Dos personas intentan arrebatar mochila frente a la puerta 2 de Zacatenco sobre Av. IPN.',
      zona_nombre: 'Zacatenco',
      zona_celda: 'ZAC-0411',
      distancia_m: 410,
      unidad_asignada_id: 'uni-p214',
      unidad_asignada_nombre: 'P-214',
      corporacion_nombre: 'Seguridad IPN',
      prioridad_ia: 96,
      prioridad_motivos: ['Prioridad catálogo 1', 'Zona de riesgo ALTO 21–24 h', 'Recién recibida por MALLA'],
      estado: 'ASIGNADA',
      canal_recepcion: 'MALLA',
      saltos_malla: 2,
      nivel_escalamiento: 0,
      sin_acuse: false,
      segundos_transcurridos_inicial: 4,
      activada_en: new Date().toISOString(),
      registrada_en: new Date().toISOString(),
      ubicaciones: [
        { id: 1, latitud: 19.5032, longitud: -99.1458, precision_m: 11, fuente: 'GNSS', capturada_en: formatHoraNow(), mapX: 52, mapY: 42 },
      ],
      historial: [
        { id: 1, hora: formatHoraNow(), estado_anterior: 'CREADA', estado_nuevo: 'EN_CUENTA_REGRESIVA', detalle: 'CREADA → EN_CUENTA_REGRESIVA', tipo_punto: 'info' },
        { id: 2, hora: formatHoraNow(), estado_anterior: 'PENDIENTE_ENVIO', estado_nuevo: 'ENVIADA', detalle: 'PENDIENTE_ENVIO → ENVIADA · recibida por MALLA (2 saltos)', tipo_punto: 'info' },
        { id: 3, hora: formatHoraNow(), estado_anterior: 'ENVIADA', estado_nuevo: 'ASIGNADA', detalle: 'ENVIADA → ASIGNADA · unidad P-214, 410 m', tipo_punto: 'alerta' },
      ],
      escalamiento_cadena: [
        { nivel: 1, destino_nombre: 'Seguridad IPN', estado: 'pendiente', segundos_restantes: 90 },
        { nivel: 2, destino_nombre: 'Guardia del sistema', estado: 'pendiente' },
      ],
      red_apoyo: [
        { id: `ra-${num}-1`, nombre: 'Contacto prioritario 1', canal: 'PUSH', estado: 'ENTREGADA', etiqueta_estado: 'ENTREGADA' },
      ],
    };

    setAlertas((prev) => [nueva, ...prev]);
    setPriorityBannerAlerta(nueva);
    registrarBitacora('ALERTA_PRIORIDAD_1_RECIBIDA', 'alerta', nueva.folio, {
      tipo: nueva.tipo_nombre,
      canal: nueva.canal_recepcion,
      prioridad_ia: nueva.prioridad_ia,
    });
  }, [alertas.length, registrarBitacora]);

  const acusarReciboAlerta = useCallback(
    (alertaId: string) => {
      const target = alertas.find((a) => a.id === alertaId);
      if (!target) return { ok: false, error: 'Alerta no encontrada.' };
      // Validación W-03: "Solo unidad asignada"
      if (currentUser?.rol === 'OPERADOR' && currentUser.unidad_id !== target.unidad_asignada_id) {
        return {
          ok: false,
          error: `Solo la unidad asignada (${target.unidad_asignada_nombre}) puede acusar recibo de esta alerta.`,
        };
      }
      if (currentUser?.rol !== 'OPERADOR') {
        return {
          ok: false,
          error: 'Solo un Operador de la unidad asignada puede ejecutar el acuse de recibo.',
        };
      }

      const hora = formatHoraNow();
      setAlertas((prev) =>
        prev.map((a) => {
          if (a.id !== alertaId) return a;
          return {
            ...a,
            estado: 'ACUSADA',
            acusada_en: new Date().toISOString(),
            acusada_por: currentUser.nombre_corto,
            escalamiento_cadena: a.escalamiento_cadena.map((n) =>
              n.estado === 'pendiente' ? { ...n, estado: 'detenido', segundos_restantes: undefined } : n
            ),
            historial: [
              ...a.historial,
              {
                id: a.historial.length + 1,
                hora,
                estado_anterior: a.estado,
                estado_nuevo: 'ACUSADA',
                detalle: `${a.estado} → ACUSADA · acuse por ${currentUser.nombre_corto} (${a.unidad_asignada_nombre}); aviso enviado a la emisora`,
                tipo_punto: 'exito',
              },
            ],
          };
        })
      );

      if (currentUser.unidad_id) {
        setUnidades((prev) =>
          prev.map((u) => (u.id === currentUser.unidad_id ? { ...u, estado: 'ATENDIENDO' } : u))
        );
      }

      // Si el operador atendió primero una alerta que no era la de mayor puntaje IA-4, registrar retroalimentación
      const maxIa = Math.max(...alertas.filter((a) => a.estado !== 'CERRADA').map((a) => a.prioridad_ia ?? 0));
      if ((target.prioridad_ia ?? 0) < maxIa) {
        setRetroalimentacionIA((prev) => [
          {
            id: prev.length + 1,
            fecha: `Hoy ${hora.slice(0, 5)}`,
            modelo: 'IA-4',
            sugerencia: `Prioridad ${target.prioridad_ia ?? 'N/A'}`,
            decision_humana: 'Operador la atendió primero',
            confianza: null,
          },
          ...prev,
        ]);
      }

      registrarBitacora('ACUSE_ALERTA', 'alerta', target.folio, {
        unidad: target.unidad_asignada_nombre,
        estado_anterior: target.estado,
      });
      return { ok: true };
    },
    [alertas, currentUser, registrarBitacora]
  );

  const cambiarEstadoAlerta = useCallback(
    (alertaId: string, nuevoEstado: AlertaEstado) => {
      const target = alertas.find((a) => a.id === alertaId);
      if (!target) return { ok: false, error: 'Alerta no encontrada.' };
      if (!target.acusada_en) {
        return { ok: false, error: 'Debes acusar recibo antes de cambiar el estado.' };
      }

      const transicionesValidas: Record<string, AlertaEstado[]> = {
        ACUSADA: ['EN_ATENCION', 'ATENDIDA'],
        EN_ATENCION: ['ATENDIDA'],
        ATENDIDA: ['CERRADA'],
      };
      const permitidos = transicionesValidas[target.estado] ?? [];
      if (!permitidos.includes(nuevoEstado) && nuevoEstado !== target.estado) {
        return {
          ok: false,
          error: `Transición no válida según E-01: ${target.estado} → ${nuevoEstado}.`,
        };
      }

      const hora = formatHoraNow();
      setAlertas((prev) =>
        prev.map((a) =>
          a.id === alertaId
            ? {
                ...a,
                estado: nuevoEstado,
                historial: [
                  ...a.historial,
                  {
                    id: a.historial.length + 1,
                    hora,
                    estado_anterior: a.estado,
                    estado_nuevo: nuevoEstado,
                    detalle: `${a.estado} → ${nuevoEstado} · actualizado por ${currentUser?.nombre_corto ?? 'Operador'}`,
                    tipo_punto: 'exito',
                  },
                ],
              }
            : a
        )
      );
      registrarBitacora('CAMBIO_ESTADO_ALERTA', 'alerta', target.folio, {
        estado_anterior: target.estado,
        estado_nuevo: nuevoEstado,
      });
      return { ok: true };
    },
    [alertas, currentUser, registrarBitacora]
  );

  const cerrarAlerta = useCallback(
    (alertaId: string, resultado: string, observaciones: string) => {
      const target = alertas.find((a) => a.id === alertaId);
      if (!target) return { ok: false, error: 'Alerta no encontrada.' };
      if (!target.acusada_en) {
        return { ok: false, error: 'Debes acusar recibo antes de cerrar la alerta.' };
      }
      if (!resultado.trim()) {
        return { ok: false, error: 'Selecciona un resultado del catálogo antes de cerrar la alerta.' };
      }
      if (observaciones.length > 1000) {
        return { ok: false, error: 'Las observaciones no pueden exceder 1000 caracteres.' };
      }

      const hora = formatHoraNow();
      setAlertas((prev) =>
        prev.map((a) =>
          a.id === alertaId
            ? {
                ...a,
                estado: 'CERRADA',
                cerrada_en: new Date().toISOString(),
                resultado_cierre: resultado,
                observaciones_cierre: observaciones.trim(),
                historial: [
                  ...a.historial,
                  {
                    id: a.historial.length + 1,
                    hora,
                    estado_anterior: a.estado,
                    estado_nuevo: 'CERRADA',
                    detalle: `${a.estado} → CERRADA · resultado: ${resultado}; enlace de ubicación revocado`,
                    tipo_punto: 'exito',
                  },
                ],
              }
            : a
        )
      );

      if (currentUser?.unidad_id) {
        setUnidades((prev) =>
          prev.map((u) => (u.id === currentUser.unidad_id ? { ...u, estado: 'DISPONIBLE' } : u))
        );
      }

      registrarBitacora('CERRAR_ALERTA', 'alerta', target.folio, {
        resultado_cierre: resultado,
        observaciones_len: observaciones.trim().length,
      });
      return { ok: true };
    },
    [alertas, currentUser, registrarBitacora]
  );

  const moderarReporte = useCallback(
    (
      reporteId: string,
      decision: 'PUBLICAR' | 'RECHAZAR',
      opciones: { motivo?: string; ocultarDatos?: boolean; unirConFolio?: string }
    ) => {
      const target = reportes.find((r) => r.id === reporteId);
      if (!target) return;

      setReportes((prev) =>
        prev.map((r) =>
          r.id === reporteId
            ? {
                ...r,
                estado: decision === 'PUBLICAR' ? 'PUBLICADO' : 'RECHAZADO',
                motivo_decision: opciones.motivo,
              }
            : r
        )
      );

      const decisionTexto =
        decision === 'PUBLICAR'
          ? opciones.unirConFolio
            ? `Publicar unido a ${opciones.unirConFolio}${opciones.ocultarDatos ? ' (ocultando datos)' : ''}`
            : opciones.ocultarDatos
              ? 'Publicar ocultando datos personales'
              : 'Publicar reporte'
          : `Rechazar: ${opciones.motivo ?? 'Sin motivo'}`;

      setRetroalimentacionIA((prev) => [
        {
          id: prev.length + 1,
          fecha: `Hoy ${formatHoraNow().slice(0, 5)}`,
          modelo: 'IA-3',
          sugerencia: target.analisis_ia.sugerencia.slice(0, 48),
          decision_humana: decisionTexto,
          confianza: Math.max(
            target.analisis_ia.prob_duplicado,
            target.analisis_ia.prob_datos_personales,
            target.analisis_ia.prob_ofensivo
          ),
        },
        ...prev,
      ]);

      setReentrenamientoInfo((prev) => ({ ...prev, ejemplos: prev.ejemplos + 1 }));

      registrarBitacora(`MODERACION_${decision}`, 'reporte', target.folio, {
        decision,
        motivo: opciones.motivo ?? null,
        oculta_datos_personales: Boolean(opciones.ocultarDatos),
        unido_a: opciones.unirConFolio ?? null,
      });
    },
    [reportes, registrarBitacora]
  );

  const consultarIdentidadProtegida = useCallback(
    (reporteId: string, justificacion: string) => {
      const clean = justificacion.trim();
      if (clean.length < 20) {
        return {
          ok: false,
          error: 'La justificación es obligatoria y debe tener al menos 20 caracteres (CU-31).',
        };
      }
      const target = reportes.find((r) => r.id === reporteId);
      if (!target) return { ok: false, error: 'Reporte no encontrado.' };

      setReportes((prev) =>
        prev.map((r) => (r.id === reporteId ? { ...r, identidad_revelada: true } : r))
      );

      registrarBitacora('CONSULTAR_IDENTIDAD', 'reporte', target.folio, {
        caso_uso: 'CU-31',
        justificacion: clean,
        autor_matricula: target.autor_protegido.matricula,
      });
      return { ok: true };
    },
    [reportes, registrarBitacora]
  );

  const validarCatalogoA08 = useCallback((): ValidacionA08Resultado => {
    const borrador = versionesCatalogo.find((v) => v.estado === 'BORRADOR');
    if (!borrador) return { valido: true, errores: [] };

    const tiposVersion = tiposCatalogo.filter((t) => t.version_catalogo_id === borrador.id);
    const reglasVersion = reglasCanalizacion.filter((r) => r.version_catalogo_id === borrador.id);
    const errores: { tipo_incidente_id: number; nombre: string; mensaje: string }[] = [];

    for (const t of tiposVersion) {
      if (!t.activo) continue;
      if (t.aplica_a === 'ALERTA' || t.aplica_a === 'AMBOS') {
        const tieneRegla = reglasVersion.some((r) => r.tipo_incidente_id === t.tipo_incidente_id);
        if (!tieneRegla) {
          errores.push({
            tipo_incidente_id: t.tipo_incidente_id,
            nombre: t.nombre,
            mensaje: `«${t.nombre}» no tiene regla de canalización. Asígnala en Canalización antes de publicar.`,
          });
        }
      }
    }

    return { valido: errores.length === 0, errores };
  }, [versionesCatalogo, tiposCatalogo, reglasCanalizacion]);

  const guardarTipoCatalogo = useCallback(
    (tipo: TipoIncidenteVersion, esNuevo = false) => {
      const nombre = tipo.nombre.trim();
      if (nombre.length < 3 || nombre.length > 60) {
        return { ok: false, error: 'El nombre debe tener entre 3 y 60 caracteres.' };
      }
      const duplicado = tiposCatalogo.some(
        (t) =>
          t.version_catalogo_id === tipo.version_catalogo_id &&
          t.tipo_incidente_id !== tipo.tipo_incidente_id &&
          t.nombre.toLowerCase() === nombre.toLowerCase()
      );
      if (duplicado) {
        return { ok: false, error: 'Ya existe un tipo con ese nombre en esta versión del catálogo.' };
      }

      if (esNuevo) {
        setTiposCatalogo((prev) => [...prev, { ...tipo, nombre }]);
      } else {
        setTiposCatalogo((prev) =>
          prev.map((t) =>
            t.version_catalogo_id === tipo.version_catalogo_id &&
            t.tipo_incidente_id === tipo.tipo_incidente_id
              ? { ...tipo, nombre }
              : t
          )
        );
      }

      registrarBitacora(
        esNuevo ? 'CREAR_TIPO_CATALOGO' : 'EDITAR_TIPO_CATALOGO',
        'tipo_incidente_version',
        `${tipo.version_catalogo_id}:${tipo.tipo_incidente_id}`,
        { nombre, categoria: tipo.categoria, prioridad: tipo.prioridad, aplica_a: tipo.aplica_a }
      );
      return { ok: true };
    },
    [tiposCatalogo, registrarBitacora]
  );

  const publicarBorradorCatalogo = useCallback(() => {
    const validacion = validarCatalogoA08();
    if (!validacion.valido) {
      return {
        ok: false,
        error: `No se puede publicar: ${validacion.errores[0].mensaje}`,
      };
    }

    const borrador = versionesCatalogo.find((v) => v.estado === 'BORRADOR');
    if (!borrador) return { ok: false, error: 'No hay borrador activo para publicar.' };

    const hoyTexto = new Date().toLocaleDateString('es-MX', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });

    const siguienteNum = borrador.numero + 1;
    setVersionesCatalogo((prev) => [
      ...prev.map((v) => {
        if (v.estado === 'PUBLICADA') return { ...v, estado: 'REEMPLAZADA' as const };
        if (v.id === borrador.id) return { ...v, estado: 'PUBLICADA' as const, publicada_en: hoyTexto };
        return v;
      }),
      { id: siguienteNum, numero: siguienteNum, estado: 'BORRADOR' as const, creada_en: hoyTexto },
    ]);

    // Clonar tipos y reglas al nuevo borrador para seguir editando sin romper integridad
    setTiposCatalogo((prev) => [
      ...prev,
      ...prev
        .filter((t) => t.version_catalogo_id === borrador.id)
        .map((t) => ({ ...t, version_catalogo_id: siguienteNum })),
    ]);
    setReglasCanalizacion((prev) => [
      ...prev,
      ...prev
        .filter((r) => r.version_catalogo_id === borrador.id)
        .map((r, i) => ({ ...r, id: r.id + 1000 + i, version_catalogo_id: siguienteNum })),
    ]);

    registrarBitacora('PUBLICAR_CATALOGO', 'version_catalogo', `v${borrador.numero}`, {
      version_publicada: borrador.numero,
      nuevo_borrador: siguienteNum,
    });
    return { ok: true };
  }, [validarCatalogoA08, versionesCatalogo, registrarBitacora]);

  const guardarReglaCanalizacion = useCallback(
    (regla: Omit<ReglaCanalizacion, 'id'>) => {
      if (regla.tiempo_max_acuse_seg < 30 || regla.tiempo_max_acuse_seg > 600) {
        return { ok: false, error: 'El tiempo máximo de acuse debe estar entre 30 y 600 segundos.' };
      }
      if (regla.pasos.length === 0) {
        return { ok: false, error: 'Agrega al menos un nivel en la cadena de escalamiento.' };
      }
      // Validación W-07: "Obligatorio. Sin destinos repetidos"
      const destinosVistos = new Set<string>();
      for (const paso of regla.pasos) {
        const key = `${paso.destino_tipo}::${paso.etiqueta_destino}`;
        if (destinosVistos.has(key)) {
          return {
            ok: false,
            error: `Destino repetido en la cadena de escalamiento: «${paso.etiqueta_destino}».`,
          };
        }
        destinosVistos.add(key);
      }

      setReglasCanalizacion((prev) => {
        const exists = prev.find(
          (r) =>
            r.version_catalogo_id === regla.version_catalogo_id &&
            r.tipo_incidente_id === regla.tipo_incidente_id
        );
        if (exists) {
          return prev.map((r) => (r.id === exists.id ? { ...regla, id: exists.id } : r));
        }
        return [...prev, { ...regla, id: Date.now() }];
      });

      registrarBitacora('GUARDAR_REGLA_CANALIZACION', 'regla_canalizacion', String(regla.tipo_incidente_id), {
        corporacion_destino_id: regla.corporacion_destino_id,
        tiempo_max_acuse_seg: regla.tiempo_max_acuse_seg,
        niveles: regla.pasos.length,
      });
      return { ok: true };
    },
    [registrarBitacora]
  );

  const registrarUnidad = useCallback(
    (data: {
      corporacion_id: number;
      nombre: string;
      operadores_nombres: string[];
      tipos_incidente_ids: number[];
    }) => {
      const clave = data.nombre.trim();
      if (!clave) return { ok: false, error: 'El nombre o clave de la unidad es obligatorio.' };
      const duplicada = unidades.some(
        (u) => u.corporacion_id === data.corporacion_id && u.nombre.toLowerCase() === clave.toLowerCase()
      );
      if (duplicada) {
        return { ok: false, error: `Ya existe la unidad «${clave}» en esa corporación.` };
      }
      if (data.tipos_incidente_ids.length === 0) {
        return { ok: false, error: 'Selecciona al menos un tipo de incidente que atiende.' };
      }

      const categorias = new Set<string>();
      data.tipos_incidente_ids.forEach((id) => {
        const tipo = tiposCatalogo.find((t) => t.tipo_incidente_id === id);
        if (tipo?.categoria === 'FISICO') categorias.add('Físico');
        if (tipo?.categoria === 'SOCIAL') categorias.add('Social');
        if (tipo?.categoria === 'DIGITAL') categorias.add('Digital');
      });

      const nueva: UnidadAtencion = {
        id: `uni-${Date.now()}`,
        corporacion_id: data.corporacion_id,
        nombre: clave,
        estado: 'DISPONIBLE',
        ultima_latitud: 19.5042,
        ultima_longitud: -99.1461,
        ultima_ubicacion_texto: 'hace 2 s',
        atiende_resumen: Array.from(categorias).join(', ') || 'Físico',
        tipos_incidente_ids: data.tipos_incidente_ids,
        operadores_nombres: data.operadores_nombres.length > 0 ? data.operadores_nombres : ['Sin asignar'],
        operadores_ids: [],
        activa: true,
      };

      setUnidades((prev) => [nueva, ...prev]);
      registrarBitacora('REGISTRAR_UNIDAD', 'unidad_atencion', nueva.nombre, {
        corporacion_id: data.corporacion_id,
        operadores: nueva.operadores_nombres,
      });
      return { ok: true };
    },
    [unidades, tiposCatalogo, registrarBitacora]
  );

  const registrarCorporacion = useCallback(
    (data: { nombre: string; tipo: Corporacion['tipo']; canal_codigo: string }) => {
      const nombre = data.nombre.trim();
      const canal = data.canal_codigo.trim().toUpperCase();
      if (!nombre || !canal) {
        return { ok: false, error: 'Nombre y código de canal son obligatorios.' };
      }
      if (corporaciones.some((c) => c.nombre.toLowerCase() === nombre.toLowerCase() || c.canal_codigo === canal)) {
        return { ok: false, error: 'Ya existe una corporación con ese nombre o código de canal.' };
      }
      const nueva: Corporacion = {
        id: Math.max(...corporaciones.map((c) => c.id)) + 1,
        nombre,
        tipo: data.tipo,
        canal_codigo: canal,
        activa: true,
      };
      setCorporaciones((prev) => [...prev, nueva]);
      registrarBitacora('REGISTRAR_CORPORACION', 'corporacion', nueva.canal_codigo, {
        nombre: nueva.nombre,
        tipo: nueva.tipo,
      });
      return { ok: true };
    },
    [corporaciones, registrarBitacora]
  );

  const asignarPerfilUsuario = useCallback(
    (userId: string, rol: UsuarioRol, unidadId: string | undefined, motivo: string) => {
      const cleanMotivo = motivo.trim();
      if (cleanMotivo.length < 10) {
        return { ok: false, error: 'El motivo del cambio es obligatorio y debe tener al menos 10 caracteres.' };
      }
      if (rol === 'OPERADOR' && !unidadId) {
        return { ok: false, error: 'Debes seleccionar una unidad de atención para el perfil OPERADOR.' };
      }
      const target = users.find((u) => u.id === userId);
      if (!target) return { ok: false, error: 'Usuario no encontrado.' };

      setUsers((prev) =>
        prev.map((u) =>
          u.id === userId ? { ...u, rol, unidad_id: rol === 'OPERADOR' ? unidadId : undefined } : u
        )
      );
      registrarBitacora('ASIGNAR_PERFIL', 'usuario', target.identificador_institucional, {
        rol_anterior: target.rol,
        rol_nuevo: rol,
        unidad_id: rol === 'OPERADOR' ? unidadId : null,
        motivo: cleanMotivo,
        sesiones_revocadas: true,
      });
      return { ok: true };
    },
    [users, registrarBitacora]
  );

  const cambiarEstadoUsuario = useCallback(
    (userId: string, nuevoEstado: UsuarioEstado, motivo: string) => {
      const cleanMotivo = motivo.trim();
      if (cleanMotivo.length < 10) {
        return { ok: false, error: 'Escribe un motivo de al menos 10 caracteres para registrar en bitácora.' };
      }
      const target = users.find((u) => u.id === userId);
      if (!target) return { ok: false, error: 'Usuario no encontrado.' };

      setUsers((prev) =>
        prev.map((u) =>
          u.id === userId
            ? {
                ...u,
                estado: nuevoEstado,
                intentos_fallidos: nuevoEstado === 'ACTIVA' ? 0 : u.intentos_fallidos,
              }
            : u
        )
      );
      registrarBitacora(
        nuevoEstado === 'ACTIVA' ? 'DESBLOQUEAR_USUARIO' : 'SUSPENDER_USUARIO',
        'usuario',
        target.identificador_institucional,
        { estado_anterior: target.estado, estado_nuevo: nuevoEstado, motivo: cleanMotivo }
      );
      return { ok: true };
    },
    [users, registrarBitacora]
  );

  const actualizarUmbralIA1 = useCallback(
    (umbral: number) => {
      const clamped = Math.min(0.95, Math.max(0.5, Number(umbral.toFixed(2))));
      setModelosIA((prev) =>
        prev.map((m) => (m.codigo === 'IA-1' ? { ...m, umbral_confianza: clamped } : m))
      );
      registrarBitacora('AJUSTAR_UMBRAL_IA', 'modelo_ia', 'IA-1', { umbral_confianza: clamped });
    },
    [registrarBitacora]
  );

  const actualizarMinReportesIA2 = useCallback(
    (min: number) => {
      const clamped = Math.min(10, Math.max(1, Math.round(min)));
      setModelosIA((prev) =>
        prev.map((m) => (m.codigo === 'IA-2' ? { ...m, min_reportes_celda: clamped } : m))
      );
      registrarBitacora('AJUSTAR_PARAMETRO_IA', 'modelo_ia', 'IA-2', { min_reportes_celda: clamped });
    },
    [registrarBitacora]
  );

  const cambiarEstadoModeloIA4 = useCallback(
    (estado: 'ACTIVO' | 'EN_PRUEBA') => {
      setModelosIA((prev) =>
        prev.map((m) => (m.codigo === 'IA-4' ? { ...m, estado } : m))
      );
      registrarBitacora(
        estado === 'ACTIVO' ? 'ACTIVAR_MODELO_IA' : 'MANTENER_PRUEBA_IA',
        'modelo_ia',
        'IA-4',
        { identificador: 'prioridad-v0.9', estado }
      );
    },
    [registrarBitacora]
  );

  const ejecutarReentrenamientoIA = useCallback(() => {
    setModelosIA((prev) =>
      prev.map((m) => {
        if (m.codigo === 'IA-1') {
          return {
            ...m,
            identificador: 'clasificador-v2.4',
            subtitulo: 'clasificador-v2.4 · TFLite en el teléfono + refinado en servidor',
            metricas: [
              { valor: '0.88', etiqueta: 'F1 macro (validación)' },
              { valor: '83 %', etiqueta: 'sugerencias aceptadas' },
              { valor: '36 ms', etiqueta: 'latencia p95 en teléfono' },
            ],
          };
        }
        return m;
      })
    );
    setReentrenamientoInfo({ fecha: '1 dic 2026', ejemplos: 0 });
    registrarBitacora('REENTRENAR_MODELOS_IA', 'modelo_ia', 'IA-1..IA-4', {
      ejemplos_anonimizados: reentrenamientoInfo.ejemplos,
      sin_datos_identidad: true,
    });
  }, [reentrenamientoInfo.ejemplos, registrarBitacora]);

  const value: SenderoContextValue = {
    currentUser,
    currentUnit,
    wsConnected,
    ia4FallbackActive,
    setIa4FallbackActive,
    priorityBannerAlerta,
    dismissPriorityBanner: () => setPriorityBannerAlerta(null),
    simulateIncomingP1Alert,
    users,
    corporaciones,
    unidades,
    versionesCatalogo,
    tiposCatalogo,
    reglasCanalizacion,
    alertas,
    reportes,
    modelosIA,
    retroalimentacionIA,
    bitacora,
    reentrenamientoInfo,
    login,
    logout,
    registrarBitacora,
    updateUnitStatus,
    acusarReciboAlerta,
    cambiarEstadoAlerta,
    cerrarAlerta,
    moderarReporte,
    consultarIdentidadProtegida,
    validarCatalogoA08,
    guardarTipoCatalogo,
    publicarBorradorCatalogo,
    guardarReglaCanalizacion,
    registrarUnidad,
    registrarCorporacion,
    asignarPerfilUsuario,
    cambiarEstadoUsuario,
    actualizarUmbralIA1,
    actualizarMinReportesIA2,
    cambiarEstadoModeloIA4,
    ejecutarReentrenamientoIA,
  };

  return <SenderoContext.Provider value={value}>{children}</SenderoContext.Provider>;
};

export function useSendero(): SenderoContextValue {
  const ctx = useContext(SenderoContext);
  if (!ctx) throw new Error('useSendero debe usarse dentro de SenderoProvider');
  return ctx;
}
