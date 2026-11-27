import { RegistroEstadisticoAlerta } from '../types/domain';

// Exportación CSV según especificación X-02 (pág. 58)
// Columnas exactas sin datos personales (LFPDPPP):
// folio,activada_en,tipo,categoria,estado_final,canal_recepcion,seg_a_acuse,nivel_escalamiento,zona,corporacion,unidad

export function generarCsvEstadistico(registros: RegistroEstadisticoAlerta[]): string {
  const encabezados = [
    'folio',
    'activada_en',
    'tipo',
    'categoria',
    'estado_final',
    'canal_recepcion',
    'seg_a_acuse',
    'nivel_escalamiento',
    'zona',
    'corporacion',
    'unidad',
  ];

  const escapar = (val: string | number) => {
    const str = String(val);
    if (str.includes(',') || str.includes('"') || str.includes('\n')) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  };

  const filas = registros.map((r) =>
    [
      r.folio,
      r.activada_en,
      r.tipo,
      r.categoria,
      r.estado_final,
      r.canal_recepcion,
      r.seg_a_acuse,
      r.nivel_escalamiento,
      r.zona,
      r.corporacion,
      r.unidad,
    ]
      .map(escapar)
      .join(',')
  );

  // UTF-8 BOM + CRLF para compatibilidad con Excel y lectores UTF-8
  return '\uFEFF' + [encabezados.join(','), ...filas].join('\r\n');
}

export function descargarArchivoCsv(contenido: string, nombreArchivo: string): void {
  const blob = new Blob([contenido], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.setAttribute('download', nombreArchivo);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
