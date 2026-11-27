// Capa de cliente API REST + WebSocket preparada para conectarse al backend Kotlin (Ktor)
// Según Figura ARQ-01 y ARQ-02 (págs. 3–5): HTTPS REST + WSS para actualización en vivo de la consola.

export interface ApiConfig {
  useMock: boolean;
  apiBaseUrl: string;
  wsUrl: string;
}

export const apiConfig: ApiConfig = {
  useMock: (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? 'https://api.senderoseguro.ipn.mx/v1',
  wsUrl: import.meta.env.VITE_WS_URL ?? 'wss://api.senderoseguro.ipn.mx/ws/consola',
};

export interface WsEventoConsola {
  tipo: 'ALERTA_NUEVA' | 'ALERTA_ACTUALIZADA' | 'UBICACION_GNSS' | 'ESCALAMIENTO' | 'HEARTBEAT';
  payload: Record<string, unknown>;
  recibido_en: string;
}

class ConsolaApiClient {
  private token: string | null = null;
  private socket: WebSocket | null = null;

  setToken(jwt: string | null) {
    this.token = jwt;
  }

  async request<T>(method: string, path: string, body?: unknown): Promise<T | null> {
    if (apiConfig.useMock) {
      // En modo local/mock el estado es gestionado por SenderoContext con latencia realista
      return null;
    }

    const res = await fetch(`${apiConfig.apiBaseUrl}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!res.ok) {
      throw new Error(`Error API ${res.status}: ${await res.text()}`);
    }
    return (await res.json()) as T;
  }

  connectWebSocket(onEvent: (evt: WsEventoConsola) => void, onStatus: (connected: boolean) => void) {
    if (apiConfig.useMock) {
      onStatus(true);
      return () => onStatus(false);
    }

    const ws = new WebSocket(`${apiConfig.wsUrl}?token=${encodeURIComponent(this.token ?? '')}`);
    this.socket = ws;

    ws.onopen = () => onStatus(true);
    ws.onclose = () => onStatus(false);
    ws.onerror = () => onStatus(false);
    ws.onmessage = (msg) => {
      try {
        const parsed = JSON.parse(msg.data) as WsEventoConsola;
        onEvent(parsed);
      } catch {
        // Ignorar trama malformada
      }
    };

    return () => {
      this.socket?.close();
      this.socket = null;
      onStatus(false);
    };
  }
}

export const consolaApi = new ConsolaApiClient();
