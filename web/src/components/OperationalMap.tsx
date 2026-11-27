import React, { useEffect, useRef, useState } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Alerta, UnidadAtencion } from '../types/domain';

interface OperationalMapProps {
  mode: 'dashboard' | 'trajectory' | 'public';
  alertas?: Alerta[];
  selectedAlerta?: Alerta;
  unit?: UnidadAtencion | null;
  height?: number;
  onSelectAlerta?: (id: string) => void;
}

function getMarkerColor(alerta: Alerta): string {
  const score = alerta.prioridad_ia ?? (6 - alerta.prioridad_catalogo) * 20;
  if (score >= 80) return '#402b47';
  if (score >= 60) return '#8350c4';
  if (score >= 35) return '#7392b5';
  return '#9ab4cc';
}

export const OperationalMap: React.FC<OperationalMapProps> = ({
  mode,
  alertas = [],
  selectedAlerta,
  unit,
  height = 420,
  onSelectAlerta,
}) => {
  const [viewMode, setViewMode] = useState<'operativo' | 'osm'>('operativo');
  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const leafletMapRef = useRef<L.Map | null>(null);

  // Inicializar y actualizar Leaflet cuando el usuario cambia a "OSM real"
  useEffect(() => {
    if (viewMode !== 'osm' || !mapContainerRef.current) return;

    if (leafletMapRef.current) {
      leafletMapRef.current.remove();
      leafletMapRef.current = null;
    }

    const lastLoc = selectedAlerta?.ubicaciones[selectedAlerta.ubicaciones.length - 1];
    const center: [number, number] = lastLoc
      ? [lastLoc.latitud, lastLoc.longitud]
      : [19.5032, -99.1456]; // IPN Zacatenco / ESCOM

    const map = L.map(mapContainerRef.current, {
      center,
      zoom: mode === 'dashboard' ? 15 : 16,
      zoomControl: true,
    });
    leafletMapRef.current = map;

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap · Capa riesgo v42',
      maxZoom: 19,
    }).addTo(map);

    // Capa de riesgo predictivo v42 (celda ~150 m en Metro Politécnico / Wilfrido Massieu)
    if (mode === 'dashboard') {
      L.circle([19.5041, -99.1468], {
        radius: 190,
        color: '#8350c4',
        weight: 1.5,
        fillColor: '#8350c4',
        fillOpacity: 0.24,
      })
        .bindPopup('<strong>Zona de riesgo ALTO · IA-2 v42</strong><br/>Salida Metro Politécnico (21–24 h)')
        .addTo(map);

      alertas.forEach((a) => {
        const pt = a.ubicaciones[a.ubicaciones.length - 1];
        if (!pt) return;
        const marker = L.circleMarker([pt.latitud, pt.longitud], {
          radius: 9,
          color: '#ffffff',
          weight: 2.5,
          fillColor: getMarkerColor(a),
          fillOpacity: 1,
        }).addTo(map);
        marker.bindTooltip(`${a.folio} · ${a.tipo_nombre} (${a.estado})`);
        if (onSelectAlerta) {
          marker.on('click', () => onSelectAlerta(a.id));
        }
      });

      if (unit) {
        const unitIcon = L.divIcon({
          className: 'custom-unit-badge',
          html: `<div style="background:#262423;color:#c9e0eb;font-weight:700;font-size:11px;padding:4px 8px;border-radius:6px;white-space:nowrap;box-shadow:0 2px 6px rgba(38,36,35,0.3);">${unit.nombre}</div>`,
          iconSize: [54, 24],
          iconAnchor: [27, 12],
        });
        L.marker([unit.ultima_latitud, unit.ultima_longitud], { icon: unitIcon }).addTo(map);
      }
    } else if (selectedAlerta) {
      const pts: [number, number][] = selectedAlerta.ubicaciones.map((u) => [u.latitud, u.longitud]);
      if (pts.length > 1) {
        L.polyline(pts, { color: '#8350c4', weight: 3, dashArray: '6 6', opacity: 0.85 }).addTo(map);
      }
      pts.forEach((p, idx) => {
        const isLast = idx === pts.length - 1;
        L.circleMarker(p, {
          radius: isLast ? 9 : 4.5,
          color: '#ffffff',
          weight: isLast ? 3 : 1.5,
          fillColor: '#8350c4',
          fillOpacity: isLast ? 1 : 0.45 + idx * 0.12,
        }).addTo(map);
      });

      if (lastLoc && mode === 'trajectory') {
        L.circle([lastLoc.latitud, lastLoc.longitud], {
          radius: lastLoc.precision_m,
          color: '#8350c4',
          weight: 1,
          fillColor: '#8350c4',
          fillOpacity: 0.16,
        }).addTo(map);

        const unitLat = unit?.ultima_latitud ?? lastLoc.latitud + 0.0018;
        const unitLon = unit?.ultima_longitud ?? lastLoc.longitud + 0.0032;
        const unitIcon = L.divIcon({
          className: 'custom-unit-badge',
          html: `<div style="background:#262423;color:#c9e0eb;font-weight:700;font-size:11px;padding:4px 9px;border-radius:6px;white-space:nowrap;box-shadow:0 2px 6px rgba(38,36,35,0.3);">${selectedAlerta.unidad_asignada_nombre} · ${selectedAlerta.distancia_m} m</div>`,
          iconSize: [108, 24],
          iconAnchor: [54, 12],
        });
        L.marker([unitLat, unitLon], { icon: unitIcon }).addTo(map);
      }
    }

    return () => {
      map.remove();
      leafletMapRef.current = null;
    };
  }, [viewMode, mode, alertas, selectedAlerta, unit, onSelectAlerta]);

  const lastLoc = selectedAlerta?.ubicaciones[selectedAlerta.ubicaciones.length - 1];

  return (
    <div
      style={{
        position: 'relative',
        height,
        borderRadius: 14,
        overflow: 'hidden',
        border: '1px solid var(--border-light)',
        background: '#d8e9f2',
      }}
    >
      {/* Encabezado flotante superior */}
      {mode === 'dashboard' && (
        <div
          style={{
            position: 'absolute',
            top: 12,
            left: 12,
            zIndex: 500,
            background: '#ffffff',
            padding: '6px 12px',
            borderRadius: 9,
            fontSize: 12,
            fontWeight: 700,
            color: 'var(--text-primary)',
            boxShadow: '0 2px 6px rgba(0,0,0,0.08)',
          }}
        >
          Mapa operativo · capa de riesgo v42
        </div>
      )}

      {/* Selector Operativo / OSM real */}
      {mode !== 'public' && (
        <div
          style={{
            position: 'absolute',
            top: 12,
            right: 12,
            zIndex: 500,
            display: 'inline-flex',
            background: 'rgba(255,255,255,0.95)',
            borderRadius: 8,
            padding: 3,
            boxShadow: '0 2px 6px rgba(0,0,0,0.1)',
            border: '1px solid var(--border-light)',
          }}
        >
          <button
            type="button"
            onClick={() => setViewMode('operativo')}
            style={{
              border: 'none',
              borderRadius: 6,
              padding: '4px 9px',
              fontSize: 11,
              fontWeight: 700,
              cursor: 'pointer',
              background: viewMode === 'operativo' ? '#402b47' : 'transparent',
              color: viewMode === 'operativo' ? '#ffffff' : '#4e5868',
            }}
          >
            Operativo
          </button>
          <button
            type="button"
            onClick={() => setViewMode('osm')}
            style={{
              border: 'none',
              borderRadius: 6,
              padding: '4px 9px',
              fontSize: 11,
              fontWeight: 700,
              cursor: 'pointer',
              background: viewMode === 'osm' ? '#402b47' : 'transparent',
              color: viewMode === 'osm' ? '#ffffff' : '#4e5868',
            }}
          >
            OSM real
          </button>
        </div>
      )}

      {viewMode === 'osm' ? (
        <div ref={mapContainerRef} style={{ width: '100%', height: '100%' }} />
      ) : (
        <svg
          viewBox="0 0 500 380"
          preserveAspectRatio="xMidYMid slice"
          style={{ width: '100%', height: '100%', display: 'block' }}
          aria-label="Mapa operativo de Sendero Seguro"
        >
          {/* Fondo cartográfico estilizado en Pale Sky */}
          <rect width="500" height="380" fill="#d8e9f2" />

          {/* Vialidades principales (Wilfrido Massieu, Av. IPN, Juan de Dios Bátiz) */}
          <path
            d="M 215 -10 L 175 390"
            stroke="#ffffff"
            strokeWidth="12"
            fill="none"
          />
          <path
            d="M 355 -10 L 355 390"
            stroke="#ffffff"
            strokeWidth="9"
            fill="none"
          />
          <path
            d="M -10 175 L 510 132"
            stroke="#ffffff"
            strokeWidth="12"
            fill="none"
          />
          <path
            d="M -10 278 L 510 278"
            stroke="#ffffff"
            strokeWidth="9"
            fill="none"
          />

          {/* MODO DASHBOARD (W-02) */}
          {mode === 'dashboard' && (
            <>
              {/* Halo de zona de riesgo ALTO v42 en Deep Lilac */}
              <circle cx="210" cy="156" r="76" fill="#8350c4" fillOpacity="0.22" />
              <circle cx="210" cy="156" r="42" fill="#8350c4" fillOpacity="0.14" />

              {/* Etiqueta de unidad operativa en Shadow Grey + Pale Sky */}
              <g transform="translate(362, 76)">
                <rect width="58" height="26" rx="6" fill="#262423" />
                <text
                  x="29"
                  y="17"
                  textAnchor="middle"
                  fill="#c9e0eb"
                  fontSize="12"
                  fontWeight="700"
                  fontFamily="JetBrains Mono, monospace"
                >
                  {unit?.nombre ?? 'P-214'}
                </text>
              </g>

              {/* Marcadores de alertas activas */}
              {alertas.map((a) => {
                const pt = a.ubicaciones[a.ubicaciones.length - 1];
                const cx = pt ? (pt.mapX / 100) * 500 : 210;
                const cy = pt ? (pt.mapY / 100) * 380 : 156;
                const color = getMarkerColor(a);
                const isUrg = (a.prioridad_ia ?? 0) >= 80;
                return (
                  <g
                    key={a.id}
                    style={{ cursor: onSelectAlerta ? 'pointer' : 'default' }}
                    onClick={() => onSelectAlerta?.(a.id)}
                  >
                    {isUrg && (
                      <circle cx={cx} cy={cy} r="18" fill={color} fillOpacity="0.24">
                        <animate
                          attributeName="r"
                          values="13;24;13"
                          dur="2s"
                          repeatCount="indefinite"
                        />
                        <animate
                          attributeName="opacity"
                          values="0.5;0.1;0.5"
                          dur="2s"
                          repeatCount="indefinite"
                        />
                      </circle>
                    )}
                    <circle
                      cx={cx}
                      cy={cy}
                      r={isUrg ? 10 : 8.5}
                      fill={color}
                      stroke="#ffffff"
                      strokeWidth="3"
                    />
                    <title>{`${a.folio} · ${a.tipo_nombre} (${a.estado})`}</title>
                  </g>
                );
              })}
            </>
          )}

          {/* MODO TRAYECTORIA (W-03) O ENLACE PÚBLICO (X-01) */}
          {(mode === 'trajectory' || mode === 'public') && selectedAlerta && (
            <>
              {/* Puntos históricos de la trayectoria GNSS */}
              {selectedAlerta.ubicaciones.map((pt, idx) => {
                const isLast = idx === selectedAlerta.ubicaciones.length - 1;
                const cx = (pt.mapX / 100) * 500;
                const cy = (pt.mapY / 100) * 380;
                if (isLast) {
                  return (
                    <g key={pt.id}>
                      <circle cx={cx} cy={cy} r="22" fill="#8350c4" fillOpacity="0.2" />
                      <circle
                        cx={cx}
                        cy={cy}
                        r="9.5"
                        fill="#8350c4"
                        stroke="#ffffff"
                        strokeWidth="3"
                      />
                    </g>
                  );
                }
                return (
                  <circle
                    key={pt.id}
                    cx={cx}
                    cy={cy}
                    r={4.2 + idx * 0.5}
                    fill="#8350c4"
                    fillOpacity={0.35 + idx * 0.14}
                  />
                );
              })}

              {mode === 'trajectory' && (
                <g transform="translate(348, 66)">
                  <rect width="102" height="28" rx="6" fill="#262423" />
                  <text
                    x="51"
                    y="18"
                    textAnchor="middle"
                    fill="#c9e0eb"
                    fontSize="12"
                    fontWeight="700"
                    fontFamily="JetBrains Mono, monospace"
                  >
                    {selectedAlerta.unidad_asignada_nombre} · {selectedAlerta.distancia_m} m
                  </text>
                </g>
              )}
            </>
          )}
        </svg>
      )}

      {/* Pie de telemetría GNSS en W-03 */}
      {mode === 'trajectory' && lastLoc && (
        <div
          style={{
            position: 'absolute',
            bottom: 12,
            left: 12,
            zIndex: 500,
            background: '#ffffff',
            padding: '6px 12px',
            borderRadius: 9,
            fontSize: 12,
            color: 'var(--text-primary)',
            boxShadow: '0 2px 6px rgba(0,0,0,0.08)',
          }}
        >
          Seguimiento cada 10 s · <span className="mono">±{lastLoc.precision_m} m</span> · fuente{' '}
          <strong className="mono">{lastLoc.fuente}</strong> · última{' '}
          <span className="mono">{lastLoc.capturada_en}</span>
        </div>
      )}
    </div>
  );
};
