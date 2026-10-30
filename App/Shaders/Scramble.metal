#include <metal_stdlib>
#include <SwiftUI/SwiftUI.h>
using namespace metal;

// Analog cable scramble. The sync is deliberately wrong, so every row of the picture lands a different distance
// sideways: slow waves that drift, a jitter that buzzes, and horizontal bands that tear away and snap back. The
// vertical roll is done by the view (two copies sliding), so this only has to move pixels sideways.
[[ stitchable ]] float2 scrambleWarp(float2 pos, float time, float2 size) {
    float y = pos.y / size.y;
    float wave = sin(y * 9.0 + time * 2.1) * 0.10 + sin(y * 31.0 - time * 5.7) * 0.03;
    float band = floor(y * 14.0 + time * 0.9);
    float tear = (fract(sin(band * 12.9898 + floor(time * 3.0) * 78.233) * 43758.5453) - 0.5) * 0.22;
    float jitter = (fract(sin((pos.y + floor(time * 60.0)) * 0.37) * 1e4) - 0.5) * 0.012;
    float x = pos.x + (wave + tear + jitter) * size.x;
    // wrap, so a torn band pulls in picture from the other edge instead of leaving a hole
    x = x - size.x * floor(x / size.x);
    return float2(x, pos.y);
}

// Chroma that can't lock: colour channels swing out of phase with one another, scanlines, and a dark sync bar that
// rides through once per roll. The picture underneath is somebody's backdrop; this makes sure nobody can tell whose.
[[ stitchable ]] half4 scrambleColor(float2 pos, half4 color, float time, float2 size) {
    float y = pos.y / size.y;
    float scan = 0.72 + 0.28 * step(0.5, fract(pos.y / 3.0));
    float bar = smoothstep(0.0, 0.035, abs(fract(y - time * 0.23) - 0.5));
    float swing = 0.5 + 0.5 * sin(time * 1.7 + y * 11.0);
    half3 c = color.rgb;
    half3 off = half3(c.g, c.b, c.r) * half3(1.3h, 0.8h, 1.4h);
    half3 mixed = mix(c, off, half(swing));
    float glow = 1.15 + 0.15 * sin(time * 23.0 + y * 90.0);   // the picture "breathes" like a weak signal
    return half4(mixed * half(scan) * half(0.25 + 0.75 * bar) * half(glow), color.a);
}
