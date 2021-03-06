package modelviewer;

import Useful.AppParams;
import enterthematrix.Matrix4x4;
import enterthematrix.Vector3;
import enterthematrix.Vector4;
import jassimp.*;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

import static org.lwjgl.assimp.Assimp.aiProcess_FixInfacingNormals;
import static org.lwjgl.assimp.Assimp.aiProcess_JoinIdenticalVertices;
import static org.lwjgl.assimp.Assimp.aiProcess_Triangulate;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE5;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

interface BlipBasicModelScene extends Blip {
    void handle(ModelViewerScene scene);
}

class BlipBasicModelSceneLoadModel implements BlipBasicModelScene {
    File file;

    BlipBasicModelSceneLoadModel(File file) {
        this.file = file;
    }

    @Override
    public void handle(ModelViewerScene scene) {
        try {
            scene.loadModel(file);
        }
        catch (Exception e) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("File cannot be loaded");
                alert.setContentText("The importer could not load this file, with error message: " + e.getLocalizedMessage());


                alert.showAndWait();
            });
        }
    }
}

class ModelViewerScene implements Scene {
    private final BlipHandler app;
    private final ArrayList<FancyCube> cubeModels = new ArrayList<>();
    private final ArrayList<FancyQuad> quadModels = new ArrayList<>();
    private final ArrayList<FancyCube> axisMarkers = new ArrayList<>();
    //    private final Shader standardShader;
    private CameraRotatingAroundOrigin camera;
    private final ModelLighting lighting;
    //    private final Shader shadowGenShader;
    private final ShaderStore shaders = new ShaderStore();
    private Mesh[] meshes;


    private boolean drawAxisMarkers = Persister.getOrElse("drawAxisMarkers", false);
    private boolean renderToDepth = true;
    private boolean renderDepthFramebuffer = Persister.getOrElse("renderDepthFramebuffer", false);
    private boolean drawFloor = Persister.getOrElse("drawFloor", false);
    private boolean drawModel = Persister.getOrElse("drawModel", true);
    private boolean drawCubes = Persister.getOrElse("drawCubes", true);
    private boolean renderLightsEnabled = Persister.getOrElse("renderLightsEnabled", false);
    private boolean shadowsEnabled = Persister.getOrElse("shadowsEnabled", true);
    private boolean shadowsHighQuality = Persister.getOrElse("shadowsHighQuality", true);
    private float shadowsBiasMulti = Persister.getOrElse("shadowsBiasMulti", 0.05f);
    private boolean drawTextures = Persister.getOrElse("drawTextures", true);
    private boolean doLighting = Persister.getOrElse("doLighting", true);
    private float shadowsBiasMax = Persister.getOrElse("shadowsBiasMax", 0.00005f);
    private float floorYOffset = Persister.getOrElse("floorYOffset", -0.2f);
    private float clearColourRed = Persister.getOrElse("clearColourRed", 0f);
    private float clearColourGreen = Persister.getOrElse("clearColourGreen", 0f);
    private float clearColourBlue = Persister.getOrElse("clearColourBlue", 0f);
    private float projectionFar = Persister.getOrElse("projectionFar", 1000f);
    private float projectionNear = Persister.getOrElse("projectionNear", 0.001f);
    private float projectionFov = Persister.getOrElse("projectionFov", 90f);
    private float orthoTop = Persister.getOrElse("orthoTop", 1f);
    private float orthoBottom = Persister.getOrElse("orthoBottom", -1f);
    private float orthoLeft = Persister.getOrElse("orthoLeft", -1f);
    private float orthoRight = Persister.getOrElse("orthoRight", 1f);
    private float orthoNear = Persister.getOrElse("orthoNear", 0.001f);
    private float orthoFar = Persister.getOrElse("orthoNear", 1.5f);

    private List<BlipUI> modelUI = new ArrayList<BlipUI>();
    private final List<BlipUI> basicUi = new ArrayList<BlipUI>();
    private final List<BlipUI> shadowsUI = new ArrayList<BlipUI>();
    private final List<BlipUI> floorUI = new ArrayList<BlipUI>();
    private final List<BlipUI> clearUI = new ArrayList<BlipUI>();
    private final List<BlipUI> projectionUI = new ArrayList<BlipUI>();
    private final List<BlipUI> orthoUI = new ArrayList<BlipUI>();
    private final Queue<BlipBasicModelScene> queued = new ArrayDeque<>();


