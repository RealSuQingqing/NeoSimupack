package com.rstech.simupack.client.ui.preview;

import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Pure math shared by the scene picker and its headless regression tests. */
final class ScenePickMath {
    private ScenePickMath() {}

    record Ray(Vector3f near, Vector3f far) {}

    /**
     * Enclose every bounding-box corner around the camera for any yaw/pitch.
     * Orthographic zoom controls X/Y only; reusing it for Z clips nearby blocks.
     * Distance to lookAt plus camera distance also remains safe while panning.
     */
    static float orthographicDepthHalfExtent(org.joml.Vector3fc lookAt,
                                              org.joml.Vector3fc minimum,
                                              org.joml.Vector3fc maximum,
                                              float cameraDistance) {
        double dx = Math.max(Math.abs(minimum.x() - lookAt.x()), Math.abs(maximum.x() - lookAt.x()));
        double dy = Math.max(Math.abs(minimum.y() - lookAt.y()), Math.abs(maximum.y() - lookAt.y()));
        double dz = Math.max(Math.abs(minimum.z() - lookAt.z()), Math.abs(maximum.z() - lookAt.z()));
        return (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) + Math.abs(cameraDistance) + 1.0);
    }

    /** GUI mouse has a top-left origin; OpenGL framebuffer coordinates have a bottom-left origin. */
    static Vector2f framebufferMouse(double x, double y, int guiWidth, int guiHeight,
                                      int framebufferWidth, int framebufferHeight) {
        if (guiWidth <= 0 || guiHeight <= 0 || framebufferWidth <= 0 || framebufferHeight <= 0
                || !Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        return new Vector2f((float) (x * framebufferWidth / guiWidth),
                (float) (framebufferHeight - y * framebufferHeight / guiHeight));
    }

    /**
     * Unproject the cursor through the ACTUAL projection * model-view inverse and GL viewport.
     * Near/far points handle both perspective and orthographic cameras, including negative near
     * planes. No hand-built camera basis, block-centre snapping, or depth-buffer read is involved.
     */
    static Ray ray(Matrix4fc inverseViewProjection, int[] viewport, float pixelX, float pixelY) {
        if (viewport.length != 4 || viewport[2] <= 0 || viewport[3] <= 0
                || !Float.isFinite(pixelX) || !Float.isFinite(pixelY)
                || pixelX < viewport[0] || pixelX >= viewport[0] + viewport[2]
                || pixelY < viewport[1] || pixelY >= viewport[1] + viewport[3]) {
            return null;
        }
        float x = 2F * (pixelX - viewport[0]) / viewport[2] - 1F;
        float y = 2F * (pixelY - viewport[1]) / viewport[3] - 1F;
        Vector3f near = worldPoint(inverseViewProjection, x, y, -1F);
        Vector3f far = worldPoint(inverseViewProjection, x, y, 1F);
        if (near == null || far == null || near.distanceSquared(far) < 1.0E-12F) {
            return null;
        }
        return new Ray(near, far);
    }

    private static Vector3f worldPoint(Matrix4fc inverse, float x, float y, float z) {
        Vector4f point = inverse.transform(new Vector4f(x, y, z, 1F));
        if (!Float.isFinite(point.w) || Math.abs(point.w) < 1.0E-8F) {
            return null;
        }
        Vector3f result = new Vector3f(point.x / point.w, point.y / point.w, point.z / point.w);
        return Float.isFinite(result.x) && Float.isFinite(result.y) && Float.isFinite(result.z)
                ? result : null;
    }
}
