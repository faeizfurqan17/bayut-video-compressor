import { requireNativeView } from 'expo';
import * as React from 'react';

import { BayutVideoCompressorViewProps } from './BayutVideoCompressor.types';

const NativeView: React.ComponentType<BayutVideoCompressorViewProps> =
  requireNativeView('BayutVideoCompressor');

export default function BayutVideoCompressorView(props: BayutVideoCompressorViewProps) {
  return <NativeView {...props} />;
}
