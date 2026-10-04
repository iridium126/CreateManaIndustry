// Non-negative IEEE float bit patterns preserve order for integer atomicMax.
// Hash collisions can only enlarge a query, never remove a moving candidate.
struct SweepCell { uvec4 positive; uvec4 negative; };