    @Override
    public void handle(Blip blip) {
        if (blip instanceof BlipSceneStart) {
            List<BlipUI> uiComplete = new ArrayList<>(basicUi);
            uiComplete.addAll(modelUI);

            app.handle(BlipUITitledSection.create("Scene",
                    BlipUIVStack.create(
                            BlipUIHStack.create(uiComplete),
                            BlipUIHStack.create(floorUI))));

            app.handle(BlipUITitledSection.create("Shadows", BlipUIHStack.create(shadowsUI)));
            app.handle(BlipUITitledSection.create("Background", BlipUIHStack.create(clearUI)));
            app.handle(BlipUITitledSection.create("Camera Projection", BlipUIHStack.create(projectionUI)));
            app.handle(BlipUITitledSection.create("Camera Ortho", BlipUIHStack.create(orthoUI)));
        }

        lighting.handle(blip);
    }

    public void loadModel(URL url) throws URISyntaxException, IOException {
        File file = new File(url.getFile());
        assert (file.exists());
        loadModel(file);
    }
    
    public void loadModel(File file) throws URISyntaxException, IOException {
        meshes = LoaderUtils.loadModel(file);    
    }


    ModelViewerScene(BlipHandler app) throws URISyntaxException, IOException {
        this.app = app;
        lighting = new ModelLighting(app, shaders);

        File initialDir = new File(System.getProperty("user.dir") + "/src/main/resources/models");

        basicUi.add(BlipUIFileDialogButton.create("Load", "Choose Model File", (file) -> {
            Persister.put("last_model", file.getAbsolutePath());
            // Cause this will fire in its own thread, and we don't want to trash the meshdata mid-scene
            queued.add(new BlipBasicModelSceneLoadModel(file));
        }, Optional.of(GLFW_KEY_O), Optional.of(initialDir)));

        basicUi.add(BlipUICheckbox.create("Model", drawModel, (v) -> {
            drawModel = v;
            Persister.put("drawModel", v);
        }, Optional.empty()));
        basicUi.add(BlipUICheckbox.create("Textures", drawTextures, (v) -> {
            drawTextures = v;
            Persister.put("drawTextures", v);
        }, Optional.empty()));
        basicUi.add(BlipUICheckbox.create("Lighting", doLighting, (v) -> {
            doLighting = v;
            Persister.put("doLighting", v);
        }, Optional.empty()));
        basicUi.add(BlipUICheckbox.create("Cubes", drawCubes, (v) -> {
            drawCubes = v;
            Persister.put("drawCubes", v);
        }, Optional.empty()));
        basicUi.add(BlipUICheckbox.create("Axis markers", drawAxisMarkers, (v) -> {
            drawAxisMarkers = v;
            Persister.put("drawAxisMarkers", v);
        }, Optional.empty()));
        shadowsUI.add(BlipUICheckbox.create("Frame buffer", renderDepthFramebuffer, (v) -> {
            renderDepthFramebuffer = v;
            Persister.put("renderDepthFramebuffer", v);
        }, Optional.empty()));
        floorUI.add(BlipUICheckbox.create("Floor", drawFloor, (v) -> {
            drawFloor = v;
            Persister.put("drawFloor", v);
        }, Optional.of(GLFW_KEY_KP_6)));
        shadowsUI.add(BlipUICheckbox.create("Shadows", shadowsEnabled, (v) -> {
            shadowsEnabled = v;
            Persister.put("shadowsEnabled", v);
        }, Optional.of(GLFW_KEY_KP_5)));
        shadowsUI.add(BlipUICheckbox.create("Shadows Quality", shadowsHighQuality, (v) -> {
            shadowsHighQuality = v;
            Persister.put("shadowsHighQuality", v);
        }, Optional.empty()));
        shadowsUI.add(BlipUITextField.create(Optional.of("Bias Max"), Float.toString(shadowsBiasMax), (v) -> {
            float value = shadowsBiasMax;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            shadowsBiasMax = value;
            Persister.put("shadowsBiasMax", value);
        }));
        shadowsUI.add(BlipUITextField.create(Optional.of("Bias Multi"), Float.toString(shadowsBiasMulti), (v) -> {
            float value = shadowsBiasMulti;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            shadowsBiasMulti = value;
            Persister.put("shadowsBiasMulti", value);
        }));
        basicUi.add(BlipUICheckbox.create("Lights", renderLightsEnabled, (v) -> {
            renderLightsEnabled = v;
            Persister.put("renderLightsEnabled", v);
        }, Optional.empty()));
        floorUI.add(BlipUITextField.create(Optional.of("Floor YOffset"), Float.toString(floorYOffset), (v) -> {
            float value = floorYOffset;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            floorYOffset = value;
            Persister.put("floorYOffset", value);
        }));
        clearUI.add(BlipUITextField.create(Optional.of("Red"), Float.toString(clearColourRed), (v) -> {
            float value = clearColourRed;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            clearColourRed = value;
            Persister.put("clearColourRed", value);
        }));
        clearUI.add(BlipUITextField.create(Optional.of("Green"), Float.toString(clearColourGreen), (v) -> {
            float value = clearColourGreen;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            clearColourGreen = value;
            Persister.put("clearColourGreen", value);
        }));
        clearUI.add(BlipUITextField.create(Optional.of("Blue"), Float.toString(clearColourBlue), (v) -> {
            float value = clearColourBlue;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            clearColourBlue = value;
            Persister.put("clearColourBlue", value);
        }));
        
        projectionUI.add(BlipUITextField.create(Optional.of("Near"), Float.toString(projectionNear), (v) -> {
            float value = projectionNear;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            projectionNear = value;
            Persister.put("projectionNear", value);
        }));
        projectionUI.add(BlipUITextField.create(Optional.of("Far"), Float.toString(projectionFar), (v) -> {
            float value = projectionFar;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            projectionFar = value;
            Persister.put("projectionFar", value);
        }));
        projectionUI.add(BlipUITextField.create(Optional.of("FOV"), Float.toString(projectionFov), (v) -> {
            float value = projectionFov;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            projectionFov = value;
            Persister.put("projectionFov", value);
        }));

        orthoUI.add(BlipUITextField.create(Optional.of("Near"), Float.toString(orthoNear), (v) -> {
            float value = orthoNear;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoNear = value;
            Persister.put("orthoNear", value);
        }));
        orthoUI.add(BlipUITextField.create(Optional.of("Far"), Float.toString(orthoFar), (v) -> {
            float value = orthoFar;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoFar = value;
            Persister.put("orthoFar", value);
        }));
        orthoUI.add(BlipUITextField.create(Optional.of("Left"), Float.toString(orthoLeft), (v) -> {
            float value = orthoLeft;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoLeft = value;
            Persister.put("orthoLeft", value);
        }));
        orthoUI.add(BlipUITextField.create(Optional.of("Right"), Float.toString(orthoRight), (v) -> {
            float value = orthoRight;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoRight = value;
            Persister.put("orthoRight", value);
        }));
        orthoUI.add(BlipUITextField.create(Optional.of("Top"), Float.toString(orthoTop), (v) -> {
            float value = orthoTop;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoTop = value;
            Persister.put("orthoTop", value);
        }));
        orthoUI.add(BlipUITextField.create(Optional.of("Bottom"), Float.toString(orthoBottom), (v) -> {
            float value = orthoBottom;
            try { value = Float.parseFloat(v); } catch (RuntimeException e) {}
            orthoBottom = value;
            Persister.put("orthoBottom", value);
        }));


        if (drawModel) {
            String lastModel = Persister.get("last_model");
            if (lastModel != null) {
                try {
                    loadModel(new File(lastModel));
                }
                catch (IOException e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("File cannot be loaded");
                        alert.setContentText("The importer could not load this file, with error message: " + e.getLocalizedMessage());


                        alert.showAndWait();
                    });
                }
            } else {
                loadModel(AppWrapper.class.getResource("/models/audi/r8_gt_3ds.3ds"));
            }
        }

