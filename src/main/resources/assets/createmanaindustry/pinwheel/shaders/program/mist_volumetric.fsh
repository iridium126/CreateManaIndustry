#include veil:space_helper

uniform sampler2D DiffuseSampler0;
uniform sampler2D DiffuseDepthSampler;

uniform int AtomizerCount;
uniform float AtomizerData[64];  // packed: x,y,z,radius per atomizer (4 floats each)

uniform vec4 MistColor;
uniform float MistDensity;
uniform float MistStepScale;
uniform vec3 SunDirection;

in vec2 texCoord;
out vec4 fragColor;

#define MAX_STEPS 32
#define NOISE_SCALE 0.3
#define FBM_OCTAVES 3

// ---------------------------------------------------------------------------
// Noise functions (hash-based, no texture required)
// ---------------------------------------------------------------------------

float hash(vec3 p) {
    float h = dot(p, vec3(127.1, 311.7, 74.7));
    return fract(sin(h) * 43758.5453123);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(mix(mix(hash(i + vec3(0,0,0)), hash(i + vec3(1,0,0)), f.x),
                   mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
                   mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
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
// Mist concentration at a world-space position
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
        float dist = abs(worldPos.x - atomizer.x)
                   + abs(worldPos.y - atomizer.y)
                   + abs(worldPos.z - atomizer.z);
        float radius = atomizer.w;
        if (dist <= radius) {
            float conc = 1.0 - dist / radius;
            if (conc > maxConc) maxConc = conc;
        }
    }
    return maxConc;
}

// ---------------------------------------------------------------------------
// Simple sunlight scattering approximation
// ---------------------------------------------------------------------------

float sunScatter(vec3 worldPos, vec3 rayDir) {
    // Approximate: light attenuated by mist density toward the sun
    float sunDist = 32.0;
    float opticalDepth = 0.0;
    vec3 sunStep = SunDirection * (sunDist / 8.0);
    vec3 sp = worldPos;
    for (int i = 0; i < 8; i++) {
        opticalDepth += getConcentration(sp) * 1.5;
        sp += sunStep;
    }
    opticalDepth /= 8.0;

    float cosTheta = dot(rayDir, SunDirection);
    // Henyey-Greenstein phase function (g=0.3 forward-ish)
    float g = 0.3;
    float gg = g * g;
    float phase = (1.0 - gg) / pow(1.0 + gg - 2.0 * g * cosTheta, 1.5);
    phase *= 0.25; // normalize

    return exp(-opticalDepth * 0.05) * (0.3 + 0.7 * phase);
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

    // Sky (depth = 1.0): use a far clip limit
    vec3 rayEnd;
    if (sceneDepth >= 1.0) {
        vec3 viewDir = viewDirFromUv(texCoord);
        rayEnd = VeilCamera.CameraPosition + viewDir * 48.0; // far mist limit
    } else {
        rayEnd = screenToWorldSpace(texCoord, sceneDepth).xyz;
    }

    vec3 rayStart = VeilCamera.CameraPosition;
    vec3 rayDir = normalize(rayEnd - rayStart);
    float rayLength = distance(rayStart, rayEnd);
    rayLength = min(rayLength, 64.0); // cap for far chunks

    float stepSize = (rayLength / float(MAX_STEPS)) * MistStepScale;
    stepSize = max(stepSize, 0.1);
    int steps = int(rayLength / stepSize);
    steps = min(steps, MAX_STEPS);

    // Dither start to hide banding
    float dither = hash(vec3(gl_FragCoord.xy, 0.0));

    vec4 accumulatedMist = vec4(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < steps; i++) {
        float t = (float(i) + dither) * stepSize;
        if (t > rayLength) break;
        if (transmittance < 0.01) break;

        vec3 pos = rayStart + rayDir * t;
        float conc = getConcentration(pos);

        if (conc > 0.001) {
            // Modulate density with noise for natural mist texture
            float noise = fbm(pos * NOISE_SCALE + vec3(0.0, 0.0, 0.0));
            float density = conc * (0.6 + 0.4 * noise) * MistDensity;

            float scatter = sunScatter(pos, rayDir);
            vec3 mistLit = MistColor.rgb * (0.5 + 0.5 * scatter);

            vec4 sampleCol = vec4(mistLit, density * MistColor.a);
            sampleCol.rgb *= sampleCol.a;
            accumulatedMist += sampleCol * transmittance;
            transmittance *= exp(-density * stepSize * 0.5);
        }
    }

    fragColor = sceneColor * transmittance + accumulatedMist;
    fragColor.a = sceneColor.a;
    gl_FragDepth = sceneDepth;
}
