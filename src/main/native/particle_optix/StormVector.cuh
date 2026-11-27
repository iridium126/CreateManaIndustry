struct vec3 {
    float x, y, z;
    __device__ explicit vec3(float v) : x(v), y(v), z(v) {}
    __device__ vec3(float x, float y, float z) : x(x), y(y), z(z) {}
    __device__ explicit vec3(float4 v) : x(v.x), y(v.y), z(v.z) {}
    __device__ vec3 operator+(vec3 b) const { return vec3(x+b.x,y+b.y,z+b.z); }
    __device__ vec3 operator-(vec3 b) const { return vec3(x-b.x,y-b.y,z-b.z); }
    __device__ vec3 operator*(float a) const { return vec3(x*a,y*a,z*a); }
    __device__ float4 row(float w) const { return make_float4(x,y,z,w); }
};
__device__ float clamp(float v,float a,float b) { return fminf(fmaxf(v,a),b); }
