highp vec3 displayP3Encode(highp vec3 linearP3) {
    linearP3 = clamp(linearP3, 0.0, 1.0);
    return mix(12.92 * linearP3,
               1.055 * pow(linearP3, vec3(1.0 / 2.4)) - 0.055,
               step(vec3(0.0031308), linearP3));
}

highp vec3 srgbToDisplayP3(highp vec3 encoded) {
    encoded = clamp(encoded, 0.0, 1.0);
    highp vec3 linearRgb = mix(encoded / 12.92,
                             pow((encoded + 0.055) / 1.055, vec3(2.4)),
                             step(vec3(0.04045), encoded));
    const highp mat3 srgbToP3 = mat3(
        0.82246197, 0.03319420, 0.01708263,
        0.17753803, 0.96680580, 0.07239744,
        0.0,        0.0,        0.91051993);
    return displayP3Encode(srgbToP3 * linearRgb);
}
