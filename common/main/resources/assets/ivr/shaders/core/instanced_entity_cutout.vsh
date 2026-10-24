#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in vec4 InstanceModel0;
in vec4 InstanceModel1;
in vec4 InstanceModel2;
in vec4 InstanceModel3;
in ivec2 InstanceLight;
in ivec2 InstanceOverlay;
in vec4 InstanceColor;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

void main() {
    mat4 instanceModel = mat4(InstanceModel0, InstanceModel1, InstanceModel2, InstanceModel3);
    vec4 worldPosition = instanceModel * vec4(Position, 1.0);
    vec3 worldNormal = normalize(mat3(instanceModel) * Normal);
    gl_Position = ProjMat * ModelViewMat * worldPosition;
    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * worldPosition.xyz, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, worldNormal, Color * InstanceColor);
    lightMapColor = texelFetch(Sampler2, InstanceLight / 16, 0);
    overlayColor = texelFetch(Sampler1, InstanceOverlay, 0);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * instanceModel * vec4(Normal, 0.0);
}
