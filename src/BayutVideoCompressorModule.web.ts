import { registerWebModule, NativeModule } from 'expo';

import { BayutVideoCompressorModuleEvents } from './BayutVideoCompressor.types';

class BayutVideoCompressorModule extends NativeModule<BayutVideoCompressorModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(BayutVideoCompressorModule, 'BayutVideoCompressorModule');
