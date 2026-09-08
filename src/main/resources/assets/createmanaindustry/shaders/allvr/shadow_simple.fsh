// Opaque ALLVR descriptor shadow pass. Certification guarantees this stream
// contains full-cube SOLID faces only, so alpha sampling/discard is unnecessary.
// An empty fragment stage preserves fixed-function depth interpolation and lets
// the GPU run early depth/stencil rejection for overlapping island layers.
void main() {
}
