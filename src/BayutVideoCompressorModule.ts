import { NativeModule, requireNativeModule } from 'expo';

import { BayutVideoCompressorModuleEvents } from './BayutVideoCompressor.types';

declare class BayutVideoCompressorModule extends NativeModule<BayutVideoCompressorModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<BayutVideoCompressorModule>('BayutVideoCompressor');
