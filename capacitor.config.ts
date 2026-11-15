import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'live.geargrid.phone',
  appName: 'GearGrid Phone',
  webDir: 'dist',
  server: {
    url: 'https://app.geargrid.live/device',
    cleartext: false,
    allowNavigation: ['app.geargrid.live', 'api.geargrid.live'],
  },
  android: {
    allowMixedContent: false,
  },
};

export default config;
