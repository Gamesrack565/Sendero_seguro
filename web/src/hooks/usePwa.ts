import { useCallback, useEffect, useState } from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';

export interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

declare global {
  interface Window {
    __pwaDeferredPrompt?: BeforeInstallPromptEvent | null;
  }
}

// Almacén global compartido para no perder el evento beforeinstallprompt al navegar entre vistas
let sharedDeferredPrompt: BeforeInstallPromptEvent | null =
  typeof window !== 'undefined' ? window.__pwaDeferredPrompt ?? null : null;

let sharedIsInstalled =
  typeof window !== 'undefined'
    ? window.matchMedia('(display-mode: standalone)').matches ||
      (window.navigator as Navigator & { standalone?: boolean }).standalone === true
    : false;

const listeners = new Set<() => void>();

function notifyListeners() {
  listeners.forEach((fn) => fn());
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e: Event) => {
    e.preventDefault();
    sharedDeferredPrompt = e as BeforeInstallPromptEvent;
    window.__pwaDeferredPrompt = sharedDeferredPrompt;
    notifyListeners();
  });

  window.addEventListener('appinstalled', () => {
    sharedIsInstalled = true;
    sharedDeferredPrompt = null;
    window.__pwaDeferredPrompt = null;
    notifyListeners();
  });
}

export function usePwaInstall() {
  const [, setVersion] = useState(0);

  useEffect(() => {
    // Sincronizar por si el script inline de index.html capturó el evento antes del bundle
    if (!sharedDeferredPrompt && window.__pwaDeferredPrompt) {
      sharedDeferredPrompt = window.__pwaDeferredPrompt;
      setVersion((v) => v + 1);
    }

    const listener = () => setVersion((v) => v + 1);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  const triggerInstall = useCallback(async (): Promise<'accepted' | 'dismissed' | 'manual'> => {
    const promptEvt = sharedDeferredPrompt ?? window.__pwaDeferredPrompt ?? null;
    if (!promptEvt) return 'manual';
    await promptEvt.prompt();
    const { outcome } = await promptEvt.userChoice;
    if (outcome === 'accepted') {
      sharedIsInstalled = true;
    }
    sharedDeferredPrompt = null;
    window.__pwaDeferredPrompt = null;
    notifyListeners();
    return outcome;
  }, []);

  return {
    canPromptInstall: Boolean(sharedDeferredPrompt) && !sharedIsInstalled,
    isInstalled: sharedIsInstalled,
    isSecureContext: typeof window !== 'undefined' ? window.isSecureContext : true,
    triggerInstall,
  };
}

export function usePwaStatus() {
  const [isOnline, setIsOnline] = useState<boolean>(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );

  const {
    offlineReady: [offlineReady, setOfflineReady],
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(_swUrl, registration) {
      if (registration) {
        setInterval(() => {
          registration.update();
        }, 30 * 60 * 1000);
      }
    },
  });

  useEffect(() => {
    const onOnline = () => setIsOnline(true);
    const onOffline = () => setIsOnline(false);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  return {
    isOnline,
    offlineReady,
    needRefresh,
    updateServiceWorker,
    dismissOfflineReady: () => setOfflineReady(false),
    dismissNeedRefresh: () => setNeedRefresh(false),
  };
}
