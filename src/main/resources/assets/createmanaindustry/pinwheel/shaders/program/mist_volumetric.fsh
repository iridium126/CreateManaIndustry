#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int AtomizerCount;
uniform float AtomizerData[64]; // packed: x,y,z,radius per atomizer

uniform vec4 MistColor;
uniform float MistDensity;
uniform float MistStepScale;
uniform vec3 SunDirection;

in vec2 texCoord;
out vec4 fragColor;

#define MAX_STEPS 32
#define MIN_STEPS 8
#define STEP_GROWTH 0.15
#define NOISE_SCALE 0.25
#define PI 3.14159265359
#define ISOTROPIC_PHASE (0.25 / PI)
#define FBM_OCTAVES 3

// ---------------------------------------------------------------------------
// Hash functions (IQ / Dave Hoskins style, from Photon)
// ---------------------------------------------------------------------------

float hash1(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float hash_sin(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453123);
}

// ---------------------------------------------------------------------------
// 3D Value noise
// ---------------------------------------------------------------------------

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash1(i + vec3(0,0,0)), hash1(i + vec3(1,0,0)), f.x),
                   mix(hash1(i + vec3(0,1,0)), hash1(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash1(i + vec3(0,0,1)), hash1(i + vec3(1,0,1)), f.x),
                   mix(hash1(i + vec3(0,1,1)), hash1(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < FBM_OCTAVES; i++) {
        f += amp * valueNoise(p * freq);
        freq *= 2.0;
        amp *= 0.5;
    }
    return f;
}

// ---------------------------------------------------------------------------
// Phase function (from Photon phase_functions.glsl)
// ---------------------------------------------------------------------------

float henyey_greenstein_phase(float nu, float g) {
    float gg = g * g;
    return (ISOTROPIC_PHASE - ISOTROPIC_PHASE * gg)
        / pow(1.0 + gg - 2.0 * g * nu, 1.5);
}

// ---------------------------------------------------------------------------
// Mist concentration at a world-space position (our custom model)
// ---------------------------------------------------------------------------

float getConcentration(vec3 worldPos) {
    float maxConc = 0.0;
    for (int i = 0; i < AtomizerCount; i++) {
        vec4 atomizer = vec4(
            AtomizerData[i * 4],
            AtomizerData[i * 4 + 1],
            AtomizerData[i * 4 + 2],
            AtomizerData[i * 4 + 3]
        );
        float dx = worldPos.x - atomizer.x;
        float dy = worldPos.y - atomizer.y;
        float dz = worldPos.z - atomizer.z;
        float dist = sqrt(dx * dx + dy * dy + dz * dz);
        float radius = atomizer.w;
        if (dist <= radius) {
            float conc = 1.0 - dist / radius;
            if (conc > maxConc) maxConc = conc;
        }
    }
    return maxConc;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void main() {
    vec4 sceneColor = texture(DiffuseSampler0, texCoord);
    float sceneDepth = texture(DiffuseDepthSampler, texCoord).r;

    // No atomizers active — pass through
    if (AtomizerCount <= 0) {
        fragColor = sceneColor;
        gl_FragDepth = sceneDepth;
        return;
    }

    // --- Ray setup (world space) ---

    vec3 worldEnd;
    if (sceneDepth >= 1.0) {
        vec3 viewDir = viewDirFromUv(texCoord);
        worldEnd = VeilCamera.CameraPosition + viewDir * 48.0;
    } else {
        worldEnd = screenToWorldSpace(texCoord, sceneDepth).xyz;
    }

    vec3 rayStart = VeilCamera.CameraPosition;
    vec3 rayDir = worldEnd - rayStart;
    float rayLength = length(rayDir);
    rayDir /= rayLength;
    rayLength = min(rayLength, 64.0);

    // --- Adaptive step count (Photon style) ---

    int stepCount = int(float(MIN_STEPS) + STEP_GROWTH * rayLength);
    stepCount = min(stepCount, MAX_STEPS);

    float stepSize = (rayLength / float(stepCount)) * MistStepScale;
    stepSize = max(stepSize, 0.1);

    // Recalculate with clamped step size
    stepCount = int(rayLength / stepSize);
    stepCount = min(stepCount, MAX_STEPS);

    // --- Dither start to hide banding (from Photon r1) ---

    float dither = hash1(vec3(gl_FragCoord.xy, 0.0));

    float t = stepSize * dither;

    // --- Ray march loop ---

    float LoV = dot(rayDir, SunDirection);

    vec4 accumulatedMist = vec4(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < stepCount; i++) {
        if (t > rayLength) break;
        if (transmittance < 0.01) break;

        vec3 pos = rayStart + rayDir * t;
        float baseConc = getConcentration(pos);

        if (baseConc > 0.001) {
            // Modulate with procedural noise for natural mist wisps
            float noise = fbm(pos * NOISE_SCALE);
            float density = baseConc * (0.5 + 0.5 * noise) * MistDensity;

            // Photon-style Henyey-Greenstein phase with forward/backward mix
            float miePhase = 0.7 * henyey_greenstein_phase(LoV, 0.5)
                           + 0.3 * henyey_greenstein_phase(LoV, -0.2);

            // Combined sun + ambient scattering
            float scatter = 0.5 + 0.5 * miePhase;
            vec3 mistLit = MistColor.rgb * scatter;

            // Front-to-back alpha compositing
            float stepDensity = density * stepSize;
            vec4 sampleCol = vec4(mistLit, density * MistColor.a * 0.4);
            sampleCol.rgb *= sampleCol.a;
            accumulatedMist += sampleCol * transmittance;
            transmittance *= exp(-stepDensity * 0.5);
        }

        t += stepSize;
    }

    fragColor = sceneColor * transmittance + accumulatedMist;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
