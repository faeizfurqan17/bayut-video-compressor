import * as React from 'react';

import { BayutVideoCompressorViewProps } from './BayutVideoCompressor.types';

export default function BayutVideoCompressorView(props: BayutVideoCompressorViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