//        loadModel(AppWrapper.class.getResource("/models/audi/r8_gt_3ds.3ds").toURI());
//        loadModel(AppWrapper.class.getResource("/models/Baymax_White_BigHero6/Bigmax_White_OBJ.obj").toURI());


//        Materials materials = new Materials();
        TextureFromFile texture = new TextureFromFile(getClass().getResource("../images/container2.png"));
        TextureFromFile specularMap = new TextureFromFile(getClass().getResource("../images/container2_specular.png"));

        // Scale, translate, then rotate.
        // Putting into a -1 to 1 space
        {
            int numCubesX = 10;
            int numCubesZ = 10;
            Material cubeMaterial = new Material("axis", Vector3.fill(1), Vector3.fill(1), Vector3.fill(1), 10);

            // Cubes!
            for (int x = 0; x < numCubesX; x++)
                for (int z = 0; z < numCubesZ; z++) {
                    if (x == 0 && z == 0) continue;
                    // Cube goes -0.5f to 0.5f
                    // Want 10 cubes in a -1 to 1 space
                    // So each cube can be max 0.2 across, spaced every 0.2f
                    float xPos = x * (2.0f / numCubesX) - 1.0f;
                    float zPos = z * (2.0f / numCubesZ) - 1.0f;
                    Vector4 pos = new Vector4(xPos, 0, zPos, 1);
//                    Matrix4x4 scale = Matrix4x4.scale(0.16f); // to 0.05 box, taking up 25% of space
                    Matrix4x4 scale = Matrix4x4.scale(0.1f); // to 0.05 box, taking up 25% of space
                    FancyCube cube = new FancyCube(pos, Optional.of(scale), Optional.empty(), cubeMaterial, texture,
                            specularMap);
                    cubeModels.add(cube);
                }
        }

        {
            Material axisMaterial = new Material("axis", Vector3.fill(1), Vector3.fill(1), Vector3.fill(1), 10);
            Matrix4x4 scale = Matrix4x4.scale(0.01f);
            // axis
            axisMarkers.add(new FancyCube(new Vector4(0, 0, 0, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));

            axisMarkers.add(new FancyCube(new Vector4(-1, 0, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, 0, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, 0, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(-1, 0, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));

            axisMarkers.add(new FancyCube(new Vector4(-1, 1, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, 1, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, 1, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(-1, 1, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));

            axisMarkers.add(new FancyCube(new Vector4(-1, -1, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, -1, -1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(1, -1, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));
            axisMarkers.add(new FancyCube(new Vector4(-1, -1, 1, 1), Optional.of(scale), Optional.empty(),
                    axisMaterial, texture, specularMap));

        }


        {
            TextureFromFile floorTexture = new TextureFromFile(getClass().getResource
                    ("../images/WM_IndoorWood-44_1024.png"));
            Optional<Matrix4x4> rotate = Optional.of(Matrix4x4.rotateAroundXAxis(90));
            // Want it -2 to 2
            Optional<Matrix4x4> scale = Optional.of(Matrix4x4.scale(4));
            Vector4 pos = new Vector4(0, floorYOffset, 0, 1);
            Material material = new Material("dull", Vector3.fill(1), Vector3.fill(1), Vector3.fill(1), 16);
            FancyQuad floor = new FancyQuad(pos, scale, rotate, material, floorTexture, floorTexture, 10);
            quadModels.add(floor);
        }

        camera = new CameraRotatingAroundOrigin();

    }

    @Override
    public void keyPressedImpl(long window, int key, int scancode, int action, int mods) {
        //-- Input processing
        float rotationDelta = 1.0f;
        float posDelta = 0.05f;

        if (key == GLFW_KEY_UP) camera.rotateUp(rotationDelta);
        else if (key == GLFW_KEY_DOWN) camera.rotateDown(rotationDelta);
        else if (key == GLFW_KEY_LEFT) camera.rotateLeft(rotationDelta);
        else if (key == GLFW_KEY_RIGHT) camera.rotateRight(rotationDelta);
        else if (key == GLFW_KEY_W) camera.moveForward(posDelta);
        else if (key == GLFW_KEY_S) camera.moveBackward(posDelta);
        else if (key == GLFW_KEY_R) camera.moveUp(posDelta);
        else if (key == GLFW_KEY_F) camera.moveDown(posDelta);
        else if (key == GLFW_KEY_A) camera.moveLeft(posDelta);
        else if (key == GLFW_KEY_D) camera.moveRight(posDelta);
    }

    private Shader getMainShader() {
//        if (debugShader) return shaders.debugShader;
        return shaders.standardShader;
    }

    @Override
    public void draw(AppParams params) {
        shaders.reset();

        Vector4 floorPos = new Vector4(0, floorYOffset, 0, 1);
        quadModels.get(0).setPos(floorPos);

        glClearColor(clearColourRed, clearColourGreen, clearColourBlue, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        queued.forEach(blip -> {
            blip.handle(this);
        });
        queued.clear();

        try (ShaderUse wrap = new ShaderUse(getMainShader())) {
            wrap.shader.setBoolean("shadowsEnabled", shadowsEnabled);
            wrap.shader.setBoolean("shadowsHighQuality", shadowsHighQuality);
            wrap.shader.setFloat("shadowBiasMax", shadowsBiasMax);
            wrap.shader.setFloat("shadowBiasMulti", shadowsBiasMulti);
            wrap.shader.setBoolean("drawTextures", drawTextures);
            wrap.shader.setBoolean("doLighting", doLighting);
        }

        Matrix4x4 projectionMatrix = SceneUtils.createPerspectiveProjectionMatrix(params, projectionFar, projectionNear, projectionFov);
        Matrix4x4 lightProjection = SceneUtils.createOrthoProjectionMatrix(orthoLeft, orthoRight, orthoTop, orthoBottom, orthoNear, orthoFar);

        int textureToRender = lighting.directional.shadowTexture;
        if (lighting.directional.isEnabled()) {
            Vector4 posToRenderFrom = (lighting.directional.direction.$times(-1)).toVector4();
            renderSceneFromPosition(posToRenderFrom, lightProjection, "lightSpaceMatrixDir", lighting.directional
                    .shadowMap);
        } else {
            try (ShaderUse wrap = new ShaderUse(getMainShader())) {
                wrap.shader.setMatrix("lightSpaceMatrixDir", Matrix4x4.identity());
            }
        }

        for (int i = 0; i < lighting.points.length; i++) {
            PointLight light = lighting.points[i];
            if (light.isEnabled()) {
                Vector4 posToRenderFrom = light.pos.toVector4();
                renderSceneFromPosition(posToRenderFrom, lightProjection, "lightSpaceMatrixes[" + i + "]", light
                        .shadowMap);
            }
        }

        // 2. then setup scene as normal with shadow mapping (using depth map)
        glViewport(0, 0, params.widthPixels, params.heightPixels);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        {
            Shader shader = getMainShader();
            Matrix4x4 cameraTranslate = camera.getMatrix();

            try (ShaderUse wrap = new ShaderUse(shaders.basicFlatShader)) {
                wrap.shader.setMatrix("projectionMatrix", projectionMatrix);
                wrap.shader.setMatrix("viewMatrix", cameraTranslate);
            }


            try (ShaderUse wrap = new ShaderUse(shader)) {
                wrap.shader.setMatrix("projectionMatrix", projectionMatrix);
                wrap.shader.setMatrix("viewMatrix", cameraTranslate);

                renderScene(shader);
            }
        }

        if (renderDepthFramebuffer) {
            // No need for depth as we're just drawing a quad
            glDisable(GL_DEPTH_TEST);
            try (ShaderUse su = new ShaderUse(shaders.renderDepthMapShader)) {
//                int depthMapLocation = GL20.glGetUniformLocation(standardShader.getShaderId(), "depthMap");
//                GL20.glUniformMatrix4fv(depthMapLocation, false, MatrixLwjgl.convertMatrixToBuffer(lightSpaceMatrix));
                Texture texture = new TextureFromExisting(textureToRender);

                su.shader.setInt("depthMap", 5);

                glActiveTexture(GL_TEXTURE5);
                glBindTexture(GL_TEXTURE_2D, texture.getTextureId());

                FancyQuad quad = new FancyQuad(new Vector4(0, 0, 0, 1), Optional.empty(), Optional.empty(), null,
                        texture, texture, 1.0f);

                quad.draw(null, null, su.shader);
            }
            glEnable(GL_DEPTH_TEST);
        }


    }

    private void renderScene(Shader shader) {
        Matrix4x4 projectionMatrix = null;
        Matrix4x4 cameraTranslate = null;
        try (ShaderUse wrap = new ShaderUse(shader)) {
            wrap.shader.setVec3("viewPos", camera.getPosition().toVector3());
            lighting.setupShader(wrap.shader);

            if (renderLightsEnabled) {
                lighting.draw(projectionMatrix, cameraTranslate, null, camera);
            }

            if (drawAxisMarkers) {
                axisMarkers.forEach(model -> model.draw(projectionMatrix, cameraTranslate, shaders.basicFlatShader));
            }
            if (drawFloor) {
                quadModels.forEach(model -> model.draw(projectionMatrix, cameraTranslate, wrap.shader));
            }
            if (drawCubes) {
                cubeModels.forEach(model -> model.draw(projectionMatrix, cameraTranslate, wrap.shader));
            }
            if (drawModel) {
                if (meshes != null) {
                    for (int i = 0; i < meshes.length; i++) {
                        meshes[i].draw(projectionMatrix, cameraTranslate, wrap.shader);
                    }
                }
            }
        }
    }

    private void renderSceneFromPosition(Vector4 position, Matrix4x4 lightProjection, String shaderPosName, ShadowMap
            shadowMap) {

        Matrix4x4 lightView = Matrix4x4.lookAt(position, new Vector4(0, 0, 0, 1), new Vector4(0, 1, 0, 1));

        Matrix4x4 lightSpaceMatrix = lightProjection.$times(lightView);

        try (ShaderUse su = new ShaderUse(shaders.shadowGenShader)) {
            shadowMap.setup(su.shader, lightSpaceMatrix);

            renderScene(su.shader);
        }

        try (ShaderUse wrap = new ShaderUse(getMainShader())) {
            wrap.shader.setMatrix(shaderPosName, lightSpaceMatrix);
        }

        // Back to default framebugger (screen)
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }


    public void changeData(Mesh[] meshes, List<BlipUI> modelUi) {
        this.meshes = meshes;
        this.modelUI = modelUi;
    }
}
