// Tipos y enumerados del dominio Sendero Seguro
// Alineados al Diccionario de Datos (pags. 14–26) y Vistas Web W-01 a W-10 (pags. 47–59)

export type AlertaEstado =
  | 'CREADA'
  | 'EN_CUENTA_REGRESIVA'
  | 'PENDIENTE_ENVIO'
  | 'EXPIRADA'
  | 'ENVIADA'
  | 'ASIGNADA'
  | 'ESCALADA'
  | 'ACUSADA'
  | 'EN_ATENCION'
  | 'ATENDIDA'
  | 'CERRADA'
  | 'CANCELADA';

export type ReporteEstado = 'PENDIENTE' | 'PUBLICADO' | 'RECHAZADO' | 'RETIRADO';
export type NotificacionEstado = 'PENDIENTE' | 'ENVIADA' | 'ENTREGADA' | 'FALLIDA' | 'DESCARTADA';
export type UsuarioEstado = 'ACTIVA' | 'BLOQUEADA_TEMPORAL' | 'SUSPENDIDA' | 'BAJA_SOLICITADA' | 'ELIMINADA';
export type UsuarioRol = 'INTEGRANTE' | 'COORDINADOR' | 'OPERADOR' | 'ADMINISTRADOR';
export type UnidadEstado = 'FUERA_DE_SERVICIO' | 'DISPONIBLE' | 'ASIGNADA' | 'ATENDIENDO';
export type CatalogoEstado = 'BORRADOR' | 'EN_VALIDACION' | 'PUBLICADA' | 'REEMPLAZADA';
export type TipoComunidad = 'ALUMNO' | 'DOCENTE' | 'PERSONAL';
export type CorporacionTipo = 'SEGURIDAD_ESCOLAR' | 'POLICIA' | 'BOMBEROS' | 'ERUM' | 'OTRA';
export type IncidenteCategoria = 'FISICO' | 'SOCIAL' | 'DIGITAL';
export type TipoAplicaA = 'ALERTA' | 'REPORTE' | 'AMBOS';
export type DestinoEscalamiento = 'CORPORACION' | 'COORDINADOR_CIRCULO' | 'OPERADOR' | 'ADMINISTRADOR';
export type CanalRecepcion = 'INTERNET' | 'MALLA' | 'SMS';
export type FuenteUbicacion = 'GNSS' | 'WIFI' | 'CELULAR' | 'ULTIMA_CONOCIDA';
export type ModeracionDecision = 'PUBLICAR' | 'RECHAZAR' | 'RETIRAR';
export type RiesgoNivel = 'BAJO' | 'MEDIO' | 'ALTO';
export type IaEstado = 'ACTIVO' | 'EN_PRUEBA' | 'INACTIVO';
export type IaEjecucion = 'TELEFONO' | 'SERVIDOR' | 'MIXTO';
export type DatoMinimo = 'ubicacion' | 'descripcion' | 'audio' | 'foto';

export interface Usuario {
  id: string;
  nombre: string;
  nombre_corto: string;
  telefono_movil: string;
  identificador_institucional: string;
  tipo_comunidad: TipoComunidad;
  escuela: string;
  carrera_adscripcion?: string;
  password: string; // Solo en capa mock; en backend es password_hash (Argon2id/bcrypt)
  rol: UsuarioRol;
  estado: UsuarioEstado;
  intentos_fallidos: number;
  bloqueada_hasta?: string;
  unidad_id?: string;
  acepta_entrenamiento_ia: boolean;
}

export interface Corporacion {
  id: number;
  nombre: string;
  tipo: CorporacionTipo;
  canal_codigo: string;
  activa: boolean;
}

export interface UnidadAtencion {
  id: string;
  corporacion_id: number;
  nombre: string;
  estado: UnidadEstado;
  ultima_latitud: number;
  ultima_longitud: number;
  ultima_ubicacion_texto: string;
  atiende_resumen: string;
  tipos_incidente_ids: number[];
  operadores_nombres: string[];
  operadores_ids: string[];
  activa: boolean;
}

export interface VersionCatalogo {
  id: number;
  numero: number;
  estado: CatalogoEstado;
  creada_en: string;
  publicada_en?: string;
}

export interface TipoIncidenteVersion {
  version_catalogo_id: number;
  tipo_incidente_id: number;
  codigo: string;
  es_generica: boolean;
  nombre: string;
  categoria?: IncidenteCategoria;
  prioridad: 1 | 2 | 3 | 4 | 5;
  aplica_a: TipoAplicaA;
  datos_minimos: DatoMinimo[];
  peso_riesgo: number;
  activo: boolean;
}

export interface PasoEscalamiento {
  id: number;
  orden: number;
  destino_tipo: DestinoEscalamiento;
  corporacion_id?: number;
  etiqueta_destino: string;
}

export interface ReglaCanalizacion {
  id: number;
  version_catalogo_id: number;
  tipo_incidente_id: number;
  corporacion_destino_id: number;
  tiempo_max_acuse_seg: number;
  pasos: PasoEscalamiento[];
}

