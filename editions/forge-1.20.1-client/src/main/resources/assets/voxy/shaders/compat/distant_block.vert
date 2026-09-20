#version 450 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec2 aLightUv;
layout(location = 3) in vec4 aColor;
layout(location = 4) in uint aFace;
layout(location = 0) uniform mat4 uTransform;
layout(location = 0) out vec2 fUv;
layout(location = 1) out vec2 fLightUv;
layout(location = 2) out vec4 fColor;
#ifdef PATCHED_SHADER
layout(location = 3) flat out uint fFace;
vec2 distantTaaShift();
#endif
void main() {
    gl_Position = uTransform * vec4(aPos, 1.0);
#ifdef PATCHED_SHADER
    gl_Position.xy += distantTaaShift() * gl_Position.w;
    fFace = aFace;
#endif
    fUv = aUv;
    fLightUv = aLightUv;
    fColor = aColor;
}
