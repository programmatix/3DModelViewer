package LightingMaterials;

import static org.lwjgl.opengl.GL20.glUseProgram;

class ShaderUse implements AutoCloseable {
    private final Shader shader;

    public ShaderUse(Shader shader) {
        this.shader = shader;
        shader.use();
    }

    @Override public void close() {
        shader.stop();
    }
}