export interface UbicacionAlerta {
  id: number;
  latitud: number;
  longitud: number;
  precision_m: number;
  fuente: FuenteUbicacion;
  capturada_en: string;
  mapX: number; // Coordenada normalizada 0-100 para el mapa operativo estilizado
  mapY: number;
}

export interface EventoAlerta {
  id: number;
  hora: string;
  estado_anterior?: AlertaEstado;
  estado_nuevo: AlertaEstado;
  detalle: string;
  tipo_punto: 'info' | 'alerta' | 'exito';
}

export interface EscalamientoNivelEstado {
  nivel: number;
  destino_nombre: string;
  estado: 'avisado' | 'pendiente' | 'detenido';
  segundos_restantes?: number;
}

export interface AvisoRedApoyo {
  id: string;
  nombre: string;
  canal: 'PUSH' | 'SMS' | 'CIRCULO';
  detalle?: string;
  estado: NotificacionEstado;
  etiqueta_estado: string;
}

export interface Alerta {
  id: string;
  folio: string;
  token_seguimiento: string;
  usuario_id: string;
  emisor_nombre: string;
  emisor_nombre_corto: string;
  emisor_matricula: string;
  emisor_escuela: string;
  emisor_telefono: string;
  tipo_incidente_id: number;
  tipo_nombre: string;
  categoria?: IncidenteCategoria;
  prioridad_catalogo: 1 | 2 | 3 | 4 | 5;
  clasificacion_origen?: string;
  descripcion: string;
  zona_nombre: string;
  zona_celda: string;
  distancia_m: number;
  unidad_asignada_id: string;
  unidad_asignada_nombre: string;
  corporacion_nombre: string;
  prioridad_ia: number | null;
  prioridad_motivos: string[];
  estado: AlertaEstado;
  canal_recepcion: CanalRecepcion;
  saltos_malla?: number;
  nivel_escalamiento: number;
  sin_acuse: boolean;
  segundos_transcurridos_inicial: number;
  activada_en: string;
  registrada_en: string;
  acusada_en?: string;
  acusada_por?: string;
  cerrada_en?: string;
  resultado_cierre?: string;
  observaciones_cierre?: string;
  ubicaciones: UbicacionAlerta[];
  historial: EventoAlerta[];
  escalamiento_cadena: EscalamientoNivelEstado[];
  red_apoyo: AvisoRedApoyo[];
}

export interface AnalisisReporteIA {
  modelo_identificador: string;
  prob_duplicado: number;
  duplicado_de_folio?: string;
  duplicado_nota?: string;
  prob_datos_personales: number;
  datos_personales_detalle?: string;
  texto_sensible_resaltado?: string;
  prob_ofensivo: number;
  sugerencia: string;
}

export interface ReporteComunitario {
  id: string;
  folio: string;
  tipo_incidente_id: number;
  tipo_nombre: string;
  categoria: IncidenteCategoria;
  descripcion: string;
  latitud: number;
  longitud: number;
  ubicacion_publica: string;
  celda: string;
  es_anonimo: boolean;
  autor_protegido: {
    nombre: string;
    matricula: string;
    escuela: string;
    telefono: string;
  };
  estado: ReporteEstado;
  fecha_texto: string;
  hace_texto: string;
  etiquetas_ia: { texto: string; tono: 'ia' | 'ok' }[];
  tiene_evidencia_foto: boolean;
  analisis_ia: AnalisisReporteIA;
  motivo_decision?: string;
  identidad_revelada?: boolean;
}

export interface ModeloIA {
  id: number;
  codigo: 'IA-1' | 'IA-2' | 'IA-3' | 'IA-4';
  nombre: string;
  identificador: string;
  subtitulo: string;
  ejecucion: IaEjecucion;
  estado: IaEstado;
  metricas: { valor: string; etiqueta: string }[];
  umbral_confianza?: number; // IA-1 (0.50 - 0.95)
  min_reportes_celda?: number; // IA-2 (1 - 10)
  nota_pie: string;
  version_candidata?: string;
  tiene_metricas_validacion: boolean;
}

export interface RetroalimentacionIA {
  id: number;
  fecha: string;
  modelo: 'IA-1' | 'IA-2' | 'IA-3' | 'IA-4';
  sugerencia: string;
  decision_humana: string;
  confianza: number | null;
}

export interface EntradaBitacora {
  id: number;
  ocurrida_en: string;
  usuario_nombre: string;
  usuario_rol: string;
  accion: string;
  entidad: string;
  entidad_id: string;
  detalle: Record<string, unknown>;
  ip: string;
  hash_prev: string;
  hash: string;
}

export interface RegistroEstadisticoAlerta {
  folio: string;
  activada_en: string;
  tipo: string;
  categoria: string;
  estado_final: AlertaEstado;
  canal_recepcion: CanalRecepcion;
  seg_a_acuse: number;
  nivel_escalamiento: number;
  zona: string;
  zona_macro: string;
  corporacion: string;
  unidad: string;
  dia_indice: number; // 0..29
}
