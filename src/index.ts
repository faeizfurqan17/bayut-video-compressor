// Reexport the native module. On web, it will be resolved to BayutVideoCompressorModule.web.ts
// and on native platforms to BayutVideoCompressorModule.ts
export { default } from './BayutVideoCompressorModule';
export { default as BayutVideoCompressorView } from './BayutVideoCompressorView';
export * from  './BayutVideoCompressor.types';
